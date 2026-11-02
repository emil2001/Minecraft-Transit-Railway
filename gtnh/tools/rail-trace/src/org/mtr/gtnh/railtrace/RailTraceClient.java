package org.mtr.gtnh.railtrace;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.tool.LegacyCamera;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.config.Config;
import org.mtr.mod.resource.CachedResource;
import org.mtr.mod.resource.OptimizedModelWrapper;
import org.mtr.mod.resource.RailResource;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/** Explicit local diagnostics. All game reads are on the native client thread. */
@SideOnly(Side.CLIENT)
public final class RailTraceClient extends RailTrace.CommonProxy {
    private static final long DURATION_NS = 30_000_000_000L;
    private static final long SAMPLE_NS = 100_000_000L;
    private static final int MAX_SAMPLES = 300, MAX_FRAMES = 18000, MAX_RAILS = 128;
    private static final int MAX_SCANNED = 20000, MAX_STYLES = 16, MAX_RECORDS = 6000;
    private static final int MAX_TRACE_BYTES = 4 * 1024 * 1024, MAX_RECEIPT_BYTES = 16384;
    private static final double RADIUS_SQUARED = 128 * 128;
    private static final Timer DEADLINES = new Timer("MTR rail trace deadline", true);
    private Session active;

    @Override
    public void init() {
        FMLCommonHandler.instance().bus().register(this);
        ClientCommandHandler.instance.registerCommand(new TraceCommand());
    }

    private final class TraceCommand extends CommandBase {
        @Override public String getCommandName() { return "mtrrailtrace"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/mtrrailtrace [help]"; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length != 0) {
                say(sender, "/mtrrailtrace: record nearby rail state for 30 seconds; /mtrrailtrace help: this help.");
                say(sender, "Local files: <Minecraft directory>/mtr-render-diagnostics. No scene changes; no upload.");
                return;
            }
            publishCompletion();
            if (active != null) {
                say(sender, "A rail trace is already running. It stops automatically after 30 seconds.");
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) {
                say(sender, "Join the affected world, then run /mtrrailtrace.");
                return;
            }
            try {
                active = new Session(mc);
                say(sender, "Rail trace started for up to 30 seconds. Reproduce the flicker normally.");
                say(sender, "Log: " + active.tracePath);
                say(sender, "Receipt: " + active.receiptPath);
            } catch (Exception error) {
                say(sender, "Rail trace could not start: " + error.getClass().getSimpleName());
            }
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Session session = active;
        if (session != null && Minecraft.getMinecraft().theWorld != session.world) session.finish("world_changed");
        publishCompletion();
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Session session = active;
        if (session == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != session.world) session.finish("world_changed");
        else if (mc.thePlayer == null || mc.renderViewEntity == null) session.finish("view_unavailable");
        else session.observe(mc);
        publishCompletion();
    }

    private void publishCompletion() {
        Session session = active;
        if (session == null || !session.finished) return;
        active = null;
        if (Minecraft.getMinecraft().thePlayer == null) return;
        say(Minecraft.getMinecraft().thePlayer, "Rail trace stopped: " + session.reason + ".");
        say(Minecraft.getMinecraft().thePlayer, "Log: " + session.tracePath);
        say(Minecraft.getMinecraft().thePlayer, "Receipt: " + session.receiptPath + (session.failure == null ? "" : " (capture error: " + session.failure + ")"));
    }

    private static void say(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }

    private static final class Session {
        final Object world;
        final Path directory, tracePath, receiptPath;
        final OutputStream trace, receipt;
        final long start = System.nanoTime(), startedAt = System.currentTimeMillis();
        final TimerTask timeout;
        final CacheReader cache = new CacheReader();
        final Map<String, String> previous = new LinkedHashMap<>();
        final JsonObject metadata = new JsonObject();
        volatile boolean finished;
        String reason, failure;
        long nextSample, bytes;
        int frames, samples, records, transitions, maxNearby, scanLimitedFrames, railLimitedFrames, styleLimitedRails;

        Session(Minecraft mc) throws IOException {
            world = mc.theWorld;
            Path base = mc.mcDataDir.toPath().toRealPath();
            Path root = base.resolve("mtr-render-diagnostics");
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !root.toRealPath().getParent().equals(base)) throw new IOException("Unsafe diagnostic directory");
            directory = Files.createDirectory(root.resolve(startedAt + "-" + UUID.randomUUID()));
            tracePath = directory.resolve("rail-trace.jsonl");
            receiptPath = directory.resolve("receipt.json");
            OutputStream traceOut = Files.newOutputStream(tracePath, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            OutputStream receiptOut;
            try {
                receiptOut = Files.newOutputStream(receiptPath, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException error) {
                traceOut.close();
                throw error;
            }
            trace = traceOut;
            receipt = receiptOut;
            metadata.addProperty("type", "start");
            metadata.addProperty("schema", 1);
            metadata.addProperty("started_epoch_ms", startedAt);
            metadata.addProperty("mtr_version", clip(Loader.instance().getIndexedModList().get("mtr").getVersion()));
            metadata.addProperty("cache_reflection", cache.error == null ? "available" : cache.error);
            metadata.addProperty("duration_seconds", 30);
            metadata.addProperty("sample_hz", 10);
            metadata.addProperty("nearby_radius_blocks", 128);
            metadata.addProperty("max_rails_per_observation", MAX_RAILS);
            metadata.addProperty("max_scanned_wrappers", MAX_SCANNED);
            metadata.addProperty("max_styles_per_rail", MAX_STYLES);
            metadata.addProperty("max_observed_frames", MAX_FRAMES);
            metadata.addProperty("max_camera_samples", MAX_SAMPLES);
            metadata.addProperty("max_trace_records", MAX_RECORDS);
            metadata.addProperty("max_trace_bytes", MAX_TRACE_BYTES);
            metadata.addProperty("max_receipt_bytes", MAX_RECEIPT_BYTES);
            metadata.addProperty("observation_phase", "native RenderTickEvent.END");
            timeout = new TimerTask() {
                @Override public void run() { finish("deadline"); }
            };
            try {
                if (!write(metadata)) throw new IOException("Could not write trace header");
                DEADLINES.schedule(timeout, Math.max(1, (DURATION_NS - (System.nanoTime() - start)) / 1_000_000));
            } catch (RuntimeException | IOException error) {
                try { trace.close(); } finally { receipt.close(); }
                throw error;
            }
        }

        synchronized void observe(Minecraft mc) {
            if (finished) return;
            long elapsed = System.nanoTime() - start;
            if (elapsed >= DURATION_NS) { finish("deadline"); return; }
            if (frames >= MAX_FRAMES) { finish("frame_limit"); return; }
            frames++;
            try {
                LegacyCamera.State camera = LegacyCamera.getState(mc);
                Map<String, MinecraftClientData.RailWrapper> live = MinecraftClientData.getInstance().railWrapperList;
                List<Candidate> nearby = new ArrayList<>();
                int scanned = 0;
                for (Map.Entry<String, MinecraftClientData.RailWrapper> entry : live.entrySet()) {
                    if (System.nanoTime() - start >= DURATION_NS) { finish("deadline"); return; }
                    if (scanned++ >= MAX_SCANNED) { scanLimitedFrames++; break; }
                    double distance = distance(entry.getValue(), camera);
                    if (distance <= RADIUS_SQUARED) nearby.add(new Candidate(entry.getKey(), entry.getValue(), distance));
                }
                maxNearby = Math.max(maxNearby, nearby.size());
                Collections.sort(nearby, Comparator.comparingDouble((Candidate c) -> c.distance).thenComparing(c -> c.id));
                if (nearby.size() > MAX_RAILS) railLimitedFrames++;
                Map<String, String> current = new LinkedHashMap<>();
                JsonArray visible = new JsonArray();
                boolean optimized = OptimizedRenderer.hasOptimizedRendering();
                boolean default3d = Config.getClient().getDefaultRail3D();
                for (int i = 0; i < Math.min(MAX_RAILS, nearby.size()); i++) {
                    if (System.nanoTime() - start >= DURATION_NS) { finish("deadline"); return; }
                    Candidate candidate = nearby.get(i);
                    JsonObject state = snapshot(candidate, optimized, default3d);
                    String stateText = state.toString();
                    current.put(candidate.id, stateText);
                    if (!stateText.equals(previous.get(candidate.id))) {
                        JsonObject change = event(previous.containsKey(candidate.id) ? "change" : "enter", elapsed);
                        change.add("rail", state);
                        if (!write(change)) { finish(limitReason()); return; }
                        transitions++;
                    }
                    JsonArray compact = new JsonArray();
                    compact.add(clip(candidate.id));
                    compact.add(candidate.wrapper.shouldRender);
                    visible.add(compact);
                }
                for (String id : previous.keySet()) {
                    if (current.containsKey(id)) continue;
                    MinecraftClientData.RailWrapper wrapper = live.get(id);
                    JsonObject leave = event("leave", elapsed);
                    leave.addProperty("id", clip(id));
                    leave.addProperty("reason", wrapper == null ? "absent_from_wrapper_list"
                            : distance(wrapper, camera) > RADIUS_SQUARED ? "outside_radius" : "outside_capture_selection");
                    if (!write(leave)) { finish(limitReason()); return; }
                    transitions++;
                }
                previous.clear();
                previous.putAll(current);
                if (elapsed >= nextSample && samples < MAX_SAMPLES) {
                    JsonObject frame = event("frame", elapsed);
                    frame.add("camera_xyz", numbers(camera.x, camera.y, camera.z));
                    frame.add("camera_yaw_pitch", numbers(camera.yaw, camera.pitch));
                    frame.addProperty("paused", mc.isGamePaused());
                    frame.addProperty("skip_render_world", mc.skipRenderWorld);
                    frame.addProperty("current_screen", mc.currentScreen == null ? null : clip(mc.currentScreen.getClass().getName()));
                    frame.addProperty("wrapper_count", live.size());
                    frame.addProperty("nearby_scanned", nearby.size());
                    frame.addProperty("scan_truncated", scanned > MAX_SCANNED);
                    frame.addProperty("rails_truncated", nearby.size() > MAX_RAILS);
                    frame.add("visibility", visible);
                    if (!write(frame)) { finish(limitReason()); return; }
                    samples++;
                    nextSample = (elapsed / SAMPLE_NS + 1) * SAMPLE_NS;
                }
                trace.flush();
            } catch (Exception error) {
                failure = error.getClass().getSimpleName();
                finish("observation_error");
            }
        }

        JsonObject snapshot(Candidate candidate, boolean optimized, boolean default3d) {
            MinecraftClientData.RailWrapper wrapper = candidate.wrapper;
            Rail rail = wrapper.getRail();
            JsonObject state = new JsonObject();
            state.addProperty("id", clip(candidate.id));
            state.addProperty("wrapper_hex_id", clip(wrapper.hexId));
            state.addProperty("rail_hex_id", clip(rail.getHexId()));
            state.addProperty("should_render", wrapper.shouldRender);
            state.addProperty("wrapper_identity", identity(wrapper));
            state.addProperty("rail_identity", identity(rail));
            state.addProperty("math_identity", identity(rail.railMath));
            double[] raw = {rail.railMath.minX, rail.railMath.minY, rail.railMath.minZ,
                    rail.railMath.maxX, rail.railMath.maxY, rail.railMath.maxZ};
            double[] wrapped = {wrapper.startVector.x, wrapper.startVector.y, wrapper.startVector.z,
                    wrapper.endVector.x, wrapper.endVector.y, wrapper.endVector.z};
            state.add("raw_aabb", numbers(raw));
            state.add("wrapper_aabb", numbers(wrapped));
            boolean equal = true;
            for (int i = 0; i < raw.length; i++) equal &= Double.compare(raw[i], wrapped[i]) == 0;
            state.addProperty("aabb_equal", equal);
            state.addProperty("optimized_rendering", optimized);
            state.addProperty("default_rail_3d", default3d);
            state.addProperty("transport_mode", rail.getTransportMode().toString());
            state.addProperty("siding", rail.isSiding());
            JsonArray styles = new JsonArray();
            int index = 0;
            for (String style : rail.getStyles()) {
                if (index++ >= MAX_STYLES) { styleLimitedRails++; break; }
                String effective = optimized && default3d && rail.getTransportMode() == TransportMode.TRAIN
                        && CustomResourceLoader.DEFAULT_RAIL_ID.equals(style)
                        ? rail.isSiding() ? CustomResourceLoader.DEFAULT_RAIL_3D_SIDING_ID : CustomResourceLoader.DEFAULT_RAIL_3D_ID : style;
                JsonObject resource = new JsonObject();
                resource.addProperty("style", clip(style));
                resource.addProperty("effective_style", clip(effective));
                resource.addProperty("flipped", effective.endsWith("_2"));
                String resourceId = RailResource.getIdWithoutDirection(effective);
                resource.addProperty("resource_id", clip(resourceId));
                cache.read(resourceId, resource);
                styles.add(resource);
            }
            state.addProperty("style_count", rail.getStyles().size());
            state.add("styles", styles);
            return state;
        }

        boolean write(JsonObject record) throws IOException {
            if (records > 0 && System.nanoTime() - start >= DURATION_NS) return false;
            byte[] encoded = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (records >= MAX_RECORDS || encoded.length > MAX_TRACE_BYTES - bytes) return false;
            trace.write(encoded);
            bytes += encoded.length;
            records++;
            return true;
        }

        String limitReason() {
            return System.nanoTime() - start >= DURATION_NS ? "deadline" : "output_limit";
        }

        synchronized void finish(String stopReason) {
            if (finished) return;
            reason = stopReason;
            timeout.cancel();
            JsonObject report = new JsonObject();
            report.addProperty("schema", 1);
            report.addProperty("stop_reason", reason);
            report.addProperty("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
            report.addProperty("capture_deadline_ms", 30000);
            report.addProperty("observed_frames", frames);
            report.addProperty("camera_samples", samples);
            report.addProperty("transition_records", transitions);
            report.addProperty("trace_records", records);
            report.addProperty("trace_bytes", bytes);
            report.addProperty("max_nearby_scanned", maxNearby);
            report.addProperty("scan_limited_frames", scanLimitedFrames);
            report.addProperty("rail_limited_frames", railLimitedFrames);
            report.addProperty("style_limited_observations", styleLimitedRails);
            report.addProperty("trace", tracePath.getFileName().toString());
            report.addProperty("cache_reflection", cache.error == null ? "available" : cache.error);
            try { trace.close(); } catch (IOException error) { failure = error.getClass().getSimpleName(); }
            report.addProperty("io_or_observation_error", failure);
            try {
                byte[] encoded = (report.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                if (encoded.length > MAX_RECEIPT_BYTES) throw new IOException("Receipt size limit");
                receipt.write(encoded);
            } catch (IOException error) {
                failure = error.getClass().getSimpleName();
            } finally {
                try { receipt.close(); } catch (IOException error) { failure = error.getClass().getSimpleName(); }
                finished = true;
            }
        }

        JsonObject event(String type, long elapsed) {
            JsonObject event = new JsonObject();
            event.addProperty("type", type);
            event.addProperty("t_ms", elapsed / 1_000_000.0);
            event.addProperty("observed_frame", frames);
            return event;
        }
    }

    private static final class CacheReader {
        Field resources, holder, data, optimizedModel, meshes;
        String error, optimizedError;

        CacheReader() {
            try {
                resources = CustomResourceLoader.class.getDeclaredField("RAILS_CACHE");
                holder = RailResource.class.getDeclaredField("cachedRailResource");
                data = CachedResource.class.getDeclaredField("data");
                resources.setAccessible(true);
                holder.setAccessible(true);
                data.setAccessible(true);
            } catch (Exception problem) { error = problem.getClass().getSimpleName(); }
            try {
                optimizedModel = OptimizedModelWrapper.class.getDeclaredField("optimizedModel");
                meshes = OptimizedModel.class.getDeclaredField("meshes");
                optimizedModel.setAccessible(true);
                meshes.setAccessible(true);
            } catch (Exception problem) { optimizedError = problem.getClass().getSimpleName(); }
        }

        void read(String id, JsonObject output) {
            if (error != null) { output.addProperty("cache_state", "unknown"); return; }
            try {
                Object resource = ((Map<?, ?>) resources.get(null)).get(id);
                output.addProperty("resource_present", resource != null);
                if (resource == null) return;
                Object cached = holder.get(resource);
                Object value = cached == null ? null : data.get(cached);
                output.addProperty("resource_identity", identity(resource));
                output.addProperty("cache_identity", identity(cached));
                output.addProperty("cached_data_identity", identity(value));
                output.addProperty("cache_state", value == null ? "empty" : "populated");
                ObjectObjectImmutablePair<?, ?> pair = (ObjectObjectImmutablePair<?, ?>) value;
                Object wrapper = pair == null ? null : pair.left();
                Object dynamic = pair == null ? null : pair.right();
                output.addProperty("optimized_wrapper_present", wrapper != null);
                output.addProperty("optimized_wrapper_identity", identity(wrapper));
                output.addProperty("dynamic_model_present", dynamic != null);
                output.addProperty("dynamic_model_identity", identity(dynamic));
                readOptimized(wrapper, output);
            } catch (Exception problem) {
                output.addProperty("cache_state", "unknown");
                output.addProperty("cache_error", problem.getClass().getSimpleName());
            }
        }

        void readOptimized(Object wrapper, JsonObject output) {
            if (optimizedError != null) {
                output.addProperty("optimized_model_state", "unknown");
                output.addProperty("optimized_model_error", optimizedError);
                return;
            }
            try {
                Object model = wrapper == null ? null : optimizedModel.get(wrapper);
                Collection<?> modelMeshes = model == null ? null : (Collection<?>) meshes.get(model);
                int meshCount = modelMeshes == null ? 0 : modelMeshes.size();
                output.addProperty("optimized_model_present", model != null);
                output.addProperty("optimized_model_identity", identity(model));
                output.addProperty("optimized_mesh_count", meshCount);
                output.addProperty("optimized_meshes_nonempty", meshCount > 0);
            } catch (Exception problem) {
                output.addProperty("optimized_model_state", "unknown");
                output.addProperty("optimized_model_error", problem.getClass().getSimpleName());
            }
        }
    }

    private static final class Candidate {
        final String id;
        final MinecraftClientData.RailWrapper wrapper;
        final double distance;
        Candidate(String id, MinecraftClientData.RailWrapper wrapper, double distance) {
            this.id = id; this.wrapper = wrapper; this.distance = distance;
        }
    }

    private static double distance(MinecraftClientData.RailWrapper wrapper, LegacyCamera.State camera) {
        Rail rail = wrapper.getRail();
        double raw = distance(camera, rail.railMath.minX, rail.railMath.minY, rail.railMath.minZ,
                rail.railMath.maxX, rail.railMath.maxY, rail.railMath.maxZ);
        double wrapped = distance(camera, wrapper.startVector.x, wrapper.startVector.y, wrapper.startVector.z,
                wrapper.endVector.x, wrapper.endVector.y, wrapper.endVector.z);
        return Math.min(raw, wrapped);
    }

    private static double distance(LegacyCamera.State camera, double x1, double y1, double z1, double x2, double y2, double z2) {
        double x = Math.max(0, Math.max(Math.min(x1, x2) - camera.x, camera.x - Math.max(x1, x2)));
        double y = Math.max(0, Math.max(Math.min(y1, y2) - camera.y, camera.y - Math.max(y1, y2)));
        double z = Math.max(0, Math.max(Math.min(z1, z2) - camera.z, camera.z - Math.max(z1, z2)));
        return x * x + y * y + z * z;
    }

    private static JsonArray numbers(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            if (Double.isNaN(value) || Double.isInfinite(value)) array.add(String.valueOf(value));
            else array.add(value);
        }
        return array;
    }

    private static String identity(Object object) {
        return object == null ? null : Integer.toHexString(System.identityHashCode(object));
    }

    private static String clip(String text) {
        return text == null || text.length() <= 256 ? text : text.substring(0, 256);
    }
}
