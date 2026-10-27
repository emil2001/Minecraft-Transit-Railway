package org.mtr.mod.screen;

import org.mtr.gtnh.DashboardMapCache;
import org.mtr.gtnh.DashboardMapStore;
import org.mtr.gtnh.DashboardMapView;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mod.Init;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Persistent terrain background; game reads and texture operations stay on the client thread. */
public class WorldMap {
    public static final int CHUNK_SIZE = 16;
    public static final int MAP_SIZE = DashboardMapStore.REGION_PIXELS;
    private static final int MAX_IMAGES = 128;
    private static final int CHUNKS_PER_FRAME = 2;
    private static final int UPLOADS_PER_FRAME = 2;
    private static final double LIGHT_DIM_FACTOR = 4;

    private final Map<Long, MapImage> mapImages = new LinkedHashMap<>();
    private final Set<Long> refreshImages = new HashSet<>();
    private List<Long> visibleRegions = Collections.emptyList();
    private Set<Long> visibleKeys = Collections.emptySet();
    private List<Long> scanChunks = Collections.emptyList();
    private DashboardMapCache cache;
    private Path cacheDirectory;
    private Path worldCacheDirectory;
    private Object contextWorld;
    private String contextWorldId;
    private long retryIdentityAt;
    private MapOverlayMode mapOverlayMode = MapOverlayMode.TOP_VIEW;
    private int scanCursor;
    private int scanCenterX = Integer.MIN_VALUE, scanCenterZ = Integer.MIN_VALUE;
    private int scanRadius = -1;
    private long nextScan;
    private double[] viewBounds;
    private boolean reportedIoFailure;

    public void setViewBounds(double minX, double minZ, double maxX, double maxZ) {
        double[] bounds = {minX, minZ, maxX, maxZ};
        if (Arrays.equals(viewBounds, bounds)) return;
        viewBounds = bounds;
        visibleRegions = DashboardMapView.cells(minX, minZ, maxX, maxZ, MAP_SIZE, MAX_IMAGES);
        visibleKeys = new HashSet<>(visibleRegions);
        refreshImages.retainAll(visibleKeys);
        Iterator<Map.Entry<Long, MapImage>> iterator = mapImages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, MapImage> entry = iterator.next();
            if (!visibleKeys.contains(entry.getKey())) {
                entry.getValue().dispose();
                iterator.remove();
            }
        }
    }

    public void tick(World world, ClientPlayerEntity player, float delta) {
        if (player == null || !ensureContext(world, player)) return;
        scanLoadedChunks(world, player);
        uploadVisibleRegions();
        if (!reportedIoFailure && cache.getIoFailureCount() > 0) {
            reportedIoFailure = true;
            Init.LOGGER.warn("Dashboard terrain cache I/O failed; live terrain remains available");
        }
    }

    private boolean ensureContext(World world, ClientPlayerEntity player) {
        String worldId = Init.getWorldId(world);
        long now = System.nanoTime();
        if (contextWorld != world.data || !Objects.equals(contextWorldId, worldId) ||
            worldCacheDirectory == null && now >= retryIdentityAt) {
            contextWorld = world.data;
            contextWorldId = worldId;
            worldCacheDirectory = MinecraftClientHelper.getDashboardMapCacheDirectory(world);
            retryIdentityAt = now + 1_000_000_000L;
        }
        Path directory = worldCacheDirectory == null ? null : worldCacheDirectory.resolve(mapOverlayMode == MapOverlayMode.TOP_VIEW
            ? "top" : "y_" + player.getBlockPos().getY());
        if (!Objects.equals(cacheDirectory, directory)) {
            disposeImages();
            cacheDirectory = directory;
            if (directory != null) cache = new DashboardMapCache(directory);
            nextScan = 0;
            scanChunks = Collections.emptyList();
            scanCursor = 0;
            reportedIoFailure = false;
        }
        return cache != null;
    }

    private void scanLoadedChunks(World world, ClientPlayerEntity player) {
        int centerX = Math.floorDiv(player.getBlockPos().getX(), CHUNK_SIZE);
        int centerZ = Math.floorDiv(player.getBlockPos().getZ(), CHUNK_SIZE);
        int radius = Math.max(1, Math.min(64, MinecraftClientHelper.getRenderDistance() + 1));
        long now = System.nanoTime();
        if (scanCenterX != centerX || scanCenterZ != centerZ || scanRadius != radius ||
            scanCursor >= scanChunks.size() && now >= nextScan) {
            scanCenterX = centerX;
            scanCenterZ = centerZ;
            scanRadius = radius;
            scanChunks = DashboardMapView.cells((centerX - radius) * (double) CHUNK_SIZE,
                (centerZ - radius) * (double) CHUNK_SIZE, (centerX + radius + 1) * (double) CHUNK_SIZE,
                (centerZ + radius + 1) * (double) CHUNK_SIZE, CHUNK_SIZE, (2 * radius + 1) * (2 * radius + 1));
            scanCursor = 0;
            nextScan = now + 2_000_000_000L;
        }
        ChunkManager chunks = world.getChunkManager();
        int sampled = 0, checked = 0;
        while (scanCursor < scanChunks.size() && sampled < CHUNKS_PER_FRAME && checked++ < 64) {
            long key = scanChunks.get(scanCursor);
            int x = DashboardMapView.x(key), z = DashboardMapView.z(key);
            if (!chunks.isChunkLoaded(x, z)) {
                scanCursor++;
                continue;
            }
            int[] pixels = sampleChunk(world, player, x, z);
            if (!cache.updateChunk(x, z, pixels)) break;
            long region = DashboardMapView.key(Math.floorDiv(x, DashboardMapStore.REGION_CHUNKS),
                Math.floorDiv(z, DashboardMapStore.REGION_CHUNKS));
            if (visibleKeys.contains(region)) refreshImages.add(region);
            sampled++;
            scanCursor++;
        }
    }

    private int[] sampleChunk(World world, ClientPlayerEntity player, int chunkX, int chunkZ) {
        int[] pixels = new int[CHUNK_SIZE * CHUNK_SIZE];
        int playerY = player.getBlockPos().getY();
        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int blockX = chunkX * CHUNK_SIZE + x, blockZ = chunkZ * CHUNK_SIZE + z;
                int topY = world.getTopY(HeightMapType.getMotionBlockingMapped(), blockX, blockZ) - 1;
                int blockY = mapOverlayMode == MapOverlayMode.TOP_VIEW ? topY : Math.min(topY, playerY);
                if (mapOverlayMode == MapOverlayMode.CURRENT_Y && blockY != topY) {
                    while (blockY >= 0 && world.getBlockState(Init.newBlockPos(blockX, blockY, blockZ)).isAir()) blockY--;
                }
                BlockPos position = Init.newBlockPos(blockX, blockY, blockZ);
                int light = mapOverlayMode.calculateLight ? Math.max(
                    world.getLightLevel(LightType.getBlockMapped(), position.up()),
                    world.getLightLevel(LightType.getSkyMapped(), position.up())) : 15;
                int color = world.getBlockState(position).getBlock().getDefaultMapColor().getColorMapped();
                pixels[z * CHUNK_SIZE + x] = convertColorABGR(divideColorRGB(color,
                    mapOverlayMode == MapOverlayMode.TOP_VIEW ? 1.5 : 1 + (15 - light) / LIGHT_DIM_FACTOR));
            }
        }
        return pixels;
    }

    private void uploadVisibleRegions() {
        int uploads = 0;
        for (long key : visibleRegions) {
            MapImage image = mapImages.get(key);
            if (image != null && !refreshImages.contains(key)) continue;
            int x = DashboardMapView.x(key), z = DashboardMapView.z(key);
            DashboardMapCache.Region region = cache.get(x, z);
            if (region == null) continue;
            boolean settled = !cache.isPending(x, z);
            if (settled) region = cache.get(x, z);
            if (region == null) continue;
            if (image == null || image.revision != region.revision) {
                if (uploads >= UPLOADS_PER_FRAME) continue;
                if (image == null) mapImages.put(key, new MapImage(region));
                else image.update(region);
                uploads++;
            }
            if (settled) refreshImages.remove(key);
        }
    }

    public void forEachTile(Consumer<MapImage> callback) {
        mapImages.values().forEach(image -> { if (!image.disposed) callback.accept(image); });
    }

    public void updateMap(World world, ClientPlayerEntity player) {
        scanChunks = Collections.emptyList();
        scanCursor = 0;
        nextScan = 0;
    }

	private static int convertColorABGR(int rgb) {
		final int a = 255;
		final int r = (rgb >> 16) & 255;
		final int g = (rgb >> 8) & 255;
		final int b = (rgb) & 255;
		return a << 24 | b << 16 | g << 8 | r;
	}

	private static int divideColorRGB(int color, double amount) {
		final int r = (int) (((color >> 16) & 0xFF) / amount);
		final int g = (int) (((color >> 8) & 0xFF) / amount);
		final int b = (int) ((color & 0xFF) / amount);
		return (r << 16) + (g << 8) + b;
	}

    public void setMapOverlayMode(MapOverlayMode overlayMode) { mapOverlayMode = overlayMode; }
    public boolean isMapOverlayMode(MapOverlayMode overlayMode) { return mapOverlayMode == overlayMode; }

    public void disposeImages() {
        for (MapImage image : mapImages.values()) image.dispose();
        mapImages.clear();
        refreshImages.clear();
        if (cache != null) cache.close();
        cache = null;
        cacheDirectory = null;
    }

    public static class MapImage {
        public final Identifier textureId;
        public final NativeImageBackedTexture texture;
        public final int chunkX, chunkZ;
        private long revision;
        private boolean disposed;

        private MapImage(DashboardMapCache.Region region) {
            chunkX = region.regionX * DashboardMapStore.REGION_CHUNKS;
            chunkZ = region.regionZ * DashboardMapStore.REGION_CHUNKS;
            NativeImage image = new NativeImage(MAP_SIZE, MAP_SIZE, true);
            fill(image, region.copyPixels());
            texture = new NativeImageBackedTexture(image);
            textureId = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("mtr_dashboard_map", texture);
            revision = region.revision;
        }

        private void update(DashboardMapCache.Region region) {
            fill(texture.getImage(), region.copyPixels());
            texture.upload();
            revision = region.revision;
        }

        private static void fill(NativeImage image, int[] pixels) {
            for (int z = 0; z < MAP_SIZE; z++) for (int x = 0; x < MAP_SIZE; x++)
                image.setPixelColor(x, z, pixels[z * MAP_SIZE + x]);
        }

        public void dispose() {
            if (disposed) return;
            disposed = true;
            MinecraftClient.getInstance().getTextureManager().destroyTexture(textureId);
        }

        public boolean disposed() { return disposed; }
    }

    public enum MapOverlayMode {
        TOP_VIEW(false), CURRENT_Y(true);
        public final boolean calculateLight;
        MapOverlayMode(boolean calculateLight) { this.calculateLight = calculateLight; }
    }
}
