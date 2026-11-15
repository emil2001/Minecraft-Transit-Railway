package org.mtr.gtnh.probe;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.*;
import org.mtr.core.Main;
import org.mtr.core.data.*;
import org.mtr.core.serializer.MessagePackReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.org.msgpack.core.*;
import org.mtr.mapping.tool.BlockStateDefinition;
import org.mtr.mapping.tool.PlayerPhysics;
import org.mtr.mod.InitClient;
import org.mtr.mod.packet.PacketPressLiftButton;
import org.mtr.core.operation.PressLift;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.Init;
import org.mtr.mod.block.*;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.item.ItemLiftRefresher;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.mtr.libraries.com.google.gson.Gson;
import org.mtr.libraries.com.google.gson.JsonObject;

/** Explicit test-only reload of one previously accepted owned save. Never creates or repairs a world. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_lift_reload_probe", name="MTR GTNH owned lift reload acceptance", version="0",
    dependencies="required-after:mtr", acceptableRemoteVersions="*")
public final class LiftReloadProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final Path TASK = Paths.get(requiredProbePath("mtr.probeTaskRoot"));
    private static final Path COPIED = Paths.get(requiredProbePath("mtr.probeClient"));
    private static final String SAVE_NAME = "MTR-lift-acceptance-f59c1542-8923-4524-aeaa-df2ecf6ff742";
    private static final Path SAVE = COPIED.resolve("saves").resolve(SAVE_NAME);
    private static final Path WORK = TASK.resolve("work/sp-lift-reload-trip");
    private static final Path SOURCE = TASK.resolve("work/mtr-port/mtr/gtnh/src/probe/java/org/mtr/gtnh/probe/LiftReloadProbe.java");
    private static final String CORE_FILE = "mtr/minecraft/overworld/lifts/BC/664A52AC3BE4A4BC";
    private static final long LIFT_ID = 7370794639837930684L;
    private static final UUID PLAYER_ID = UUID.fromString("6901a56c-c6c4-4350-ab76-1a2f4579791f");
    private static final int[] FLOOR_Y = {72,80,88};
    private static final String[] FLOOR_NAMES = {"Lower","Middle","Upper"};
    private enum Stage { MENU, WORLD, VERIFY, CALL, OPEN, BOARD, QUEUE3, UP, TOP, QUEUE1, DOWN, BOTTOM, EXIT, DISMOUNT, GRAVITY, CLOSING, CLOSE, FINISHED }
    private volatile Stage stage = Stage.MENU;
    private volatile Throwable failure;
    private volatile ClientSample clientSample;
    private volatile CoreSample coreSample;
    private volatile int clientTicks;
    private volatile boolean called, clientBoardingAuthorized;
    private volatile String shotRequest, shotAcknowledged;
    private volatile Stage queued;
    private volatile long instructionAt;
    private int stillTicks, jumpClientTicks;
    private boolean upMotion, downMotion, jumped, gravityFalling;
    private double previousJumpY, jumpPeak, serverJumpPeak;
    private final JsonArray screenshots = new JsonArray();
    private final AtomicBoolean pendingCoreRead = new AtomicBoolean();
    private final RenderGate renderGate = new RenderGate();
    private final JsonObject report = new JsonObject();
    private final JsonArray timeline = new JsonArray(), transitions = new JsonArray();
    private List<StoredBlock> baselineBlocks;
    private JsonObject baselineCore, admission;
    private Main beforeMain, loadedMain;
    private Simulator simulator;
    private MinecraftClientData beforeData;
    private Path resultDirectory;
    private long startedAt, stageAt, consumedFrame;
    private int menuTicks, restoredTicks;
    private boolean admitted;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        Guard.onlyProbe(loadedMods());
        startedAt = stageAt = System.currentTimeMillis();
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage == Stage.FINISHED) return;
        Minecraft client = Minecraft.getMinecraft();
        try {
            if (failure != null) { finish(client); return; }
            require(System.currentTimeMillis() - startedAt < 10 * 60 * 1000L, "Reload total deadline: " + stage);
            if (stage == Stage.MENU) {
                if (!Loader.instance().hasReachedState(LoaderState.AVAILABLE) || client.theWorld != null
                    || client.getIntegratedServer() != null || client.currentScreen == null) { menuTicks = 0; return; }
                String screen = client.currentScreen.getClass().getName();
                if (!screen.equals("net.minecraft.client.gui.GuiMainMenu") && !screen.equals("lumien.custommainmenu.gui.GuiCustom")) { menuTicks = 0; return; }
                if (++menuTicks < 60) return;
                preflight(client);
                advance(Stage.WORLD);
                // Keep Forge's existing-save refusal/conversion boundary; null settings are supplied by Forge.
                FMLClientHandler.instance().tryLoadExistingWorld(new GuiSelectWorld(new GuiMainMenu()), SAVE_NAME,
                    "MTR lift acceptance " + Guard.ACCEPTED);
                return;
            }
            require(System.currentTimeMillis() - stageAt < 90000, "Reload phase deadline: " + stage);
            if (stage == Stage.CLOSE) { finish(client); return; }
            if (stage == Stage.WORLD) {
                if (client.theWorld == null || client.thePlayer == null || client.getIntegratedServer() == null) return;
                owned(client.getIntegratedServer());
                loadedMain = actualMain();
                require(loadedMain != null && loadedMain != beforeMain, "Native start did not create a new original Main");
                simulator = actualSimulator(loadedMain);
                require(MinecraftClientData.getInstance() != beforeData, "Original client join did not reset MinecraftClientData");
                require(PLAYER_ID.equals(client.thePlayer.getUniqueID()), "Wrong restored native client player");
                report.addProperty("new_original_main_identity", System.identityHashCode(loadedMain));
                report.addProperty("new_original_simulator_identity", System.identityHashCode(simulator));
                report.addProperty("new_original_client_data_identity", System.identityHashCode(MinecraftClientData.getInstance()));
                report.addProperty("new_original_main_created", true);
                report.addProperty("loaded_existing_save", true);
                advance(Stage.VERIFY);
            }
            owned(client.getIntegratedServer());
            require(client.theWorld != null && client.thePlayer != null, "Owned client world/player disappeared");
            require(client.thePlayer.dimension == 0 && PLAYER_ID.equals(client.thePlayer.getUniqueID()), "Client left owned overworld/player");
            require(!client.gameSettings.pauseOnLostFocus, "Copied native pauseOnLostFocus must already be disabled");
            if (client.currentScreen != null) client.displayGuiScreen(null);
            KeyBinding.unPressAllKeys();
            Stage current = stage;
            if (current == Stage.BOARD) {
                if (client.thePlayer.posZ < 6.55) key(client.gameSettings.keyBindForward, true);
                else if (!clientBoardingAuthorized) key(client.gameSettings.keyBindBack, true);
            } else if (current == Stage.EXIT && client.thePlayer.posZ > 4.35) key(client.gameSettings.keyBindBack, true);
            else if (current == Stage.GRAVITY && jumpClientTicks++ < 2) key(client.gameSettings.keyBindJump, true);
            if ((current == Stage.QUEUE3 || current == Stage.QUEUE1) && queued != current) {
                require(stage == current && VehicleRidingMovement.isRiding(LIFT_ID), "Destination before owned natural boarding");
                PressLift press = new PressLift();
                press.add(new Position(8, current == Stage.QUEUE3 ? 88 : 72, 8), LiftDirection.NONE);
                instructionAt = System.nanoTime();
                InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketPressLiftButton(press));
                queued = current;
            }
            if (current == Stage.CALL && !called) {
                assertClientPlayer(client);
                require(clientSample != null && clientSample.blocksMatch && clientSample.liftId == LIFT_ID, "Call before native loaded client state");
                double reach = client.playerController.getBlockReachDistance();
                require(client.thePlayer.getDistanceSq(9.5,72.5,5.5) < reach * reach, "Lower landing button is outside native reach");
                require(client.playerController.onPlayerRightClick(client.thePlayer, client.theWorld, null, 9,72,5,2,
                    Vec3.createVectorHelper(9.5,72.375,5.5)), "Native reachable lower landing-button activation rejected");
                report.addProperty("original_reachable_lower_landing_call", true);
                called = true;
            }
            clientTicks++;
        } catch (Throwable problem) { fail(problem); }
    }

    private void preflight(Minecraft client) throws Exception {
        require(client.theWorld == null && client.getIntegratedServer() == null, "Refusing to replace an open world/server");
        Guard.onlyProbe(loadedMods());
        Guard.paths(client.mcDataDir.toPath(), COPIED, SAVE, SAVE);
        Path artifact = Guard.artifact(LiftReloadProbe.class.getProtectionDomain().getCodeSource().getLocation(),
            COPIED.resolve("mods/MTR-GTNH-4.0.5-gtnh.0.jar"));
        admission = Guard.admission(WORK.resolve("parent-admission.json"), WORK, SOURCE, artifact, COPIED, SAVE);
        Guard.baseline(SAVE, fixedBaseline());
        NBTTagCompound level;
        try (InputStream input = Files.newInputStream(SAVE.resolve("level.dat"))) { level = CompressedStreamTools.readCompressed(input); }
        require(!level.getCompoundTag("FML").hasKey("ModItemData"), "Legacy conversion is outside reload admission");
        require(("MTR lift acceptance " + Guard.ACCEPTED).equals(level.getCompoundTag("Data").getString("LevelName")), "Wrong native level name");
        assertSavedPlayer(level.getCompoundTag("Data").getCompoundTag("Player"));
        require(client.getSaveLoader().canLoadWorld(SAVE_NAME), "Native existing save loader rejected exact save");
        baselineBlocks = savedBlocks();
        singleCorePayload();
        baselineCore = readCore(SAVE, SAVE.resolve(CORE_FILE));
        require(idle(baselineCore), "Accepted disk cabin is not idle and empty");
        beforeMain = actualMain(); beforeData = MinecraftClientData.getInstance();
        require(beforeMain == null, "Reload probe requires a fresh client process, not a stopped cached Main");
        Guard.claim(WORK.resolve("parent-admission.json"), WORK, admission);
        admitted = true;
        resultDirectory = Files.createDirectory(WORK.resolve("run-" + admission.get("run_id").getAsString()));
        report.add("parent_admission", admission);
        report.addProperty("accepted_run_id", Guard.ACCEPTED); report.addProperty("save_directory", SAVE.toString());
        report.addProperty("existing_save_load_admitted", true); report.addProperty("setup_teleport_count", 0);
        report.addProperty("ride_teleport_count", 0); report.addProperty("probe_motion_writes", 0);
        report.add("saved_core_baseline", baselineCore);
        report.add("saved_baseline_sha256", new Gson().toJsonTree(fixedBaseline()));
        JsonArray blocks = new JsonArray();
        for (StoredBlock block : baselineBlocks) {
            JsonObject value = new JsonObject(); value.addProperty("x",block.x); value.addProperty("y",block.y); value.addProperty("z",block.z);
            value.addProperty("block",Block.blockRegistry.getNameForObject(Block.getBlockById(block.id))); value.addProperty("metadata",block.metadata);
            value.add("properties",new Gson().toJsonTree(block.properties)); value.addProperty("native_tile_nbt",block.tag==null?"absent":block.tag.toString()); blocks.add(value);
        }
        report.add("native_32_block_baseline", blocks);
        Guard.paths(client.mcDataDir.toPath(), COPIED, SAVE, SAVE);
        Guard.baseline(SAVE, fixedBaseline()); // Last action before the native existing-world entrypoint.
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        Stage current = stage;
        if (event.phase != TickEvent.Phase.END || failure != null || current == Stage.MENU || current == Stage.WORLD
            || current == Stage.CLOSE || current == Stage.FINISHED) return;
        try {
            MinecraftServer server = MinecraftServer.getServer(); owned(server);
            require(actualMain() == loadedMain && actualSimulator(loadedMain) == simulator, "Original core identity changed during reload");
            EntityPlayerMP player = null;
            for (Object candidate : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP value = (EntityPlayerMP) candidate;
                if (PLAYER_ID.equals(value.getUniqueID())) player = value;
            }
            require(player != null && player.worldObj == server.worldServerForDimension(0), "Missing restored native server player");
            WorldServer world = server.worldServerForDimension(0);
            Chunk chunk = (Chunk) world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(0L);
            if (chunk == null || chunk instanceof EmptyChunk || !chunk.isChunkLoaded) return;
            assertPlayerBasics(player);
            if (current == Stage.VERIFY || current == Stage.CALL || current == Stage.OPEN) assertServerPlayer(player);
            clientBoardingAuthorized = PlayerPhysics.authorized(player);
            readCoreSoon();
            ClientSample sample = clientSample; CoreSample core = coreSample;
            if (sample == null || core == null || sample.tick != clientTicks || sample.frame <= consumedFrame
                || System.nanoTime() - sample.atNanos > 250000000L) return;
            require(System.currentTimeMillis() - core.at < 5000, "Stale actual original core sample");
            consumedFrame = sample.frame;
            require(sameConfiguration(core.json, baselineCore), "Reloaded original core configuration differs from saved baseline");
            require(sample.liftId == LIFT_ID && sameConfiguration(sample.json, baselineCore), "Reloaded actual client Lift differs");
            require(!sample.flying && sample.emptyHand, "Reload trip requires the real empty-handed player without flight");
            JsonObject observation = sample.observation();
            observation.addProperty("phase", current.name()); observation.addProperty("server_world_tick", world.getTotalWorldTime());
            observation.add("original_live_core_lift", core.json);
            observation.addProperty("server_authorized", clientBoardingAuthorized); observation.addProperty("server_no_clip", player.noClip);
            observation.addProperty("server_feet_y", player.boundingBox.minY); timeline.add(observation);
            switch (current) {
                case VERIFY:
                    verifyBlocks(world, baselineBlocks);
                    if (!sample.blocksMatch || !sample.playerRestored || !sample.upButton || sample.door > .02 || sample.landingDoor > .02) return;
                    require(idle(core.json), "Loaded original cabin is not idle and empty at floor1");
                    if (++restoredTicks < 5) return;
                    report.addProperty("native_loaded_blocks_verified", baselineBlocks.size());
                    report.addProperty("native_player_restored", true); report.addProperty("actual_client_lift_rendered", true);
                    report.addProperty("rendered_original_client_data_identity", sample.dataIdentity);
                    report.addProperty("loaded_lift_id", Long.toString(LIFT_ID));
                    report.add("loaded_original_core_lift", core.json); report.add("loaded_actual_client_lift", sample.json);
                    advance(Stage.CALL); break;
                case CALL:
                    if (called) advance(Stage.OPEN);
                    break;
                case OPEN:
                    if (core.door < .95 || sample.door < .95 || sample.landingDoor < .70) return;
                    require(Math.abs(sample.cabinY - 72) < .01, "Lower call moved cabin away from persisted floor1");
                    if (!captureDone("reload-open")) return;
                    report.addProperty("persisted_cabin_and_landing_opened", true);
                    advance(Stage.BOARD); break;
                case BOARD:
                    require(stoppedAt(sample, core, 0) && sample.door > .2, "Natural boarding missed persisted lower door window");
                    if (!sample.riding || !clientBoardingAuthorized || sample.z < 6.2 || sample.z > 7.1) return;
                    assertPassenger(player, sample);
                    require(!instruction(core.json, 2) && !instruction(sample.json, 2), "Destination existed before original cabin request");
                    report.addProperty("natural_render_boarding_authorized", true); advance(Stage.QUEUE3); break;
                case QUEUE3:
                case QUEUE1:
                    assertPassenger(player, sample);
                    int destination = current == Stage.QUEUE3 ? 2 : 0;
                    if (queued != current || core.atNanos <= instructionAt || sample.atNanos <= instructionAt
                        || !instruction(core.json, destination) || !instruction(sample.json, destination)) return;
                    advance(current == Stage.QUEUE3 ? Stage.UP : Stage.DOWN); break;
                case UP:
                    assertPassenger(player, sample);
                    if (Math.abs(core.speed) > .00001) require(sample.door <= .02, "Persisted cabin door open during upward motion");
                    if (core.speed > .00001 && sample.cabinY > 73 && sample.cabinY < 87) { upMotion = true; captureDone("reload-moving"); }
                    if (!stoppedAt(sample, core, 2) || instruction(core.json, 2) || instruction(sample.json, 2)) return;
                    require(upMotion && "reload-moving".equals(shotAcknowledged), "Missing actual upward passenger motion/framebuffer");
                    advance(Stage.TOP); break;
                case TOP:
                    assertPassenger(player, sample);
                    if (sample.door < .95 || sample.landingDoors[2] < .70 || !captureDone("reload-top")) return;
                    report.addProperty("actual_stop3_client_y", sample.y); report.addProperty("actual_stop3_server_y", player.boundingBox.minY);
                    advance(Stage.QUEUE1); break;
                case DOWN:
                    assertPassenger(player, sample);
                    if (Math.abs(core.speed) > .00001) require(sample.door <= .02, "Persisted cabin door open during downward motion");
                    if (core.speed < -.00001 && sample.cabinY > 73 && sample.cabinY < 87) downMotion = true;
                    if (!stoppedAt(sample, core, 0) || instruction(core.json, 0) || instruction(sample.json, 0)) return;
                    require(upMotion && downMotion, "Missing actual original round trip motion"); advance(Stage.BOTTOM); break;
                case BOTTOM:
                    assertPassenger(player, sample);
                    if (sample.door < .95 || sample.landingDoors[0] < .70 || !captureDone("reload-return")) return;
                    report.addProperty("original_round_trip_travel", true);
                    report.addProperty("actual_stop1_client_y", sample.y); report.addProperty("actual_stop1_server_y", player.boundingBox.minY);
                    advance(Stage.EXIT); break;
                case EXIT:
                    require(stoppedAt(sample, core, 0) && sample.door > .05, "Natural exit missed persisted lower door window");
                    if (sample.z <= 4.4) advance(Stage.DISMOUNT); break;
                case DISMOUNT:
                    if (sample.riding || clientBoardingAuthorized || !sample.onGround || !player.onGround) return;
                    require(!sample.noClip && !player.noClip && Math.abs(sample.y - 72) < .1 && Math.abs(player.boundingBox.minY - 72) < .1,
                        "Natural dismount did not restore clipping/lower platform position");
                    if (++stillTicks < 5) return;
                    report.addProperty("natural_dismount_server_revoked", true);
                    previousJumpY = sample.y; jumpPeak = sample.y; serverJumpPeak = player.boundingBox.minY;
                    advance(Stage.GRAVITY); break;
                case GRAVITY:
                    require(!sample.riding && !clientBoardingAuthorized && !sample.noClip && !player.noClip, "Rider physics returned after natural exit");
                    require(Math.abs(PlayerPhysics.gravity(.08, player) - .08) < 1E-10, "Normal server gravity still suppressed");
                    if (sample.y > 72.15) jumped = true;
                    jumpPeak = Math.max(jumpPeak, sample.y); serverJumpPeak = Math.max(serverJumpPeak, player.boundingBox.minY);
                    if (jumped && sample.y < previousJumpY - .01) gravityFalling = true; previousJumpY = sample.y;
                    if (!jumped || !gravityFalling || !sample.onGround || !player.onGround || Math.abs(sample.y - 72) > .1) return;
                    require(serverJumpPeak > 72.15 && Math.abs(player.boundingBox.minY - 72) < .1, "Server did not observe native jump/fall/landing");
                    report.addProperty("native_jump_peak_y_after_dismount", jumpPeak); report.addProperty("native_jump_server_peak_y_after_dismount", serverJumpPeak);
                    report.addProperty("native_gravity_restored", true); advance(Stage.CLOSING); break;
                case CLOSING:
                    require(!sample.riding && !clientBoardingAuthorized && !sample.noClip && !player.noClip, "Rider flags returned at final idle");
                    if (!idle(core.json) || core.door > .02 || sample.door > .02 || sample.landingDoor > .02) return;
                    verifyBlocks(world, baselineBlocks);
                    if (!sample.blocksMatch) return;
                    report.addProperty("persisted_cabin_and_landing_closed", true);
                    advance(Stage.CLOSE); break;
                default: break;
            }
        } catch (Throwable problem) { fail(problem); }
    }

    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if (!admitted || failure != null || stage == Stage.MENU || stage == Stage.WORLD || stage == Stage.CLOSE || stage == Stage.FINISHED) return;
        try {
            Minecraft client = Minecraft.getMinecraft(); owned(client.getIntegratedServer());
            Lift lift = MinecraftClientData.getLift(LIFT_ID);
            MinecraftClientData.LiftWrapper wrapper = MinecraftClientData.getInstance().liftWrapperList.get(LIFT_ID);
            boolean eligible = client.theWorld != null && client.thePlayer != null && PLAYER_ID.equals(client.thePlayer.getUniqueID())
                && client.thePlayer.dimension == 0 && client.currentScreen == null && !client.isGamePaused() && !client.skipRenderWorld
                && client.displayWidth > 0 && client.displayHeight > 0
                && client.renderViewEntity == client.thePlayer && lift != null && wrapper != null && wrapper.getLift() == lift && wrapper.shouldRender;
            long rendered = eligible ? ((Number) field(org.mtr.mod.render.MainRenderer.class, "lastRenderedMillis")).longValue() : -1;
            ClientSample sample = renderGate.sample(event.phase, eligible, client.theWorld, client.thePlayer, lift, clientTicks, rendered,
                () -> new ClientSample(client, lift, clientTicks, renderGate.frame, rendered, baselineBlocks));
            if (sample == null) return;
            clientSample = sample;
            String requested = shotRequest;
            boolean moving = "reload-moving".equals(requested);
            boolean shotEligible = moving ? stage == Stage.UP && sample.riding && sample.cabinY > 73 && sample.cabinY < 87 && sample.door <= .02
                : ("reload-open".equals(requested) && stage == Stage.OPEN || "reload-top".equals(requested) && stage == Stage.TOP
                    || "reload-return".equals(requested) && stage == Stage.BOTTOM) && sample.door >= .95
                    && sample.landingDoors["reload-top".equals(requested) ? 2 : 0] >= .70;
            if (requested != null && !requested.equals(shotAcknowledged) && shotEligible) {
                String name = requested + ".png";
                ScreenShotHelper.saveScreenshot(resultDirectory.toFile(), name, client.displayWidth, client.displayHeight, client.getFramebuffer());
                Path screenshot = resultDirectory.resolve("screenshots").resolve(name);
                require(Files.isRegularFile(screenshot) && Files.size(screenshot) > 100, "Native reload framebuffer missing");
                JsonObject value = new JsonObject(); value.addProperty("phase", requested); value.addProperty("path", screenshot.toString());
                value.addProperty("render_frame", sample.frame); value.addProperty("client_tick", sample.tick); screenshots.add(value);
                if ("reload-open".equals(requested)) { report.addProperty("actual_open_framebuffer", screenshot.toString()); report.addProperty("actual_open_frame", sample.frame); }
                shotAcknowledged = requested;
            }
        } catch (Throwable problem) { fail(problem); }
    }

    private void readCoreSoon() {
        if (!pendingCoreRead.compareAndSet(false, true)) return;
        simulator.run(() -> {
            try {
                require(simulator.lifts.size() == 1, "Native-loaded overworld must contain exactly one original Lift");
                Lift lift = simulator.lifts.iterator().next(); validateLift(lift);
                coreSample = new CoreSample(lift);
            } catch (Throwable problem) { fail(problem); }
            finally { pendingCoreRead.set(false); }
        });
    }

    private void owned(MinecraftServer server) throws Exception {
        require(admitted && server != null && SAVE_NAME.equals(server.getFolderName()), "Refusing to act on/close a foreign server");
        Guard.paths(Minecraft.getMinecraft().mcDataDir.toPath(), COPIED, SAVE, SAVE);
        require(Guard.checked(server.worldServerForDimension(0).getSaveHandler().getWorldDirectory().toPath()).equals(Guard.checked(SAVE)), "Native server save directory differs");
    }
    private static Collection<String> loadedMods() {
        List<String> result = new ArrayList<>();
        for (ModContainer mod : Loader.instance().getActiveModList()) result.add(mod.getModId());
        return result;
    }
    private static Object field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
    private static Main actualMain() throws Exception { return (Main) field(Init.class, "main"); }
    private static Simulator actualSimulator(Main main) throws Exception {
        Field field = Main.class.getDeclaredField("simulators"); field.setAccessible(true);
        Simulator selected = null;
        for (Object candidate : (Iterable<?>) field.get(main)) {
            Simulator value = (Simulator) candidate;
            if ("minecraft/overworld".equals(value.dimension)) { require(selected == null, "Multiple actual overworld simulators"); selected = value; }
        }
        require(selected != null, "Original Main has no native overworld simulator"); return selected;
    }
    private static void assertSavedPlayer(NBTTagCompound player) {
        NBTTagList pos = player.getTagList("Pos", 6);
        require(pos.tagCount() == 3 && restoredPosition(pos.func_150309_d(0),pos.func_150309_d(1),pos.func_150309_d(2))
            && player.getInteger("Dimension") == 0 && player.getTagList("Inventory",10).tagCount() == 0
            && !player.getCompoundTag("abilities").getBoolean("flying"), "Saved native player baseline differs");
    }
    private static boolean restoredPosition(double x, double y, double z) {
        return Math.abs(x-8)<.08 && Math.abs(y-72)<.08 && Math.abs(z-3.9952726411609003)<.08;
    }
    private static void assertClientPlayer(Minecraft client) {
        require(restoredPosition(client.thePlayer.posX,client.thePlayer.boundingBox.minY,client.thePlayer.posZ)
            && client.thePlayer.onGround && !client.thePlayer.capabilities.isFlying && !client.thePlayer.noClip
            && client.thePlayer.getHeldItem() == null && !VehicleRidingMovement.isRiding(LIFT_ID), "Actual native client player did not restore outside cabin");
        for (net.minecraft.item.ItemStack item : client.thePlayer.inventory.mainInventory) require(item==null,"Restored native client inventory differs");
        for (net.minecraft.item.ItemStack item : client.thePlayer.inventory.armorInventory) require(item==null,"Restored native client armor differs");
    }
    private static void assertServerPlayer(EntityPlayerMP player) {
        assertPlayerBasics(player);
        require(restoredPosition(player.posX,player.boundingBox.minY,player.posZ) && !player.noClip, "Actual native server player did not restore outside cabin");
    }
    private static void assertPlayerBasics(EntityPlayerMP player) {
        require(!player.capabilities.isFlying && player.getHeldItem() == null, "Reload trip cannot use flight/held items");
        for (net.minecraft.item.ItemStack item : player.inventory.mainInventory) require(item==null,"Restored native server inventory differs");
        for (net.minecraft.item.ItemStack item : player.inventory.armorInventory) require(item==null,"Restored native server armor differs");
    }
    private static void key(KeyBinding binding, boolean down) { KeyBinding.setKeyBindState(binding.getKeyCode(), down); }
    private static void assertPassenger(EntityPlayerMP player, ClientSample client) {
        require(client.riding && PlayerPhysics.authorized(player), "Original rider lost client bind or real server physics authorization");
        require(player.noClip && Math.abs(PlayerPhysics.gravity(.08, player)) < 1E-10, "Authorized rider lacks native clipping/gravity flags");
        require(!player.capabilities.isFlying && !client.flying, "Lift ride was replaced by native flight");
        require(Math.abs(client.y - client.cabinY) < .35 && Math.abs(player.boundingBox.minY - client.cabinY) < 1.5, "Passenger did not follow original cabin Y");
        require(Math.abs(client.x - 8) < .8 && client.z >= 5.7 && client.z <= 7.3, "Passenger left actual original cabin bounds");
        require(Math.abs(player.posX - client.x) < 1 && Math.abs(player.posZ - client.z) < 1, "Native client/server rider coordinates diverged");
    }
    private static boolean stoppedAt(ClientSample client, CoreSample core, int floor) {
        return Math.abs(core.speed) < 1E-8 && Math.abs(client.cabinY - FLOOR_Y[floor]) < .03
            && Math.abs(core.json.get("railProgress").getAsDouble() - (FLOOR_Y[floor] - 72)) < .03;
    }
    private static boolean instruction(JsonObject lift, int floor) {
        for (org.mtr.libraries.com.google.gson.JsonElement value : lift.getAsJsonArray("instructions"))
            if (value.getAsJsonObject().get("floor").getAsInt() == floor && "NONE".equals(value.getAsJsonObject().get("direction").getAsString())) return true;
        return false;
    }
    private boolean captureDone(String label) { if (!label.equals(shotRequest)) { shotRequest = label; return false; } return label.equals(shotAcknowledged); }
    private void advance(Stage next) {
        JsonObject transition = new JsonObject(); transition.addProperty("from",stage.name()); transition.addProperty("to",next.name());
        transition.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt); transitions.add(transition);
        stillTicks = 0; stageAt = System.currentTimeMillis(); stage = next;
    }
    private synchronized void fail(Throwable problem) { if (failure == null) failure = problem; }
    private static void require(boolean condition, String message) { Guard.require(condition,message); }

    private void finish(Minecraft client) {
        if (stage == Stage.FINISHED) return;
        report.addProperty("terminal_phase",stage.name()); stage = Stage.FINISHED;
        boolean safeShutdown = admitted && client.theWorld == null && client.getIntegratedServer() == null;
        boolean boundaryReturned = false, readbackSuccess = false;
        try {
            if (client.getIntegratedServer() != null) {
                owned(client.getIntegratedServer());
                KeyBinding.unPressAllKeys();
                if (client.theWorld != null) client.theWorld.sendQuittingDisconnectingPacket();
                client.loadWorld(null); client.displayGuiScreen(new GuiMainMenu());
                safeShutdown = true;
                require(client.getIntegratedServer() == null && client.theWorld == null, "Native stop did not finish");
                boundaryReturned = true;
            }
            if (failure == null) {
                require(boundaryReturned,"Owned native stop boundary was not called/returned");
                Guard.paths(client.mcDataDir.toPath(), COPIED, SAVE, SAVE);
                List<StoredBlock> persisted = savedBlocks();
                require(persisted.size() == baselineBlocks.size(), "Saved native state count changed");
                for (int i=0;i<persisted.size();i++) baselineBlocks.get(i).same(persisted.get(i));
                JsonObject saved = readCore(SAVE,SAVE.resolve(CORE_FILE));
                singleCorePayload();
                require(sameConfiguration(saved,baselineCore) && idle(saved), "Post-reload saved original cabin differs/is busy");
                report.addProperty("saved_native_blocks_verified",persisted.size()); report.add("saved_core_after_reload",saved);
                readbackSuccess = true;
            }
        } catch (Throwable problem) { fail(problem); }
        String status = outcome(report,readbackSuccess,boundaryReturned,failure!=null);
        report.addProperty("save_reopened",report.has("loaded_existing_save")&&report.get("loaded_existing_save").getAsBoolean());
        report.addProperty("lift_menu_mouse_gui_accepted",false); report.addProperty("travel_accepted",tripComplete(report) && readbackSuccess && boundaryReturned && failure == null);
        report.add("transitions",transitions); report.add("timeline",timeline); report.add("screenshots",screenshots);
        if (failure != null) { report.addProperty("error",failure.toString()); failure.printStackTrace(); }
        try {
            Path output = resultDirectory == null ? WORK.resolve("rejected-"+UUID.randomUUID()+".json") : resultDirectory.resolve("lift-reload-probe.json");
            Files.write(output,report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            System.out.println(status+report);
            if (safeShutdown) client.shutdown();
        } catch (Throwable problem) { throw new IllegalStateException("Could not write reload evidence",problem); }
    }

    static String outcome(JsonObject result,boolean readback,boolean boundary,boolean failed) {
        boolean complete = readback && boundary && !failed && tripComplete(result);
        result.addProperty("scenario_readback_success",complete);
        result.addProperty("native_stop_boundary_returned",boundary);
        // Native stop may swallow save errors; only parent-owned fresh logs and natural process exit can finish acceptance.
        result.addProperty("native_full_save_verified",false);
        result.addProperty("runtime_acceptance_pending",true);
        result.addProperty("saved_and_closed_normally",false);
        result.addProperty("passed",false);
        return complete?"MTR_LIFT_RELOAD_PROBE_PROVISIONAL ":"MTR_LIFT_RELOAD_PROBE_FAIL ";
    }

    private static boolean tripComplete(JsonObject result) {
        for (String key : new String[]{"loaded_existing_save","new_original_main_created","native_player_restored","actual_client_lift_rendered",
            "original_reachable_lower_landing_call","persisted_cabin_and_landing_opened","natural_render_boarding_authorized",
            "original_round_trip_travel","natural_dismount_server_revoked","native_gravity_restored","persisted_cabin_and_landing_closed"})
            if (!result.has(key) || !result.get(key).isJsonPrimitive() || !result.get(key).getAsJsonPrimitive().isBoolean() || !result.get(key).getAsBoolean()) return false;
        return result.has("native_loaded_blocks_verified") && result.get("native_loaded_blocks_verified").isJsonPrimitive()
            && result.get("native_loaded_blocks_verified").getAsJsonPrimitive().isNumber() && result.get("native_loaded_blocks_verified").getAsDouble() == 32;
    }

    static JsonObject readCore(Path root, Path file) throws Exception {
        Path real = Guard.checked(file); require(real.startsWith(Guard.checked(root)), "Core payload escaped owned save");
        String before = Guard.sha(file); Lift decoded;
        try (MessageUnpacker boundary = MessagePack.newDefaultUnpacker(Files.newInputStream(file))) {
            require(boundary.unpackValue().isMapValue(), "Original Lift payload must begin with one complete map");
        }
        // Original FileLoader is intentionally not used by this read-only baseline decoder.
        try (InputStream input = Files.newInputStream(file); MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(input)) {
            decoded = new Lift(new MessagePackReader(unpacker), new Data() {});
            // Native FileLoader reads the first map; CREATE-only shorter saves can retain suffix bytes.
        }
        require(before.equals(Guard.sha(file)), "Read-only payload decoder changed bytes");
        validateLift(decoded); return Utilities.getJsonObjectFromData(decoded);
    }
    static void validateLift(Lift lift) {
        require(lift.getId()==LIFT_ID && lift.getFloorCount()==3 && lift.getHeight()==3 && lift.getWidth()==2 && lift.getDepth()==2
            && lift.getOffsetX()==0 && lift.getOffsetY()==0 && lift.getOffsetZ()==-1.5 && !lift.getIsDoubleSided()
            && "default_transparent".equals(lift.getStyle()) && Math.abs(lift.getAngle().angleRadians-Angle.fromAngle(270).angleRadians)<1E-8,
            "Wrong original loaded Lift ID/configuration");
        List<LiftFloor> floors = new ArrayList<>(); lift.iterateFloors(floors::add);
        for (int i=0;i<3;i++) require(floors.get(i).getPosition().equals(new Position(8,FLOOR_Y[i],8))
            && Integer.toString(i+1).equals(floors.get(i).getNumber()) && FLOOR_NAMES[i].equals(floors.get(i).getDescription()), "Wrong original loaded floor");
    }
    private static boolean sameConfiguration(JsonObject actual, JsonObject expected) {
        for (String key : new String[]{"id","transportMode","name","color","height","width","depth","offsetX","offsetY","offsetZ","isDoubleSided","style","angle","floors"})
            if (!expected.get(key).equals(actual.get(key))) return false;
        return true;
    }
    private static boolean idle(JsonObject value) {
        return Math.abs(value.get("railProgress").getAsDouble())<.01 && Math.abs(value.get("speed").getAsDouble())<1E-8
            && value.get("stoppingCoolDown").getAsLong()==0 && value.getAsJsonArray("instructions").size()==0 && value.getAsJsonArray("ridingEntities").size()==0;
    }
    private static Map<String,String> fixedBaseline() {
        Map<String,String> result = new LinkedHashMap<>();
        result.put("level.dat","f218943cbf7284f1c2db46897e68d03a73622e582a0e0d1f6a863e896ee68994");
        result.put("playerdata/6901a56c-c6c4-4350-ab76-1a2f4579791f.dat","7228a58d64a8dcfa9cd05b7c95e9c5422c598aa3bd293206a0ed87e3765a9d50");
        result.put("region/r.0.0.mca","c935219766a2112f636d8df966c768fd569be4a826c368af4d69aeb234bfe908");
        result.put(CORE_FILE,"e7f0c711df0a7345cf005e6da068688a086a0a86f64679ee9a085e01a7c17d39");
        return result;
    }
    private static final class CoreSample {
        final long at = System.currentTimeMillis(), atNanos = System.nanoTime(); final JsonObject json; final double door, speed;
        CoreSample(Lift lift) { json=Utilities.getJsonObjectFromData(lift); door=lift.getDoorValue(); speed=json.get("speed").getAsDouble(); }
    }
    private static final class ClientSample {
        final long atNanos=System.nanoTime(), frame, liftId, rendered;
        final int tick,dataIdentity; final JsonObject json; final double door,landingDoor,cabinY,x,y,z;
        final double[] landingDoors = new double[3];
        final boolean blocksMatch,playerRestored,upButton,riding,onGround,noClip,flying,emptyHand;
        final String nativeStateError;
        ClientSample(Minecraft client,Lift lift,int tick,long frame,long rendered,List<StoredBlock> baseline) {
            this.tick=tick;this.frame=frame;this.rendered=rendered;dataIdentity=System.identityHashCode(MinecraftClientData.getInstance());liftId=lift.getId();json=Utilities.getJsonObjectFromData(lift);
            door=lift.getDoorValue(); Vector pos=lift.getPosition((a,b)->ItemLiftRefresher.findPath(new org.mtr.mapping.holder.World(client.theWorld),a,b));
            cabinY=pos.y+lift.getOffsetY(); x=client.thePlayer.posX; y=client.thePlayer.boundingBox.minY; z=client.thePlayer.posZ;
            riding=VehicleRidingMovement.isRiding(LIFT_ID);onGround=client.thePlayer.onGround;noClip=client.thePlayer.noClip;
            flying=client.thePlayer.capabilities.isFlying;emptyHand=client.thePlayer.getHeldItem()==null;
            for (int i=0;i<3;i++) { TileEntity tile=client.theWorld.getTileEntity(8,FLOOR_Y[i],5);
                landingDoors[i]=tile instanceof BlockPSDAPGDoorBase.BlockEntityBase ? ((BlockPSDAPGDoorBase.BlockEntityBase)tile).getDoorValue():0; }
            landingDoor=landingDoors[0];
            boolean matches=false;String error="";
            try { verifyBlocks(client.theWorld,baseline);matches=true; } catch (AssertionError | RuntimeException invalid) { error=invalid.toString(); }
            blocksMatch=matches;nativeStateError=error;
            boolean restored=false;try { assertClientPlayer(client);restored=true; } catch(AssertionError invalid) {}
            playerRestored=restored;
            boolean[] buttons={false,false};BlockLiftButtons.hasButtonsClient(new BlockPos(8,72,8),buttons,(i,l)->{});
            upButton=!buttons[0]&&buttons[1];
        }
        JsonObject observation() {
            JsonObject value=new JsonObject();value.addProperty("render_frame",frame);value.addProperty("original_mtr_render_millis",rendered);
            value.addProperty("client_tick",tick);value.addProperty("lift_id",Long.toString(liftId));value.addProperty("cabin_y",cabinY);
            value.addProperty("cabin_door",door);value.addProperty("lower_landing_door",landingDoor);value.addProperty("native_32_states_match",blocksMatch);
            value.addProperty("native_state_error",nativeStateError);value.add("actual_client_lift",json);
            value.addProperty("client_x",x);value.addProperty("client_feet_y",y);value.addProperty("client_z",z);value.addProperty("client_riding",riding);
            value.addProperty("client_on_ground",onGround);value.addProperty("client_no_clip",noClip);value.addProperty("client_flying",flying);
            value.add("landing_door_values_floor1_2_3",new Gson().toJsonTree(landingDoors));return value;
        }
    }
    private static final class RenderGate {
        long frame,mtrBefore;int tick;boolean armed;Object world,player,lift;
        <T> T sample(TickEvent.Phase phase,boolean eligible,Object world,Object player,Object lift,int tick,long rendered,java.util.function.Supplier<T> read) {
            if(phase==TickEvent.Phase.START) { frame++;armed=eligible&&world!=null&&player!=null;this.world=world;this.player=player;this.lift=lift;this.tick=tick;mtrBefore=rendered;return null; }
            boolean completed=armed&&eligible&&this.world==world&&this.player==player&&this.lift==lift&&this.tick==tick&&rendered>mtrBefore;
            armed=false;return completed?read.get():null;
        }
    }
    private static List<StoredBlock> savedBlocks() throws Exception {
        NBTTagCompound chunk = readChunk(SAVE,SAVE.resolve("region/r.0.0.mca"));
        require(chunk.getInteger("xPos")==0 && chunk.getInteger("zPos")==0,"Saved stand chunk differs");
        List<StoredBlock> result = new ArrayList<>();
        for(int y=72;y<=88;y++) result.add(savedBlock(chunk,8,y,8));
        for(int y:FLOOR_Y) {
            result.add(savedBlock(chunk,9,y,5));
            for(int x=7;x<=8;x++) for(int dy=0;dy<=1;dy++) result.add(savedBlock(chunk,x,y+dy,5));
        }
        require(result.size()==32,"Incomplete saved native fixture");return result;
    }
    private static void singleCorePayload() throws Exception {
        Path directory=SAVE.resolve("mtr/minecraft/overworld/lifts");List<Path> files=new ArrayList<>();
        require(Guard.checked(directory).startsWith(Guard.checked(SAVE)),"Core lifts directory escaped owned save");
        try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
            paths.forEach(path->{require(!Files.isSymbolicLink(path),"Symlink in owned lift payload set");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))files.add(path);});
        }
        require(files.size()==1&&files.get(0).equals(SAVE.resolve(CORE_FILE)),"Saved original Lift payload set differs");
    }
    private static StoredBlock savedBlock(NBTTagCompound chunk,int x,int y,int z) {
        NBTTagList sections=chunk.getTagList("Sections",10);NBTTagCompound section=null;
        for(int i=0;i<sections.tagCount();i++) if((sections.getCompoundTagAt(i).getByte("Y")&255)==y>>4) section=sections.getCompoundTagAt(i);
        require(section!=null,"Saved lift section missing");int slot=((y&15)<<8)|((z&15)<<4)|(x&15);
        byte[] low=array(section,"Blocks",4096,false),high=array(section,"Add",2048,true),higher=array(section,"BlocksB2Hi",2048,true),highest=array(section,"BlocksB3",4096,true);
        int id=(low[slot]&255)|(high.length==0?0:nibble(high,slot)<<8)|(higher.length==0?0:nibble(higher,slot)<<12)|(highest.length==0?0:(highest[slot]&255)<<16);
        byte[] data=array(section,"Data",2048,false),dataHigh=array(section,"Data1High",2048,true),dataHighest=array(section,"Data2",4096,true);
        int metadata=nibble(data,slot)|(dataHigh.length==0?0:nibble(dataHigh,slot)<<4)|(dataHighest.length==0?0:(dataHighest[slot]&255)<<8);
        NBTTagCompound tag=null;NBTTagList tiles=chunk.getTagList("TileEntities",10);
        for(int i=0;i<tiles.tagCount();i++) {
            NBTTagCompound value=tiles.getCompoundTagAt(i);
            if(value.getInteger("x")==x&&value.getInteger("y")==y&&value.getInteger("z")==z) { require(tag==null,"Duplicate saved native tile");tag=value; }
        }
        return new StoredBlock(x,y,z,id,metadata,tag);
    }
    private static void verifyBlocks(net.minecraft.world.World world,List<StoredBlock> expected) {
        require(expected.size()==32&&world.getChunkProvider().chunkExists(0,0),"Actual loaded stand chunk/32 states missing");
        org.mtr.mapping.holder.World mapped = new org.mtr.mapping.holder.World(world);
        for(StoredBlock block:expected) {
            TileEntity tile=world.getTileEntity(block.x,block.y,block.z);NBTTagCompound tag=null;
            if(tile!=null) { tag=new NBTTagCompound();tile.writeToNBT(tag); }
            block.same(new StoredBlock(block.x,block.y,block.z,Block.getIdFromBlock(world.getBlock(block.x,block.y,block.z)),
                world.getBlockMetadata(block.x,block.y,block.z),tag));
            if(block.x==8&&block.z==8) {
                Block nativeBlock=world.getBlock(block.x,block.y,block.z);
                require(nativeBlock instanceof BlockLiftTrackBase && (nativeBlock instanceof BlockLiftTrackFloor)==(block.y==72||block.y==80||block.y==88),"Original shaft block type differs");
                org.mtr.mapping.holder.BlockState state=mapped.getBlockState(new BlockPos(block.x,block.y,block.z));
                require("north".equals(state.data.getSerializedProperties().get("facing"))
                    && ((BlockLiftTrackBase)nativeBlock).getConnectingDirections(state).contains(org.mtr.mapping.holder.Direction.UP)
                    && ((BlockLiftTrackBase)nativeBlock).getConnectingDirections(state).contains(org.mtr.mapping.holder.Direction.DOWN),"Original shaft state/connections differ");
            } else require(world.getBlock(block.x,block.y,block.z) instanceof BlockLiftDoor
                || block.x==9&&world.getBlock(block.x,block.y,block.z) instanceof BlockLiftButtons,"Original native landing block type differs");
            if(block.x==8&&block.z==8&&(block.y==72||block.y==80||block.y==88)) {
                require(tile instanceof BlockLiftTrackFloor.BlockEntity,"Original floor tile type missing");
                BlockLiftTrackFloor.BlockEntity floor=(BlockLiftTrackFloor.BlockEntity)tile;int index=(block.y-72)/8;
                require(Integer.toString(index+1).equals(floor.getFloorNumber())&&FLOOR_NAMES[index].equals(floor.getFloorDescription())&&!floor.getShouldDing(),"Native floor labels/ding differ");
            } else if(block.x==9&&block.z==5) {
                require(tile instanceof BlockLiftButtons.BlockEntity,"Original landing-button tile missing");
                List<BlockPos> positions=new ArrayList<>();((BlockLiftButtons.BlockEntity)tile).forEachTrackPosition(positions::add);
                require(positions.size()==1&&positions.get(0).equals(new BlockPos(8,block.y,8)),"Native persisted floor link differs");
            }
        }
    }
    private static final class StoredBlock {
        final int x,y,z,id,metadata;final NBTTagCompound tag;final Map<String,String> properties;
        StoredBlock(int x,int y,int z,int id,int metadata,NBTTagCompound tag) {
            this.x=x;this.y=y;this.z=z;this.id=id;this.metadata=metadata;this.tag=tag;
            Block block=Block.getBlockById(id);require(block!=null,"Unknown native saved block ID");
            BlockStateDefinition definition=BlockStateDefinition.forBlock(block);
            require((tag!=null)==block.hasTileEntity(metadata),"Native tile presence differs from original block contract");
            require(!definition.requiresTileEntity()||tag!=null&&tag.hasKey("mtr_block_state",10),"Missing persisted extended native state");
            properties=definition.read(metadata,tag==null?new NBTTagCompound():tag).getSerializedProperties();
        }
        void same(StoredBlock actual) {
            require(x==actual.x&&y==actual.y&&z==actual.z&&id==actual.id&&metadata==actual.metadata
                &&properties.equals(actual.properties)&&Objects.equals(tag,actual.tag),"Loaded/saved native state or tile differs at "+x+","+y+","+z);
        }
    }
    static NBTTagCompound readChunk(Path root,Path file) throws Exception {
        require(Guard.checked(file).startsWith(Guard.checked(root)),"Anvil escaped exact owned save");
        try(RandomAccessFile region=new RandomAccessFile(file.toFile(),"r")) {
            require(region.length()>=8192&&region.length()%4096==0,"Malformed saved Anvil length");
            int location=region.readInt(),sector=location>>>8,sectors=location&255;
            require(sector>=2&&sectors>0&&((long)sector+sectors)*4096<=region.length(),"Invalid saved Anvil sectors");
            region.seek((long)sector*4096);int length=region.readInt(),compression=region.readUnsignedByte();
            require(length>1&&length<=sectors*4096-4&&(compression==1||compression==2),"Malformed saved chunk payload");
            byte[] bytes=new byte[length-1];region.readFully(bytes);
            try(InputStream compressed=new ByteArrayInputStream(bytes);InputStream expanded=compression==1?new GZIPInputStream(compressed):new InflaterInputStream(compressed);
                DataInputStream input=new DataInputStream(expanded)) {
                return CompressedStreamTools.read(input).getCompoundTag("Level");
            }
        }
    }
    private static byte[] array(NBTTagCompound section,String name,int length,boolean optional) {
        if(!section.hasKey(name)) { require(optional,"Missing saved Anvil array "+name);return new byte[0]; }
        require(section.hasKey(name,7),"Saved Anvil array has wrong type");byte[] bytes=section.getByteArray(name);
        require(bytes.length==length,"Saved Anvil array has wrong length");return bytes;
    }
    private static int nibble(byte[] data,int slot) { return (data[slot>>1]>>((slot&1)*4))&15; }

    static final class Guard {
        static final String ACCEPTED = "f59c1542-8923-4524-aeaa-df2ecf6ff742";
        static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
        static Path artifact(java.net.URL location,Path allowedJar) throws Exception {
            if ("jar".equals(location.getProtocol())) {
                java.net.JarURLConnection connection=(java.net.JarURLConnection)location.openConnection();
                String entry=connection.getEntryName();
                require(entry==null||entry.isEmpty()||entry.equals("org/mtr/gtnh/probe/LiftReloadProbe.class"),"Unexpected nested/class CodeSource");
                location=connection.getJarFileURL(); // Parse only: never connect or open a ZIP filesystem.
            }
            java.net.URI uri=location.toURI();
            require("file".equals(uri.getScheme())&&uri.getAuthority()==null&&uri.getQuery()==null&&uri.getFragment()==null,"CodeSource must be a local file JAR");
            Path artifact=checked(Paths.get(uri));
            require(artifact.equals(checked(allowedJar))&&Files.isRegularFile(artifact,LinkOption.NOFOLLOW_LINKS),"Foreign CodeSource JAR");
            return artifact;
        }
        static Path checked(Path value) throws Exception {
            Path absolute = value.toAbsolutePath().normalize();
            for (Path part = absolute; part != null; part = part.getParent()) require(!Files.isSymbolicLink(part), "Symlink path: " + part);
            return absolute.toRealPath();
        }
        static void paths(Path client, Path allowedClient, Path save, Path allowedSave) throws Exception {
            require(client.toAbsolutePath().normalize().equals(allowedClient.toAbsolutePath().normalize()), "Foreign copied client");
            require(save.toAbsolutePath().normalize().equals(allowedSave.toAbsolutePath().normalize()), "Foreign save");
            Path realClient = checked(client), realSave = checked(save);
            require(realClient.equals(checked(allowedClient)) && realSave.equals(checked(allowedSave))
                && realSave.getParent().equals(realClient.resolve("saves")), "Save escaped exact client");
            Path owner = realSave.resolve("mtr-lift-probe-owner.txt");
            require(Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) && checked(owner).startsWith(realSave)
                && ACCEPTED.equals(new String(Files.readAllBytes(owner), StandardCharsets.UTF_8)), "Foreign save owner");
        }
        static void onlyProbe(Collection<String> mods) {
            for (String mod : mods) require(!mod.startsWith("mtr_gtnh_") || !mod.endsWith("_probe")
                || mod.equals("mtr_gtnh_lift_reload_probe"), "Coexisting acceptance probe: " + mod);
        }
        static JsonObject admission(Path marker, Path work, Path source, Path jar, Path client, Path save) throws Exception {
            Path realWork = checked(work);
            require(marker.toAbsolutePath().normalize().equals(realWork.resolve("parent-admission.json")), "Foreign admission path");
            require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && checked(marker).startsWith(realWork)
                && Files.size(marker) > 0 && Files.size(marker) < 65536, "Missing/invalid parent admission");
            JsonObject value = new Gson().fromJson(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8),JsonObject.class);
            require("mtr-lift-native-reload-admission-v1".equals(value.get("schema").getAsString())
                && ACCEPTED.equals(value.get("accepted_run_id").getAsString()), "Wrong admission scope");
            String run = value.get("run_id").getAsString();
            require(UUID.fromString(run).toString().equals(run), "Noncanonical run UUID");
            require(client.toString().equals(value.get("client_directory").getAsString())
                && save.toString().equals(value.get("save_directory").getAsString()), "Wrong admitted paths");
            require(value.get("backup_verified").isJsonPrimitive() && value.get("backup_verified").getAsJsonPrimitive().isBoolean()
                && value.get("backup_verified").getAsBoolean(), "Backup was not verified by parent");
            Path backup = checked(Paths.get(value.get("backup_archive").getAsString()));
            require(backup.startsWith(realWork) && !backup.startsWith(client) && Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                && Files.size(backup) > 0 && sha(backup).equals(value.get("backup_sha256").getAsString()), "Invalid verified backup");
            require(sha(checked(source)).equals(value.get("probe_source_sha256").getAsString())
                && Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)
                && sha(checked(jar)).equals(value.get("probe_jar_sha256").getAsString()), "Probe source/artifact pin differs");
            require(!Files.exists(realWork.resolve("parent-admission.used-" + run + ".json"), LinkOption.NOFOLLOW_LINKS), "Admission already spent");
            value.addProperty("_marker_sha256", sha(marker));
            return value;
        }
        static void baseline(Path save, Map<String,String> expected) throws Exception {
            Path root = checked(save);
            for (Map.Entry<String,String> entry : expected.entrySet()) {
                Path file = root.resolve(entry.getKey());
                require(checked(file).startsWith(root) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && sha(file).equals(entry.getValue()), "Saved baseline changed: " + entry.getKey());
            }
        }
        static void claim(Path marker, Path work, JsonObject value) throws Exception {
            require(checked(marker).equals(checked(work).resolve("parent-admission.json"))
                && sha(marker).equals(value.get("_marker_sha256").getAsString()), "Admission changed before claim");
            String run = value.get("run_id").getAsString();
            require(UUID.fromString(run).toString().equals(run), "Invalid claim UUID");
            Files.move(marker, work.resolve("parent-admission.used-" + run + ".json"));
        }
        static String sha(Path file) throws Exception {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b & 255)); return result.toString();
        }
    }
}
