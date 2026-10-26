package org.mtr.gtnh.probe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import org.mtr.core.Main;
import org.mtr.core.data.Data;
import org.mtr.core.data.Lift;
import org.mtr.core.data.LiftDirection;
import org.mtr.core.data.LiftFloor;
import org.mtr.core.data.Position;
import org.mtr.core.operation.PressLift;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.serializer.MessagePackReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.gtnh.RidingPermissions;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.org.msgpack.core.MessagePack;
import org.mtr.libraries.org.msgpack.core.MessageUnpacker;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.tool.BlockStateDefinition;
import org.mtr.mapping.tool.BlockStateTileEntity;
import org.mtr.mapping.tool.PlayerPhysics;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockLiftButtons;
import org.mtr.mod.block.BlockLiftDoor;
import org.mtr.mod.block.BlockLiftTrackBase;
import org.mtr.mod.block.BlockLiftTrackFloor;
import org.mtr.mod.block.BlockPSDAPGDoorBase;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.item.ItemBlockClickingBase;
import org.mtr.mod.item.ItemLiftRefresher;
import org.mtr.mod.packet.PacketPressLiftButton;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateLiftTrackFloorConfig;
import org.mtr.mod.model.ModelLift1;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector4f;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Test-only, explicitly selected alternative to WorldProbe and MenuProbe. Never opens a previous save. */
@SideOnly(Side.CLIENT)
@Mod(modid = "mtr_gtnh_lift_probe", name = "MTR GTNH original lift acceptance probe", version = "0",
    dependencies = "required-after:mtr", acceptableRemoteVersions = "*")
public final class LiftProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT = new File(requiredProbePath("mtr.probeClient"));
    private static final int[] FLOOR_Y = {72, 80, 88};
    private static final String[] DESCRIPTIONS = {"Lower", "Middle", "Upper"};
    private enum Stage { MENU, WORLD, SETUP, CHUNKS, LABELS, GENERATE, CONFIGURE, READY, CALL, PICKUP,
        BOARD, QUEUE3, QUEUE1, QUEUED, UP, TOP, DOWN, BOTTOM, EXIT, DISMOUNT, GRAVITY, IDLE, CLOSE, FINISHED }

    // Stage/ack publish handoffs; client samples and core samples are immutable after publication.
    private volatile Stage stage = Stage.MENU, clientAcknowledged;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private volatile ClientSample clientSample;
    private volatile int clientTicks;
    private volatile JsonObject renderSampleDiagnostics;
    private final RenderSampleGate renderSampleGate = new RenderSampleGate();
    private long consumedClientFrame;
    private volatile CoreSample coreSample;
    private volatile long liftId;
    private volatile int shotRequest, shotAcknowledged;
    private volatile String shotLabel;
    private final AtomicBoolean coreReadPending = new AtomicBoolean();
    private final AtomicInteger s08 = new AtomicInteger(), c03 = new AtomicInteger();
    private final AtomicInteger c17 = new AtomicInteger(), fmlProxy = new AtomicInteger(), mtrFrames = new AtomicInteger();
    private final AtomicInteger ridingInbound = new AtomicInteger(), ridingRejections = new AtomicInteger();
    private final ConcurrentLinkedQueue<JsonObject> corrections = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonObject> inboundFrames = new ConcurrentLinkedQueue<>(), rejectionReasons = new ConcurrentLinkedQueue<>();
    private volatile JsonObject lastC03;
    private volatile JsonObject lastRidingInbound, lastRidingRejection;
    private org.apache.logging.log4j.core.Logger ridingLogger;
    private org.apache.logging.log4j.Level previousRidingLevel;
    private org.apache.logging.log4j.core.appender.AbstractAppender ridingLogObserver;
    private boolean ridingLogRestored;
    private volatile JsonObject clientDiagnostics;
    private volatile boolean packetObserverReady;
    private volatile boolean clientBoardingAuthorized;
    private boolean packetObserverRequested;
    private int menuTicks, phaseTicks, readyChunkTicks, renderedFrames, setupTeleports;
    private int boardingS08, motionS08, motionC03, stillTicks, jumpClientTicks;
    private long startedAt;
    private volatile long stageStartedAt;
    private boolean copyVerified, standBuilt, queueConfirmed, movingCaptured, jumped, gravityFalling;
    private double jumpPeak, serverJumpPeak, previousJumpY;
    private String runId, saveName;
    private File saveDirectory;
    private Simulator simulator;
    private final JsonObject report = new JsonObject();
    private final JsonArray timeline = new JsonArray(), transitions = new JsonArray(), screenshots = new JsonArray();
    private final JsonArray placements = new JsonArray(), interactions = new JsonArray();
    private final JsonArray pauseOptionCorrections = new JsonArray();
    private final JsonArray cabinRenderWitnesses = new JsonArray();
    private int stationaryRidingWitnesses, droppedRenderWitnesses;
    private int lastScriptedControlTick = -1000;
    private static final double CABIN_JITTER_MAX_RANGE = .001;
    private double ridingFloorMinimum = Double.POSITIVE_INFINITY, ridingFloorMaximum = Double.NEGATIVE_INFINITY;
    private final List<StoredBlock> expectedSavedBlocks = new ArrayList<>();
    private final DoorCycle[] doors = {new DoorCycle(), new DoorCycle(), new DoorCycle()};
    private final DoorCycle[] landingDoors = {new DoorCycle(), new DoorCycle(), new DoorCycle()};

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        startedAt = stageStartedAt = System.currentTimeMillis();
        require(!Loader.isModLoaded("mtr_gtnh_world_probe") && !Loader.isModLoaded("mtr_gtnh_probe"),
            "LiftProbe must be the only selected acceptance probe");
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage == Stage.FINISHED) return;
        Minecraft client = Minecraft.getMinecraft();
        try {
            if (failure != null) { finish(client); return; }
            require(System.currentTimeMillis() - startedAt < 15 * 60 * 1000L, "Lift probe total deadline in " + stage);
            if (stage == Stage.MENU) {
                if (!Loader.instance().hasReachedState(LoaderState.AVAILABLE) || client.theWorld != null || client.currentScreen == null) {
                    menuTicks = 0;
                    return;
                }
                String screen = client.currentScreen.getClass().getName();
                if (!screen.equals("net.minecraft.client.gui.GuiMainMenu") && !screen.equals("lumien.custommainmenu.gui.GuiCustom")) {
                    menuTicks = 0;
                    return;
                }
                if (++menuTicks < 60) return;
                reserveNewSave(client);
                report.addProperty("menu_class", screen);
                client.gameSettings.pauseOnLostFocus = false;
                client.gameSettings.thirdPersonView = 0;
                client.gameSettings.renderDistanceChunks = 5;
                client.gameSettings.saveOptions();
                advance(Stage.WORLD);
                WorldSettings settings = new WorldSettings(0x4D54524C494654L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT)
                    .enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName, "MTR lift acceptance " + runId, settings);
                return;
            }
            if (copyVerified && client.getIntegratedServer() != null) {
                assertOwnedServer(client.getIntegratedServer());
                if (client.gameSettings.pauseOnLostFocus) {
                    client.gameSettings.pauseOnLostFocus = false;
                    client.gameSettings.saveOptions();
                    JsonObject correction = new JsonObject();
                    correction.addProperty("phase", stage.name()); correction.addProperty("elapsed_ms", System.currentTimeMillis() - startedAt);
                    pauseOptionCorrections.add(correction);
                }
            }
            if (stage == Stage.WORLD) {
                if (client.theWorld == null || client.thePlayer == null || client.getIntegratedServer() == null) return;
                assertOwnedServer(client.getIntegratedServer());
                require(client.thePlayer.dimension == 0, "Actual client player did not enter overworld");
                playerId = client.thePlayer.getUniqueID();
                report.addProperty("player_uuid", playerId.toString());
                advance(Stage.SETUP);
            }
            if (stage == Stage.CLOSE) { finish(client); return; }
            require(client.theWorld != null && client.thePlayer != null && client.getIntegratedServer() != null,
                "Owned world/player unexpectedly disappeared");
            JsonObject diagnostic = new JsonObject();
            diagnostic.addProperty("phase", stage.name()); diagnostic.addProperty("client_tick", clientTicks);
            diagnostic.addProperty("sampling_boundary", "ClientTick.END native actions/diagnostics");
            diagnostic.addProperty("screen_before_owned_dismiss", client.currentScreen == null ? "null" : client.currentScreen.getClass().getName());
            diagnostic.addProperty("native_paused", client.isGamePaused()); diagnostic.addProperty("pause_on_lost_focus", client.gameSettings.pauseOnLostFocus);
            diagnostic.addProperty("native_world_tick", client.theWorld.getTotalWorldTime()); diagnostic.addProperty("native_focus", client.inGameHasFocus);
            clientDiagnostics = diagnostic;
            if (client.currentScreen != null) client.displayGuiScreen(null);
            KeyBinding.unPressAllKeys();
            Stage current = stage;
            require(System.currentTimeMillis() - stageStartedAt < (current == Stage.CHUNKS ? 90000 : 60000),
                "Client watchdog: stalled lift phase " + diagnostic);
            if (clientTicks % 100 == 0) System.out.println("MTR_LIFT_PROBE_PROGRESS " + diagnostic);
            if (current == Stage.BOARD) {
                // Keep natural movement active for the original delayed (1000ms) boarding update.
                double z = client.thePlayer.posZ;
                if (z < 6.55) key(client.gameSettings.keyBindForward, true);
                else if (!clientBoardingAuthorized) key(client.gameSettings.keyBindBack, true);
            } else if (current == Stage.EXIT) {
                if (client.thePlayer.posZ > 4.35) key(client.gameSettings.keyBindBack, true);
            } else if (current == Stage.GRAVITY && jumpClientTicks++ < 2) {
                key(client.gameSettings.keyBindJump, true);
            }
            if (movementControlsActive(client)) lastScriptedControlTick = clientTicks;

            Lift lift = liftId == 0 ? findOnlyClientLift() : MinecraftClientData.getLift(liftId);
            if (current == Stage.LABELS && clientAcknowledged != current && clientStandReady(client)) {
                for (int floor = 0; floor < 3; floor++) InitClient.REGISTRY_CLIENT.sendPacketToServer(
                    new PacketUpdateLiftTrackFloorConfig(floorPos(floor), Integer.toString(floor + 1), DESCRIPTIONS[floor], false));
                clientAcknowledged = current;
            } else if (current == Stage.CONFIGURE && clientAcknowledged != current && lift != null) {
                require(lift.getId() == liftId, "Configuration would target a different original Lift");
                lift.setDimensions(3, 2, 2, 0, 0, -1.5);
                lift.setAngle(Angle.fromAngle(270));
                lift.setIsDoubleSided(false);
                InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance()).addLift(lift)));
                clientAcknowledged = current;
            } else if (current == Stage.CALL && clientAcknowledged != current) {
                require(client.thePlayer.getHeldItem() == null, "Landing call must use the real empty hand");
                require(client.theWorld.getBlock(9, 80, 5).onBlockActivated(client.theWorld, 9, 80, 5,
                    client.thePlayer, 2, .5F, .375F, .5F), "Original client landing-button activation rejected floor2 UP");
                clientAcknowledged = current;
            } else if ((current == Stage.QUEUE3 || current == Stage.QUEUE1) && clientAcknowledged != current) {
                require(VehicleRidingMovement.isRiding(liftId), "Cabin selection attempted before native boarding");
                PressLift press = new PressLift();
                press.add(new Position(8, current == Stage.QUEUE3 ? 88 : 72, 8), LiftDirection.NONE);
                InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketPressLiftButton(press));
                clientAcknowledged = current;
            }
            ++clientTicks; // Native tick counter/actions remain independent of render frequency.
        } catch (Throwable problem) { fail(problem); }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || failure != null) return;
        Stage current = stage;
        if (current == Stage.MENU || current == Stage.WORLD || current == Stage.CLOSE || current == Stage.FINISHED) return;
        try {
            MinecraftServer server = MinecraftServer.getServer();
            assertOwnedServer(server);
            EntityPlayerMP player = actualPlayer(server);
            if (player == null) {
                require(current == Stage.SETUP && ++phaseTicks < 200, "Actual server player is missing or disconnected");
                return;
            }
            WorldServer world = server.worldServerForDimension(0);
            require(player.worldObj == world, "Stand and real player are in different native worlds");
            require(System.currentTimeMillis() - stageStartedAt < (current == Stage.CHUNKS ? 90000 : 60000),
                "Lift phase deadline in " + current);
            ++phaseTicks;
            if (current == Stage.SETUP) {
                if (!packetObserverRequested) {
                    report.add("initial_server_player", playerPosition(player));
                    player.setGameType(WorldSettings.GameType.CREATIVE);
                    player.capabilities.isFlying = false;
                    player.sendPlayerAbilities();
                    installPacketObserver(player.playerNetServerHandler.netManager);
                    packetObserverRequested = true;
                    return;
                }
                if (!packetObserverReady) return;
                setupTeleport(player, 8, 5, 4.5);
                advance(Stage.CHUNKS);
                return;
            }
            ClientSample client = nextRenderedClientSample();
            if (client == null) return; // Watchdogs above still run when no fresh actual frame is available.
            if (current == Stage.CHUNKS) {
                JsonObject chunk = chunkStatus(world, 0, 0);
                report.add("stand_chunk_last_wait", chunk);
                report.addProperty("stand_chunk_wait_ticks", phaseTicks);
                report.add("stand_wait_player", playerPosition(player));
                boolean arrived = client != null && Math.abs(client.x - 8) < .5 && Math.abs(client.z - 4.5) < .5
                    && Math.abs(player.managedPosX - 8) < .5 && Math.abs(player.managedPosZ - 4.5) < .5;
                readyChunkTicks = arrived && chunk.get("ready").getAsBoolean() ? readyChunkTicks + 1 : 0;
                if (readyChunkTicks < 2 || !packetObserverReady) return;
                buildStand(world, player);
                simulator = actualSimulator();
                advance(Stage.LABELS);
                return;
            }
            readCoreSoon();
            CoreSample core = coreSample;
            if (core != null) require(System.currentTimeMillis() - core.at < 5000, "Original simulator stopped publishing read-only samples");
            observe(world, player, client, core);
            switch (current) {
                case LABELS:
                    if (clientAcknowledged != current || !labelsMatch(world) || !client.labelsMatch) return;
                    report.addProperty("labels_server_client_acknowledged_before_refresher", true);
                    report.add("before_generate_actual_client_interest", client.interest());
                    use(world, player, new ItemStack(originalItem("lift_refresher", org.mtr.mod.Items.LIFT_REFRESHER.get().data)), 8, 72, 8, 1, .5F);
                    advance(Stage.GENERATE);
                    break;
                case GENERATE:
                    if (core == null || core.count == 0 || client.liftId == 0) return;
                    require(core.count == 1 && core.id == client.liftId, "Refresher did not create exactly one server/client Lift");
                    liftId = core.id;
                    require(liftId != 0 && core.floorCount == 3 && client.floorCount == 3, "Original Lift has wrong floor count/ID");
                    require(core.floorGeometry && client.floorGeometry, "Original refresher floor order/labels differ");
                    report.addProperty("original_assigned_lift_id", Long.toString(liftId));
                    report.add("generated_original_core_lift", core.json);
                    advance(Stage.CONFIGURE);
                    break;
                case CONFIGURE:
                    if (clientAcknowledged != current || core == null || !core.configured || !client.configured) return;
                    require(core.id == liftId && core.floorGeometry, "Server accepted cabin configuration for the wrong Lift/floors");
                    require(Math.abs(client.cabinX - 8) < .01 && Math.abs(client.cabinY - 72) < .01
                        && Math.abs(client.cabinZ - 6.5) < .01, "Actual original path/offsets do not match proposed geometry");
                    report.add("authoritative_cabin_configuration", core.json);
                    report.add("actual_cabin_geometry", client.geometry());
                    connectAndUnlock(world, player);
                    player.inventory.mainInventory[0] = null;
                    player.inventoryContainer.detectAndSendChanges();
                    player.capabilities.isFlying = false;
                    player.sendPlayerAbilities();
                    setupTeleport(player, 8, 80, 4.5);
                    advance(Stage.READY);
                    break;
                case READY:
                    if (!client.linksMatch || !client.unlocked || !client.buttonStates || !client.emptyHand
                        || !client.onGround || Math.abs(client.y - 80) > .08 || Math.abs(player.boundingBox.minY - 80) > .08) return;
                    require(Math.abs(client.x - 8) < .1 && Math.abs(client.z - 4.5) < .1, "Pickup setup teleport did not arrive");
                    if (++stillTicks < 10) return;
                    assertStand(world);
                    if (!captureDone("shaft-links")) return;
                    boardingS08 = s08.get();
                    advance(Stage.CALL);
                    break;
                case CALL:
                    if (clientAcknowledged == current) advance(Stage.PICKUP);
                    break;
                case PICKUP:
                    if (!stoppedAt(client, core, 1) || client.door < .95 || client.landingDoors[1] < .70) return;
                    if (!captureDone("pickup2")) return;
                    report.addProperty("pickup_actual_core_rail_progress", core.progress);
                    advance(Stage.BOARD);
                    break;
                case BOARD:
                    require(stoppedAt(client, core, 1) && client.door > .2,
                        "Natural boarding/authorization missed the original pickup door window");
                    if (!client.riding || !PlayerPhysics.authorized(player) || client.z < 6.2 || client.z > 7.1) return;
                    require(Math.abs(client.x - 8) < .6, "Native boarding introduced lateral drift");
                    report.addProperty("natural_render_boarding_authorized", true);
                    motionS08 = s08.get(); motionC03 = c03.get();
                    advance(Stage.QUEUE3);
                    break;
                case QUEUE3:
                    assertPassenger(player, client);
                    if (clientAcknowledged == current) advance(Stage.QUEUE1);
                    break;
                case QUEUE1:
                    assertPassenger(player, client);
                    if (clientAcknowledged == current) advance(Stage.QUEUED);
                    break;
                case QUEUED:
                    assertPassenger(player, client);
                    if (core == null || !core.topInstruction || !core.bottomInstruction || !client.topInstruction || !client.bottomInstruction) return;
                    queueConfirmed = true;
                    report.add("server_confirmed_two_separate_instructions", core.json);
                    advance(Stage.UP);
                    break;
                case UP:
                    assertPassenger(player, client);
                    if (!movingCaptured && client.cabinY > 81 && client.cabinY < 87 && core != null && Math.abs(core.speed) > .00001) {
                        require(client.door <= .02, "Cabin doors are open during upward travel");
                        movingCaptured = captureDone("moving-passenger");
                    }
                    if (stoppedAt(client, core, 2)) {
                        require(movingCaptured, "No real moving-passenger framebuffer was captured");
                        require(!core.topInstruction && core.bottomInstruction, "Server did not consume floor3 while preserving floor1");
                        advance(Stage.TOP);
                    }
                    break;
                case TOP:
                    assertPassenger(player, client);
                    if (client.door >= .95 && client.landingDoors[2] >= .70 && captureDone("stop3")) {
                        report.addProperty("actual_stop3_client_y", client.y);
                        report.addProperty("actual_stop3_server_y", player.boundingBox.minY);
                    }
                    if (core != null && core.speed < -.00001 && client.cabinY < 87.8) {
                        require(doors[2].complete() && landingDoors[2].complete(), "Upper-floor cabin/landing door cycle missing");
                        require(shotAcknowledged >= shotRequest && "stop3".equals(shotLabel), "Upper-stop framebuffer missing");
                        advance(Stage.DOWN);
                    }
                    break;
                case DOWN:
                    assertPassenger(player, client);
                    if (core != null && Math.abs(core.speed) > .00001) require(client.door <= .02, "Cabin doors are open during downward travel");
                    if (stoppedAt(client, core, 0)) {
                        require(!core.topInstruction && !core.bottomInstruction, "Server queue did not clear at lower stop");
                        advance(Stage.BOTTOM);
                    }
                    break;
                case BOTTOM:
                    assertPassenger(player, client);
                    if (client.door < .95 || client.landingDoors[0] < .70 || !captureDone("stop1")) return;
                    require(queueConfirmed && doors[1].complete() && doors[2].complete()
                        && landingDoors[1].complete() && landingDoors[2].complete(), "Pickup/top real door cycles incomplete");
                    report.addProperty("actual_stop1_client_y", client.y);
                    report.addProperty("actual_stop1_server_y", player.boundingBox.minY);
                    advance(Stage.EXIT);
                    break;
                case EXIT:
                    require(stoppedAt(client, core, 0) && client.door > .05, "Natural exit missed the original lower-door window");
                    if (client.z <= 4.4) advance(Stage.DISMOUNT);
                    break;
                case DISMOUNT:
                    if (client.riding || PlayerPhysics.authorized(player) || !client.onGround || !player.onGround) return;
                    require(!client.noClip && !player.noClip, "Original dismount did not restore clipping");
                    require(Math.abs(client.y - 72) < .1 && Math.abs(player.boundingBox.minY - 72) < .1,
                        "Dismounted player did not land on the actual lower stone platform");
                    report.addProperty("natural_dismount_server_revoked", true);
                    if (++stillTicks < 5) return;
                    previousJumpY = client.y; jumpPeak = client.y; serverJumpPeak = player.boundingBox.minY;
                    advance(Stage.GRAVITY);
                    break;
                case GRAVITY:
                    require(!client.riding && !PlayerPhysics.authorized(player), "Riding permission returned after natural dismount");
                    require(Math.abs(PlayerPhysics.gravity(.08, player) - .08) < 1E-10, "Normal server gravity is still suppressed");
                    if (client.y > 72.15) jumped = true;
                    jumpPeak = Math.max(jumpPeak, client.y);
                    serverJumpPeak = Math.max(serverJumpPeak, player.boundingBox.minY);
                    if (jumped && client.y < previousJumpY - .01) gravityFalling = true;
                    previousJumpY = client.y;
                    if (!jumped || !gravityFalling || !client.onGround || !player.onGround || Math.abs(client.y - 72) > .1) return;
                    require(serverJumpPeak > 72.15 && Math.abs(player.boundingBox.minY - 72) < .1,
                        "Native server did not observe the dismounted jump and landing on stone");
                    report.addProperty("native_jump_peak_y_after_dismount", jumpPeak);
                    report.addProperty("native_jump_server_peak_y_after_dismount", serverJumpPeak);
                    report.addProperty("native_gravity_restored", true);
                    advance(Stage.IDLE);
                    break;
                case IDLE:
                    if (!stoppedAt(client, core, 0) || client.door != 0 || core.cooldown || !core.emptyQueue || client.riding) return;
                    for (int floor = 0; floor < 3; floor++) require(doors[floor].complete() && landingDoors[floor].complete(),
                        "Cabin/landing door cycles incomplete on floor " + (floor + 1));
                    require(setupTeleports == 2, "Unexpected setup teleport count");
                    require(s08.get() - motionS08 <= 2, "Repeated native S08 corrections during the original ride/exit");
                    require(c03.get() > motionC03 + 15, "Too few real native movement packets during trip");
                    assertStand(world);
                    snapshotStand(world);
                    report.add("final_original_core_state", core.json);
                    advance(Stage.CLOSE);
                    break;
                default: break;
            }
        } catch (Throwable problem) { fail(problem); }
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (failure != null || stage == Stage.FINISHED || !copyVerified) return;
        try {
            Minecraft client = Minecraft.getMinecraft();
            if (client.getIntegratedServer() != null) assertOwnedServer(client.getIntegratedServer());
            boolean owned = client.getIntegratedServer() != null && client.theWorld != null && client.thePlayer != null
                && playerId != null && playerId.equals(client.thePlayer.getUniqueID()) && client.thePlayer.dimension == 0;
            Lift lift = owned ? (liftId == 0 ? findOnlyClientLift() : MinecraftClientData.getLift(liftId)) : null;
            MinecraftClientData.LiftWrapper wrapper = lift == null ? null : MinecraftClientData.getInstance().liftWrapperList.get(lift.getId());
            boolean visible = lift == null || wrapper != null && wrapper.getLift() == lift && wrapper.shouldRender;
            boolean eligible = owned && stage != Stage.MENU && stage != Stage.WORLD && stage != Stage.CLOSE
                && client.currentScreen == null && !client.skipRenderWorld && !client.isGamePaused()
                && client.renderViewEntity == client.thePlayer && client.displayWidth > 0 && client.displayHeight > 0 && visible;
            int tick = clientTicks;
            // Original MainRenderer writes this only in its non-shadow world-render branch, before RenderLifts reconciles the rider.
            long mtrRenderedMillis = eligible ? ((Number) diagnosticField(org.mtr.mod.render.MainRenderer.class, "lastRenderedMillis")).longValue() : -1;
            ClientSample sampled = renderSampleGate.sample(event.phase, eligible, client.theWorld, client.thePlayer, lift, tick, mtrRenderedMillis,
                () -> new ClientSample(client, lift, tick, renderSampleGate.frame, mtrRenderedMillis));
            if (event.phase == TickEvent.Phase.START && eligible && lift != null) scheduleCabinRenderWitness(client, lift, event.renderTickTime);
            if (event.phase != TickEvent.Phase.END) return;
            JsonObject diagnostic = new JsonObject(); diagnostic.addProperty("sampling_boundary", "RenderTick.END after original MTR world render");
            diagnostic.addProperty("frame", renderSampleGate.frame); diagnostic.addProperty("native_client_tick", tick);
            diagnostic.addProperty("eligible", eligible); diagnostic.addProperty("owned_world_player", owned); diagnostic.addProperty("lift_visible", visible);
            diagnostic.addProperty("native_paused", client.isGamePaused()); diagnostic.addProperty("skip_render_world", client.skipRenderWorld);
            diagnostic.addProperty("mtr_render_millis_before", renderSampleGate.mtrBefore); diagnostic.addProperty("mtr_render_millis_after", mtrRenderedMillis);
            diagnostic.addProperty("sample_published", sampled != null); renderSampleDiagnostics = diagnostic;
            if (sampled == null) return;
            clientSample = sampled;
            if (shotRequest <= shotAcknowledged) return;
            if (++renderedFrames < 4) return;
            String name = "mtr-lift-probe-" + runId + "-" + shotLabel + ".png";
            ScreenShotHelper.saveScreenshot(client.mcDataDir, name, client.displayWidth, client.displayHeight, client.getFramebuffer());
            File file = new File(new File(client.mcDataDir, "screenshots"), name);
            require(file.isFile() && file.length() > 100, "Actual lift framebuffer was not written");
            JsonObject value = new JsonObject();
            value.addProperty("phase", shotLabel); value.addProperty("path", file.getCanonicalPath());
            value.addProperty("render_frames_after_request", renderedFrames); value.addProperty("client_tick", tick); value.addProperty("render_frame", sampled.frame);
            screenshots.add(value);
            shotAcknowledged = shotRequest;
        } catch (Throwable problem) { fail(problem); }
    }

    private void scheduleCabinRenderWitness(Minecraft client, Lift lift, float partial) throws Exception {
        org.mtr.mapping.holder.Identifier id = new org.mtr.mapping.holder.Identifier("mtr", "lift_probe_witness_" + runId);
        MainRenderer.cancelRender(id);
        if (stage != Stage.PICKUP && stage != Stage.UP && stage != Stage.TOP && stage != Stage.DOWN && stage != Stage.BOTTOM) return;
        net.minecraft.client.entity.EntityClientPlayerMP player = client.thePlayer;
        JsonObject value = new JsonObject(); value.addProperty("frame", renderSampleGate.frame); value.addProperty("phase", stage.name());
        value.addProperty("partial_tick", partial); value.addProperty("frame_started_nanos", System.nanoTime());
        value.addProperty("first_person", client.gameSettings.thirdPersonView == 0); value.addProperty("riding_at_frame_start", VehicleRidingMovement.isRiding(lift.getId()));
        double originY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partial;
        value.addProperty("start_feet_y", player.boundingBox.minY); value.addProperty("start_pos_y", player.posY);
        value.addProperty("start_prev_pos_y", player.prevPosY); value.addProperty("start_last_tick_pos_y", player.lastTickPosY);
        value.addProperty("start_native_origin_y", originY);
        value.addProperty("start_interpolation_mismatch_y", player.boundingBox.minY - originY + player.yOffset - player.ySize);
        // TEXT runs after the actual INTERIOR callbacks; inspect their submitted transform, not a freshly recomputed pose.
        MainRenderer.scheduleRender(id, false, QueuedRenderLayer.TEXT, (graphics, offset) -> {
            if (org.mtr.mapping.mapper.OptimizedRenderer.renderingShadows()) return;
            try { captureCabinRenderWitness(client, lift, graphics, offset, value); }
            catch (Throwable problem) { value.addProperty("observer_error", problem.toString()); fail(problem); }
            if (cabinRenderWitnesses.size() < 4096) cabinRenderWitnesses.add(value); else droppedRenderWitnesses++;
        });
    }

    @SuppressWarnings("unchecked")
    private void captureCabinRenderWitness(Minecraft client, Lift lift, GraphicsHolder graphics, org.mtr.mapping.holder.Vector3d offset, JsonObject value) throws Exception {
        List<?> priorities = (List<?>) diagnosticField(MainRenderer.class, "CURRENT_RENDERS");
        Map<?, ?> interior = (Map<?, ?>) ((List<?>) priorities.get(0)).get(QueuedRenderLayer.INTERIOR.ordinal());
        StoredMatrixTransformations submitted = null; ModelLift1 model = null; int matches = 0;
        for (Object callbacks : interior.values()) for (Object callback : (List<?>) callbacks) {
            StoredMatrixTransformations transform = null; ModelLift1 candidate = null;
            for (Field field : callback.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true); Object captured = field.get(callback);
                if (captured instanceof StoredMatrixTransformations) transform = (StoredMatrixTransformations) captured;
                if (captured instanceof ModelLift1) candidate = (ModelLift1) captured;
            }
            if (transform != null && candidate != null) { submitted = transform; model = candidate; matches++; }
        }
        value.addProperty("completed_interior_lift_callbacks", matches);
        if (matches == 0) { value.addProperty("geometry_witness_valid", false); return; }
        require(matches == 1, "Expected one actual ModelLift1 INTERIOR callback in owned single-lift world");
        value.addProperty("geometry_witness_valid", true);
        value.addProperty("submitted_use_default_offset", submitted.useDefaultOffset);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16); GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, buffer);
        Matrix4f view = new Matrix4f(); view.load(buffer); Matrix4f inverse = Matrix4f.invert(view, null);
        require(inverse != null, "Singular native world view during cabin witness");
        Object floor = diagnosticField(model, "main");
        Vector4f floorPivot = new Vector4f(((Number) diagnosticField(floor, "pivotX")).floatValue() / 16,
            ((Number) diagnosticField(floor, "pivotY")).floatValue() / 16, ((Number) diagnosticField(floor, "pivotZ")).floatValue() / 16, 1);
        submitted.transform(graphics, offset);
        Vector4f submittedFloor;
        try { submittedFloor = Matrix4f.transform(graphics.copyPositionMatrix().data, floorPivot, null); }
        finally { graphics.pop(); }
        Vector4f eyeFloor = Matrix4f.transform(view, submittedFloor, null);
        value.addProperty("sampling_boundary", "MainRenderer TEXT after actual ModelLift1 INTERIOR callback, before HUD");
        value.addProperty("witness_source", "completed callback captured StoredMatrixTransformations + actual floor pivot + current GraphicsHolder/native GL view; no extra draw");
        value.addProperty("native_render_manager_origin_y", net.minecraft.client.renderer.entity.RenderManager.renderPosY);
        value.addProperty("native_camera_world_y", net.minecraft.client.renderer.entity.RenderManager.renderPosY + inverse.m31);
        value.addProperty("submitted_floor_world_y", net.minecraft.client.renderer.entity.RenderManager.renderPosY + submittedFloor.y);
        double relativeY = submittedFloor.y - inverse.m31;
        value.addProperty("submitted_floor_camera_relative_y", relativeY); value.addProperty("submitted_floor_eye_space_y", eyeFloor.y);
        value.addProperty("post_move_feet_y", client.thePlayer.boundingBox.minY);
        value.addProperty("post_move_pos_y", client.thePlayer.posY); value.addProperty("post_move_prev_pos_y", client.thePlayer.prevPosY);
        value.addProperty("post_move_last_tick_pos_y", client.thePlayer.lastTickPosY);
        value.addProperty("ticks_since_scripted_controls", clientTicks - lastScriptedControlTick);
        value.addProperty("movement_controls_active", movementControlsActive(client));
        boolean stationary = VehicleRidingMovement.isRiding(lift.getId()) && clientBoardingAuthorized && !submitted.useDefaultOffset
            && (stage == Stage.UP || stage == Stage.DOWN) && client.gameSettings.thirdPersonView == 0
            && clientTicks - lastScriptedControlTick >= 2 && !movementControlsActive(client);
        value.addProperty("stationary_rider_during_trip", stationary);
        if (stationary) { stationaryRidingWitnesses++; ridingFloorMinimum = Math.min(ridingFloorMinimum, relativeY); ridingFloorMaximum = Math.max(ridingFloorMaximum, relativeY); }
    }

    private static boolean movementControlsActive(Minecraft client) {
        for (KeyBinding binding : new KeyBinding[]{client.gameSettings.keyBindForward, client.gameSettings.keyBindBack,
                client.gameSettings.keyBindLeft, client.gameSettings.keyBindRight, client.gameSettings.keyBindJump,
                client.gameSettings.keyBindSneak, client.gameSettings.keyBindSprint}) if (binding.getIsKeyPressed()) return true;
        net.minecraft.util.MovementInput input = client.thePlayer.movementInput;
        return client.thePlayer.moveForward != 0 || client.thePlayer.moveStrafing != 0 || client.thePlayer.isSneaking()
            || input != null && (input.moveForward != 0 || input.moveStrafe != 0 || input.jump || input.sneak);
    }

    private ClientSample nextRenderedClientSample() {
        ClientSample value = clientSample;
        if (value == null || !renderedSampleFresh(value.tick, clientTicks, value.frame, consumedClientFrame, value.atNanos, System.nanoTime())) return null;
        consumedClientFrame = value.frame;
        return value;
    }

    private static boolean renderedSampleFresh(int tick, int currentTick, long frame, long consumed, long atNanos, long nowNanos) {
        return tick == currentTick && frame > consumed && nowNanos >= atNanos && nowNanos - atNanos <= 250000000L;
    }

    private static final class RenderSampleGate {
        private long frame, mtrBefore;
        private int tick;
        private boolean armed;
        private Object world, player, lift;
        private <T> T sample(TickEvent.Phase phase, boolean eligible, Object world, Object player, Object lift, int tick, long mtrRenderedMillis,
                java.util.function.Supplier<T> read) {
            if (phase == TickEvent.Phase.START) {
                frame++; armed = eligible && world != null && player != null;
                this.world = world; this.player = player; this.lift = lift; this.tick = tick; mtrBefore = mtrRenderedMillis;
                return null;
            }
            boolean completed = armed && eligible && this.world == world && this.player == player && this.lift == lift
                && this.tick == tick && mtrRenderedMillis > mtrBefore;
            armed = false;
            return completed ? read.get() : null;
        }
    }


    private void buildStand(WorldServer world, EntityPlayerMP player) {
        require(!standBuilt && chunkStatus(world, 0, 0).get("ready").getAsBoolean(), "Stand placement without native loaded chunk");
        standBuilt = true;
        world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        world.setWorldTime(6000); world.difficultySetting = EnumDifficulty.PEACEFUL;
        for (int x = 6; x <= 10; x++) for (int z = 2; z <= 9; z++) for (int y = 71; y <= 92; y++) world.setBlockToAir(x, y, z);
        for (int y = 71; y <= 92; y++) {
            for (int z = 5; z <= 9; z++) {
                world.setBlock(6, y, z, net.minecraft.init.Blocks.glass, 0, 3);
                world.setBlock(10, y, z, net.minecraft.init.Blocks.glass, 0, 3);
            }
            for (int x = 6; x <= 10; x++) world.setBlock(x, y, 9, net.minecraft.init.Blocks.glass, 0, 3);
        }
        for (int y : FLOOR_Y) for (int x = 6; x <= 10; x++) for (int z = 2; z <= 5; z++)
            world.setBlock(x, y - 1, z, net.minecraft.init.Blocks.stone, 0, 3);
        world.setBlock(8, 71, 8, net.minecraft.init.Blocks.stone, 0, 3);
        player.inventory.currentItem = 0;
        player.rotationYaw = 180;
        for (int y = 72; y <= 88; y++) {
            boolean floor = y == 72 || y == 80 || y == 88;
            Block original = floor ? org.mtr.mod.Blocks.LIFT_TRACK_FLOOR_1.get().data : org.mtr.mod.Blocks.LIFT_TRACK_VERTICAL_1.get().data;
            use(world, player, new ItemStack(originalItem(floor ? "lift_track_floor_1" : "lift_track_1", Item.getItemFromBlock(original))),
                8, y - 1, 8, 1, 1);
            require(world.getBlock(8, y, 8) == original, "Original track BlockItem placed wrong native block at y=" + y);
        }
        player.rotationYaw = 0;
        for (int y : FLOOR_Y) {
            use(world, player, new ItemStack(originalItem("lift_door_1", org.mtr.mod.Items.LIFT_DOOR_1.get().data)), 8, y - 1, 5, 1, 1);
            use(world, player, new ItemStack(originalItem("lift_buttons_1", Item.getItemFromBlock(org.mtr.mod.Blocks.LIFT_BUTTONS_1.get().data))), 9, y - 1, 5, 1, 1);
        }
        player.inventory.mainInventory[0] = null;
        player.inventoryContainer.detectAndSendChanges();
        assertTrack(world);
        report.addProperty("native_chunk_ready_before_all_one_shot_placements", true);
    }

    private void connectAndUnlock(WorldServer world, EntityPlayerMP player) {
        ItemStack connector = new ItemStack(originalItem("lift_buttons_link_connector", org.mtr.mod.Items.LIFT_BUTTONS_LINK_CONNECTOR.get().data));
        for (int floor = 0; floor < 3; floor++) {
            int y = FLOOR_Y[floor];
            use(world, player, connector, 8, y, 8, 1, .5F);
            require(connector.hasTagCompound() && connector.getTagCompound().hasKey(ItemBlockClickingBase.TAG_POS)
                && connector.getTagCompound().getLong(ItemBlockClickingBase.TAG_POS) == floorPos(floor).asLong(), "Original connector did not store first click");
            use(world, player, connector, 9, y, 5, 2, .5F);
            require(!connector.getTagCompound().hasKey(ItemBlockClickingBase.TAG_POS), "Original connector did not clear completed click pair");
            player.inventory.mainInventory[0] = new ItemStack(originalItem("brush", org.mtr.mod.Items.BRUSH.get().data));
            if (!unlocked(world, 9, y, 5)) brush(world, player, 9, y, 5);
            if (!unlocked(world, 8, y, 5)) brush(world, player, 8, y, 5);
        }
        require(linksMatch(world), "Original connector failed server floor-to-button links");
        assertStand(world);
    }

    private void use(WorldServer world, EntityPlayerMP player, ItemStack stack, int x, int y, int z, int side, float hitY) {
        require(chunkStatus(world, x >> 4, z >> 4).get("ready").getAsBoolean(), "Native chunk not ready for original item use");
        player.inventory.mainInventory[0] = stack;
        JsonObject value = new JsonObject();
        value.addProperty("item", Item.itemRegistry.getNameForObject(stack.getItem()));
        value.addProperty("item_class", stack.getItem().getClass().getName());
        value.addProperty("clicked_x", x); value.addProperty("clicked_y", y); value.addProperty("clicked_z", z);
        value.addProperty("side", side); value.addProperty("yaw", player.rotationYaw); value.addProperty("stack_before", stack.stackSize);
        placements.add(value);
        boolean accepted = stack.getItem().onItemUse(stack, player, world, x, y, z, side, .5F, hitY, .5F);
        value.addProperty("accepted", accepted); value.addProperty("stack_after", stack.stackSize);
        value.add("clicked_after", blockStatus(world, x, y, z));
        value.add("above_after", blockStatus(world, x, y + 1, z));
        require(accepted, "One-shot original item placement/use rejected: " + value);
    }

    private void brush(WorldServer world, EntityPlayerMP player, int x, int y, int z) {
        JsonObject value = blockStatus(world, x, y, z);
        interactions.add(value);
        boolean accepted = world.getBlock(x, y, z).onBlockActivated(world, x, y, z, player, 2, .5F, .7F, .5F);
        value.addProperty("original_brush_accepted", accepted);
        require(accepted && unlocked(world, x, y, z), "Original brush failed to unlock landing block");
    }

    private static Item originalItem(String path, Item expected) {
        Item item = (Item) Item.itemRegistry.getObject("mtr:" + path);
        require(item != null && item == expected, "Registry mtr:" + path + " is not the original item identity");
        return item;
    }

    private void setupTeleport(EntityPlayerMP player, double x, double y, double z) {
        require(stage == Stage.SETUP || stage == Stage.CONFIGURE, "Probe teleport forbidden after setup");
        require(setupTeleports < 2 && !PlayerPhysics.authorized(player), "Unexpected setup teleport/rider permission");
        player.playerNetServerHandler.setPlayerLocation(x, y, z, 0, 10);
        setupTeleports++;
    }

    private void observe(WorldServer world, EntityPlayerMP player, ClientSample client, CoreSample core) {
        JsonObject value = client.json();
        value.addProperty("phase", stage.name()); value.addProperty("phase_tick", phaseTicks);
        value.addProperty("server_world_tick", world.getTotalWorldTime());
        value.addProperty("client_sample_age_ms", (System.nanoTime() - client.atNanos) / 1000000D);
        value.addProperty("native_client_tick_at_consumption", clientTicks);
        value.add("server_player", playerPosition(player));
        clientBoardingAuthorized = PlayerPhysics.authorized(player);
        value.addProperty("server_authorized", clientBoardingAuthorized);
        value.addProperty("server_gravity_for_0_08", PlayerPhysics.gravity(.08, player));
        value.add("server_riding_view", serverRidingDiagnostics(player.getUniqueID(), liftId, world.getTotalWorldTime(), player.dimension));
        value.addProperty("native_s08_count", s08.get()); value.addProperty("native_c03_count", c03.get());
        value.addProperty("mtr_inbound_frame_count", mtrFrames.get()); value.addProperty("riding_inbound_first_frame_count", ridingInbound.get());
        value.addProperty("riding_rejection_count", ridingRejections.get());
        if (lastRidingInbound != null) value.add("last_riding_inbound", lastRidingInbound);
        if (lastRidingRejection != null) value.add("last_riding_rejection", lastRidingRejection);
        JsonObject movement = lastC03;
        if (movement != null) value.add("last_native_c03", movement);
        if (core != null) { value.addProperty("core_sample_age_ms", System.currentTimeMillis() - core.at); value.add("core_lift", core.json); }
        timeline.add(value);
        for (int floor = 0; floor < 3; floor++) if (client.liftId == liftId && Math.abs(client.cabinY - FLOOR_Y[floor]) < .05) {
            doors[floor].observe(client.door);
            landingDoors[floor].observe(Math.min(1, client.landingDoors[floor] / .75));
        }
    }

    private void assertPassenger(EntityPlayerMP player, ClientSample client) {
        require(client.riding && PlayerPhysics.authorized(player), "Original rider lost client bind or real server physics authorization");
        require(player.noClip && Math.abs(PlayerPhysics.gravity(.08, player)) < 1E-10,
            "Original authorized rider lacks the native server clipping/gravity flags");
        require(!player.capabilities.isFlying && !client.flying, "Lift ride was replaced by native flight");
        require(Math.abs(client.y - client.cabinY) < .35 && Math.abs(player.boundingBox.minY - client.cabinY) < 1.5,
            "Real client/server passenger did not follow original cabin Y");
        require(Math.abs(client.x - 8) < .8 && client.z >= 5.7 && client.z <= 7.3, "Passenger left actual original cabin bounds");
        require(Math.abs(player.posX - client.x) < 1 && Math.abs(player.posZ - client.z) < 1,
            "Native client/server rider coordinates diverged");
        require(s08.get() - motionS08 <= 2, "Repeated native S08 corrections during lift motion");
    }

    private static boolean stoppedAt(ClientSample client, CoreSample core, int floor) {
        return core != null && core.id == client.liftId && Math.abs(core.speed) < 1E-8
            && Math.abs(client.cabinY - FLOOR_Y[floor]) < .03 && Math.abs(core.progress - (FLOOR_Y[floor] - 72)) < .03;
    }

    private boolean captureDone(String label) {
        if (!label.equals(shotLabel)) {
            require(shotRequest == shotAcknowledged, "Previous actual screenshot has no render acknowledgment");
            renderedFrames = 0; shotLabel = label; shotRequest++;
            return false;
        }
        return shotAcknowledged == shotRequest;
    }

    private void advance(Stage next) {
        JsonObject value = new JsonObject();
        value.addProperty("from", stage.name()); value.addProperty("to", next.name());
        value.addProperty("elapsed_ms", System.currentTimeMillis() - startedAt);
        transitions.add(value);
        phaseTicks = 0; stillTicks = 0; stageStartedAt = System.currentTimeMillis();
        stage = next;
    }

    // Read only: Main has no public simulator accessor. Never construct Main/Simulator or alter their fields.
    private static Simulator actualSimulator() throws Exception {
        Field mainField = Init.class.getDeclaredField("main"); mainField.setAccessible(true);
        Main main = (Main) mainField.get(null);
        require(main != null, "Original server Main was not started");
        Field simulatorsField = Main.class.getDeclaredField("simulators"); simulatorsField.setAccessible(true);
        for (Object candidate : (Iterable<?>) simulatorsField.get(main)) {
            Simulator value = (Simulator) candidate;
            if ("minecraft/overworld".equals(value.dimension)) return value;
        }
        throw new AssertionError("Original overworld simulator is missing");
    }

    private void readCoreSoon() {
        if (simulator == null || !coreReadPending.compareAndSet(false, true)) return;
        simulator.run(() -> {
            try { coreSample = new CoreSample(simulator); }
            catch (Throwable problem) { fail(problem); }
            finally { coreReadPending.set(false); }
        });
    }

    private void installPacketObserver(NetworkManager manager) throws Exception {
        installRidingLogObserver();
        Channel channel = null;
        for (Field field : NetworkManager.class.getDeclaredFields()) if (Channel.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true); channel = (Channel) field.get(manager);
        }
        require(channel != null, "Actual integrated connection channel is missing");
        final Channel actual = channel;
        actual.eventLoop().execute(() -> {
            try {
                actual.pipeline().addBefore("packet_handler", "mtr_lift_probe_observer", new ChannelDuplexHandler() {
                    @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                        observeNativeInbound(message);
                        if (message instanceof C03PacketPlayer) {
                            C03PacketPlayer packet = (C03PacketPlayer) message;
                            JsonObject value = new JsonObject();
                            value.addProperty("sequence", c03.incrementAndGet()); value.addProperty("at_ms", System.currentTimeMillis() - startedAt);
                            value.addProperty("has_position", packet.func_149466_j()); value.addProperty("on_ground", packet.func_149465_i());
                            if (packet.func_149466_j()) {
                                value.addProperty("x", packet.func_149464_c()); value.addProperty("y", packet.func_149467_d()); value.addProperty("z", packet.func_149472_e());
                            }
                            lastC03 = value;
                        }
                        super.channelRead(context, message);
                    }
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof S08PacketPlayerPosLook) {
                            S08PacketPlayerPosLook packet = (S08PacketPlayerPosLook) message;
                            JsonObject value = new JsonObject();
                            value.addProperty("sequence", s08.incrementAndGet()); value.addProperty("at_ms", System.currentTimeMillis() - startedAt);
                            value.addProperty("phase", stage.name()); value.addProperty("x", packet.func_148932_c());
                            value.addProperty("y", packet.func_148928_d()); value.addProperty("z", packet.func_148933_e());
                            corrections.add(value);
                        }
                        super.write(context, message, promise);
                    }
                });
                packetObserverReady = true;
            } catch (Throwable problem) { fail(problem); }
        });
    }

    // Diagnostics only. A duplicate shares bytes but has separate indexes; do not retain, release or decode a packet.
    private static String peekMtrPacketClass(ByteBuf frame) {
        ByteBuf view = frame.duplicate(); int start = view.readerIndex();
        if (view.readableBytes() < 20 || view.getInt(start + 8) != 0) return null;
        int length = view.getInt(start + 16);
        if (length < 1 || length > 256 || view.readableBytes() < 20 + length * 2) return null;
        char[] name = new char[length];
        for (int index = 0; index < length; index++) name[index] = view.getChar(start + 20 + index * 2);
        return new String(name);
    }

    private void observeNativeInbound(Object message) {
        ByteBuf frame = null; boolean ownedWrapper = false;
        try {
            String channel;
            if (message instanceof FMLProxyPacket) {
                fmlProxy.incrementAndGet(); FMLProxyPacket packet = (FMLProxyPacket) message;
                channel = packet.channel(); frame = packet.payload();
            } else if (message instanceof C17PacketCustomPayload) {
                c17.incrementAndGet(); C17PacketCustomPayload packet = (C17PacketCustomPayload) message;
                channel = packet.func_149559_c();
                if ("mtr:packet".equals(channel)) { frame = Unpooled.wrappedBuffer(packet.func_149558_e()); ownedWrapper = true; }
            } else return;
            if (!"mtr:packet".equals(channel)) return;
            int sequence = mtrFrames.incrementAndGet();
            JsonObject value = new JsonObject(); value.addProperty("sequence", sequence);
            value.addProperty("at_ms", System.currentTimeMillis() - startedAt); value.addProperty("phase", stage.name());
            value.addProperty("boundary", "server_channel_before_packet_handler"); value.addProperty("native_class", message.getClass().getName());
            value.addProperty("channel", channel);
            if (frame != null) {
                value.addProperty("readable_bytes", frame.readableBytes());
                if (frame.readableBytes() >= 16) { value.addProperty("frame_index", frame.getInt(frame.readerIndex() + 8)); value.addProperty("frame_count", frame.getInt(frame.readerIndex() + 12)); }
                String packetClass = peekMtrPacketClass(frame);
                if (packetClass != null) {
                    value.addProperty("packet_class", packetClass);
                    if ("org.mtr.mod.packet.PacketUpdateVehicleRidingEntities".equals(packetClass)) {
                        value.addProperty("riding_sequence", ridingInbound.incrementAndGet()); lastRidingInbound = value;
                    }
                }
            }
            if (sequence <= 64) inboundFrames.add(value);
        } catch (RuntimeException diagnosticError) {
            JsonObject value = new JsonObject(); value.addProperty("error", diagnosticError.toString());
            if (inboundFrames.size() < 64) inboundFrames.add(value);
        } finally { if (ownedWrapper) frame.release(); }
    }

    private void installRidingLogObserver() {
        if (ridingLogger != null) return;
        ridingLogger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("MTRRiding");
        previousRidingLevel = ridingLogger.getLevel();
        ridingLogObserver = new org.apache.logging.log4j.core.appender.AbstractAppender("mtr_lift_probe_riding_" + System.identityHashCode(this), null, null) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) {
                String message = event.getMessage().getFormattedMessage();
                if (!message.startsWith("Rejected riding request:")) return;
                int sequence = ridingRejections.incrementAndGet();
                JsonObject value = new JsonObject(); value.addProperty("sequence", sequence);
                value.addProperty("at_ms", System.currentTimeMillis() - startedAt); value.addProperty("phase", stage.name());
                value.addProperty("reason", message); value.addProperty("thread", event.getThreadName());
                lastRidingRejection = value; if (sequence <= 64) rejectionReasons.add(value);
            }
        };
        ridingLogObserver.start(); ridingLogger.addAppender(ridingLogObserver);
        ridingLogger.setLevel(org.apache.logging.log4j.Level.DEBUG);
    }

    private void closeRidingLogObserver() {
        if (ridingLogger == null) return;
        try { ridingLogger.removeAppender(ridingLogObserver); }
        finally { ridingLogObserver.stop(); ridingLogger.setLevel(previousRidingLevel); ridingLogRestored = true; }
    }

    private static Object diagnosticField(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type = owner instanceof Class<?> ? (Class<?>) owner : owner.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner instanceof Class<?> ? null : owner);
    }

    private static JsonObject originalRidingDiagnostics() {
        JsonObject value = new JsonObject(); value.addProperty("sampled_at_millis", System.currentTimeMillis());
        try {
            value.addProperty("ridingVehicleId", Long.toString(((Number) diagnosticField(VehicleRidingMovement.class, "ridingVehicleId")).longValue()));
            for (String name : new String[]{"sendPositionUpdateTime", "ridingVehicleCarNumber", "ridingVehicleX", "ridingVehicleY", "ridingVehicleZ", "ridingVehicleCooldown"})
                value.addProperty(name, (Number) diagnosticField(VehicleRidingMovement.class, name));
        } catch (ReflectiveOperationException | RuntimeException diagnosticError) { value.addProperty("error", diagnosticError.toString()); }
        return value;
    }

    private static JsonObject serverRidingDiagnostics(UUID uuid, long id, long now, int dimension) {
        JsonObject value = new JsonObject(); value.addProperty("current_server_tick", now); value.addProperty("player_dimension", dimension);
        value.addProperty("requested_lift_id", Long.toString(id));
        synchronized (RidingPermissions.class) {
            try {
                Object view = ((Map<?, ?>) diagnosticField(RidingPermissions.class, "VIEWS")).get(uuid);
                value.addProperty("view_present", view != null); if (view == null) return value;
                value.addProperty("view_dimension", (Number) diagnosticField(view, "dimension"));
                Object snapshot = ((Map<?, ?>) diagnosticField(view, "lifts")).get(id);
                value.addProperty("lift_snapshot_present", snapshot != null); if (snapshot == null) return value;
                for (String name : new String[]{"freshAt", "poseAt", "siding", "depot"}) value.addProperty(name, (Number) diagnosticField(snapshot, name));
                Object[] cabins = (Object[]) diagnosticField(snapshot, "cabins"); value.addProperty("cabin_count", cabins.length);
                if (cabins.length > 0) {
                    Object cabin = cabins[0], frame = diagnosticField(cabin, "frame");
                    for (String name : new String[]{"width", "length", "height"}) value.addProperty(name, (Number) diagnosticField(cabin, name));
                    for (String name : new String[]{"yaw", "pitch"}) value.addProperty(name, (Number) diagnosticField(frame, name));
                    Vector position = (Vector) diagnosticField(frame, "position");
                    value.addProperty("center_x", position.x); value.addProperty("center_y", position.y); value.addProperty("center_z", position.z);
                }
            } catch (ReflectiveOperationException | RuntimeException diagnosticError) { value.addProperty("error", diagnosticError.toString()); }
        }
        return value;
    }

    private static Lift findOnlyClientLift() {
        require(MinecraftClientData.getInstance().lifts.size() <= 1, "New owned save has multiple unexpected client lifts");
        for (Lift value : MinecraftClientData.getInstance().lifts) return value;
        return null;
    }

    private static BlockPos floorPos(int floor) { return new BlockPos(8, FLOOR_Y[floor], 8); }
    private static void key(KeyBinding binding, boolean down) { KeyBinding.setKeyBindState(binding.getKeyCode(), down); }

    private EntityPlayerMP actualPlayer(MinecraftServer server) {
        for (Object candidate : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP value = (EntityPlayerMP) candidate;
            if (value.getUniqueID().equals(playerId)) return value;
        }
        return null;
    }

    private static boolean labelsMatch(net.minecraft.world.World world) {
        for (int floor = 0; floor < 3; floor++) {
            TileEntity value = world.getTileEntity(8, FLOOR_Y[floor], 8);
            if (!(value instanceof BlockLiftTrackFloor.BlockEntity)) return false;
            BlockLiftTrackFloor.BlockEntity tile = (BlockLiftTrackFloor.BlockEntity) value;
            if (!Integer.toString(floor + 1).equals(tile.getFloorNumber()) || !DESCRIPTIONS[floor].equals(tile.getFloorDescription()) || tile.getShouldDing()) return false;
        }
        return true;
    }

    private static boolean linksMatch(net.minecraft.world.World world) {
        for (int floor = 0; floor < 3; floor++) {
            TileEntity value = world.getTileEntity(9, FLOOR_Y[floor], 5);
            if (!(value instanceof BlockLiftButtons.BlockEntity)) return false;
            List<BlockPos> positions = new ArrayList<>();
            ((BlockLiftButtons.BlockEntity) value).forEachTrackPosition(positions::add);
            if (positions.size() != 1 || !positions.get(0).equals(floorPos(floor))) return false;
            NBTTagCompound tag = new NBTTagCompound(); value.writeToNBT(tag);
            long[] saved = new CompoundTag(tag).getLongArray("track_floor_pos");
            if (saved.length != 1 || saved[0] != floorPos(floor).asLong()) return false;
        }
        return true;
    }

    private static boolean clientStandReady(Minecraft client) {
        try {
            for (int y : FLOOR_Y) if (!(client.theWorld.getTileEntity(8, y, 8) instanceof BlockLiftTrackFloor.BlockEntity)) return false;
            return true;
        } catch (IllegalArgumentException | IllegalStateException waitingForNativeTileSync) { return false; }
    }

    private static BlockState state(net.minecraft.world.World world, int x, int y, int z) {
        return new org.mtr.mapping.holder.World(world).getBlockState(new BlockPos(x, y, z));
    }

    private static boolean unlocked(net.minecraft.world.World world, int x, int y, int z) {
        return "true".equals(state(world, x, y, z).data.getSerializedProperties().get("unlocked"));
    }

    private static void assertTrack(WorldServer world) {
        for (int y = 72; y <= 88; y++) {
            BlockState state = state(world, 8, y, 8);
            require(state.getBlock().data instanceof BlockLiftTrackBase, "Native shaft track missing at y=" + y);
            require("north".equals(state.data.getSerializedProperties().get("facing")), "Original shaft track facing is not NORTH");
            BlockLiftTrackBase track = (BlockLiftTrackBase) state.getBlock().data;
            require(track.getConnectingDirections(state).contains(Direction.UP) && track.getConnectingDirections(state).contains(Direction.DOWN),
                "Original track lacks UP/DOWN connections");
            require((state.getBlock().data instanceof BlockLiftTrackFloor) == (y == 72 || y == 80 || y == 88), "Shaft floor/vertical type differs");
        }
        for (int floor = 0; floor < 2; floor++) {
            List<Vector> path = ItemLiftRefresher.findPath(new org.mtr.mapping.holder.World(world),
                new Position(8, FLOOR_Y[floor], 8), new Position(8, FLOOR_Y[floor + 1], 8));
            require(path.size() == 9, "Original native path fell back to endpoints or omitted track blocks");
            for (int index = 0; index < path.size(); index++) require(path.get(index).x == 8 && path.get(index).z == 8
                && path.get(index).y == FLOOR_Y[floor] + index, "Original path has wrong contiguous track geometry");
        }
    }

    private static void assertStand(WorldServer world) {
        assertTrack(world);
        require(labelsMatch(world) && linksMatch(world), "Server labels or links no longer match original entrypoints");
        for (int y : FLOOR_Y) {
            require(world.getBlock(9, y, 5) instanceof BlockLiftButtons && unlocked(world, 9, y, 5), "Original landing button missing/locked");
            for (int x = 7; x <= 8; x++) for (int dy = 0; dy <= 1; dy++) {
                require(world.getBlock(x, y + dy, 5) instanceof BlockLiftDoor && unlocked(world, x, y + dy, 5), "Original landing door half missing/locked");
                Map<String, String> values = state(world, x, y + dy, 5).data.getSerializedProperties();
                require("south".equals(values.get("facing")) && (dy == 0 ? "lower" : "upper").equals(values.get("half"))
                    && (x == 8 ? "left" : "right").equals(values.get("side")), "Original landing-door state is wrong");
            }
        }
    }

    private static boolean floorGeometry(Lift lift) {
        List<LiftFloor> floors = new ArrayList<>(); lift.iterateFloors(floors::add);
        if (floors.size() != 3) return false;
        for (int floor = 0; floor < 3; floor++) {
            LiftFloor value = floors.get(floor);
            if (!value.getPosition().equals(new Position(8, FLOOR_Y[floor], 8)) || !Integer.toString(floor + 1).equals(value.getNumber())
                || !DESCRIPTIONS[floor].equals(value.getDescription())) return false;
        }
        return true;
    }

    private static boolean configured(Lift lift) {
        return lift.getHeight() == 3 && lift.getWidth() == 2 && lift.getDepth() == 2 && lift.getOffsetX() == 0
            && lift.getOffsetY() == 0 && lift.getOffsetZ() == -1.5 && !lift.getIsDoubleSided()
            && Math.abs(lift.getAngle().angleRadians - Angle.fromAngle(270).angleRadians) < 1E-8;
    }

    private static JsonObject playerPosition(EntityPlayerMP player) {
        JsonObject value = new JsonObject();
        value.addProperty("x", player.posX); value.addProperty("feet_y", player.boundingBox.minY); value.addProperty("z", player.posZ);
        value.addProperty("managed_x", player.managedPosX); value.addProperty("managed_z", player.managedPosZ);
        value.addProperty("on_ground", player.onGround); value.addProperty("no_clip", player.noClip);
        value.addProperty("flying", player.capabilities.isFlying); value.addProperty("motion_y", player.motionY);
        return value;
    }

    private static JsonObject chunkStatus(WorldServer world, int x, int z) {
        boolean exists = world.getChunkProvider().chunkExists(x, z);
        Chunk chunk = (Chunk) world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x, z));
        JsonObject value = new JsonObject();
        value.addProperty("chunk_x", x); value.addProperty("chunk_z", z); value.addProperty("chunk_exists", exists);
        value.addProperty("load_on_provide", world.theChunkProviderServer.loadChunkOnProvideRequest);
        value.addProperty("ready", exists && chunk != null && !(chunk instanceof EmptyChunk)
            && chunk.xPosition == x && chunk.zPosition == z && chunk.isChunkLoaded);
        if (chunk != null) { value.addProperty("class", chunk.getClass().getName()); value.addProperty("terrain_populated", chunk.isTerrainPopulated); }
        return value;
    }

    private static JsonObject blockStatus(WorldServer world, int x, int y, int z) {
        JsonObject value = new JsonObject();
        value.addProperty("x", x); value.addProperty("y", y); value.addProperty("z", z);
        Block block = world.getBlock(x, y, z);
        value.addProperty("block_registry", Block.blockRegistry.getNameForObject(block));
        value.addProperty("block_id", Block.getIdFromBlock(block)); value.addProperty("class", block.getClass().getName());
        value.addProperty("metadata", world.getBlockMetadata(x, y, z));
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile != null) {
            NBTTagCompound tag = new NBTTagCompound(); tile.writeToNBT(tag);
            value.addProperty("native_tile_nbt", tag.toString()); value.addProperty("tile_class", tile.getClass().getName());
        }
        value.add("chunk", chunkStatus(world, x >> 4, z >> 4));
        return value;
    }

    private void reserveNewSave(Minecraft client) throws Exception {
        require(client.mcDataDir.getCanonicalFile().equals(COPIED_CLIENT.getCanonicalFile()), "Refusing to run outside disposable copied client");
        require(client.theWorld == null && client.getIntegratedServer() == null, "Refusing to replace an existing open world");
        runId = UUID.randomUUID().toString(); saveName = "MTR-lift-acceptance-" + runId;
        Path saves = client.mcDataDir.toPath().resolve("saves"); Files.createDirectories(saves);
        require(!Files.isSymbolicLink(saves) && saves.toRealPath().getParent().equals(client.mcDataDir.toPath().toRealPath()), "Copied saves directory escaped client");
        Path reserved = Files.createDirectory(saves.resolve(saveName));
        saveDirectory = reserved.toFile();
        Files.write(reserved.resolve("mtr-lift-probe-owner.txt"), runId.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        copyVerified = true;
        report.addProperty("run_id", runId); report.addProperty("save_directory", saveDirectory.getCanonicalPath());
        report.addProperty("client_directory", client.mcDataDir.getCanonicalPath());
        report.addProperty("new_uuid_save_reserved", true); report.addProperty("existing_save_loaded", false);
        report.addProperty("scenario", "original items/packets/render rider; pickup2 -> 3 -> 1; native dismount; Anvil + core MessagePack");
    }

    private void assertOwnedServer(MinecraftServer server) throws Exception {
        require(copyVerified && server != null && saveDirectory != null && saveName.equals(server.getFolderName()), "Refusing to mutate/close a foreign server");
        Path marker = saveDirectory.toPath().resolve("mtr-lift-probe-owner.txt");
        require(!Files.isSymbolicLink(saveDirectory.toPath()) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            && runId.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)), "Owned save marker changed");
    }

    private void snapshotStand(WorldServer world) {
        for (int y = 72; y <= 88; y++) expectedSavedBlocks.add(snapshot(world, 8, y, 8));
        for (int y : FLOOR_Y) {
            expectedSavedBlocks.add(snapshot(world, 9, y, 5));
            for (int x = 7; x <= 8; x++) for (int dy = 0; dy <= 1; dy++) expectedSavedBlocks.add(snapshot(world, x, y + dy, 5));
        }
        require(expectedSavedBlocks.size() == 32, "Incomplete native Anvil expectations");
    }

    private static StoredBlock snapshot(WorldServer world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        NBTTagCompound tag = null;
        if (tile != null) { tag = new NBTTagCompound(); tile.writeToNBT(tag); }
        storedProperties(world.getBlock(x, y, z), world.getBlockMetadata(x, y, z), tag);
        return new StoredBlock(x, y, z, Block.getIdFromBlock(world.getBlock(x, y, z)), world.getBlockMetadata(x, y, z), tag,
            state(world, x, y, z).data.getSerializedProperties());
    }

    private static Map<String, String> storedProperties(Block block, int metadata, NBTTagCompound tag) {
        BlockStateDefinition definition = BlockStateDefinition.forBlock(block);
        require((tag != null) == block.hasTileEntity(metadata), "Native lift TileEntity presence differs from original block contract");
        if (definition.requiresTileEntity()) require(tag != null && tag.hasKey("mtr_block_state", 10), "Extended lift state is missing");
        return definition.read(metadata, tag == null ? new NBTTagCompound() : tag).getSerializedProperties();
    }

    private void finish(Minecraft client) {
        if (stage == Stage.FINISHED) return;
        report.addProperty("terminal_phase", stage.name());
        stage = Stage.FINISHED;
        boolean safeShutdown = copyVerified && client.getIntegratedServer() == null && client.theWorld == null;
        if (copyVerified) KeyBinding.unPressAllKeys();
        try {
            if (client.getIntegratedServer() != null) {
                assertOwnedServer(client.getIntegratedServer());
                safeShutdown = true;
                if (client.theWorld != null) client.theWorld.sendQuittingDisconnectingPacket();
                client.loadWorld(null);
                client.displayGuiScreen(new GuiMainMenu());
                report.addProperty("saved_and_closed_normally", true);
            }
            if (failure == null) {
                Path marker = saveDirectory.toPath().resolve("mtr-lift-probe-owner.txt");
                require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && runId.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)), "Owned save marker changed after shutdown");
                verifySavedAnvil(); verifySavedCore();
                require(stationaryRidingWitnesses >= 10, "Too few actual first-person stationary rider render witnesses");
                require(ridingFloorMaximum - ridingFloorMinimum < CABIN_JITTER_MAX_RANGE, "Submitted cabin geometry jitters relative to camera: range=" + (ridingFloorMaximum - ridingFloorMinimum));
            }
        } catch (Throwable problem) { fail(problem); }
        closeRidingLogObserver();
        report.addProperty("passed", failure == null); report.addProperty("setup_teleport_count", setupTeleports);
        if (clientDiagnostics != null) report.add("last_client_diagnostics", clientDiagnostics);
        if (renderSampleDiagnostics != null) report.add("last_render_sample_diagnostics", renderSampleDiagnostics);
        report.add("cabin_submitted_geometry_frames", cabinRenderWitnesses); report.addProperty("cabin_render_witnesses_dropped", droppedRenderWitnesses);
        report.addProperty("stationary_riding_render_witnesses", stationaryRidingWitnesses);
        report.addProperty("cabin_render_witness_is_framebuffer_pixel_tracking", false);
        report.addProperty("cabin_jitter_max_range_blocks", CABIN_JITTER_MAX_RANGE);
        report.addProperty("jitter_pass", stationaryRidingWitnesses >= 10 && ridingFloorMaximum - ridingFloorMinimum < CABIN_JITTER_MAX_RANGE);
        report.addProperty("stationary_input_settle_ticks", 2);
        if (stationaryRidingWitnesses > 0) {
            report.addProperty("stationary_cabin_camera_relative_y_min", ridingFloorMinimum); report.addProperty("stationary_cabin_camera_relative_y_max", ridingFloorMaximum);
            report.addProperty("stationary_cabin_camera_relative_y_range", ridingFloorMaximum - ridingFloorMinimum);
        }
        report.add("owned_pause_on_lost_focus_corrections", pauseOptionCorrections);
        report.addProperty("ride_teleport_count", 0); report.addProperty("probe_motion_writes", 0);
        report.addProperty("s08_observed_after_channel_install", s08.get()); report.addProperty("c03_observed_after_channel_install", c03.get());
        report.addProperty("s08_after_final_setup_ack", s08.get() - boardingS08);
        report.addProperty("authorized_boarding_s08_anchor_reached", report.has("natural_render_boarding_authorized"));
        if (report.has("natural_render_boarding_authorized")) report.addProperty("s08_after_authorized_boarding", s08.get() - motionS08);
        else report.add("s08_after_authorized_boarding", org.mtr.libraries.com.google.gson.JsonNull.INSTANCE);
        report.addProperty("native_c17_inbound_count", c17.get()); report.addProperty("native_fml_proxy_inbound_count", fmlProxy.get());
        report.addProperty("mtr_inbound_frame_count", mtrFrames.get()); report.addProperty("riding_inbound_first_frame_count", ridingInbound.get());
        report.addProperty("riding_rejection_count", ridingRejections.get()); report.addProperty("riding_logger_level_restored", ridingLogRestored);
        if (previousRidingLevel != null) report.addProperty("riding_logger_previous_level", previousRidingLevel.toString());
        JsonArray frames = new JsonArray(); for (JsonObject value : inboundFrames) frames.add(value); report.add("native_mtr_inbound_frames_first_64", frames);
        JsonArray reasons = new JsonArray(); for (JsonObject value : rejectionReasons) reasons.add(value); report.add("riding_rejections_first_64", reasons);
        JsonArray nativeCorrections = new JsonArray(); for (JsonObject correction : corrections) nativeCorrections.add(correction);
        report.add("actual_native_s08_packets", nativeCorrections);
        report.add("timeline", timeline); report.add("transitions", transitions); report.add("screenshots", screenshots);
        report.add("original_one_shot_item_uses", placements); report.add("original_brush_interactions", interactions);
        JsonArray cycles = new JsonArray(); for (DoorCycle door : doors) cycles.add(door.json()); report.add("door_cycles_floor1_2_3", cycles);
        JsonArray landingCycles = new JsonArray(); for (DoorCycle door : landingDoors) landingCycles.add(door.json());
        report.add("landing_door_cycles_floor1_2_3_normalized_to_original_0_75_max", landingCycles);
        report.addProperty("save_reopened", false); report.addProperty("lift_menu_mouse_gui_accepted", false);
        report.addProperty("audio_accepted", false); report.addProperty("remote_multiplayer_accepted", false);
        if (failure != null) { report.addProperty("error", failure.toString()); failure.printStackTrace(); }
        try {
            if (copyVerified) Files.write(new File(client.mcDataDir, "mtr-lift-probe-" + runId + ".json").toPath(),
                report.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            System.out.println((failure == null ? "MTR_LIFT_PROBE_PASS " : "MTR_LIFT_PROBE_FAIL ") + report);
            if (safeShutdown) client.shutdown();
        } catch (Throwable problem) { problem.printStackTrace(); throw new IllegalStateException("Could not write lift acceptance result", problem); }
    }

    private void verifySavedAnvil() throws Exception {
        require(new File(saveDirectory, "level.dat").isFile() && expectedSavedBlocks.size() == 32, "Actual new save/expectations missing");
        File file = new File(saveDirectory, "region/r.0.0.mca"); require(file.isFile(), "Actual saved Anvil region missing");
        JsonArray persisted = new JsonArray();
        NBTTagCompound level = readSavedChunkReadOnly(saveDirectory, file);
        require(level.getInteger("xPos") == 0 && level.getInteger("zPos") == 0, "Saved chunk location differs from stand");
            for (StoredBlock expected : expectedSavedBlocks) {
                int slot = ((expected.y & 15) << 8) | ((expected.z & 15) << 4) | (expected.x & 15);
                NBTTagCompound section = null; NBTTagList sections = level.getTagList("Sections", 10);
                for (int index = 0; index < sections.tagCount(); index++) {
                    NBTTagCompound value = sections.getCompoundTagAt(index);
                    if ((value.getByte("Y") & 255) == expected.y >> 4) section = value;
                }
                require(section != null, "Saved lift section missing");
                byte[] low = savedArray(section, "Blocks", 4096, false), high = savedArray(section, "Add", 2048, true);
                byte[] higher = savedArray(section, "BlocksB2Hi", 2048, true), highest = savedArray(section, "BlocksB3", 4096, true);
                int id = (low[slot] & 255) | (high.length == 0 ? 0 : nibble(high, slot) << 8)
                    | (higher.length == 0 ? 0 : nibble(higher, slot) << 12) | (highest.length == 0 ? 0 : (highest[slot] & 255) << 16);
                byte[] data = savedArray(section, "Data", 2048, false), dataHigh = savedArray(section, "Data1High", 2048, true);
                byte[] dataHighest = savedArray(section, "Data2", 4096, true);
                int metadata = nibble(data, slot) | (dataHigh.length == 0 ? 0 : nibble(dataHigh, slot) << 4)
                    | (dataHighest.length == 0 ? 0 : (dataHighest[slot] & 255) << 8);
                require(id == expected.id && metadata == expected.metadata, "Saved native lift ID/metadata differs");
                NBTTagCompound tag = null; NBTTagList tiles = level.getTagList("TileEntities", 10);
                for (int index = 0; index < tiles.tagCount(); index++) {
                    NBTTagCompound value = tiles.getCompoundTagAt(index);
                    if (value.getInteger("x") == expected.x && value.getInteger("y") == expected.y && value.getInteger("z") == expected.z) tag = value;
                }
                Block block = Block.getBlockById(id);
                BlockStateDefinition definition = BlockStateDefinition.forBlock(block);
                require((tag != null) == (expected.tag != null), "Saved lift TileEntity presence changed");
                Map<String, String> properties = storedProperties(block, metadata, tag);
                if (definition.requiresTileEntity()) require(expected.tag.getCompoundTag("mtr_block_state").equals(tag.getCompoundTag("mtr_block_state")), "Saved extended lift state differs");
                TileEntity restored = null;
                if (tag != null) {
                    restored = TileEntity.createAndLoadEntity(tag);
                    require(restored instanceof BlockStateTileEntity, "Actual saved tile ID cannot decode original state");
                    require(properties.equals(((BlockStateTileEntity) restored).readMtrState(definition, metadata).data.getSerializedProperties()),
                        "Saved native TileEntity codec disagrees with original block definition");
                }
                require(expected.properties.equals(properties), "Saved lift state properties changed through original codec");
                if (expected.x == 8 && expected.z == 8 && (expected.y == 72 || expected.y == 80 || expected.y == 88)) {
                    int floor = (expected.y - 72) / 8;
                    require(restored instanceof BlockLiftTrackFloor.BlockEntity, "Saved floor tile lost original type");
                    BlockLiftTrackFloor.BlockEntity tile = (BlockLiftTrackFloor.BlockEntity) restored;
                    require(Integer.toString(floor + 1).equals(tile.getFloorNumber()) && DESCRIPTIONS[floor].equals(tile.getFloorDescription()) && !tile.getShouldDing(),
                        "Saved original floor labels/ding differ");
                } else if (expected.x == 9 && expected.z == 5) {
                    long[] links = new CompoundTag(tag).getLongArray("track_floor_pos");
                    require(restored instanceof BlockLiftButtons.BlockEntity && links.length == 1 && links[0] == floorPos((expected.y - 72) / 8).asLong(),
                        "Saved native long-list button link differs");
                }
                JsonObject value = new JsonObject(); value.addProperty("x", expected.x); value.addProperty("y", expected.y); value.addProperty("z", expected.z);
                value.addProperty("block", Block.blockRegistry.getNameForObject(block)); value.addProperty("native_nbt", tag == null ? "absent" : tag.toString());
                value.addProperty("has_native_tile", tag != null); value.addProperty("extended_state_required", definition.requiresTileEntity()); persisted.add(value);
            }
        report.add("persisted_anvil_blocks", persisted); report.addProperty("saved_native_blocks_verified", persisted.size());
        report.addProperty("anvil_open_mode", "r");
    }

    private static NBTTagCompound readSavedChunkReadOnly(File ownedSave, File file) throws Exception {
        Path root = ownedSave.toPath().toRealPath(), resolved = file.toPath().toRealPath();
        require(resolved.startsWith(root) && !resolved.equals(root), "Anvil path escaped owned save");
        require(!Files.isSymbolicLink(file.toPath()), "Owned Anvil region must not be a symlink");
        // RegionFile opens in rw mode and may pad damaged files; this reader never writes a header or sector.
        try (RandomAccessFile region = new RandomAccessFile(resolved.toFile(), "r")) {
            require(region.length() >= 8192 && region.length() % 4096 == 0, "Malformed saved Anvil region length");
            int location = region.readInt(); // Stand is wholly in chunk(0,0), location-table entry zero.
            int sector = location >>> 8, sectors = location & 255;
            require(sector >= 2 && sectors > 0 && ((long) sector + sectors) * 4096 <= region.length(), "Saved chunk sectors outside region");
            region.seek((long) sector * 4096);
            int length = region.readInt();
            require(length > 1 && length <= sectors * 4096 - 4, "Malformed saved chunk payload length");
            int compression = region.readUnsignedByte();
            require(compression == 1 || compression == 2, "Unsupported native Anvil compression " + compression);
            byte[] bytes = new byte[length - 1]; region.readFully(bytes);
            try (InputStream compressed = new ByteArrayInputStream(bytes);
                 InputStream decompressed = compression == 1 ? new GZIPInputStream(compressed) : new InflaterInputStream(compressed);
                 DataInputStream input = new DataInputStream(decompressed)) {
                return CompressedStreamTools.read(input).getCompoundTag("Level");
            }
        }
    }

    private void verifySavedCore() throws Exception {
        Path directory = saveDirectory.toPath().resolve("mtr/minecraft/overworld/lifts");
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Actual original core lifts save directory missing");
        Path real = directory.toRealPath(); require(real.startsWith(saveDirectory.toPath().toRealPath()), "Core save escaped owned save");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.forEach(path -> { require(!Files.isSymbolicLink(path), "Symlink in owned core lift save"); if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) files.add(path); });
        }
        require(files.size() == 1, "Expected exactly one real core Lift payload, got " + files.size());
        Path file = files.get(0); byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        Lift decoded;
        // FileLoader.readFile is deliberately not called: its error handler may delete corrupt data.
        try (InputStream input = Files.newInputStream(file); MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(input)) {
            decoded = new Lift(new MessagePackReader(unpacker), new Data() {});
        }
        byte[] after = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        require(java.util.Arrays.equals(before, after), "Read-only core check changed actual saved bytes");
        require(decoded.getId() == liftId && floorGeometry(decoded) && configured(decoded), "Saved core original ID/floors/configuration differ");
        JsonObject json = Utilities.getJsonObjectFromData(decoded);
        require(Math.abs(json.get("railProgress").getAsDouble()) < .01 && Math.abs(json.get("speed").getAsDouble()) < 1E-8
            && !decoded.hasCoolDown() && json.getAsJsonArray("instructions").size() == 0 && json.getAsJsonArray("ridingEntities").size() == 0,
            "Saved original core Lift is not idle and empty at floor1");
        require(coreSample != null && decoded.getStyle().equals(coreSample.style), "Saved core style changed");
        report.addProperty("saved_core_payload_file", file.toString()); report.add("saved_core_lift", json);
        report.addProperty("saved_core_readonly_bytes_unchanged", true);
    }

    private static byte[] savedArray(NBTTagCompound section, String name, int length, boolean optional) {
        if (!section.hasKey(name)) { require(optional, "Missing saved Anvil array " + name); return new byte[0]; }
        require(section.hasKey(name, 7), "Saved Anvil field is not byte array: " + name);
        byte[] value = section.getByteArray(name); require(value.length == length, "Wrong Anvil array length " + name); return value;
    }
    private static int nibble(byte[] value, int index) { return (value[index >> 1] >> ((index & 1) * 4)) & 15; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private synchronized void fail(Throwable problem) { if (failure == null) failure = problem; }

    private static final class DoorCycle {
        double previous;
        boolean opening, open, closing, closed;
        void observe(double value) {
            if (value > previous + .005) opening = true;
            if (opening && value >= .95) open = true;
            if (open && value < previous - .005) closing = true;
            if (closing && value <= .02) closed = true;
            previous = value;
        }
        boolean complete() { return opening && open && closing && closed; }
        JsonObject json() { JsonObject value = new JsonObject(); value.addProperty("opening", opening); value.addProperty("open", open);
            value.addProperty("closing", closing); value.addProperty("closed", closed); return value; }
    }

    private static final class CoreSample {
        final long at = System.currentTimeMillis(), id;
        final int count, floorCount;
        final boolean configured, floorGeometry, topInstruction, bottomInstruction, cooldown, emptyQueue;
        final double progress, speed;
        final String style;
        final JsonObject json;
        CoreSample(Simulator simulator) {
            count = simulator.lifts.size();
            require(count <= 1, "New owned original simulator has unexpected multiple lifts");
            Lift lift = null; for (Lift value : simulator.lifts) lift = value;
            if (lift == null) {
                id = 0; floorCount = 0; configured = floorGeometry = topInstruction = bottomInstruction = cooldown = false;
                emptyQueue = true; progress = speed = 0; style = ""; json = new JsonObject();
            } else {
                id = lift.getId(); floorCount = lift.getFloorCount(); configured = configured(lift); floorGeometry = floorGeometry(lift);
                topInstruction = lift.hasInstruction(2).contains(LiftDirection.NONE); bottomInstruction = lift.hasInstruction(0).contains(LiftDirection.NONE);
                cooldown = lift.hasCoolDown(); style = lift.getStyle(); json = Utilities.getJsonObjectFromData(lift);
                progress = json.get("railProgress").getAsDouble(); speed = json.get("speed").getAsDouble();
                emptyQueue = json.getAsJsonArray("instructions").size() == 0;
            }
        }
    }

    private static final class ClientSample {
        final int tick, floorCount, renderDistanceChunks, cameraX, cameraY, cameraZ;
        final long requestRadius, frame, atNanos, mtrRenderedMillis;
        final long liftId;
        final double x, y, z, motionY, cabinX, cabinY, cabinZ, yaw, door;
        final boolean riding, onGround, noClip, flying, labelsMatch, linksMatch, unlocked, buttonStates, emptyHand;
        final boolean floorGeometry, configured, topInstruction, bottomInstruction;
        final boolean shouldRender;
        final double[] landingDoors = new double[3];
        final JsonObject liftJson, nativeInput, originalRiding;
        ClientSample(Minecraft client, Lift lift, int tick, long frame, long mtrRenderedMillis) {
            this.frame = frame; this.mtrRenderedMillis = mtrRenderedMillis; atNanos = System.nanoTime();
            this.tick = tick; x = client.thePlayer.posX; y = client.thePlayer.boundingBox.minY; z = client.thePlayer.posZ;
            renderDistanceChunks = MinecraftClientHelper.getRenderDistance(); requestRadius = renderDistanceChunks * 16L;
            BlockPos camera = org.mtr.mapping.holder.MinecraftClient.getInstance().getGameRendererMapped().getCamera().getBlockPos();
            cameraX = camera.getX(); cameraY = camera.getY(); cameraZ = camera.getZ();
            motionY = client.thePlayer.motionY;
            nativeInput = new JsonObject();
            nativeInput.addProperty("entity_move_forward", client.thePlayer.moveForward); nativeInput.addProperty("entity_move_strafing", client.thePlayer.moveStrafing);
            nativeInput.addProperty("movement_input_present", client.thePlayer.movementInput != null);
            if (client.thePlayer.movementInput != null) {
                nativeInput.addProperty("movement_input_move_forward", client.thePlayer.movementInput.moveForward);
                nativeInput.addProperty("movement_input_move_strafe", client.thePlayer.movementInput.moveStrafe);
            }
            nativeInput.addProperty("key_forward", client.gameSettings.keyBindForward.getIsKeyPressed()); nativeInput.addProperty("key_back", client.gameSettings.keyBindBack.getIsKeyPressed());
            nativeInput.addProperty("rotation_yaw", client.thePlayer.rotationYaw); nativeInput.addProperty("motion_x", client.thePlayer.motionX); nativeInput.addProperty("motion_z", client.thePlayer.motionZ);
            originalRiding = originalRidingDiagnostics();
            onGround = client.thePlayer.onGround; noClip = client.thePlayer.noClip; flying = client.thePlayer.capabilities.isFlying;
            emptyHand = client.thePlayer.getHeldItem() == null;
            labelsMatch = labelsMatch(client.theWorld); linksMatch = linksMatch(client.theWorld);
            boolean allUnlocked = true, allButtons = true;
            for (int floor = 0; floor < 3; floor++) {
                int fy = FLOOR_Y[floor];
                if (!(client.theWorld.getBlock(9, fy, 5) instanceof BlockLiftButtons)) { allUnlocked = allButtons = false; continue; }
                try { allUnlocked &= unlocked(client.theWorld, 9, fy, 5); }
                catch (IllegalArgumentException | IllegalStateException waitingForNativeStateSync) { allUnlocked = false; }
                for (int dx = 7; dx <= 8; dx++) for (int dy = 0; dy <= 1; dy++) {
                    if (!(client.theWorld.getBlock(dx, fy + dy, 5) instanceof BlockLiftDoor)) allUnlocked = false;
                    else {
                        try { allUnlocked &= unlocked(client.theWorld, dx, fy + dy, 5); }
                        catch (IllegalArgumentException | IllegalStateException waitingForNativeStateSync) { allUnlocked = false; }
                    }
                }
                boolean[] buttons = {false, false};
                BlockLiftButtons.hasButtonsClient(floorPos(floor), buttons, (index, value) -> {});
                allButtons &= buttons[0] == (floor > 0) && buttons[1] == (floor < 2);
                TileEntity tile = client.theWorld.getTileEntity(8, fy, 5);
                try {
                    landingDoors[floor] = tile instanceof BlockPSDAPGDoorBase.BlockEntityBase ? ((BlockPSDAPGDoorBase.BlockEntityBase) tile).getDoorValue() : 0;
                } catch (IllegalArgumentException | IllegalStateException waitingForNativeDoorState) { landingDoors[floor] = 0; }
            }
            unlocked = allUnlocked; buttonStates = allButtons;
            if (lift == null) {
                liftId = 0; floorCount = 0; cabinX = cabinY = cabinZ = yaw = door = 0;
                riding = floorGeometry = configured = topInstruction = bottomInstruction = shouldRender = false; liftJson = new JsonObject();
            } else {
                liftId = lift.getId(); floorCount = lift.getFloorCount(); riding = VehicleRidingMovement.isRiding(liftId);
                MinecraftClientData.LiftWrapper wrapper = MinecraftClientData.getInstance().liftWrapperList.get(liftId);
                shouldRender = wrapper != null && wrapper.shouldRender;
                Vector position = lift.getPosition((a, b) -> ItemLiftRefresher.findPath(new org.mtr.mapping.holder.World(client.theWorld), a, b));
                cabinX = position.x + lift.getOffsetX(); cabinY = position.y + lift.getOffsetY(); cabinZ = position.z + lift.getOffsetZ();
                yaw = -Math.PI / 2 - lift.getAngle().angleRadians;
                door = lift.getDoorValue(); floorGeometry = floorGeometry(lift); configured = configured(lift);
                topInstruction = lift.hasInstruction(2).contains(LiftDirection.NONE); bottomInstruction = lift.hasInstruction(0).contains(LiftDirection.NONE);
                liftJson = Utilities.getJsonObjectFromData(lift);
            }
        }
        JsonObject geometry() {
            JsonObject value = new JsonObject(); value.addProperty("center_x", cabinX); value.addProperty("center_y", cabinY); value.addProperty("center_z", cabinZ);
            value.addProperty("original_renderer_yaw_radians", yaw);
            // RenderLifts deliberately caps the landing-door animation at its .75 doorway width.
            value.addProperty("original_landing_door_max", .75);
            value.addProperty("front_doorway_min_x", cabinX - .75); value.addProperty("front_doorway_max_x", cabinX + .75);
            value.addProperty("front_doorway_min_z", cabinZ - 1); value.addProperty("front_doorway_max_z", cabinZ - .75);
            value.addProperty("cabin_floor_min_z", cabinZ - .75); value.addProperty("cabin_floor_max_z", cabinZ + .75); return value;
        }
        JsonObject json() {
            JsonObject value = new JsonObject(); value.addProperty("client_sample_boundary", "RenderTick.END after original MTR world render");
            value.addProperty("render_frame", frame); value.addProperty("original_mtr_render_millis", mtrRenderedMillis);
            value.addProperty("client_tick", tick); value.addProperty("client_x", x); value.addProperty("client_feet_y", y); value.addProperty("client_z", z);
            value.addProperty("client_on_ground", onGround); value.addProperty("client_no_clip", noClip); value.addProperty("client_flying", flying);
            value.addProperty("client_motion_y", motionY); value.addProperty("original_renderer_should_render", shouldRender);
            value.add("actual_client_interest", interest());
            value.add("client_native_input", nativeInput); value.add("original_riding_state", originalRiding);
            value.addProperty("client_riding", riding); value.addProperty("lift_id", Long.toString(liftId)); value.add("cabin_geometry", geometry());
            value.addProperty("door", door); value.addProperty("client_relative_feet_y", y - cabinY);
            JsonArray landing = new JsonArray(); for (double entry : landingDoors) landing.add(new org.mtr.libraries.com.google.gson.JsonPrimitive(entry));
            value.add("landing_door_values_floor1_2_3", landing); value.add("original_client_lift", liftJson); return value;
        }
        JsonObject interest() {
            JsonObject value = new JsonObject(); value.addProperty("render_distance_chunks", renderDistanceChunks);
            value.addProperty("original_data_request_radius", requestRadius); value.addProperty("camera_block_x", cameraX);
            value.addProperty("camera_block_y", cameraY); value.addProperty("camera_block_z", cameraZ);
            value.addProperty("camera_inside_original_lift_interest_bounds", Utilities.isBetween(new Position(cameraX, cameraY, cameraZ),
                new Position(8, 72, 8), new Position(8, 88, 8), requestRadius));
            return value;
        }
    }

    private static final class StoredBlock {
        final int x, y, z, id, metadata;
        final NBTTagCompound tag;
        final Map<String, String> properties;
        StoredBlock(int x, int y, int z, int id, int metadata, NBTTagCompound tag, Map<String, String> properties) {
            this.x = x; this.y = y; this.z = z; this.id = id; this.metadata = metadata;
            this.tag = tag == null ? null : (NBTTagCompound) tag.copy(); this.properties = properties;
        }
    }
}
