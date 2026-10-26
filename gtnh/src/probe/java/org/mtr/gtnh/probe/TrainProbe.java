package org.mtr.gtnh.probe;

import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.*;
import net.minecraft.world.chunk.*;
import org.mtr.core.Main;
import org.mtr.core.data.*;
import org.mtr.core.data.Position;
import org.mtr.core.operation.*;
import org.mtr.core.serializer.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.*;
import org.mtr.core.tool.Vector;
import org.mtr.gtnh.RidingPermissions;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.org.msgpack.core.*;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mapping.tool.*;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.client.*;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.item.ItemBlockClickingBase;
import org.mtr.mod.packet.*;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;
import org.mtr.mod.resource.VehicleResource;
import org.mtr.mod.resource.VehicleResourceCache;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Explicit test-only full-MTR train acceptance; never opens a pre-existing world. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_train_probe", name="MTR GTNH original scheduled train acceptance", version="0", dependencies="required-after:mtr", acceptableRemoteVersions="*")
public final class TrainProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT=new File(requiredProbePath("mtr.probeClient"));
    private static final int RIDE_FPS_LIMIT=12;
    private static final int FIXTURE_RAIL_SPEED_KMH=10;
    private static final int[] NODES={0,16,24,48,64,88,112};
    private static final String CAR="eidan_9000_mini_cab_3", A_NAME="Probe Station A", B_NAME="Probe Station B", D_NAME="Probe Depot", R_NAME="Probe A to B";
    private enum Stage {MENU,WORLD,SETUP,CHUNKS,SIGNAL_MODELS,DASHBOARD,AREAS,PLATFORMS,ROUTE,SIDING,DEPOT,GENERATE,SCHEDULE,PICKUP_A,BOARD,DOOR_HOLD,DOOR_RELEASE,DEPART,RIDE,STOP_B,EXIT,DISMOUNT,GRAVITY,RETURN_IDLE,AUDIO_RELOAD,STATION_OBJECTS,AUDIO_TACTILE,AUDIO_UNLOAD,CLOSE,FINISHED}
    private volatile Stage stage=Stage.MENU,clientAcknowledged;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private volatile long stationAId,stationBId,depotId,routeId,sidingId,platformAId,platformBId,vehicleId,generationRequestedAt,generationPreviousMillis,plannedDepartureMillis,plannedDepartureAbsolute;
    private volatile long startedAt,stageStartedAt;
    private volatile ClientSample clientSample;
    private volatile CoreSample coreSample;
    private final AtomicBoolean coreReadPending=new AtomicBoolean();
    private final AtomicInteger s08=new AtomicInteger(),c03=new AtomicInteger(),c17=new AtomicInteger(),fmlProxy=new AtomicInteger(),mtrFrames=new AtomicInteger(),ridingInbound=new AtomicInteger(),ridingRejections=new AtomicInteger();
    private final ConcurrentLinkedQueue<JsonObject> corrections=new ConcurrentLinkedQueue<>(),inboundFrames=new ConcurrentLinkedQueue<>(),rejectionReasons=new ConcurrentLinkedQueue<>();
    private volatile JsonObject lastC03,lastPositionC03,lastRidingInbound,lastRidingRejection;
    private volatile RecoveryAck recoveryAck;
    private volatile boolean recoveryAckAccepted;
    private org.apache.logging.log4j.core.Logger ridingLogger;
    private org.apache.logging.log4j.core.config.LoggerConfig ridingObserverConfig;
    private org.apache.logging.log4j.Level previousRidingLevel;
    private org.apache.logging.log4j.core.appender.AbstractAppender ridingLogObserver;
    private boolean ridingLogRestored;
    private volatile boolean packetObserverReady,clientBoardingAuthorized,dashboardObserved;
    private boolean packetObserverRequested,copyVerified,boardAnchor;
    private int menuTicks,clientTicks,phaseTicks,readyChunkTicks,setupTeleports,boardingS08,motionS08,stillTicks,jumpClientTicks;
    private double jumpPeak=Double.NEGATIVE_INFINITY,serverJumpPeak=Double.NEGATIVE_INFINITY;
    private Simulator simulator;
    private String runId,saveName;
    private File saveDirectory;
    private volatile int shotRequest,shotAcknowledged;
    private volatile String shotLabel;
    private int renderedFrames;
    private final JsonObject report=new JsonObject();
    private final TrainAudioObserver trainAudio=new TrainAudioObserver();
    private final TactileAudioObserver tactileAudio=new TactileAudioObserver();
    private final AudioUnloadObserver audioUnload=new AudioUnloadObserver();
    private final SignalModels signalModels=new SignalModels();
    private final JsonArray rideRenderFrameNanos=new JsonArray();
    private long lastRideRenderFrame;
    private final StationObjects stationObjects=new StationObjects();
    private final JsonArray timeline=new JsonArray(),transitions=new JsonArray(),screenshots=new JsonArray(),placements=new JsonArray();
    private final List<StoredBlock> expectedSavedBlocks=new ArrayList<>();
    private final Map<String,JsonObject> expectedCore=new LinkedHashMap<>();

    private volatile Vector pickupPose;
    private double boardedZ,jumpBase;
    private boolean movingCaptured,gravityFalling;
    private long passengerHoldSince,passengerLastCoreHold;
    private boolean passengerDoorHoldAccepted,passengerDoorReleaseAccepted,passengerNaturalDeparture;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=stageStartedAt=System.currentTimeMillis();
        require(!Loader.isModLoaded("mtr_gtnh_probe")&&!Loader.isModLoaded("mtr_gtnh_world_probe")&&!Loader.isModLoaded("mtr_gtnh_lift_probe"),"TrainProbe must be the only selected probe");
        FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null||stage==Stage.CLOSE){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<15*60*1000L,"Train probe total deadline in "+stage);
            if(stage==Stage.MENU) {
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();
                if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                reserveNewSave(client);report.addProperty("menu_class",screen);
                client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=1;client.gameSettings.renderDistanceChunks=8;client.gameSettings.limitFramerate=RIDE_FPS_LIMIT;client.gameSettings.saveOptions();
                advance(Stage.WORLD);
                WorldSettings settings=new WorldSettings(0x4D5452545241494EL,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT).enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName,"MTR train acceptance "+runId,settings);return;
            }
            if(stage==Stage.AUDIO_UNLOAD){unloadAudio(client);return;}
            if(client.getIntegratedServer()!=null)assertOwnedServer(client.getIntegratedServer());
            if(stage==Stage.WORLD) {
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                playerId=client.thePlayer.getUniqueID();report.addProperty("player_uuid",playerId.toString());advance(Stage.SETUP);return;
            }
            require(client.theWorld!=null&&client.thePlayer!=null&&client.getIntegratedServer()!=null,"Owned train world/player disappeared");
            if(client.gameSettings.pauseOnLostFocus){client.gameSettings.pauseOnLostFocus=false;client.gameSettings.saveOptions();}
            if(stage==Stage.DASHBOARD&&client.currentScreen instanceof org.mtr.mod.screen.DashboardScreen){dashboardObserved=true;report.addProperty("dashboard_native_screen_class",client.currentScreen.getClass().getName());}
            if(client.currentScreen!=null&&!(stage==Stage.STATION_OBJECTS&&stationObjects.keepScreen(client.currentScreen)))client.displayGuiScreen(null);
            KeyBinding.unPressAllKeys();
            Stage current=stage;
            ClientSample previous=clientSample;
            if(current==Stage.BOARD) {
                if(previous!=null&&previous.vehiclePresent&&previous.doorway!=null) {
                    boolean enterDoor=!previous.riding||originalPassengerBlocked(previous)<=0;
                    Box door=previous.doorway;
                    Vector doorwayCenter=new Vector((door.getMinXMapped()+door.getMaxXMapped())/2,door.getMaxYMapped(),(door.getMinZMapped()+door.getMaxZMapped())/2)
                            .rotateY(previous.yaw).add(new Vector(previous.centerX,previous.centerY,previous.centerZ));
                    if(enterDoor)key(client.gameSettings.keyBindForward,client.thePlayer.posX<doorwayCenter.x);
                    if(enterDoor&&client.thePlayer.posX>=doorwayCenter.x)key(client.gameSettings.keyBindBack,true);
                }
            } else if(current==Stage.DOOR_RELEASE&&previous!=null)key(client.gameSettings.keyBindForward,client.thePlayer.posX<previous.centerX+.2);
            else if(current==Stage.EXIT&&previous!=null&&client.thePlayer.posX>previous.centerX-2.4)key(client.gameSettings.keyBindBack,true);
            else if(current==Stage.GRAVITY&&jumpClientTicks++<2)key(client.gameSettings.keyBindJump,true);
            if(current==Stage.STATION_OBJECTS)stationObjects.client(client);
            if(current==Stage.AUDIO_TACTILE&&tactileAudio.placed)tactileAudio.observe(client,clientTicks+1);
            if(clientAcknowledged!=current&&configureClient(current))clientAcknowledged=current;
            ClientSample nextSample=new ClientSample(client,++clientTicks);
            if(current==Stage.RIDE&&trainAudio.geometryPendingObserved&&!trainAudio.geometryRecovered&&nextSample.vehicleGeometryCacheReady)
                armRecoveryAck(nextSample,trainAudio.activeRefreshEvidence.getAsJsonObject("pending_geometry_rider_sample"));
            clientSample=nextSample;
            trainAudio.observe(client,current.name(),vehicleId,clientSample);
            if(current==Stage.RIDE)trainAudio.refreshInMotion(client,clientSample);
            if(current==Stage.SIGNAL_MODELS)signalModels.observe(client,false);
            if(current==Stage.AUDIO_RELOAD&&trainAudio.accepted())signalModels.observe(client,true);
            if(clientTicks%100==0)System.out.println("MTR_TRAIN_PROBE_PROGRESS "+stage+" tick="+clientTicks+" station_step="+stationObjects.step);
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null)return;
        Stage current=stage;if(current==Stage.MENU||current==Stage.WORLD||current==Stage.AUDIO_UNLOAD||current==Stage.CLOSE||current==Stage.FINISHED)return;
        try {
            MinecraftServer server=MinecraftServer.getServer();assertOwnedServer(server);EntityPlayerMP player=actualPlayer(server);
            if(player==null){require(current==Stage.SETUP&&++phaseTicks<200,"Actual server passenger missing");return;}
            WorldServer world=server.worldServerForDimension(0);require(player.worldObj==world,"Passenger/stand world mismatch");
            require(System.currentTimeMillis()-stageStartedAt<(current==Stage.SCHEDULE?180000:current==Stage.STATION_OBJECTS?120000:current==Stage.CHUNKS?90000:60000),"Train phase deadline in "+current);++phaseTicks;
            if(current==Stage.SETUP) {
                if(!packetObserverRequested){report.add("initial_server_player",playerPosition(player));player.setGameType(WorldSettings.GameType.CREATIVE);player.capabilities.isFlying=false;player.sendPlayerAbilities();installPacketObserver(player.playerNetServerHandler.netManager);packetObserverRequested=true;return;}
                if(!packetObserverReady)return;setupTeleport(player,4.5,4,32.5,0);advance(Stage.CHUNKS);return;
            }
            if(current==Stage.CHUNKS) {
                boolean ready=true;JsonArray chunks=new JsonArray();for(int z=0;z<=7;z++){JsonObject chunk=chunkStatus(world,0,z);chunks.add(chunk);ready&=chunk.get("ready").getAsBoolean();}
                report.add("stand_chunk_last_wait",chunks);ClientSample client=clientSample;
                ready&=client!=null&&Math.abs(client.x-4.5)<.2&&Math.abs(client.z-32.5)<.2;
                readyChunkTicks=ready?readyChunkTicks+1:0;if(readyChunkTicks<2)return;
                buildStand(world,player);simulator=actualSimulator();advance(Stage.SIGNAL_MODELS);return;
            }
            if(current==Stage.SIGNAL_MODELS) {
                if(!signalModels.before)return;advance(Stage.DASHBOARD);
                Item item=originalItem("dashboard",org.mtr.mod.Items.RAILWAY_DASHBOARD.get().data);ItemStack stack=new ItemStack(item);player.inventory.mainInventory[0]=stack;item.onItemRightClick(stack,world,player);return;
            }
            readCoreSoon();ClientSample client=clientSample;CoreSample core=coreSample;require(client!=null,"Actual client sample missing");
            if(core!=null)require(System.currentTimeMillis()-core.at<5000,"Original simulator stopped publishing read-only samples");
            observe(world,player,client,core);
            if(core==null)return;
            if(current==Stage.DASHBOARD&&core.railsComplete&&client.fixtureReady&&dashboardObserved&&client.dashboardSavedRailsReady){sidingId=core.siding;platformAId=core.platformA;platformBId=core.platformB;require(sidingId!=0&&platformAId!=0&&platformBId!=0,"Original saved rail IDs missing");if(!captureDone("stand"))return;player.inventory.mainInventory[0]=null;advance(Stage.AREAS);}
            else if(current==Stage.AREAS&&clientAcknowledged==current&&core.areasConfigured){report.addProperty("fixture_speed_acknowledged_before_generation",true);advance(Stage.PLATFORMS);}
            else if(current==Stage.PLATFORMS&&clientAcknowledged==current&&core.platformsConfigured)advance(Stage.ROUTE);
            else if(current==Stage.ROUTE&&clientAcknowledged==current&&core.routeConfigured)advance(Stage.SIDING);
            else if(current==Stage.SIDING&&clientAcknowledged==current&&core.sidingConfigured)advance(Stage.DEPOT);
            else if(current==Stage.DEPOT&&clientAcknowledged==current&&core.depotConfigured)advance(Stage.GENERATE);
            else if(current==Stage.GENERATE&&clientAcknowledged==current) {
                if(core.generationFailed)throw new AssertionError("Original route generation failed: "+core.depotJson);
                if(!core.generated)return;report.add("generated_original_depot",core.depotJson);report.add("generated_siding_paths",core.sidingJson);advance(Stage.SCHEDULE);
            } else if(current==Stage.SCHEDULE) {
                if(!core.vehiclePresent||!core.onRoute||!client.vehiclePresent)return;vehicleId=core.vehicle;require(client.vehicle==vehicleId,"Client/core scheduled vehicle identity mismatch");
                report.addProperty("naturally_scheduled_vehicle_id",Long.toString(vehicleId));report.addProperty("natural_scheduled_startup_observed",true);advance(Stage.PICKUP_A);
            } else if(current==Stage.PICKUP_A) {
                if(!atPlatform(client,core,platformAId,24,48)||!client.resourceReady||!client.shouldRender||client.door<.95)return;
                if(setupTeleports<2){require(!client.riding&&!PlayerPhysics.authorized(player),"Final setup would move an existing rider");pickupPose=client.pickup;require(pickupPose!=null&&Math.abs(pickupPose.y-5)<.02,"Actual model floor differs from physical platform");setupTeleport(player,pickupPose.x,pickupPose.y,pickupPose.z,-90);report.add("pickup_actual_geometry",client.geometry());return;}
                if(Math.abs(client.x-pickupPose.x)>.15||Math.abs(client.y-pickupPose.y)>.15||Math.abs(client.z-pickupPose.z)>.15)return;
                if(!client.emptyHand||player.getHeldItem()!=null)return;
                if(!captureDone("pickupA"))return;boardingS08=s08.get();advance(Stage.BOARD);
            } else if(current==Stage.BOARD) {
                require(atPlatform(client,core,platformAId,24,48)&&client.door>.2,"Natural boarding/authorization missed original platform A door window");
                require(client.emptyHand&&player.getHeldItem()==null,"Natural boarding requires empty hands");
                if(!client.riding||!core.rider||!PlayerPhysics.authorized(player))return;
                if(originalPassengerBlocked(client)<=0||originalDoorOverrideTicks(client)<=0||!core.passengerDoorOverride)return;
                require(!core.passengerControls,"Original no-key passenger acquired manual controls");
                boardAnchor=true;motionS08=s08.get();boardedZ=client.z;
                passengerHoldSince=passengerLastCoreHold=System.currentTimeMillis();
                report.addProperty("natural_render_boarding_authorized",true);report.add("passenger_doorway_entry",client.geometry());advance(Stage.DOOR_HOLD);
            } else if(current==Stage.DOOR_HOLD) {
                if(!assertPassenger(player,client,core))return;
                require(!client.moving&&!core.moving&&originalPassengerBlocked(client)>0&&originalDoorOverrideTicks(client)>0,"Original no-key doorway occupancy did not keep the train stopped");
                require(!core.passengerControls,"Passenger hold acquired a manual control");
                if(core.passengerDoorOverride)passengerLastCoreHold=core.at;
                require(System.currentTimeMillis()-passengerLastCoreHold<3000,"Fresh native passenger hold no longer reached the original Core");
                if(core.dwellMillis>=20000&&core.doorTarget==-1&&client.door<.05&&core.doorCooldown>0&&core.passengerDoorOverride
                        &&System.currentTimeMillis()-passengerHoldSince>22000) {
                    if(!captureDone("doorHolding"))return;
                    passengerDoorHoldAccepted=true;report.addProperty("passenger_hold_after_original_dwell_with_closed_target",true);advance(Stage.DOOR_RELEASE);
                }
            } else if(current==Stage.DOOR_RELEASE) {
                if(!assertPassenger(player,client,core))return;
                if(Math.abs(client.x-client.centerX)>=.9||originalPassengerBlocked(client)>0||originalDoorOverrideTicks(client)>0||core.passengerDoorOverride)return;
                require(!core.passengerControls,"Passenger release acquired a manual control");
                passengerDoorReleaseAccepted=true;boardedZ=client.z;report.addProperty("passenger_hold_release_reached_core",true);advance(Stage.DEPART);
            } else if(current==Stage.DEPART) {
                if(!assertPassenger(player,client,core))return;if(client.moving&&core.moving&&Math.abs(client.centerZ-boardedZ)>1){passengerNaturalDeparture=true;advance(Stage.RIDE);}
            } else if(current==Stage.RIDE) {
                if(!assertPassenger(player,client,core))return;
                if(!movingCaptured&&client.moving&&Math.abs(client.centerZ-boardedZ)>2)movingCaptured=captureDone("moving");
                if(atPlatform(client,core,platformBId,64,88)&&client.door>.95){require(movingCaptured&&Math.abs(client.z-boardedZ)>8,"No captured real passenger journey");advance(Stage.STOP_B);}
            } else if(current==Stage.STOP_B) {
                if(!assertPassenger(player,client,core))return;require(atPlatform(client,core,platformBId,64,88),"Train left destination before exit");if(captureDone("arrivalB"))advance(Stage.EXIT);
            } else if(current==Stage.EXIT) {
                require(atPlatform(client,core,platformBId,64,88)&&client.door>.2,"Natural exit missed original destination door window");
                if(!client.riding&&client.x<client.centerX-1.9)advance(Stage.DISMOUNT);
            } else if(current==Stage.DISMOUNT) {
                if(client.riding||core.rider||PlayerPhysics.authorized(player)||player.noClip||client.noClip)return;
                require(Math.abs(PlayerPhysics.gravity(.08,player)-.08)<1E-10&&!client.flying&&!player.capabilities.isFlying,"Native dismount did not restore ordinary player physics");
                jumpBase=client.y;jumpPeak=client.y;serverJumpPeak=player.boundingBox.minY;jumpClientTicks=0;advance(Stage.GRAVITY);
            } else if(current==Stage.GRAVITY) {
                require(!client.riding&&!PlayerPhysics.authorized(player)&&!player.noClip&&!client.flying&&!player.capabilities.isFlying,"Rider flags returned after native exit");
                jumpPeak=Math.max(jumpPeak,client.y);serverJumpPeak=Math.max(serverJumpPeak,player.boundingBox.minY);
                if(client.y<jumpPeak-.1&&client.motionY<-.02)gravityFalling=true;
                if(phaseTicks>10&&gravityFalling&&client.onGround&&player.onGround){require(jumpPeak>jumpBase+.2&&serverJumpPeak>jumpBase+.2,"No real client/server native jump after dismount");if(captureDone("dismounted"))advance(Stage.RETURN_IDLE);}
            } else if(current==Stage.RETURN_IDLE) {
                require(!client.riding&&!core.rider&&!PlayerPhysics.authorized(player),"Passenger reattached after exit");
                stillTicks=core.vehiclePresent&&core.vehicle==vehicleId&&core.closeToDepot&&!core.onRoute&&!core.moving?stillTicks+1:0;
                if(stillTicks<20||!trainAudio.stopped())return;advance(Stage.AUDIO_RELOAD);
            } else if(current==Stage.AUDIO_RELOAD) {
                require(!client.riding&&!core.rider&&!PlayerPhysics.authorized(player)&&core.vehiclePresent&&core.vehicle==vehicleId&&!core.onRoute&&!core.moving&&core.closeToDepot,"Resource refresh escaped dismounted idle train");
                if(clientAcknowledged!=current||!trainAudio.accepted()||!signalModels.after)return;advance(Stage.STATION_OBJECTS);
            } else if(current==Stage.STATION_OBJECTS) {
                if(!stationObjects.server(world,player,client,core))return;advance(Stage.AUDIO_TACTILE);
            } else if(current==Stage.AUDIO_TACTILE) {
                require(!client.riding&&!core.rider&&!core.onRoute&&!core.moving&&core.closeToDepot&&trainAudio.accepted(),"Tactile lifecycle escaped the accepted idle train");
                if(!tactileAudio.placed){
                    require(world.isAirBlock(4,5,82)&&world.isAirBlock(4,6,82)&&world.getBlock(4,4,82)==org.mtr.mod.Blocks.PLATFORM.get().data,"Tactile footprint is not clear above the original annex floor");
                    ItemStack map=new ItemStack(originalItem("tactile_map",Item.getItemFromBlock(org.mtr.mod.Blocks.TACTILE_MAP.get().data)));
                    use(world,player,map,4,4,82,1,.5F);require(map.stackSize==0&&world.getBlock(4,5,82)==org.mtr.mod.Blocks.TACTILE_MAP.get().data&&world.getBlock(4,6,82)==org.mtr.mod.Blocks.TACTILE_MAP.get().data,"Original tactile item did not create its two blocks");
                    tactileAudio.placed=true;return;
                }
                if(!tactileAudio.accepted)return;snapshotStand(world);expectedCore.putAll(core.fullData);report.add("pre_save_core",core.json());advance(Stage.AUDIO_UNLOAD);
            }
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        try {Minecraft client=Minecraft.getMinecraft();
            if(stage==Stage.RIDE&&failure==null){require(client.gameSettings.limitFramerate==RIDE_FPS_LIMIT,"RIDE FPS limit changed");long now=System.nanoTime();if(rideRenderFrameNanos.size()>0)require(now>lastRideRenderFrame,"Non-increasing actual RIDE render timestamps");rideRenderFrameNanos.add(new JsonPrimitive(now));lastRideRenderFrame=now;}
            if(failure!=null||shotRequest==shotAcknowledged)return;
            if(client.theWorld==null||client.thePlayer==null||++renderedFrames<4)return;
            if("moving".equals(shotLabel)&&(clientSample==null||!clientSample.moving))return;
            capture(client,shotLabel);shotAcknowledged=shotRequest;
        }catch(Throwable problem){fail(problem);}
    }
    private void capture(Minecraft client,String label)throws Exception {
        require(client.currentScreen==null,"World framebuffer is not rendered");String name="mtr-train-probe-"+runId+"-"+label+".png";
        ScreenShotHelper.saveScreenshot(client.mcDataDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());File file=new File(new File(client.mcDataDir,"screenshots"),name);
        require(file.isFile()&&file.length()>100,"Actual train framebuffer was not written");JsonObject value=new JsonObject();value.addProperty("phase",label);value.addProperty("path",file.getCanonicalPath());value.addProperty("render_frames_after_request",renderedFrames);value.addProperty("client_tick",clientTicks);screenshots.add(value);
    }
    private void advance(Stage next) {
        JsonObject value=new JsonObject();value.addProperty("from",stage.name());value.addProperty("to",next.name());value.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt);transitions.add(value);
        phaseTicks=stillTicks=0;stageStartedAt=System.currentTimeMillis();stage=next;
    }
    private void setupTeleport(EntityPlayerMP player,double x,double y,double z,float yaw) {
        require((stage==Stage.SETUP||stage==Stage.PICKUP_A)&&setupTeleports<2&&!PlayerPhysics.authorized(player),"Train teleport forbidden outside strict setup");
        player.playerNetServerHandler.setPlayerLocation(x,y,z,yaw,10);setupTeleports++;
    }
    private static void key(KeyBinding binding,boolean down){KeyBinding.setKeyBindState(binding.getKeyCode(),down);}
    private static void send(UpdateDataRequest request){InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(request));}
    private boolean configureClient(Stage current)throws Exception {
        if(current==Stage.AUDIO_RELOAD){trainAudio.refresh(Minecraft.getMinecraft(),clientSample);return true;}
        MinecraftClientData data=MinecraftClientData.getDashboardInstance();
        if(current==Stage.AREAS) {
            Station a=new Station(data),b=new Station(data);Depot d=new Depot(TransportMode.TRAIN,data);
            a.setName(A_NAME);a.setColor(0x3366CC);a.setCorners(new Position(0,Long.MIN_VALUE,20),new Position(16,Long.MAX_VALUE,52));
            b.setName(B_NAME);b.setColor(0x33AA66);b.setCorners(new Position(0,Long.MIN_VALUE,60),new Position(16,Long.MAX_VALUE,92));
            d.setName(D_NAME);d.setColor(0xAA6633);d.setCorners(new Position(0,Long.MIN_VALUE,-4),new Position(16,Long.MAX_VALUE,18));
            require(generationRequestedAt==0&&plannedDepartureAbsolute==0&&completeRails(data.rails),"Original item rails must be configured before scheduling/generation");
            UpdateDataRequest request=new UpdateDataRequest(data).addStation(a).addStation(b).addDepot(d);JsonArray slowRails=new JsonArray();
            for(Rail rail:data.rails) {
                JsonObject values=full(rail);values.addProperty("speedLimit1",FIXTURE_RAIL_SPEED_KMH);values.addProperty("speedLimit2",FIXTURE_RAIL_SPEED_KMH);
                Rail slow=new Rail(new JsonReader(values));
                require(slow.isValid()&&slow.getHexId().equals(rail.getHexId())&&slow.getSpeedLimitKilometersPerHour(true)==FIXTURE_RAIL_SPEED_KMH&&slow.getSpeedLimitKilometersPerHour(false)==FIXTURE_RAIL_SPEED_KMH,"Original rail speed update changed identity or failed");
                request.addRail(slow);slowRails.add(full(slow));
            }
            require(slowRails.size()==6,"Original six-rail speed update incomplete");
            stationAId=a.getId();stationBId=b.getId();depotId=d.getId();send(request);
            report.addProperty("fixture_rail_speed_kmh",FIXTURE_RAIL_SPEED_KMH);report.addProperty("fixture_speed_configured_before_generation",true);report.add("fixture_speed_updated_rails",slowRails);return true;
        } else if(current==Stage.PLATFORMS) {
            Platform a=data.platformIdMap.get(platformAId),b=data.platformIdMap.get(platformBId);if(a==null||b==null)return false;
            a.setName("1");b.setName("1");a.setDwellTime(20000);b.setDwellTime(20000);send(new UpdateDataRequest(data).addPlatform(a).addPlatform(b));return true;
        } else if(current==Stage.ROUTE) {
            if(!data.platformIdMap.containsKey(platformAId)||!data.platformIdMap.containsKey(platformBId))return false;
            Route route=new Route(TransportMode.TRAIN,data);route.setName(R_NAME);route.setColor(0x3366CC);route.setCircularState(Route.CircularState.NONE);
            for(long id:new long[]{platformAId,platformBId}){RoutePlatformData row=new RoutePlatformData(id);route.getRoutePlatforms().add(row);row.writePlatformCache(route,data.platformIdMap);}
            routeId=route.getId();send(new UpdateDataRequest(data).addRoute(route));return true;
        } else if(current==Stage.SIDING) {
            Siding siding=data.sidingIdMap.get(sidingId);if(siding==null)return false;final VehicleResource[] selected={null};
            CustomResourceLoader.getVehicleById(TransportMode.TRAIN,CAR,details->{if(CAR.equals(details.left().getId()))selected[0]=details.left();});
            if(selected[0]==null)return false;VehicleResource resource=selected[0];if(resource.getCachedVehicleResource(0,1,false)==null)return false;
            ObjectArrayList<VehicleCar> cars=new ObjectArrayList<>();cars.add(new VehicleCar(resource.getId(),resource.getLength(),resource.getWidth(),resource.getBogie1Position(),resource.getBogie2Position(),resource.getCouplingPadding1(),resource.getCouplingPadding2()));
            siding.setVehicleCars(cars);siding.setMaxVehicles(1);siding.setAcceleration(Siding.ACCELERATION_DEFAULT);siding.setDeceleration(Siding.ACCELERATION_DEFAULT);
            send(new UpdateDataRequest(data).addSiding(siding));return true;
        } else if(current==Stage.DEPOT) {
            Depot depot=data.depotIdMap.get(depotId);if(depot==null||!data.routeIdMap.containsKey(routeId))return false;
            depot.getRouteIds().clear();depot.getRouteIds().add(routeId);depot.setRepeatInfinitely(false);depot.setUseRealTime(true);
            plannedDepartureAbsolute=(System.currentTimeMillis()+120000)/1000*1000;plannedDepartureMillis=Math.floorMod(plannedDepartureAbsolute,86400000L);
            depot.getRealTimeDepartures().clear();depot.getRealTimeDepartures().add(plannedDepartureMillis);send(new UpdateDataRequest(data).addDepot(depot));
            report.addProperty("planned_departure_absolute_ms",plannedDepartureAbsolute);report.addProperty("planned_departure_millis_of_day",plannedDepartureMillis);report.addProperty("schedule_timezone",TimeZone.getDefault().getID());return true;
        } else if(current==Stage.GENERATE) {
            if(coreSample==null)return false;require(coreSample.fixtureRailSpeedConfigured,"Original lower rail speeds not acknowledged before generation");generationPreviousMillis=coreSample.generationMillis;generationRequestedAt=System.currentTimeMillis();DepotOperationByIds operation=new DepotOperationByIds();operation.addDepotId(depotId);InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDepotGenerate(operation));return true;
        }
        return false;
    }
    private void buildStand(WorldServer world,EntityPlayerMP player) {
        world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.setWorldTime(6000);world.difficultySetting=EnumDifficulty.PEACEFUL;
        for(int z=0;z<=112;z++)for(int x=5;x<=10;x++){world.setBlock(x,3,z,net.minecraft.init.Blocks.stone);for(int y=4;y<=10;y++)world.setBlockToAir(x,y,z);}
        player.rotationYaw=0;
        for(int z:NODES){ItemStack stack=new ItemStack(originalItem("rail",Item.getItemFromBlock(org.mtr.mod.Blocks.RAIL_NODE.get().data)));use(world,player,stack,8,3,z,1,.5F);require(stack.stackSize==0,"Original node item did not consume one block");}
        player.rotationYaw=-90;
        for(int start:new int[]{24,64})for(int z=start;z<=start+24;z++)for(int x=5;x<=7;x++){ItemStack stack=new ItemStack(originalItem("platform",Item.getItemFromBlock(org.mtr.mod.Blocks.PLATFORM.get().data)));use(world,player,stack,x,3,z,1,.5F);require(stack.stackSize==0,"Original platform item did not consume one block");}
        player.rotationYaw=0;
        for(int edge=0;edge<6;edge++) {
            String name=edge==0?"rail_connector_siding":edge==2||edge==4?"rail_connector_platform":edge==5?"rail_connector_turn_back":"rail_connector_20";
            Item item=(Item)Item.itemRegistry.getObject("mtr:"+name);require(item instanceof org.mtr.mod.item.ItemRailModifier,"Original train connector missing");ItemStack connector=new ItemStack(item);
            use(world,player,connector,8,4,NODES[edge],1,.5F);
            require(connector.hasTagCompound()&&connector.getTagCompound().getLong(ItemBlockClickingBase.TAG_POS)==new BlockPos(8,4,NODES[edge]).asLong(),"Original rail first-click state missing");
            use(world,player,connector,8,4,NODES[edge+1],1,.5F);require(!connector.getTagCompound().hasKey(ItemBlockClickingBase.TAG_POS)&&connector.stackSize==1,"Original reusable connector click pair failed");
        }
        player.inventory.mainInventory[0]=null;player.inventory.currentItem=0;report.addProperty("original_native_node_count",7);report.addProperty("original_native_platform_block_count",150);stationObjects.build(world,player);signalModels.build(world,player);
    }
    private static boolean completeRails(Iterable<Rail> rails) {return completeRails(rails,false);}
    private static boolean completeRails(Iterable<Rail> rails,boolean configuredSpeed) {
        boolean[] seen=new boolean[6];int count=0;long speed=configuredSpeed?FIXTURE_RAIL_SPEED_KMH:20;
        for(Rail rail:rails){int edge=-1;for(int i=0;i<6;i++)if(rail.getHexId().equals(TwoPositionsBase.getHexId(new Position(8,4,NODES[i]),new Position(8,4,NODES[i+1]))))edge=i;
            if(edge<0||seen[edge]||rail.getTransportMode()!=TransportMode.TRAIN||Math.abs(rail.railMath.getLength()-(NODES[edge+1]-NODES[edge]))>.01||rail.isSiding()!=(edge==0)||rail.isPlatform()!=(edge==2||edge==4)||rail.canTurnBack()!=(edge==5))return false;
            if((configuredSpeed||edge==1||edge==3)&&(rail.getSpeedLimitKilometersPerHour(true)!=speed||rail.getSpeedLimitKilometersPerHour(false)!=speed))return false;seen[edge]=true;count++;
        }return count==6;
    }
    private static Vector pickupPoint(Vector center,double yaw,Box door) {
        double x=(door.getMinXMapped()+door.getMaxXMapped())/2,z=(door.getMinZMapped()+door.getMaxZMapped())/2;
        return new Vector(x+Math.copySign(.75,x),door.getMaxYMapped(),z).rotateY(yaw).add(center);
    }
    private static boolean atPlatform(ClientSample client,CoreSample core,long platform,int min,int max) {
        return client.carConfigured&&core.carConfigured&&client.vehiclePresent&&core.vehiclePresent&&client.vehicle==core.vehicle&&!client.moving&&!core.moving&&core.onRoute&&client.centerZ>=min&&client.centerZ<=max&&core.stoppedPlatform==platform;
    }
    private static long stoppedPlatform(List<PathData> path,int nextStoppingIndex,double railProgress) {
        // Terminal route-info already points beyond the platform during its dwell.
        if(nextStoppingIndex<0||nextStoppingIndex>=path.size())return 0;
        PathData stop=path.get(nextStoppingIndex);
        return stop.getDwellTime()>0&&stop.getEndDistance()==railProgress?stop.getSavedRailBaseId():0;
    }
    private static double originalPassengerBlocked(ClientSample client) {
        JsonObject row=client.originalRiding;
        if(client.doorway==null||!client.riding||!row.has("ridingVehicleX")||!row.has("ridingVehicleY")||!row.has("ridingVehicleZ"))return 0;
        return RenderVehicleHelper.getDoorBlockedAmount(client.doorway,row.get("ridingVehicleX").getAsDouble(),row.get("ridingVehicleY").getAsDouble(),row.get("ridingVehicleZ").getAsDouble());
    }
    private static int originalDoorOverrideTicks(ClientSample client) {
        return client.originalRiding.has("doorOverrideTicks")?client.originalRiding.get("doorOverrideTicks").getAsInt():0;
    }
    private boolean assertPassenger(EntityPlayerMP player,ClientSample client,CoreSample core) {
        require(client.carConfigured&&core.carConfigured,"Actual passenger train lost original Eidan 9000 Mini BVE car");
        require(client.emptyHand&&player.getHeldItem()==null,"Natural train passenger must have empty hands");
        require(client.riding&&core.rider&&client.vehicle==vehicleId&&core.vehicle==vehicleId&&PlayerPhysics.authorized(player),"Original train passenger lost client/core/server binding");
        require(player.noClip&&Math.abs(PlayerPhysics.gravity(.08,player))<1E-10&&!client.flying&&!player.capabilities.isFlying,"Passenger native physics/flight mismatch");
        require(Math.abs(client.y-client.floorY)<.4&&Math.abs(player.boundingBox.minY-client.floorY)<1.5,"Actual client/server passenger stopped following original car");
        require(s08.get()-motionS08<=2,"Repeated native S08 correction during train ride");
        if(!recoveryPositionReady(player,client,core))return false;
        require(Math.abs(player.posX-client.x)<1&&Math.abs(player.posZ-client.z)<1,"Actual client/server passenger stopped following original car");
        return true;
    }
    private static final class RecoveryAck {
        final long startedNanos,deadlineNanos;final int cursor,readyTick;final double pendingX,pendingZ;
        final JsonObject evidence=new JsonObject();final JsonArray waits=new JsonArray();
        RecoveryAck(long now,int cursor,ClientSample ready,JsonObject pending) {
            startedNanos=now;deadlineNanos=now+2_000_000_000L;this.cursor=cursor;readyTick=ready.tick;
            pendingX=pending.get("client_x").getAsDouble();pendingZ=pending.get("client_z").getAsDouble();
            evidence.addProperty("episode_count",1);evidence.addProperty("started_at_nanos",now);evidence.addProperty("deadline_nanos",deadlineNanos);
            evidence.addProperty("pre_recovery_c03_sequence",cursor);evidence.addProperty("pending_client_tick",pending.get("client_tick").getAsInt());
            evidence.addProperty("ready_client_tick",ready.tick);evidence.addProperty("pending_client_x",pendingX);evidence.addProperty("pending_client_z",pendingZ);
            evidence.addProperty("acknowledged",false);evidence.add("wait_samples",waits);
        }
    }
    private void armRecoveryAck(ClientSample ready,JsonObject pending) {
        require(stage==Stage.RIDE&&recoveryAck==null,"Repeated or misplaced geometry recovery ACK episode");
        recoveryAck=new RecoveryAck(System.nanoTime(),c03.get(),ready,pending);
    }
    private static boolean near(double first,double second,double tolerance){return Math.abs(first-second)<tolerance;}
    private boolean recoveryPositionReady(EntityPlayerMP player,ClientSample client,CoreSample core) {
        RecoveryAck episode=recoveryAck;if(episode==null||recoveryAckAccepted)return true;
        JsonObject packet=lastPositionC03;
        long now=System.nanoTime();require(now-episode.startedNanos<2_000_000_000L,"Native recovery C03/server acknowledgement deadline expired");
        if(client.tick<episode.readyTick)return false;
        boolean moved=Math.abs(client.x-episode.pendingX)>=1||Math.abs(client.z-episode.pendingZ)>=1;
        boolean acknowledged=moved&&packet!=null&&packet.get("has_position").getAsBoolean()&&packet.get("sequence").getAsInt()>episode.cursor
                &&packet.get("at_nanos").getAsLong()>=episode.startedNanos
                &&near(player.posX,client.x,1)&&near(player.posZ,client.z,1)&&near(player.boundingBox.minY,client.y,1.5)
                &&near(packet.get("x").getAsDouble(),client.x,1)&&near(packet.get("z").getAsDouble(),client.z,1)&&near(packet.get("y").getAsDouble(),client.y,1.5)
                &&near(packet.get("x").getAsDouble(),player.posX,1)&&near(packet.get("z").getAsDouble(),player.posZ,1)&&near(packet.get("y").getAsDouble(),player.boundingBox.minY,1.5);
        JsonObject sample=client.json();sample.addProperty("at_nanos",now);sample.addProperty("server_world_tick",player.worldObj.getTotalWorldTime());
        sample.add("server_player",playerPosition(player));sample.add("core",core.json());sample.addProperty("server_authorized",PlayerPhysics.authorized(player));
        sample.addProperty("server_gravity_for_0_08",PlayerPhysics.gravity(.08,player));sample.addProperty("server_empty_hand",player.getHeldItem()==null);
        sample.addProperty("native_s08_since_boarding",s08.get()-motionS08);sample.add("positional_c03",packet==null?JsonNull.INSTANCE:packet);
        if(acknowledged){episode.evidence.add("ack_sample",sample);episode.evidence.addProperty("resolved_at_nanos",now);episode.evidence.addProperty("acknowledged",true);recoveryAckAccepted=true;return true;}
        episode.waits.add(sample);return false;
    }
    private void observe(WorldServer world,EntityPlayerMP player,ClientSample client,CoreSample core) {
        clientBoardingAuthorized=PlayerPhysics.authorized(player);JsonObject value=client.json();value.addProperty("server_observed_at_nanos",System.nanoTime());value.addProperty("phase",stage.name());value.addProperty("phase_tick",phaseTicks);value.addProperty("server_world_tick",world.getTotalWorldTime());value.add("server_player",playerPosition(player));
        value.addProperty("server_authorized",clientBoardingAuthorized);value.addProperty("server_gravity_for_0_08",PlayerPhysics.gravity(.08,player));value.addProperty("server_empty_hand",player.getHeldItem()==null);value.addProperty("original_passenger_door_blocked_amount",originalPassengerBlocked(client));value.add("server_riding_view",serverRidingDiagnostics(player.getUniqueID(),vehicleId==0?client.vehicle:vehicleId,world.getTotalWorldTime(),player.dimension));
        value.addProperty("native_s08_count",s08.get());value.addProperty("native_c03_count",c03.get());value.addProperty("riding_inbound_first_frame_count",ridingInbound.get());value.addProperty("riding_rejection_count",ridingRejections.get());if(lastRidingRejection!=null)value.add("last_riding_rejection",lastRidingRejection);if(lastRidingInbound!=null)value.add("last_riding_inbound",lastRidingInbound);if(lastC03!=null)value.add("last_native_c03",lastC03);
        if(core!=null){value.addProperty("core_sample_age_ms",System.currentTimeMillis()-core.at);value.add("core",core.json());}timeline.add(value);
    }

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
                actual.pipeline().addBefore("packet_handler", "mtr_train_probe_observer", new ChannelDuplexHandler() {
                    @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                        observeNativeInbound(message);
                        if (message instanceof C03PacketPlayer) {
                            C03PacketPlayer packet = (C03PacketPlayer) message;
                            JsonObject value = new JsonObject();
                            value.addProperty("sequence", c03.incrementAndGet()); value.addProperty("at_ms", System.currentTimeMillis() - startedAt); value.addProperty("at_nanos",System.nanoTime());
                            value.addProperty("has_position", packet.func_149466_j()); value.addProperty("on_ground", packet.func_149465_i());
                            if (packet.func_149466_j()) {
                                value.addProperty("x", packet.func_149464_c()); value.addProperty("y", packet.func_149467_d()); value.addProperty("z", packet.func_149472_e());
                            }
                            if(packet.func_149466_j())lastPositionC03=value;
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
        ridingLogObserver = new org.apache.logging.log4j.core.appender.AbstractAppender("mtr_train_probe_riding_" + System.identityHashCode(this), null, null) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) {
                if (!"MTRRiding".equals(event.getLoggerName())) return;
                String message = event.getMessage().getFormattedMessage();
                if (!message.startsWith("Rejected riding request:")) return;
                int sequence = ridingRejections.incrementAndGet();
                JsonObject value = new JsonObject(); value.addProperty("sequence", sequence);
                value.addProperty("at_ms", System.currentTimeMillis() - startedAt); value.addProperty("phase", stage.name());
                value.addProperty("reason", message); value.addProperty("thread", event.getThreadName());
                lastRidingRejection = value; if (sequence <= 64) rejectionReasons.add(value);
            }
        };
        // Logger.addAppender resets other mods' private levels in Log4j 2 beta9.
        ridingObserverConfig=ridingLogger.getContext().getConfiguration().getLoggerConfig(ridingLogger.getName());
        ridingLogObserver.start(); ridingObserverConfig.addAppender(ridingLogObserver,null,null);
        ridingLogger.setLevel(org.apache.logging.log4j.Level.DEBUG);
    }

    private void closeRidingLogObserver() {
        if (ridingLogger == null) return;
        try { ridingObserverConfig.removeAppender(ridingLogObserver.getName()); }
        finally { ridingLogObserver.stop(); ridingLogger.setLevel(previousRidingLevel); ridingLogRestored = true; }
    }

    private static Object diagnosticField(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type = owner instanceof Class<?> ? (Class<?>) owner : owner.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner instanceof Class<?> ? null : owner);
    }

    private static JsonObject snapshotPredictionDiagnostics(VehicleExtension vehicle) {
        JsonObject value = new JsonObject(); value.addProperty("sampled_at_nanos", System.nanoTime());
        value.addProperty("sample_thread", Thread.currentThread().getName()); value.addProperty("vehicle_present", vehicle != null);
        if (vehicle != null) try {
            value.addProperty("vehicle_instance_identity", System.identityHashCode(vehicle));
            value.addProperty("has_simulated", (Boolean) diagnosticField(vehicle, "hasSimulated"));
            value.addProperty("snapshot_receive_age_millis", ((java.util.function.LongSupplier) diagnosticField(vehicle, "millisSinceReceived")).getAsLong());
        } catch (ReflectiveOperationException | RuntimeException diagnosticError) { value.addProperty("error", diagnosticError.toString()); }
        return value;
    }

    private static JsonObject originalRidingDiagnostics() {
        JsonObject value = new JsonObject(); value.addProperty("sampled_at_millis", System.currentTimeMillis());
        try {
            value.addProperty("ridingVehicleId", Long.toString(((Number) diagnosticField(VehicleRidingMovement.class, "ridingVehicleId")).longValue()));
            for (String name : new String[]{"sendPositionUpdateTime", "ridingVehicleCarNumber", "ridingVehicleX", "ridingVehicleY", "ridingVehicleZ", "ridingVehicleCooldown", "doorOverrideTicks"})
                value.addProperty(name, (Number) diagnosticField(VehicleRidingMovement.class, name));
        } catch (ReflectiveOperationException | RuntimeException diagnosticError) { value.addProperty("error", diagnosticError.toString()); }
        return value;
    }

    private static JsonObject serverRidingDiagnostics(UUID uuid, long id, long now, int dimension) {
        JsonObject value = new JsonObject(); value.addProperty("current_server_tick", now); value.addProperty("player_dimension", dimension);
        value.addProperty("requested_vehicle_id", Long.toString(id));
        synchronized (RidingPermissions.class) {
            try {
                Object view = ((Map<?, ?>) diagnosticField(RidingPermissions.class, "VIEWS")).get(uuid);
                value.addProperty("view_present", view != null); if (view == null) return value;
                value.addProperty("view_dimension", (Number) diagnosticField(view, "dimension"));
                Object snapshot = ((Map<?, ?>) diagnosticField(view, "vehicles")).get(id);
                value.addProperty("vehicle_snapshot_present", snapshot != null); if (snapshot == null) return value;
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

    private boolean captureDone(String label) {
        if (!label.equals(shotLabel)) {
            require(shotRequest == shotAcknowledged, "Previous actual screenshot has no render acknowledgment");
            renderedFrames = 0; shotLabel = label; shotRequest++;
            return false;
        }
        return shotAcknowledged == shotRequest;
    }

    private EntityPlayerMP actualPlayer(MinecraftServer server) {
        for (Object candidate : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP value = (EntityPlayerMP) candidate;
            if (value.getUniqueID().equals(playerId)) return value;
        }
        return null;
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

    private static Item originalItem(String path, Item expected) {
        Item item = (Item) Item.itemRegistry.getObject("mtr:" + path);
        require(item != null && item == expected, "Registry mtr:" + path + " is not the original item identity");
        return item;
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

    private void reserveNewSave(Minecraft client) throws Exception {
        require(client.mcDataDir.getCanonicalFile().equals(COPIED_CLIENT.getCanonicalFile()), "Refusing to run outside disposable copied client");
        require(client.theWorld == null && client.getIntegratedServer() == null, "Refusing to replace an existing open world");
        runId = UUID.randomUUID().toString(); saveName = "MTR-train-acceptance-" + runId;
        Path saves = client.mcDataDir.toPath().resolve("saves"); Files.createDirectories(saves);
        require(!Files.isSymbolicLink(saves) && saves.toRealPath().getParent().equals(client.mcDataDir.toPath().toRealPath()), "Copied saves directory escaped client");
        Path reserved = Files.createDirectory(saves.resolve(saveName));
        saveDirectory = reserved.toFile();
        Files.write(reserved.resolve("mtr-train-probe-owner.txt"), runId.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        copyVerified = true;
        report.addProperty("run_id", runId); report.addProperty("save_directory", saveDirectory.getCanonicalPath());
        report.addProperty("client_directory", client.mcDataDir.getCanonicalPath());
        report.addProperty("new_uuid_save_reserved", true); report.addProperty("existing_save_loaded", false);
        report.addProperty("scenario", "original train items + Dashboard DTO packets; scheduled A-to-B; natural render rider/exit; owned Anvil + core MessagePack");
    }

    private void assertOwnedServer(MinecraftServer server) throws Exception {
        require(copyVerified && server != null && saveDirectory != null && saveName.equals(server.getFolderName()), "Refusing to mutate/close a foreign server");
        Path marker = saveDirectory.toPath().resolve("mtr-train-probe-owner.txt");
        require(!Files.isSymbolicLink(saveDirectory.toPath()) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            && runId.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)), "Owned save marker changed");
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
        require((tag != null) == block.hasTileEntity(metadata), "Native train TileEntity presence differs from original block contract");
        if (definition.requiresTileEntity()) require(tag != null && tag.hasKey("mtr_block_state", 10), "Extended train state is missing");
        return definition.read(metadata, tag == null ? new NBTTagCompound() : tag).getSerializedProperties();
    }

    private static byte[] savedArray(NBTTagCompound section, String name, int length, boolean optional) {
        if (!section.hasKey(name)) { require(optional, "Missing saved Anvil array " + name); return new byte[0]; }
        require(section.hasKey(name, 7), "Saved Anvil field is not byte array: " + name);
        byte[] value = section.getByteArray(name); require(value.length == length, "Wrong Anvil array length " + name); return value;
    }

    private static int nibble(byte[] value, int index) { return (value[index >> 1] >> ((index & 1) * 4)) & 15; }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private synchronized void fail(Throwable problem) { if (failure == null) failure = problem; }
    private static BlockState state(net.minecraft.world.World world,int x,int y,int z){return new org.mtr.mapping.holder.World(world).getBlockState(new BlockPos(x,y,z));}
    private static JsonObject full(SerializedDataBase value){JsonObject result=new JsonObject();value.serializeFullData(new JsonWriter(result));return result;}
    private static boolean fixtureReady(net.minecraft.world.World world) {
        try {
            for(int z:NODES)if(world.getBlock(8,4,z)!=org.mtr.mod.Blocks.RAIL_NODE.get().data||!"true".equals(state(world,8,4,z).data.getSerializedProperties().get("is_connected")))return false;
            for(int start:new int[]{24,64})for(int z=start;z<=start+24;z++)for(int x=5;x<=7;x++)if(world.getBlock(x,4,z)!=org.mtr.mod.Blocks.PLATFORM.get().data)return false;
            return true;
        }catch(IllegalArgumentException|IllegalStateException waitingForNativeState){return false;}
    }
    private static final class ClientSample {
        final int tick;final long vehicle;final VehicleExtension sourceVehicle;final double x,y,z,motionY,centerX,centerY,centerZ,yaw,floorY,door;
        final boolean onGround,noClip,flying,riding,moving,vehiclePresent,resourceReady,vehicleGeometryCacheReady,shouldRender,fixtureReady,dashboardSavedRailsReady,emptyHand,carConfigured;
        final int vehicleGeometryFloorCount;
        final Vector pickup;final JsonObject nativeInput,originalRiding,vehicleJson,interest,snapshotPrediction;final Box doorway;
        ClientSample(Minecraft client,int tick) {
            this.tick=tick;x=client.thePlayer.posX;y=client.thePlayer.boundingBox.minY;z=client.thePlayer.posZ;motionY=client.thePlayer.motionY;
            onGround=client.thePlayer.onGround;noClip=client.thePlayer.noClip;flying=client.thePlayer.capabilities.isFlying;emptyHand=client.thePlayer.getHeldItem()==null;
            fixtureReady=fixtureReady(client.theWorld);dashboardSavedRailsReady=MinecraftClientData.getDashboardInstance().platforms.size()==2&&MinecraftClientData.getDashboardInstance().sidings.size()==1;
            nativeInput=new JsonObject();nativeInput.addProperty("entity_move_forward",client.thePlayer.moveForward);nativeInput.addProperty("entity_move_strafing",client.thePlayer.moveStrafing);
            if(client.thePlayer.movementInput!=null){nativeInput.addProperty("movement_input_move_forward",client.thePlayer.movementInput.moveForward);nativeInput.addProperty("movement_input_move_strafe",client.thePlayer.movementInput.moveStrafe);}
            nativeInput.addProperty("rotation_yaw",client.thePlayer.rotationYaw);nativeInput.addProperty("native_focus",client.inGameHasFocus);nativeInput.addProperty("native_paused",client.isGamePaused());
            originalRiding=originalRidingDiagnostics();interest=new JsonObject();BlockPos camera=org.mtr.mapping.holder.MinecraftClient.getInstance().getGameRendererMapped().getCamera().getBlockPos();
            interest.addProperty("render_distance_chunks",MinecraftClientHelper.getRenderDistance());interest.addProperty("original_data_request_radius",MinecraftClientHelper.getRenderDistance()*16L);interest.addProperty("camera_x",camera.getX());interest.addProperty("camera_y",camera.getY());interest.addProperty("camera_z",camera.getZ());
            VehicleExtension found=null;for(VehicleExtension value:MinecraftClientData.getInstance().vehicles){require(found==null,"Owned client has multiple unexpected trains");found=value;}
            sourceVehicle=found;vehiclePresent=found!=null;snapshotPrediction=snapshotPredictionDiagnostics(found);
            if(found==null){vehicle=0;centerX=centerY=centerZ=yaw=floorY=door=0;riding=moving=resourceReady=vehicleGeometryCacheReady=shouldRender=carConfigured=false;vehicleGeometryFloorCount=-1;pickup=null;doorway=null;vehicleJson=new JsonObject();}
            else {
                final VehicleExtension actual=found;carConfigured=correctCar(actual);vehicle=actual.getId();riding=VehicleRidingMovement.isRiding(vehicle);moving=actual.isMoving();door=actual.persistentVehicleData.getDoorValue();vehicleJson=Utilities.getJsonObjectFromData(actual);
                shouldRender=actual.persistentVehicleData.rayTracing.length>0&&actual.persistentVehicleData.rayTracing[0];
                final PositionAndRotation[] pose={null};
                // Unlike getSmoothedVehicleCarsAndPositions, this original getter never temporarily assigns railProgress.
                actual.getVehicleCarsAndPositions().stream().findFirst().ifPresent(car->{ObjectArrayList<PositionAndRotation> bogies=new ObjectArrayList<>();car.right().forEach(pair->bogies.add(new PositionAndRotation(pair.left(),pair.right(),true)));pose[0]=new PositionAndRotation(bogies,car.left(),false);});
                final VehicleResourceCache[] cache={null};CustomResourceLoader.getVehicleById(TransportMode.TRAIN,CAR,details->{if(CAR.equals(details.left().getId()))cache[0]=details.left().getCachedVehicleResource(0,1,false);});
                vehicleGeometryCacheReady=cache[0]!=null;vehicleGeometryFloorCount=cache[0]==null?-1:cache[0].floors.size();
                Box west=null;Vector outside=null;double westX=Double.POSITIVE_INFINITY;
                if(pose[0]!=null&&cache[0]!=null&&!cache[0].floors.isEmpty())for(Box candidate:cache[0].doorways){Vector point=pickupPoint(pose[0].position,pose[0].yaw,candidate);if(point.x<westX&&RenderVehicleHelper.canOpenDoors(candidate,pose[0],door*2)){westX=point.x;west=candidate;outside=point;}}
                doorway=west;pickup=outside;resourceReady=carConfigured&&pose[0]!=null&&cache[0]!=null&&west!=null;
                centerX=pose[0]==null?0:pose[0].position.x;centerY=pose[0]==null?0:pose[0].position.y;centerZ=pose[0]==null?0:pose[0].position.z;yaw=pose[0]==null?0:pose[0].yaw;
                floorY=west==null?centerY+1:centerY+west.getMaxYMapped();
            }
        }
        JsonObject geometry() {
            JsonObject result=new JsonObject();result.addProperty("source","original_getVehicleCarsAndPositions_unsmoothed");result.addProperty("center_x",centerX);result.addProperty("center_y",centerY);result.addProperty("center_z",centerZ);result.addProperty("yaw",yaw);result.addProperty("floor_y",floorY);
            if(doorway!=null){JsonObject box=new JsonObject();box.addProperty("min_x",doorway.getMinXMapped());box.addProperty("max_x",doorway.getMaxXMapped());box.addProperty("min_z",doorway.getMinZMapped());box.addProperty("max_z",doorway.getMaxZMapped());box.addProperty("y",doorway.getMaxYMapped());box.addProperty("min_y",doorway.getMinYMapped());box.addProperty("max_y",doorway.getMaxYMapped());result.add("original_local_doorway",box);}
            if(pickup!=null){JsonObject value=new JsonObject();value.addProperty("x",pickup.x);value.addProperty("feet_y",pickup.y);value.addProperty("z",pickup.z);result.add("derived_external_setup_pose",value);}return result;
        }
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("client_tick",tick);result.addProperty("client_x",x);result.addProperty("client_feet_y",y);result.addProperty("client_z",z);result.addProperty("client_motion_y",motionY);result.addProperty("client_on_ground",onGround);result.addProperty("client_no_clip",noClip);result.addProperty("client_flying",flying);result.addProperty("client_riding",riding);result.addProperty("empty_hand",emptyHand);result.addProperty("vehicle_id",Long.toString(vehicle));result.addProperty("vehicle_moving",moving);result.addProperty("actual_eidan_9000_mini_bve_car_configured",carConfigured);result.addProperty("door",door);result.addProperty("original_renderer_should_render",shouldRender);result.addProperty("actual_resource_ready",resourceReady);result.addProperty("vehicle_geometry_cache_ready",vehicleGeometryCacheReady);result.addProperty("vehicle_geometry_floor_count",vehicleGeometryFloorCount);result.add("car_geometry",geometry());result.add("native_input",nativeInput);result.add("original_riding_state",originalRiding);result.add("actual_client_interest",interest);result.add("original_client_vehicle",vehicleJson);result.add("client_snapshot_prediction",snapshotPrediction);return result;}
    }
    private final class CoreSample {
        final long at=System.currentTimeMillis(),siding,platformA,platformB,vehicle,thisPlatform,nextPlatform,stoppedPlatform,generationMillis;
        final boolean railsComplete,fixtureRailSpeedConfigured,areasConfigured,platformsConfigured,routeConfigured,sidingConfigured,depotConfigured,generated,generationFailed,vehiclePresent,moving,onRoute,closeToDepot,rider,carConfigured,stationBGateValid;
        final int riderCount;final boolean passengerDoorOverride,passengerControls;final double dwellMillis,doorTarget;final long doorCooldown;final JsonObject depotJson,sidingJson,vehicleJson;final Map<String,JsonObject> fullData=new LinkedHashMap<>();
        CoreSample(Simulator data)throws Exception {
            railsComplete=completeRails(data.rails);fixtureRailSpeedConfigured=completeRails(data.rails,true);Platform a=null,b=null;Siding s=null;Station sa=null,sb=null;Depot d=null;Route r=null;
            for(Platform value:data.platforms){if(value.containsPos(new Position(8,4,24))&&value.containsPos(new Position(8,4,48)))a=value;if(value.containsPos(new Position(8,4,64))&&value.containsPos(new Position(8,4,88)))b=value;}
            for(Siding value:data.sidings)if(value.containsPos(new Position(8,4,0))&&value.containsPos(new Position(8,4,16)))s=value;
            for(Station value:data.stations){if(value.getId()==stationAId)sa=value;if(value.getId()==stationBId)sb=value;}
            int gateAreas=0;for(Station value:data.stations)if(value.inArea(new Position(4,5,80)))gateAreas++;
            stationBGateValid=sb!=null&&sb.inArea(new Position(4,5,80))&&gateAreas==1&&sb.getZone1()==0&&sb.getZone2()==0&&sb.getZone3()==0;
            for(Depot value:data.depots)if(value.getId()==depotId)d=value;for(Route value:data.routes)if(value.getId()==routeId)r=value;
            siding=s==null?0:s.getId();platformA=a==null?0:a.getId();platformB=b==null?0:b.getId();
            areasConfigured=fixtureRailSpeedConfigured&&sa!=null&&sb!=null&&d!=null&&a!=null&&b!=null&&s!=null&&a.area==sa&&b.area==sb&&s.area==d&&sa.getName().equals(A_NAME)&&sb.getName().equals(B_NAME)&&d.getName().equals(D_NAME)&&data.stations.size()==2&&data.depots.size()==1;
            platformsConfigured=areasConfigured&&a.getDwellTime()==20000&&b.getDwellTime()==20000;
            routeConfigured=r!=null&&r.getTransportMode()==TransportMode.TRAIN&&r.getRoutePlatforms().size()==2&&r.getRoutePlatforms().get(0).getPlatform()!=null&&r.getRoutePlatforms().get(1).getPlatform()!=null&&r.getRoutePlatforms().get(0).getPlatform().getId()==platformAId&&r.getRoutePlatforms().get(1).getPlatform().getId()==platformBId&&r.getCircularState()==Route.CircularState.NONE&&data.routes.size()==1;
            sidingConfigured=s!=null&&s.getMaxVehicles()==1&&!s.getIsManual()&&s.getVehicleCars().size()==1&&CAR.equals(s.getVehicleCars().get(0).getVehicleId())&&Math.abs(Siding.getTotalVehicleLength(s.getVehicleCars())-10)<1E-6;
            depotConfigured=d!=null&&d.getRouteIds().size()==1&&d.getRouteIds().getLong(0)==routeId&&!d.getRepeatInfinitely()&&d.getUseRealTime()&&d.getRealTimeDepartures().size()==1&&d.getRealTimeDepartures().getLong(0)==plannedDepartureMillis;
            depotJson=d==null?new JsonObject():full(d);sidingJson=s==null?new JsonObject():full(s);
            generationMillis=d==null?0:d.getLastGeneratedMillis();
            generated=d!=null&&s!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()==Depot.GeneratedStatus.SUCCESSFUL&&!d.getPath().isEmpty()&&!((Collection<?>)diagnosticField(s,"pathSidingToMainRoute")).isEmpty()&&!((Collection<?>)diagnosticField(s,"pathMainRouteToSiding")).isEmpty();
            generationFailed=d!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()!=Depot.GeneratedStatus.SUCCESSFUL;
            Vehicle found=null;if(s!=null)for(Object value:(Iterable<?>)diagnosticField(s,"vehicles")){require(found==null,"Owned core has unexpected multiple vehicles");found=(Vehicle)value;}
            vehiclePresent=found!=null;
            if(found==null){vehicle=thisPlatform=nextPlatform=stoppedPlatform=0;moving=onRoute=closeToDepot=rider=carConfigured=false;riderCount=0;passengerDoorOverride=passengerControls=false;dwellMillis=doorTarget=0;doorCooldown=0;vehicleJson=new JsonObject();}
            else {carConfigured=correctCar(found);vehicle=found.getId();moving=found.isMoving();onRoute=found.getIsOnRoute();closeToDepot=found.closeToDepot();thisPlatform=found.vehicleExtraData.getThisPlatformId();nextPlatform=found.vehicleExtraData.getNextPlatformId();vehicleJson=Utilities.getJsonObjectFromData(found);stoppedPlatform=stoppedPlatform(found.vehicleExtraData.immutablePath,vehicleJson.get("nextStoppingIndexAto").getAsInt(),vehicleJson.get("railProgress").getAsDouble());final int[] count={0};final boolean[] actual={false},hold={false},controls={false};found.vehicleExtraData.iterateRidingEntities(row->{count[0]++;if(row.uuid.equals(playerId)&&row.getRidingCar()==0){actual[0]=true;hold[0]=row.getDoorOverride();controls[0]=row.isDriver()||row.manualAccelerate()||row.manualBrake()||row.manualToggleDoors()||row.manualToggleAto();}});riderCount=count[0];rider=actual[0]&&riderCount==1;passengerDoorOverride=hold[0];passengerControls=controls[0];dwellMillis=vehicleJson.get("elapsedDwellTime").getAsDouble();doorTarget=found.vehicleExtraData.getDoorMultiplier();doorCooldown=((Number)diagnosticField(found,"doorCooldown")).longValue();}
            for(Station value:data.stations)fullData.put("stations/"+value.getHexId(),full(value));for(Platform value:data.platforms)fullData.put("platforms/"+value.getHexId(),full(value));for(Siding value:data.sidings)fullData.put("sidings/"+value.getHexId(),full(value));for(Route value:data.routes)fullData.put("routes/"+value.getHexId(),full(value));for(Depot value:data.depots)fullData.put("depots/"+value.getHexId(),full(value));for(Rail value:data.rails)fullData.put("rails/"+value.getHexId(),full(value));
        }
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("rails_complete",railsComplete);result.addProperty("fixture_rail_speed_configured",fixtureRailSpeedConfigured);result.addProperty("areas_configured",areasConfigured);result.addProperty("platforms_configured",platformsConfigured);result.addProperty("route_configured",routeConfigured);result.addProperty("siding_configured",sidingConfigured);result.addProperty("depot_configured",depotConfigured);result.addProperty("generated",generated);result.addProperty("vehicle_id",Long.toString(vehicle));result.addProperty("vehicle_moving",moving);result.addProperty("actual_eidan_9000_mini_bve_car_configured",carConfigured);result.addProperty("on_route",onRoute);result.addProperty("close_to_depot",closeToDepot);result.addProperty("rider_count",riderCount);result.addProperty("actual_passenger",rider);result.addProperty("passenger_door_override",passengerDoorOverride);result.addProperty("passenger_manual_controls",passengerControls);result.addProperty("elapsed_dwell_millis",dwellMillis);result.addProperty("door_target",doorTarget);result.addProperty("door_cooldown",doorCooldown);result.addProperty("this_platform",Long.toString(thisPlatform));result.addProperty("next_platform",Long.toString(nextPlatform));result.addProperty("stopped_platform",Long.toString(stoppedPlatform));result.add("vehicle",vehicleJson);return result;}
    }
    private static boolean correctCar(Vehicle value) {
        if(value.vehicleExtraData.immutableVehicleCars.size()!=1)return false;VehicleCar car=value.vehicleExtraData.immutableVehicleCars.get(0);JsonObject serialized=full(car);
        return CAR.equals(car.getVehicleId())&&Math.abs(car.getLength()-10)<1E-6&&Math.abs(car.getWidth()-2)<1E-6&&Math.abs(serialized.get("couplingPadding1").getAsDouble()-1)<1E-6&&Math.abs(serialized.get("couplingPadding2").getAsDouble()-1)<1E-6&&Math.abs(car.getBogie1Position())<1E-6&&Math.abs(car.getBogie2Position())<1E-6;
    }
    /** Fixed native signal fixture and active renderer bake checks; no replacement decoder. */
    private final class SignalModels {
        private final String[] names={"signal_light_1","signal_light_2","signal_light_3","signal_light_4","signal_light_3_aspect_1","signal_light_3_aspect_2","signal_light_4_aspect_1","signal_light_4_aspect_2","signal_semaphore_1","signal_semaphore_2"};
        final List<BlockPos> savedPositions=new ArrayList<>();
        private final List<Block> blocks=new ArrayList<>();
        private final List<Map<String,String>> properties=new ArrayList<>();
        final JsonArray gates=new JsonArray();
        private volatile boolean placed,before,after;
        private Object beforeContext;
        private Thread clientThread;
        void build(WorldServer world,EntityPlayerMP player) {
            require(savedPositions.isEmpty(),"Signal fixture already built");
            Block[] original={org.mtr.mod.Blocks.SIGNAL_LIGHT_2_ASPECT_1.get().data,org.mtr.mod.Blocks.SIGNAL_LIGHT_2_ASPECT_2.get().data,
                org.mtr.mod.Blocks.SIGNAL_LIGHT_2_ASPECT_3.get().data,org.mtr.mod.Blocks.SIGNAL_LIGHT_2_ASPECT_4.get().data,
                org.mtr.mod.Blocks.SIGNAL_LIGHT_3_ASPECT_1.get().data,org.mtr.mod.Blocks.SIGNAL_LIGHT_3_ASPECT_2.get().data,
                org.mtr.mod.Blocks.SIGNAL_LIGHT_4_ASPECT_1.get().data,org.mtr.mod.Blocks.SIGNAL_LIGHT_4_ASPECT_2.get().data,
                org.mtr.mod.Blocks.SIGNAL_SEMAPHORE_1.get().data,org.mtr.mod.Blocks.SIGNAL_SEMAPHORE_2.get().data};
            float oldYaw=player.rotationYaw;
            try { for(int i=0;i<names.length;i++) {
                int x=12,z=26+2*i;float yaw=i%2==0?90:67.5F;
                require(world.getBlock(x,3,z).getMaterial().blocksMovement()&&world.isAirBlock(x,4,z)&&world.isAirBlock(x,5,z),"Signal ground/footprint is not clear");
                require(("mtr:"+names[i]).equals(Block.blockRegistry.getNameForObject(original[i])),"Original signal registry identity differs");
                player.rotationYaw=yaw;ItemStack stack=new ItemStack(originalItem(names[i],Item.getItemFromBlock(original[i])));
                use(world,player,stack,x,3,z,1,.5F);require(stack.stackSize==0&&world.getBlock(x,4,z)==original[i],"Original signal item placement failed");
                Map<String,String> value=new LinkedHashMap<>(state(world,x,4,z).data.getSerializedProperties());int quadrant=Angle.getQuadrant(yaw,true);
                require(value.get("facing").equals(org.mtr.mapping.holder.Direction.fromHorizontal(quadrant/4).name().toLowerCase(Locale.ROOT))
                    &&value.get("is_45").equals(String.valueOf(quadrant%4>=2))&&value.get("is_22_5").equals(String.valueOf(quadrant%2==1)),"Native signal yaw/state differs");
                require(world.getTileEntity(x,4,z) instanceof BlockStateTileEntity,"Original signal tile missing");
                savedPositions.add(new BlockPos(x,4,z));blocks.add(original[i]);properties.add(value);
            }} finally {player.rotationYaw=oldYaw;}
            require(savedPositions.size()==10,"Incomplete fixed signal fixture");placed=true;
        }
        void observe(Minecraft client,boolean refreshed)throws Exception {
            if(!placed||(refreshed?after:before))return;
            if(clientThread==null)clientThread=Thread.currentThread();require(clientThread==Thread.currentThread(),"Signal bake escaped client thread");
            for(int i=0;i<10;i++){BlockPos pos=savedPositions.get(i);if(client.theWorld.getBlock(pos.getX(),pos.getY(),pos.getZ())!=blocks.get(i)||!state(client.theWorld,pos.getX(),pos.getY(),pos.getZ()).data.getSerializedProperties().equals(properties.get(i)))return;}
            Object context=field(org.mtr.mapping.registry.LegacyBlockRenderers.class,null,"active");if(context==null||refreshed&&context==beforeContext)return;
            Class<?> type=context.getClass();net.minecraft.client.renderer.texture.TextureMap atlas=(net.minecraft.client.renderer.texture.TextureMap)field(type,context,"atlas");
            require(atlas==client.getTextureMapBlocks(),"Signal bake did not use the actual block atlas");
            Map<?,?> icons=(Map<?,?>)field(type,context,"icons"),baked=(Map<?,?>)field(type,context,"baked");Set<?> failures=(Set<?>)field(type,context,"failures");Object missing=field(type,context,"missing");
            java.lang.reflect.Method facesMethod=type.getDeclaredMethod("faces",BlockState.class),iconMethod=type.getDeclaredMethod("icon",org.mtr.mapping.holder.Identifier.class);facesMethod.setAccessible(true);iconMethod.setAccessible(true);
            net.minecraft.util.IIcon missingIcon=atlas.getAtlasSprite("missingno");JsonArray entries=new JsonArray();
            for(int i=0;i<10;i++) {
                BlockPos pos=savedPositions.get(i);BlockState nativeState=state(client.theWorld,pos.getX(),pos.getY(),pos.getZ());
                @SuppressWarnings("unchecked") List<LegacyBlockModels.Quad> faces=(List<LegacyBlockModels.Quad>)facesMethod.invoke(context,nativeState);
                require(faces!=missing&&!faces.isEmpty()&&baked.get(nativeState)==faces&&!failures.contains(blocks.get(i)),"Actual signal bake failed: "+names[i]);
                Set<String> textures=new LinkedHashSet<>();
                for(LegacyBlockModels.Quad face:faces) {
                    net.minecraft.util.IIcon icon=(net.minecraft.util.IIcon)iconMethod.invoke(context,face.texture);
                    require(icon!=null&&icon!=missingIcon&&icon==icons.get(face.texture)&&icon==atlas.getTextureExtry(face.texture.toString())&&!icon.getIconName().contains("missingno")&&icon.getIconWidth()>0&&icon.getIconHeight()>0,"Actual signal atlas icon missing: "+face.texture);
                    for(float value:face.vertices)require(Float.isFinite(value),"Non-finite signal vertex");textures.add(face.texture.toString());
                }
                JsonObject entry=new JsonObject();entry.addProperty("block","mtr:"+names[i]);entry.addProperty("client_native_id",Block.getIdFromBlock(blocks.get(i)));entry.addProperty("x",pos.getX());entry.addProperty("y",pos.getY());entry.addProperty("z",pos.getZ());entry.addProperty("quad_count",faces.size());JsonObject props=new JsonObject();properties.get(i).forEach(props::addProperty);entry.add("client_state",props);JsonArray textureIds=new JsonArray();for(String texture:textures)textureIds.add(new JsonPrimitive(texture));entry.add("actual_atlas_textures",textureIds);entries.add(entry);
            }
            JsonObject result=new JsonObject();result.addProperty("phase",refreshed?"after_resource_refresh":"before_dashboard");result.addProperty("client_tick",clientTicks);result.addProperty("active_context_identity",System.identityHashCode(context));result.addProperty("atlas_identity",System.identityHashCode(atlas));result.addProperty("client_thread",Thread.currentThread().getName());result.addProperty("selected_signal_failures",0);result.addProperty("selected_missing_icons",0);result.add("signals",entries);gates.add(result);
            if(refreshed)after=true;else{beforeContext=context;before=true;}
        }
        boolean contains(int x,int y,int z){for(BlockPos pos:savedPositions)if(pos.getX()==x&&pos.getY()==y&&pos.getZ()==z)return true;return false;}
        private Object field(Class<?> type,Object owner,String name)throws Exception{Field value=type.getDeclaredField(name);value.setAccessible(true);return value.get(owner);}
    }
    private static double rideFrameRate(JsonArray frames) {
        require(frames.size()>=2,"Missing actual RIDE render timestamps");long first=frames.get(0).getAsLong(),previous=first;
        for(int i=1;i<frames.size();i++){long value=frames.get(i).getAsLong();require(value>previous,"Non-increasing actual RIDE render timestamps");previous=value;}
        return (frames.size()-1)*1000000000D/(previous-first);
    }

    /** Bounded native station controls after the unchanged train/audio acceptance. */
    private final class StationObjects {
        private static final String MESSAGE="Платформа B · Проверка";
        final List<BlockPos> savedPositions=new ArrayList<>();
        final JsonObject result=new JsonObject();
        final JsonArray steps=new JsonArray(),gateStates=new JsonArray();
        volatile int step,clientAck=-1;
        volatile boolean clientUnlocked,clientLocked,clientOpen,clientClosed,clientPids,clientGateOpen,clientArrived,serverApproachReady;
        volatile String pidsScreenClass,barrierFeedback="";
        int clickStep=-1,stepTicks,nativeClicks,pidsScreens;
        Block powerBefore;int powerMetadata;String playerName,lastGate="";
        boolean complete;

        void build(WorldServer world,EntityPlayerMP player) {
            require(savedPositions.isEmpty(),"Station fixture was already built");
            player.rotationYaw=-90;
            for(int z=70;z<=82;z++)for(int x=2;x<=4;x++) {
                for(int y=5;y<=9;y++)require(world.isAirBlock(x,y,z),"Annex overlaps an existing block");
                ItemStack floor=new ItemStack(originalItem("platform",Item.getItemFromBlock(org.mtr.mod.Blocks.PLATFORM.get().data)));
                use(world,player,floor,x,3,z,1,.5F);require(floor.stackSize==0,"Annex platform item was not consumed");savedPositions.add(new BlockPos(x,4,z));
            }
            player.rotationYaw=90;
            ItemStack gate=new ItemStack(originalItem("ticket_barrier_entrance_1",Item.getItemFromBlock(org.mtr.mod.Blocks.TICKET_BARRIER_ENTRANCE_1.get().data)));
            use(world,player,gate,4,4,80,1,.5F);require(gate.stackSize==0,"Barrier item was not consumed");savedPositions.add(new BlockPos(4,5,80));
            ItemStack apg=new ItemStack(originalItem("apg_door",org.mtr.mod.Items.APG_DOOR.get().data));
            use(world,player,apg,3,4,72,1,.5F);require(apg.stackSize==0,"APG item was not consumed");
            for(int z:new int[]{72,71})for(int y=5;y<=6;y++) {
                BlockPos pos=new BlockPos(3,y,z);savedPositions.add(pos);BlockState state=state(world,3,y,z);
                Map<String,String> properties=state.data.getSerializedProperties();
                require(world.getBlock(3,y,z)==org.mtr.mod.Blocks.APG_DOOR.get().data&&"west".equals(properties.get("facing"))
                    &&(y==5?"lower":"upper").equals(properties.get("half"))&&(z==72?"left":"right").equals(properties.get("side"))
                    &&"true".equals(properties.get("unlocked"))&&world.getTileEntity(3,y,z) instanceof org.mtr.mod.block.BlockAPGDoor.BlockEntity,"Original APG footprint differs");
            }
            for(int y=5;y<=6;y++){require(world.setBlock(3,y,77,net.minecraft.init.Blocks.stone),"PIDS support placement rejected");savedPositions.add(new BlockPos(3,y,77));}
            player.rotationYaw=0;
            ItemStack pids=new ItemStack(originalItem("pids_1",Item.getItemFromBlock(org.mtr.mod.Blocks.PIDS_1.get().data)));
            use(world,player,pids,3,6,77,1,.5F);require(pids.stackSize==0,"PIDS item was not consumed");
            for(int z:new int[]{77,76}){savedPositions.add(new BlockPos(3,7,z));require(world.getBlock(3,7,z)==org.mtr.mod.Blocks.PIDS_1.get().data
                &&(z==77?"north":"south").equals(state(world,3,7,z).data.getSerializedProperties().get("facing"))
                &&world.getTileEntity(3,7,z) instanceof org.mtr.mod.block.BlockPIDSHorizontal1.BlockEntity,"Original PIDS pair differs");}
            powerBefore=world.getBlock(3,3,72);powerMetadata=world.getBlockMetadata(3,3,72);require(world.getTileEntity(3,3,72)==null,"Power fixture would replace a native tile");savedPositions.add(new BlockPos(3,3,72));
            require(savedPositions.size()==49&&"closed".equals(gate(world)),"Station fixture coordinate count/state differs");
            player.rotationYaw=0;player.inventory.mainInventory[0]=null;player.inventory.currentItem=0;
            result.addProperty("original_item_fixture",true);result.addProperty("extra_saved_blocks",49);result.add("steps",steps);result.add("gate_states",gateStates);
        }
        boolean keepScreen(net.minecraft.client.gui.GuiScreen screen){return (step==5||step==6)&&screen instanceof org.mtr.mod.screen.PIDSConfigScreen;}
        void client(Minecraft client)throws Exception {
            clientUnlocked=apgFlags(client.theWorld,"true");clientLocked=apgFlags(client.theWorld,"false");
            org.mtr.mod.block.BlockPSDAPGDoorBase.BlockEntityBase door=door(client.theWorld);
            double open=door==null?-1:door.getDoorValue();clientOpen=open>.95;
            clientClosed=open==0&&client.theWorld.getBlock(3,5,72).getCollisionBoundingBoxFromPool(client.theWorld,3,5,72)!=null;
            clientPids=pidsMatches(client.theWorld);clientGateOpen="open_concessionary".equals(gate(client.theWorld));
            if(step==1){clientArrived=walk(client,4.8,74.5,false);if(clientArrived){aim(client,3.5,5.8,72.5);if(serverApproachReady&&clientUnlocked&&clientOpen&&client.theWorld.getBlock(3,3,72)==net.minecraft.init.Blocks.redstone_block&&click(client,1,3,5,72))clientAck=1;}}
            else if(step==2){aim(client,3.5,5.8,72.5);if(clientLocked&&clientClosed&&click(client,2,3,5,72))clientAck=2;}
            else if(step==3){aim(client,3.5,5.8,72.5);}
            else if(step==4){aim(client,3.5,5.8,72.5);if(click(client,4,3,5,72))clientAck=4;}
            else if(step==5||step==6) {
                aim(client,3.5,7.4,77.5);
                if(client.currentScreen instanceof org.mtr.mod.screen.PIDSConfigScreen) {
                    org.mtr.mod.screen.PIDSConfigScreen screen=(org.mtr.mod.screen.PIDSConfigScreen)client.currentScreen;
                    require(new BlockPos(3,7,77).equals(diagnosticField(screen,"blockPos"))&&((Number)diagnosticField(screen,"maxArrivals")).intValue()==1,"Original PIDS screen owner/count differs");
                    org.mtr.mapping.mapper.TextFieldWidgetExtension[] messages=(org.mtr.mapping.mapper.TextFieldWidgetExtension[])diagnosticField(screen,"textFieldMessages");
                    org.mtr.mapping.mapper.CheckboxWidgetExtension[] hide=(org.mtr.mapping.mapper.CheckboxWidgetExtension[])diagnosticField(screen,"buttonsHideArrival");
                    org.mtr.mapping.mapper.CheckboxWidgetExtension all=(org.mtr.mapping.mapper.CheckboxWidgetExtension)diagnosticField(screen,"selectAllCheckbox");
                    org.mtr.mapping.mapper.TextFieldWidgetExtension page=(org.mtr.mapping.mapper.TextFieldWidgetExtension)diagnosticField(screen,"displayPageInput");
                    org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet ids=(org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet)diagnosticField(screen,"filterPlatformIds");
                    if(step==5){messages[0].setText2(MESSAGE);hide[0].setChecked(true);all.setChecked(false);page.setText2("1");ids.clear();ids.add(platformBId);}
                    else require(MESSAGE.equals(messages[0].getText2())&&hide[0].isChecked2()&&!all.isChecked2()&&"1".equals(page.getText2())&&ids.size()==1&&ids.contains(platformBId),"Native PIDS reopened controls did not read server config");
                    pidsScreenClass=screen.getClass().getName();pidsScreens++;screen.onClose2();clientAck=step;
                } else click(client,step,3,7,77);
            } else if(step==7||step==11){aim(client,3.5,7.4,77.5);}
            else if(step==8)clientArrived=walk(client,5.6,80.5,true);
            else if(step==9){walk(client,4.92,80.5,true);barrierFeedback=cpw.mods.fml.relauncher.ReflectionHelper.getPrivateValue(net.minecraft.client.gui.GuiIngame.class,client.ingameGUI,"recordPlaying","field_73838_g");}
            else if(step==10)clientArrived=walk(client,3.7,80.5,true);
        }
        boolean server(WorldServer world,EntityPlayerMP player,ClientSample client,CoreSample core)throws Exception {
            require(!client.riding&&!core.rider&&!PlayerPhysics.authorized(player)&&!player.noClip&&!client.noClip&&!client.flying&&!player.capabilities.isFlying
                &&core.vehiclePresent&&core.vehicle==vehicleId&&core.closeToDepot&&!core.onRoute&&!core.moving,"Station phase changed parked train/rider state");
            require(core.stationBGateValid,"Station B zones/unique gate area differ");++stepTicks;result.addProperty("current_step",step);result.addProperty("step_ticks",stepTicks);
            if(step==0){playerName=player.getGameProfile().getName();ledger(world,playerName,true);equip(player,new ItemStack(originalItem("brush",org.mtr.mod.Items.BRUSH.get().data)));require(world.setBlock(3,3,72,net.minecraft.init.Blocks.redstone_block),"APG redstone fixture rejected");next(1);}
            else if(step==1){serverApproachReady=Math.abs(player.posX-4.8)<.2&&Math.abs(player.posZ-74.5)<.2&&Math.abs(client.x-4.8)<.2&&Math.abs(client.z-74.5)<.2&&player.onGround&&client.onGround;if(clientAck==1&&clientLocked&&apgFlags(world,"false")){result.addProperty("native_apg_initial_lock_callback",true);next(2);}}
            else if(step==2&&clientAck==2&&clientUnlocked&&apgFlags(world,"true")&&clientOpen&&captureDone("station_apg_open")){result.addProperty("native_apg_unlock_callback",true);require(world.setBlock(3,3,72,powerBefore,powerMetadata,3),"APG power restoration rejected");next(3);}
            else if(step==3&&clientClosed&&captureDone("station_apg_closed"))next(4);
            else if(step==4&&clientAck==4&&clientLocked&&apgFlags(world,"false")){result.addProperty("native_apg_redstone_open_close_and_relock",true);next(5);}
            else if(step==5&&clientAck==5&&clientPids&&pidsMatches(world)){result.addProperty("original_pids_packet_server_ack",true);next(6);}
            else if(step==6&&clientAck==6&&clientPids&&pidsMatches(world)){equip(player,null);result.addProperty("pids_screen_class",pidsScreenClass);result.addProperty("pids_screen_open_count",pidsScreens);result.addProperty("pids_mouse_layout_accepted",false);next(7);}
            else if(step==7&&client.emptyHand&&player.getHeldItem()==null&&captureDone("station_pids_B")){result.addProperty("pids_custom_message",MESSAGE);result.addProperty("pids_platform_id",Long.toString(platformBId));next(8);}
            else if(step==8&&clientArrived&&Math.abs(client.x-5.6)<.15&&Math.abs(client.z-80.5)<.15&&Math.abs(player.posX-5.6)<.2&&Math.abs(player.posZ-80.5)<.2&&player.onGround&&client.onGround){require(Arrays.equals(new int[]{10,0,0,0},ledger(world,playerName,false)),"Gate precondition changed before approach");next(9);}
            else if(step==9) {
                recordGate(world);
                if("open_concessionary".equals(gate(world))&&clientGateOpen&&Arrays.equals(new int[]{10,1,1,1},ledger(world,playerName,false))&&barrierFeedback.contains(B_NAME)&&captureDone("station_gate_open")){result.addProperty("barrier_feedback",barrierFeedback);next(10);}
            } else if(step==10) {
                recordGate(world);
                if(clientArrived&&client.x<4&&player.posX<4&&"closed".equals(gate(world))){require(Arrays.equals(new int[]{10,1,1,1},ledger(world,playerName,false)),"Native entrance repeated or charged an unexpected fare");result.addProperty("native_barrier_collision_core_scoreboard_close",true);next(11);}
            } else if(step==11&&captureDone("station_B_final")) {
                require(clientLocked&&apgFlags(world,"false")&&clientPids&&pidsMatches(world)&&client.emptyHand&&player.getHeldItem()==null,"Final station state/inventory differs");
                result.addProperty("native_controller_brush_clicks",nativeClicks);result.add("steps",steps);result.add("gate_states",gateStates);result.addProperty("complete",true);complete=true;return true;
            }
            return false;
        }
        private void recordGate(WorldServer world){String value=gate(world);if(!value.equals(lastGate)){JsonObject row=new JsonObject();row.addProperty("state",value);row.addProperty("world_tick",world.getTotalWorldTime());gateStates.add(row);lastGate=value;}}
        private void next(int value){JsonObject row=new JsonObject();row.addProperty("from",step);row.addProperty("to",value);row.addProperty("server_ticks",stepTicks);steps.add(row);stepTicks=0;clientArrived=false;step=value;}
        private boolean click(Minecraft client,int current,int x,int y,int z) {
            if(clickStep==current)return true;
            ItemStack held=client.thePlayer.getHeldItem();if(held==null||held.getItem()!=org.mtr.mod.Items.BRUSH.get().data)return false;
            require(client.thePlayer.getDistanceSq(x+.5,y+.5,z+.5)<20.25,"Station brush click outside native reach");
            require(client.playerController.onPlayerRightClick(client.thePlayer,client.theWorld,held,x,y,z,5,net.minecraft.util.Vec3.createVectorHelper(x+.5,y+.5,z+.5)),"Native station brush click rejected");
            clickStep=current;nativeClicks++;return true;
        }
        private boolean walk(Minecraft client,double x,double z,boolean xFirst) {
            double dx=x-client.thePlayer.posX,dz=z-client.thePlayer.posZ;
            boolean useX=xFirst?Math.abs(dx)>.12:Math.abs(dz)<=.12;
            if(Math.abs(dx)<=.12&&Math.abs(dz)<=.12)return true;
            client.thePlayer.rotationYaw=useX?(dx>0?-90:90):(dz>0?0:180);client.thePlayer.rotationPitch=8;
            key(client.gameSettings.keyBindForward,true);return false;
        }
        private void aim(Minecraft client,double x,double y,double z) {
            double dx=x-client.thePlayer.posX,dz=z-client.thePlayer.posZ,dy=y-(client.thePlayer.boundingBox.minY+client.thePlayer.getEyeHeight());
            client.thePlayer.rotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);client.thePlayer.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        }
        private void equip(EntityPlayerMP player,ItemStack stack){player.inventory.currentItem=0;player.inventory.mainInventory[0]=stack;player.inventory.markDirty();player.inventoryContainer.detectAndSendChanges();}
        private boolean apgFlags(net.minecraft.world.World world,String expected){for(int z:new int[]{72,71})for(int y=5;y<=6;y++)if(world.getBlock(3,y,z)!=org.mtr.mod.Blocks.APG_DOOR.get().data||!expected.equals(state(world,3,y,z).data.getSerializedProperties().get("unlocked")))return false;return true;}
        private org.mtr.mod.block.BlockPSDAPGDoorBase.BlockEntityBase door(net.minecraft.world.World world){TileEntity tile=world.getTileEntity(3,5,72);return tile instanceof org.mtr.mod.block.BlockPSDAPGDoorBase.BlockEntityBase?(org.mtr.mod.block.BlockPSDAPGDoorBase.BlockEntityBase)tile:null;}
        private String gate(net.minecraft.world.World world){return state(world,4,5,80).data.getSerializedProperties().get("open");}
        private boolean pidsMatches(net.minecraft.world.World world){TileEntity tile=world.getTileEntity(3,7,77);if(!(tile instanceof org.mtr.mod.block.BlockPIDSBase.BlockEntityBase))return false;org.mtr.mod.block.BlockPIDSBase.BlockEntityBase pids=(org.mtr.mod.block.BlockPIDSBase.BlockEntityBase)tile;return MESSAGE.equals(pids.getMessage(0))&&pids.getHideArrival(0)&&pids.getDisplayPage()==0&&pids.getPlatformIds().size()==1&&pids.getPlatformIds().contains(platformBId);}
        boolean contains(int x,int y,int z){for(BlockPos pos:savedPositions)if(pos.getX()==x&&pos.getY()==y&&pos.getZ()==z)return true;return false;}
        private int[] ledger(WorldServer world,String name,boolean seed) {
            org.mtr.mapping.holder.Scoreboard board=new org.mtr.mapping.holder.World(world).getScoreboard();String[] keys={"mtr_balance","mtr_entry_zone_1","mtr_entry_zone_2","mtr_entry_zone_3"};int[] values=new int[4];
            for(int i=0;i<keys.length;i++) {org.mtr.mapping.holder.ScoreboardObjective objective=org.mtr.mapping.mapper.ScoreboardHelper.getScoreboardObjective(board,keys[i]);
                if(objective==null&&seed)objective=org.mtr.mapping.mapper.ScoreboardHelper.addObjective(board,keys[i],org.mtr.mapping.mapper.ScoreboardCriteria.DUMMY,new org.mtr.mapping.holder.Text(new net.minecraft.util.ChatComponentText(keys[i])),org.mtr.mapping.holder.ScoreboardCriterionRenderType.INTEGER);
                require(objective!=null,"Native station objective missing");if(seed)org.mtr.mapping.mapper.ScoreboardHelper.setPlayerScore(board,name,objective,i==0?10:0);values[i]=org.mtr.mapping.mapper.ScoreboardHelper.getPlayerScore(board,name,objective);}
            return values;
        }
        void verifySaved()throws Exception {
            require(complete,"Station callbacks did not complete");Path root=saveDirectory.toPath().toRealPath(),file=root.resolve("data/scoreboard.dat");require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&file.toRealPath().startsWith(root),"Saved station scoreboard escaped or is absent");byte[] before=sha256(file);NBTTagCompound data;
            try(InputStream input=Files.newInputStream(file)){data=CompressedStreamTools.readCompressed(input).getCompoundTag("data");}
            Map<String,Integer> scores=new LinkedHashMap<>();NBTTagList list=data.getTagList("PlayerScores",10);
            for(int i=0;i<list.tagCount();i++){NBTTagCompound row=list.getCompoundTagAt(i);String key=row.getString("Objective");if(playerName.equals(row.getString("Name"))&&(key.equals("mtr_balance")||key.startsWith("mtr_entry_zone_")))require(scores.put(key,row.getInteger("Score"))==null,"Duplicate saved station score");}
            require(scores.size()==4&&Integer.valueOf(10).equals(scores.get("mtr_balance"))&&Integer.valueOf(1).equals(scores.get("mtr_entry_zone_1"))&&Integer.valueOf(1).equals(scores.get("mtr_entry_zone_2"))&&Integer.valueOf(1).equals(scores.get("mtr_entry_zone_3")),"Native entrance scoreboard did not persist");
            require(Arrays.equals(before,sha256(file)),"Read-only station scoreboard verification changed bytes");result.addProperty("saved_native_scoreboard_verified",true);
        }
    }

    private static final class StoredBlock {
        final int x,y,z,id,metadata;final NBTTagCompound tag;final Map<String,String> properties;
        StoredBlock(int x,int y,int z,int id,int metadata,NBTTagCompound tag,Map<String,String> properties){this.x=x;this.y=y;this.z=z;this.id=id;this.metadata=metadata;this.tag=tag==null?null:(NBTTagCompound)tag.copy();this.properties=properties;}
    }

    private void snapshotStand(WorldServer world) {
        require(expectedSavedBlocks.isEmpty(),"Native train expectations already captured");
        for(int z:NODES)expectedSavedBlocks.add(snapshot(world,8,4,z));
        for(int start:new int[]{24,64})for(int z=start;z<=start+24;z++)for(int x=5;x<=7;x++)expectedSavedBlocks.add(snapshot(world,x,4,z));
        require(expectedSavedBlocks.size()==157,"Incomplete native train Anvil expectations");
        for(BlockPos pos:stationObjects.savedPositions)expectedSavedBlocks.add(snapshot(world,pos.getX(),pos.getY(),pos.getZ()));
        for(BlockPos pos:signalModels.savedPositions)expectedSavedBlocks.add(snapshot(world,pos.getX(),pos.getY(),pos.getZ()));
        for(int y=5;y<=6;y++)expectedSavedBlocks.add(snapshot(world,4,y,82));
        require(expectedSavedBlocks.size()==218&&stationObjects.savedPositions.size()==49&&signalModels.savedPositions.size()==10,"Incomplete native station/signal Anvil expectations");
    }
    private void unloadAudio(Minecraft client)throws Exception {
        require(System.currentTimeMillis()-stageStartedAt<60000,"Native audio world-unload deadline");
        if(!audioUnload.started){
            assertOwnedServer(client.getIntegratedServer());require(client.theWorld!=null&&trainAudio.accepted()&&tactileAudio.accepted,"Cannot close before original audio consumers were accepted");
            audioUnload.capture(client);client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());report.addProperty("saved_and_closed_normally",true);return;
        }
        require(client.theWorld==null&&client.thePlayer==null&&client.getIntegratedServer()==null,"Native unload retained an owned world/player/server");
        if(audioUnload.observe(client,++clientTicks))advance(Stage.CLOSE);
    }
    private void finish(Minecraft client) {
        if(stage==Stage.FINISHED)return;report.addProperty("terminal_phase",stage.name());stage=Stage.FINISHED;
        boolean safeShutdown=copyVerified&&client.getIntegratedServer()==null&&client.theWorld==null;
        if(copyVerified)KeyBinding.unPressAllKeys();
        try {
            if(client.getIntegratedServer()!=null) {
                assertOwnedServer(client.getIntegratedServer());safeShutdown=true;
                if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();
                client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());report.addProperty("saved_and_closed_normally",true);
            }
            if(failure==null) {
                Path marker=saveDirectory.toPath().resolve("mtr-train-probe-owner.txt");
                require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&runId.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Owned train save marker changed after shutdown");
                require(boardAnchor&&movingCaptured&&gravityFalling,"Full actual train scenario was not completed");
                require(passengerDoorHoldAccepted&&passengerDoorReleaseAccepted&&passengerNaturalDeparture,"Original no-key passenger hold, release or natural departure was not accepted");
                require(signalModels.before&&signalModels.after&&rideFrameRate(rideRenderFrameNanos)>0,"Native signal bakes or actual low-FPS ride evidence missing");
                require(trainAudio.geometryRefreshAccepted(),"Active refresh did not observe pending geometry and recovered geometry with the same rider");
                require(recoveryAckAccepted,"Actual native recovery C03/server acknowledgement witness missing");
                require(trainAudio.accepted()&&tactileAudio.accepted&&audioUnload.accepted,"Original BVE restart, tactile loop restart or native world-unload cleanup was not accepted");require(stationObjects.complete,"Native station controls were not accepted");verifySavedAnvil();verifySavedCore();stationObjects.verifySaved();
            }
        }catch(Throwable problem){fail(problem);}
        closeRidingLogObserver();report.addProperty("passed",failure==null);report.addProperty("setup_teleport_count",setupTeleports);
        report.addProperty("passenger_doorway_hold_accepted",passengerDoorHoldAccepted);report.addProperty("passenger_doorway_release_accepted",passengerDoorReleaseAccepted);report.addProperty("passenger_natural_departure_after_release",passengerNaturalDeparture);
        report.addProperty("ride_teleport_count",0);report.addProperty("probe_motion_writes",0);
        report.addProperty("s08_observed_after_channel_install",s08.get());report.addProperty("c03_observed_after_channel_install",c03.get());
        report.addProperty("s08_after_final_setup_ack",s08.get()-boardingS08);report.addProperty("authorized_boarding_s08_anchor_reached",boardAnchor);
        if(boardAnchor)report.addProperty("s08_after_authorized_boarding",s08.get()-motionS08);else report.add("s08_after_authorized_boarding",JsonNull.INSTANCE);
        report.addProperty("native_c17_inbound_count",c17.get());report.addProperty("native_fml_proxy_inbound_count",fmlProxy.get());report.addProperty("mtr_inbound_frame_count",mtrFrames.get());
        report.addProperty("riding_inbound_first_frame_count",ridingInbound.get());report.addProperty("riding_rejection_count",ridingRejections.get());report.addProperty("riding_logger_level_restored",ridingLogRestored);
        if(previousRidingLevel!=null)report.addProperty("riding_logger_previous_level",previousRidingLevel.toString());
        JsonArray frames=new JsonArray();for(JsonObject value:inboundFrames)frames.add(value);report.add("native_mtr_inbound_frames_first_64",frames);
        JsonArray reasons=new JsonArray();for(JsonObject value:rejectionReasons)reasons.add(value);report.add("riding_rejections_first_64",reasons);
        JsonArray nativeCorrections=new JsonArray();for(JsonObject value:corrections)nativeCorrections.add(value);report.add("actual_native_s08_packets",nativeCorrections);
        report.add("signal_native_bake_gates",signalModels.gates);report.addProperty("signal_native_bakes_before_and_after_refresh",signalModels.before&&signalModels.after);report.addProperty("configured_fps_limit",RIDE_FPS_LIMIT);report.add("ride_render_frame_nanos",rideRenderFrameNanos);report.addProperty("ride_render_frame_count",rideRenderFrameNanos.size());if(rideRenderFrameNanos.size()>=2)report.addProperty("ride_render_observed_fps",rideFrameRate(rideRenderFrameNanos));
        report.add("station_objects",stationObjects.result);report.add("timeline",timeline);report.add("transitions",transitions);report.add("screenshots",screenshots);report.add("original_one_shot_item_uses",placements);
        report.addProperty("dashboard_dto_packets_used",true);report.addProperty("dashboard_mouse_layout_accepted",false);report.addProperty("save_reopened",false);report.addProperty("audio_accepted",false);report.addProperty("remote_multiplayer_accepted",false);
        report.addProperty("audio_tactile_loop_refresh_accepted",tactileAudio.accepted);report.add("audio_tactile_loop_refresh",tactileAudio.result);report.addProperty("audio_native_world_unload_accepted",audioUnload.accepted);report.add("audio_native_world_unload",audioUnload.result);
        report.addProperty("fixed_vehicle_profile",CAR);report.addProperty("audio_train_bve_loop_stop_refresh_accepted",trainAudio.accepted());report.add("audio_train_bve_loop_stop_refresh",trainAudio.report());
        report.add("recovery_c03_ack",recoveryAck==null?JsonNull.INSTANCE:recoveryAck.evidence);
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try {
            if(copyVerified)Files.write(new File(client.mcDataDir,"mtr-train-probe-"+runId+".json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            System.out.println((failure==null?"MTR_TRAIN_PROBE_PASS ":"MTR_TRAIN_PROBE_FAIL ")+report);if(safeShutdown)client.shutdown();
        }catch(Throwable problem){problem.printStackTrace();throw new IllegalStateException("Could not write train acceptance result",problem);}
    }
    private void verifySavedAnvil()throws Exception {
        require(new File(saveDirectory,"level.dat").isFile()&&expectedSavedBlocks.size()==218&&stationObjects.savedPositions.size()==49&&signalModels.savedPositions.size()==10,"Actual new save/train/station/signal expectations missing");
        Map<String,NBTTagCompound> levels=new LinkedHashMap<>();Map<Path,byte[]> before=new LinkedHashMap<>();JsonArray persisted=new JsonArray();
        for(StoredBlock expected:expectedSavedBlocks) {
            int chunkX=Math.floorDiv(expected.x,16),chunkZ=Math.floorDiv(expected.z,16);String chunk=chunkX+","+chunkZ;
            if(!levels.containsKey(chunk)) {
                Path file=saveDirectory.toPath().resolve("region/r."+Math.floorDiv(chunkX,32)+"."+Math.floorDiv(chunkZ,32)+".mca");
                require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Actual saved train Anvil region missing");
                Path real=file.toRealPath();require(real.startsWith(saveDirectory.toPath().toRealPath()),"Anvil path escaped owned save");
                if(!before.containsKey(file))before.put(file,sha256(real));levels.put(chunk,readSavedChunkReadOnly(saveDirectory,file.toFile(),chunkX,chunkZ));
            }
            NBTTagCompound level=levels.get(chunk),section=null;int slot=((expected.y&15)<<8)|((expected.z&15)<<4)|(expected.x&15);NBTTagList sections=level.getTagList("Sections",10);
            for(int index=0;index<sections.tagCount();index++){NBTTagCompound value=sections.getCompoundTagAt(index);if((value.getByte("Y")&255)==expected.y>>4)section=value;}
            require(section!=null,"Saved train section missing");
            byte[] low=savedArray(section,"Blocks",4096,false),high=savedArray(section,"Add",2048,true),higher=savedArray(section,"BlocksB2Hi",2048,true),highest=savedArray(section,"BlocksB3",4096,true);
            int id=(low[slot]&255)|(high.length==0?0:nibble(high,slot)<<8)|(higher.length==0?0:nibble(higher,slot)<<12)|(highest.length==0?0:(highest[slot]&255)<<16);
            byte[] data=savedArray(section,"Data",2048,false),dataHigh=savedArray(section,"Data1High",2048,true),dataHighest=savedArray(section,"Data2",4096,true);
            int metadata=nibble(data,slot)|(dataHigh.length==0?0:nibble(dataHigh,slot)<<4)|(dataHighest.length==0?0:(dataHighest[slot]&255)<<8);
            require(id==expected.id&&metadata==expected.metadata,"Saved native train ID/metadata differs");
            NBTTagCompound tag=null;NBTTagList tiles=level.getTagList("TileEntities",10);
            for(int index=0;index<tiles.tagCount();index++){NBTTagCompound value=tiles.getCompoundTagAt(index);if(value.getInteger("x")==expected.x&&value.getInteger("y")==expected.y&&value.getInteger("z")==expected.z){require(tag==null,"Duplicate saved native train tile");tag=value;}}
            Block block=Block.getBlockById(id);BlockStateDefinition definition=BlockStateDefinition.forBlock(block);require((tag!=null)==(expected.tag!=null),"Saved train TileEntity presence changed");
            if(stationObjects.contains(expected.x,expected.y,expected.z)||signalModels.contains(expected.x,expected.y,expected.z)||(expected.x==4&&expected.z==82&&(expected.y==5||expected.y==6)))require(Objects.equals(expected.tag,tag),"Complete typed native station NBT changed on save");
            Map<String,String> properties=storedProperties(block,metadata,tag);require(expected.properties.equals(properties),"Saved train state properties changed through original codec");
            if(definition.requiresTileEntity())require(expected.tag.getCompoundTag("mtr_block_state").equals(tag.getCompoundTag("mtr_block_state")),"Saved extended train state differs");
            if(tag!=null){TileEntity restored=TileEntity.createAndLoadEntity(tag);require(restored instanceof BlockStateTileEntity,"Actual saved train tile ID cannot decode original state");require(properties.equals(((BlockStateTileEntity)restored).readMtrState(definition,metadata).data.getSerializedProperties()),"Saved train TileEntity codec disagrees with original definition");}
            JsonObject value=new JsonObject();value.addProperty("x",expected.x);value.addProperty("y",expected.y);value.addProperty("z",expected.z);value.addProperty("block",Block.blockRegistry.getNameForObject(block));value.addProperty("metadata",metadata);value.addProperty("has_native_tile",tag!=null);value.addProperty("extended_state_required",definition.requiresTileEntity());value.addProperty("native_nbt",tag==null?"absent":tag.toString());persisted.add(value);
        }
        for(Map.Entry<Path,byte[]> entry:before.entrySet())require(Arrays.equals(entry.getValue(),sha256(entry.getKey())),"Read-only Anvil check changed actual saved bytes");
        report.add("persisted_anvil_blocks",persisted);report.addProperty("saved_native_blocks_verified",persisted.size());report.addProperty("saved_native_chunks_verified",levels.size());report.addProperty("anvil_open_mode","r");report.addProperty("saved_anvil_readonly_bytes_unchanged",true);
    }
    private static NBTTagCompound readSavedChunkReadOnly(File ownedSave,File file,int chunkX,int chunkZ)throws Exception {
        Path root=ownedSave.toPath().toRealPath(),resolved=file.toPath().toRealPath();require(resolved.startsWith(root)&&!resolved.equals(root),"Anvil path escaped owned save");require(!Files.isSymbolicLink(file.toPath()),"Owned Anvil region must not be a symlink");
        // RegionFile uses rw and may repair damaged files; this reader never writes headers or sectors.
        try(RandomAccessFile region=new RandomAccessFile(resolved.toFile(),"r")) {
            require(region.length()>=8192&&region.length()%4096==0,"Malformed saved Anvil region length");region.seek(4L*(Math.floorMod(chunkX,32)+Math.floorMod(chunkZ,32)*32));
            int location=region.readInt(),sector=location>>>8,sectors=location&255;require(sector>=2&&sectors>0&&((long)sector+sectors)*4096<=region.length(),"Saved chunk sectors outside region");
            region.seek((long)sector*4096);int length=region.readInt();require(length>1&&length<=sectors*4096-4,"Malformed saved chunk payload length");int compression=region.readUnsignedByte();require(compression==1||compression==2,"Unsupported native Anvil compression "+compression);byte[] bytes=new byte[length-1];region.readFully(bytes);
            try(InputStream compressed=new ByteArrayInputStream(bytes);InputStream decompressed=compression==1?new GZIPInputStream(compressed):new InflaterInputStream(compressed);DataInputStream input=new DataInputStream(decompressed)) {
                NBTTagCompound level=CompressedStreamTools.read(input).getCompoundTag("Level");require(level.hasKey("xPos",3)&&level.hasKey("zPos",3)&&level.getInteger("xPos")==chunkX&&level.getInteger("zPos")==chunkZ,"Saved chunk location differs from requested train fixture");return level;
            }
        }
    }
    private void verifySavedCore()throws Exception {
        String[] kinds={"stations","platforms","sidings","routes","depots","rails"};int[] counts={2,2,1,1,1,6};Set<String> seen=new HashSet<>();JsonObject persisted=new JsonObject();
        require(expectedCore.size()==13&&coreSample!=null&&coreSample.riderCount==0&&!coreSample.onRoute&&!coreSample.moving,"Final original core expectations are incomplete or occupied");
        for(int kind=0;kind<kinds.length;kind++) {
            Path directory=saveDirectory.toPath().resolve("mtr/minecraft/overworld/"+kinds[kind]);require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"Actual original core "+kinds[kind]+" save directory missing");
            require(directory.toRealPath().startsWith(saveDirectory.toPath().toRealPath()),"Core train save escaped owned save");List<Path> files=new ArrayList<>();
            try(Stream<Path> paths=Files.walk(directory)){paths.forEach(path->{require(!Files.isSymbolicLink(path),"Symlink in owned core train save");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))files.add(path);});}
            require(files.size()==counts[kind],"Unexpected saved core "+kinds[kind]+" count: "+files.size());
            for(Path file:files) {
                String key=kinds[kind]+"/"+file.getFileName();JsonObject expected=expectedCore.get(key);require(expected!=null&&seen.add(key),"Saved core train ID differs: "+key);JsonObject actual=readSavedCoreReadOnly(saveDirectory,file.toFile());
                if(kinds[kind].equals("sidings")) {
                    JsonObject a=copyJson(actual),e=copyJson(expected);a.remove("vehicles");e.remove("vehicles");require(e.equals(a),"Saved siding identity/configuration/generated paths differ");
                    JsonArray vehicles=actual.getAsJsonArray("vehicles"),expectedVehicles=expected.getAsJsonArray("vehicles");require(vehicles!=null&&vehicles.size()==1&&expectedVehicles!=null&&expectedVehicles.size()==1,"Saved original core train count differs");
                    JsonObject train=vehicles.get(0).getAsJsonObject(),idle=expectedVehicles.get(0).getAsJsonObject();require(savedVehicleMatches(train,idle,vehicleId),"Saved original core train identity/state differs");
                    // VehicleSchema persists motion, not VehicleExtraData; report that boundary explicitly.
                    boolean hasRiders=train.has("ridingEntities");if(hasRiders)require(train.getAsJsonArray("ridingEntities").size()==0,"Saved original train still has riders");report.addProperty("saved_vehicle_rider_field_present",hasRiders);report.addProperty("empty_core_riders_verified_before_shutdown",true);report.add("saved_original_core_train",train);
                } else require(expected.equals(actual),"Saved original core identity/configuration differs: "+key);
                persisted.add(key,actual);
            }
        }
        require(seen.size()==expectedCore.size(),"Saved original core payload set differs");report.add("saved_original_core_payloads",persisted);report.addProperty("saved_core_payload_count",seen.size());report.addProperty("saved_core_readonly_bytes_unchanged",true);
    }
    private static boolean savedVehicleMatches(JsonObject actual,JsonObject expected,long id) {
        // After RETURN_IDLE, simulateInDepot preserves every persisted field; exclude none.
        return expected.equals(actual)&&actual.get("id").getAsLong()==id&&expected.get("id").getAsLong()==id&&Math.abs(actual.get("speed").getAsDouble())<1E-8;
    }
    private static JsonObject readSavedCoreReadOnly(File ownedSave,File file)throws Exception {
        Path root=ownedSave.toPath().toRealPath(),real=file.toPath().toRealPath();require(real.startsWith(root)&&!real.equals(root)&&!Files.isSymbolicLink(file.toPath()),"Core payload escaped owned save");byte[] before=sha256(real);JsonObject decoded;
        // Native FileLoader reads the first map; its CREATE-only writer can retain an older suffix.
        // Decode raw maps without FileLoader.readFile, which may delete corrupt data, or active DTOs.
        try(InputStream input=Files.newInputStream(real);MessageUnpacker unpacker=MessagePack.newDefaultUnpacker(input)) {
            org.mtr.libraries.org.msgpack.value.Value value=unpacker.unpackValue();require(value.isMapValue(),"Saved core payload must begin with a complete map");decoded=new JsonParser().parse(value.toJson()).getAsJsonObject();
        }
        require(Arrays.equals(before,sha256(real)),"Read-only core check changed actual saved bytes");return decoded;
    }
    private static JsonObject copyJson(JsonObject value){return new JsonParser().parse(value.toString()).getAsJsonObject();}
    private static byte[] sha256(Path file)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));}


    /** Observes the stock BVE noise loop; never creates, plays or stops a sound. */
    private static final class TrainAudioObserver {
        private static final String EVENT="mtr_compat:blocks/mtr/eidan_9000_loop",SAMPLE="mtr:sounds/eidan_9000/loop.ogg";
        private LoopGate gate=new LoopGate();
        private RestartGate restart;
        private LoopGate.Sample lastNative;
        private JsonObject activeRefreshEvidence;
        private JsonArray firstObservations;
        private JsonObject firstBinding;
        private boolean firstMoved;
        private JsonArray observations=new JsonArray();
        private Object world,soundIdentity;
        private Thread observerThread;
        private String channelId,lastWait="stock BVE loop not bound";
        private paulscode.sound.Source sourceIdentity;
        private paulscode.sound.Channel channelIdentity;
        private JsonObject binding,stopEvidence,reloadEvidence;
        private boolean moved,reloadRequested,activeRefreshRequested,geometryPendingObserved,geometryRecovered;
        private volatile boolean stopped,reloaded;
        private float firstX,firstZ;
        private int stoppedTick;
        private void bindVehicle(ClientSample sample)throws Exception {
            List<?> sounds=(List<?>)audioField(sample.sourceVehicle.persistentVehicleData.getClass(),sample.sourceVehicle.persistentVehicleData,"vehicleSoundBaseList");
            if(sounds.isEmpty())return;
            require(sounds.size()==1&&sounds.get(0) instanceof org.mtr.mod.sound.BveVehicleSound,"Expected one stock BVE car sound holder");
            Object holder=audioField(org.mtr.mod.sound.BveVehicleSound.class,sounds.get(0),"vehicleLoopingSoundHolder");
            Object noise=audioField(holder.getClass(),holder,"soundLoopNoise");
            require(noise instanceof org.mtr.mod.sound.VehicleLoopingSoundInstance,"Stock BVE noise loop missing");
            if(soundIdentity==null)soundIdentity=noise;else require(soundIdentity==noise,"Original vehicle loop instance changed");
        }
        void observe(Minecraft client,String phase,long expectedVehicle,ClientSample sample)throws Exception {
            if(sample.sourceVehicle==null||!sample.carConfigured||expectedVehicle==0||sample.vehicle!=expectedVehicle)return;
            if(!(phase.equals("BOARD")||phase.equals("DEPART")||phase.equals("RIDE")||phase.equals("STOP_B")||phase.equals("EXIT")||phase.equals("DISMOUNT")||phase.equals("GRAVITY")||phase.equals("RETURN_IDLE")||phase.equals("AUDIO_RELOAD")))return;
            require(client.theWorld!=null&&client.thePlayer!=null&&!client.isGamePaused(),"BVE observer requires unpaused owned world");
            if(world==null){world=client.theWorld;observerThread=Thread.currentThread();}
            require(world==client.theWorld&&observerThread==Thread.currentThread(),"BVE observer world/thread changed");
            if(activeRefreshRequested&&!reloadRequested&&activeRefreshEvidence!=null&&!geometryRecovered) {
                require(phase.equals("RIDE")&&sample.riding&&sample.sourceVehicle.getIsOnRoute(),"Active geometry recovery lost the original moving rider phase");
                if(!sample.vehicleGeometryCacheReady) {
                    if(!geometryPendingObserved)activeRefreshEvidence.add("pending_geometry_rider_sample",sample.json());
                    geometryPendingObserved=true;
                } else if(geometryPendingObserved) {
                    require(sample.vehicleGeometryFloorCount>0,"Recovered actual vehicle geometry has no floors");
                    activeRefreshEvidence.add("recovered_geometry_rider_sample",sample.json());geometryRecovered=true;
                }
                activeRefreshEvidence.addProperty("geometry_pending_riding_observed",geometryPendingObserved);activeRefreshEvidence.addProperty("geometry_ready_riding_recovered",geometryRecovered);
            }
            bindVehicle(sample);if(soundIdentity==null)return;
            net.minecraft.client.audio.ISound sound=(net.minecraft.client.audio.ISound)soundIdentity;
            Object manager=manager(client);paulscode.sound.SoundSystem system=system(manager);
            if(channelId!=null&&!sample.sourceVehicle.getIsOnRoute()&&sound.getVolume()<=0) {
                JsonObject evidence=cleanupEvidence(manager,system,sound,sample.tick);
                if(evidence!=null){if(!stopped){stoppedTick=sample.tick;stopEvidence=evidence;}stopped=true;lastWait="natural idle stop verified";}
                else lastWait="waiting for natural idle source cleanup";
            }
            if(reloadRequested) {
                JsonObject evidence=cleanupEvidence(manager,system,sound,sample.tick);
                if(evidence!=null){reloadEvidence.add("post_refresh_cleanup",evidence);reloaded=sample.tick>stoppedTick;lastWait=reloaded?"idle refresh verified without stale loop":"waiting for post-refresh tick";}
                return;
            }
            if(stopped)return;
            Map<?,?> inverse=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"invPlayingSounds","field_148630_i");
            String uuid=(String)inverse.get(sound);if(uuid==null){lastWait="stock BVE loop not yet submitted";return;}
            Map<?,?> playing=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSounds","field_148629_h");
            Map<?,?> entries=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSoundPoolEntries","field_148627_j");
            net.minecraft.client.audio.SoundPoolEntry selected=(net.minecraft.client.audio.SoundPoolEntry)entries.get(sound);
            require(playing.get(uuid)==sound&&EVENT.equals(sound.getPositionedSoundLocation().toString())&&selected!=null&&SAMPLE.equals(selected.getSoundPoolEntryLocation().toString()),"BVE consumer/event/sample/channel binding differs");
            require(sound.canRepeat()&&sound.getRepeatDelay()==0&&!selected.func_148648_d(),"Expected stock immediate-repeat nonstream BVE loop");
            if(system==null)return;
            synchronized(paulscode.sound.SoundSystemConfig.THREAD_SYNC) {
                paulscode.sound.Library library=(paulscode.sound.Library)audioField(paulscode.sound.SoundSystem.class,system,"soundLibrary");
                if(library==null)return;
                require(supportedOpenALClass(library.getClass(),paulscode.sound.libraries.LibraryLWJGLOpenAL.class,"com.mitchej123.hodgepodge.client.sound.LibraryHodgepodgeOpenAL"),"Actual OpenAL library required");
                paulscode.sound.Source source=(paulscode.sound.Source)library.getSources().get(uuid);
                if(source==null||source.channel==null||source.channel.attachedSource!=source){lastWait="native source/channel pending";return;}
                require(source.getClass()==paulscode.sound.libraries.SourceLWJGLOpenAL.class&&supportedOpenALClass(source.channel.getClass(),paulscode.sound.libraries.ChannelLWJGLOpenAL.class,"com.mitchej123.hodgepodge.client.sound.LibraryHodgepodgeOpenAL$1"),"Unexpected native source/channel");
                require(source.toLoop&&!source.toStream&&SAMPLE.equals(source.filenameURL.getFilename()),"Native source does not own the stock BVE loop sample");
                long context=ownedAudioContext();
                int sourceId=((java.nio.IntBuffer)audioField(paulscode.sound.libraries.ChannelLWJGLOpenAL.class,source.channel,"ALSource")).get(0);
                int buffer=((java.nio.IntBuffer)audioField(source.getClass(),source,"myBuffer")).get(0);
                require(sourceId>0&&buffer>0&&sourceInt(sourceId,0x1009)==buffer,"Native source/buffer binding differs");
                int state=sourceInt(sourceId,0x1010),loop=sourceInt(sourceId,0x1007),offset=sourceInt(sourceId,0x1025);
                float seconds=sourceFloat(sourceId,0x1024),gain=sourceFloat(sourceId,0x100A),pitch=sourceFloat(sourceId,0x1003);
                float listener=((Number)Class.forName("org.lwjgl.openal.AL10").getMethod("alGetListenerf",int.class).invoke(null,0x100A)).floatValue();
                int size=bufferInt(buffer,0x2004),frequency=bufferInt(buffer,0x2001),bits=bufferInt(buffer,0x2002),channels=bufferInt(buffer,0x2003);
                LoopGate.Sample value=new LoopGate.Sample(uuid,sound,source,source.channel,context,sourceId,buffer,state,loop,gain,listener,pitch,size,frequency,bits,channels,offset,seconds,System.nanoTime(),sample.tick);
                if(!value.valid()){lastWait="native loop not PLAYING with positive gain/buffer";return;}
                if(channelId==null) {
                    channelId=uuid;sourceIdentity=source;channelIdentity=source.channel;firstX=source.position.x;firstZ=source.position.z;
                    binding=new JsonObject();binding.addProperty("car",CAR);binding.addProperty("vehicle_id",Long.toString(expectedVehicle));binding.addProperty("event",EVENT);binding.addProperty("sample",SAMPLE);binding.addProperty("channel_uuid",uuid);binding.addProperty("loop_instance_identity",System.identityHashCode(sound));binding.addProperty("native_source_identity",System.identityHashCode(source));binding.addProperty("backend_class",library.getClass().getName());binding.addProperty("client_thread",Thread.currentThread().getName());
                }
                require(channelId.equals(uuid)&&sourceIdentity==source&&channelIdentity==source.channel,"Owned BVE loop native identity changed before natural stop");
                double dx=sound.getXPosF()-sample.centerX,dy=sound.getYPosF()-sample.centerY,dz=sound.getZPosF()-sample.centerZ;
                boolean poseNear=dx*dx+dy*dy+dz*dz<9&&Math.abs(source.position.x-sound.getXPosF())<2&&Math.abs(source.position.y-sound.getYPosF())<2&&Math.abs(source.position.z-sound.getZPosF())<2;
                if(sample.moving&&poseNear&&Math.abs(source.position.x-firstX)+Math.abs(source.position.z-firstZ)>=1)moved=true;
                boolean wasAccepted=gate.accepted;if(restart!=null)restart.observe(value);else gate.observe(value);lastNative=value;
                if(observations.size()==0||!wasAccepted&&gate.accepted||moved&&observations.size()<3) {
                    JsonObject row=new JsonObject();row.addProperty("phase",phase);row.addProperty("tick",sample.tick);row.addProperty("at_nanos",value.at);row.addProperty("AL_source",sourceId);row.addProperty("AL_buffer",buffer);row.addProperty("owned_context",Long.toHexString(context));row.addProperty("AL_state",state);row.addProperty("AL_looping",loop);row.addProperty("AL_gain",gain);row.addProperty("AL_listener_gain",listener);row.addProperty("AL_pitch",pitch);row.addProperty("AL_SIZE",size);row.addProperty("AL_FREQUENCY",frequency);row.addProperty("AL_BITS",bits);row.addProperty("AL_CHANNELS",channels);row.addProperty("AL_SAMPLE_OFFSET",offset);row.addProperty("AL_SEC_OFFSET",seconds);row.addProperty("native_source_x",source.position.x);row.addProperty("native_source_y",source.position.y);row.addProperty("native_source_z",source.position.z);row.addProperty("car_x",sample.centerX);row.addProperty("car_y",sample.centerY);row.addProperty("car_z",sample.centerZ);row.addProperty("moving_pose_matches",sample.moving&&poseNear&&moved);observations.add(row);
                }
                lastWait=gate.accepted&&moved?"native moving loop verified; waiting for natural idle stop":"waiting for loop playback progress and movement";
            }
        }
        private JsonObject cleanupEvidence(Object manager,paulscode.sound.SoundSystem system,net.minecraft.client.audio.ISound sound,int tick)throws Exception {
            if(channelId==null||system==null)return null;
            Map<?,?> playing=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSounds","field_148629_h");
            Map<?,?> inverse=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"invPlayingSounds","field_148630_i");
            Map<?,?> entries=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSoundPoolEntries","field_148627_j");
            Map<?,?> delayed=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"delayedSounds","field_148626_m");
            Map<?,?> stopTimes=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSoundsStopTime","field_148624_n");
            List<?> ticks=(List<?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"tickableSounds","field_148625_l");
            com.google.common.collect.Multimap<?,?> categories=(com.google.common.collect.Multimap<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"categorySounds","field_148628_k");
            synchronized(paulscode.sound.SoundSystemConfig.THREAD_SYNC) {
                paulscode.sound.Library library=(paulscode.sound.Library)audioField(paulscode.sound.SoundSystem.class,system,"soundLibrary");
                if(library==null)return null;
                boolean mapsAbsent=!playing.containsKey(channelId)&&!inverse.containsKey(sound)&&!entries.containsKey(sound)&&!delayed.containsKey(sound)&&!stopTimes.containsKey(channelId)&&!ticks.contains(sound)&&!categories.values().contains(channelId);
                boolean sourceAbsent=!library.getSources().containsKey(channelId),detached=channelIdentity!=null&&channelIdentity.attachedSource!=sourceIdentity;
                // Paulscode keeps attachedSource after Source.cleanup; a retained pooled channel must be STOPPED.
                int nativeState=-1;
                boolean noOldPlayback=stopped||detached;
                if(!noOldPlayback&&mapsAbsent&&sourceAbsent&&channelIdentity!=null){ownedAudioContext();java.nio.IntBuffer id=(java.nio.IntBuffer)audioField(paulscode.sound.libraries.ChannelLWJGLOpenAL.class,channelIdentity,"ALSource");if(id!=null&&id.limit()>0&&id.get(0)>0){nativeState=sourceInt(id.get(0),0x1010);noOldPlayback=nativeState==0x1014||nativeState==0x1011;}}
                if(!LoopGate.clean(sound.getVolume(),mapsAbsent,sourceAbsent,noOldPlayback))return null;
                JsonObject row=new JsonObject();row.addProperty("tick",tick);row.addProperty("channel_uuid",channelId);row.addProperty("native_sound_volume",sound.getVolume());row.addProperty("all_native_manager_entries_absent",mapsAbsent);row.addProperty("paulscode_source_absent",sourceAbsent);row.addProperty("old_native_source_detached",detached);row.addProperty("retained_channel_AL_state",nativeState);row.addProperty("original_playback_stopped",noOldPlayback);return row;
            }
        }
        void refreshInMotion(Minecraft client,ClientSample sample)throws Exception {
            if(activeRefreshRequested||!gate.accepted||!moved)return;
            require(sample.riding&&sample.moving&&sample.sourceVehicle.getIsOnRoute()&&world==client.theWorld&&observerThread==Thread.currentThread(),"Active refresh must remain on the original moving/ridden train");
            require(lastNative!=null,"Active refresh has no accepted native source");
            Object manager=manager(client);paulscode.sound.SoundSystem oldSystem=system(manager);
            paulscode.sound.Library oldLibrary=(paulscode.sound.Library)audioField(paulscode.sound.SoundSystem.class,oldSystem,"soundLibrary");
            require(oldLibrary!=null&&oldLibrary.getSources().get(channelId)==sourceIdentity,"Active refresh source lost original engine ownership");
            net.minecraft.client.audio.SoundEventAccessorComposite before=client.getSoundHandler().getSound(new net.minecraft.util.ResourceLocation(EVENT));
            activeRefreshRequested=true;restart=new RestartGate(lastNative);firstMoved=moved;firstBinding=binding;firstObservations=observations;
            client.refreshResources();
            net.minecraft.client.audio.SoundEventAccessorComposite after=client.getSoundHandler().getSound(new net.minecraft.util.ResourceLocation(EVENT));
            require(after!=null&&after!=before&&SAMPLE.equals(after.func_148720_g().getSoundPoolEntryLocation().toString()),"Active refresh did not replace original BVE registry/sample");
            boolean mapsAbsent=ownerAbsent(manager,(net.minecraft.client.audio.ISound)soundIdentity,channelId);
            boolean sourceAbsent=(oldLibrary.getSources()==null||!oldLibrary.getSources().containsKey(channelId));
            boolean nativeStopped=oldSourceStopped(lastNative,sourceIdentity,channelIdentity);
            require(restart.cleanup(mapsAbsent,sourceAbsent,nativeStopped),"Active resource refresh left stale BVE ownership/playback");
            activeRefreshEvidence=new JsonObject();activeRefreshEvidence.addProperty("during_original_ridden_movement",true);activeRefreshEvidence.addProperty("old_channel_uuid",channelId);activeRefreshEvidence.addProperty("all_old_manager_entries_absent",mapsAbsent);activeRefreshEvidence.addProperty("old_paulscode_source_absent",sourceAbsent);activeRefreshEvidence.addProperty("old_native_playback_stopped",nativeStopped);activeRefreshEvidence.addProperty("original_event_accessor_replaced",true);
            gate=restart.progress;channelId=null;sourceIdentity=null;channelIdentity=null;binding=null;observations=new JsonArray();moved=false;
        }
        private static boolean ownerAbsent(Object manager,net.minecraft.client.audio.ISound sound,String uuid)throws Exception {
            Map<?,?> playing=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSounds","field_148629_h");
            Map<?,?> inverse=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"invPlayingSounds","field_148630_i");
            Map<?,?> entries=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSoundPoolEntries","field_148627_j");
            Map<?,?> delayed=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"delayedSounds","field_148626_m");
            Map<?,?> stopTimes=(Map<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSoundsStopTime","field_148624_n");
            List<?> ticks=(List<?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"tickableSounds","field_148625_l");
            com.google.common.collect.Multimap<?,?> categories=(com.google.common.collect.Multimap<?,?>)audioField(net.minecraft.client.audio.SoundManager.class,manager,"categorySounds","field_148628_k");
            return !playing.containsKey(uuid)&&!playing.containsValue(sound)&&!inverse.containsKey(sound)&&!entries.containsKey(sound)&&!delayed.containsKey(sound)&&!stopTimes.containsKey(uuid)&&!ticks.contains(sound)&&!categories.values().contains(uuid);
        }
        private static boolean oldSourceStopped(LoopGate.Sample old,paulscode.sound.Source source,paulscode.sound.Channel channel)throws Exception {
            if(channel.attachedSource!=source)return true;
            Class<?> shim=Class.forName("org.lwjglx.openal.AL");
            if(!(Boolean)shim.getMethod("isCreated").invoke(null))return true;
            long context=ownedAudioContext();
            if(context!=old.context)return true;
            if(!((Boolean)Class.forName("org.lwjgl.openal.AL10").getMethod("alIsSource",int.class).invoke(null,old.id)))return true;
            int state=sourceInt(old.id,0x1010);return state==0x1014||state==0x1011;
        }
        void refresh(Minecraft client,ClientSample sample)throws Exception {
            require(!reloadRequested&&stopped&&gate.accepted&&moved&&!sample.riding&&sample.sourceVehicle!=null&&!sample.sourceVehicle.getIsOnRoute()&&!sample.sourceVehicle.isMoving(),"Resource refresh requires naturally stopped idle train after dismount");
            require(world==client.theWorld&&observerThread==Thread.currentThread(),"Refresh escaped owned client world/thread");
            net.minecraft.client.audio.SoundEventAccessorComposite before=client.getSoundHandler().getSound(new net.minecraft.util.ResourceLocation(EVENT));require(before!=null,"Original BVE event missing before refresh");
            reloadRequested=true;client.refreshResources();
            net.minecraft.client.audio.SoundEventAccessorComposite after=client.getSoundHandler().getSound(new net.minecraft.util.ResourceLocation(EVENT));
            require(after!=null&&after!=before&&SAMPLE.equals(after.func_148720_g().getSoundPoolEntryLocation().toString()),"Resource refresh did not reload original BVE sample/alias");
            reloadEvidence=new JsonObject();reloadEvidence.addProperty("refresh_count",1);reloadEvidence.addProperty("after_dismount_and_idle",true);reloadEvidence.addProperty("event_accessor_replaced",true);reloadEvidence.addProperty("reloaded_sample",SAMPLE);reloadEvidence.addProperty("playback_restart_after_refresh_tested",true);
        }
        boolean stopped(){return stopped&&gate.accepted&&moved&&restart!=null&&restart.progress.accepted&&firstMoved;}
        boolean geometryRefreshAccepted(){return geometryPendingObserved&&geometryRecovered;}
        boolean accepted(){return stopped()&&reloaded;}
        JsonObject report(){JsonObject row=new JsonObject();row.addProperty("accepted",accepted());row.addProperty("scope","one original Eidan 9000 BVE moving noise loop, active refresh and original-consumer restart/progress/movement, natural idle stop, idle resource refresh");row.addProperty("synthetic_play_calls",0);row.addProperty("synthetic_stop_calls",0);row.addProperty("event_callbacks_injected",0);row.addProperty("audio_context_mutations",0);row.addProperty("audio_queue_mutations",0);row.addProperty("resource_refresh_count",(activeRefreshRequested?1:0)+(reloadRequested?1:0));row.addProperty("native_loop_progress_accepted",gate.accepted);row.addProperty("native_moving_pose_accepted",moved);row.addProperty("natural_idle_stop_accepted",stopped());row.addProperty("idle_resource_refresh_accepted",reloaded);row.addProperty("playback_restart_after_refresh_tested",restart!=null&&restart.progress.accepted&&moved);row.addProperty("last_wait",lastWait);if(activeRefreshEvidence!=null)row.add("active_resource_refresh",activeRefreshEvidence);if(firstBinding!=null)row.add("before_refresh_binding",firstBinding);if(firstObservations!=null)row.add("before_refresh_native_samples",firstObservations);if(binding!=null)row.add("binding",binding);if(stopEvidence!=null)row.add("natural_stop",stopEvidence);if(reloadEvidence!=null)row.add("resource_refresh",reloadEvidence);row.add("native_samples",observations);return row;}
        private static Object manager(Minecraft client)throws Exception{return audioField(net.minecraft.client.audio.SoundHandler.class,client.getSoundHandler(),"sndManager","field_147694_f");}
        private static paulscode.sound.SoundSystem system(Object manager)throws Exception{return (paulscode.sound.SoundSystem)audioField(net.minecraft.client.audio.SoundManager.class,manager,"sndSystem","field_148620_e");}
        private static boolean supportedOpenALClass(Class<?> actual,Class<?> owner,String allowed){return owner.isAssignableFrom(actual)&&(actual==owner||actual.getName().equals(allowed));}
        private static Object audioField(Class<?> type,Object owner,String... names)throws Exception {for(String name:names)try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(type.getName()+Arrays.toString(names));}
        private static int sourceInt(int id,int parameter)throws Exception{return ((Number)Class.forName("org.lwjgl.openal.AL10").getMethod("alGetSourcei",int.class,int.class).invoke(null,id,parameter)).intValue();}
        private static float sourceFloat(int id,int parameter)throws Exception{return ((Number)Class.forName("org.lwjgl.openal.AL10").getMethod("alGetSourcef",int.class,int.class).invoke(null,id,parameter)).floatValue();}
        private static int bufferInt(int id,int parameter)throws Exception{return ((Number)Class.forName("org.lwjgl.openal.AL10").getMethod("alGetBufferi",int.class,int.class).invoke(null,id,parameter)).intValue();}
        private static long ownedAudioContext()throws Exception {
            Class<?> shim=Class.forName("org.lwjglx.openal.AL");require((Boolean)shim.getMethod("isCreated").invoke(null),"Original OpenAL backend not created");Object owned=shim.getMethod("getContext").invoke(null);
            require(owned!=null,"Missing original OpenAL context");long expected=((Number)audioField(owned.getClass(),owned,"context")).longValue();
            long current=((Number)Class.forName("org.lwjgl.openal.ALC10").getMethod("alcGetCurrentContext").invoke(null)).longValue();
            Object alcCaps=Class.forName("org.lwjgl.openal.ALC").getMethod("getCapabilities").invoke(null);require(alcCaps!=null,"Observer thread lacks ALC capabilities");
            if(alcCaps.getClass().getField("ALC_EXT_thread_local_context").getBoolean(alcCaps)){long thread=((Number)Class.forName("org.lwjgl.openal.EXTThreadLocalContext").getMethod("alcGetThreadContext").invoke(null)).longValue();if(thread!=0)current=thread;}
            require(expected!=0&&current==expected&&Class.forName("org.lwjgl.openal.AL").getMethod("getCapabilities").invoke(null)!=null,"Observer thread lacks original OpenAL context/capabilities");return expected;
        }
    }

    /** The real block-entity callback owns playback; this observer never invokes setPos/play/stop. */
    private static final class TactileAudioObserver {
        private static final String EVENT="mtr_compat:blocks/mtr/tactile_map_music",SAMPLE="mtr:sounds/tactile_map_music.ogg";
        private net.minecraft.client.audio.ISound sound;
        private LoopGate gate=new LoopGate();
        private RestartGate restart;
        private String uuid;
        private paulscode.sound.Source source;
        private paulscode.sound.Channel channel;
        private LoopGate.Sample last;
        private Object world;
        private Thread thread;
        private final JsonArray samples=new JsonArray();
        final JsonObject result=new JsonObject();
        volatile boolean placed,accepted;
        void observe(Minecraft client,int tick)throws Exception {
            if(accepted)return;if(sound==null)sound=InitClient.TACTILE_MAP_SOUND_INSTANCE;
            if(world==null){world=client.theWorld;thread=Thread.currentThread();}
            require(world==client.theWorld&&thread==Thread.currentThread()&&!client.isGamePaused(),"Tactile observer escaped original world/thread");
            if(client.theWorld.getBlock(4,5,82)!=org.mtr.mod.Blocks.TACTILE_MAP.get().data||client.theWorld.getBlock(4,6,82)!=org.mtr.mod.Blocks.TACTILE_MAP.get().data)return;
            require(client.theWorld.getTileEntity(4,5,82) instanceof org.mtr.mod.block.BlockTactileMap.BlockEntity&&client.theWorld.getTileEntity(4,6,82) instanceof org.mtr.mod.block.BlockTactileMap.BlockEntity,"Original tactile block entities missing");
            Object manager=TrainAudioObserver.manager(client);paulscode.sound.SoundSystem system=TrainAudioObserver.system(manager);
            Map<?,?> inverse=(Map<?,?>)TrainAudioObserver.audioField(net.minecraft.client.audio.SoundManager.class,manager,"invPlayingSounds","field_148630_i");
            String current=(String)inverse.get(sound);if(current==null||system==null)return;
            Map<?,?> playing=(Map<?,?>)TrainAudioObserver.audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSounds","field_148629_h");
            Map<?,?> entries=(Map<?,?>)TrainAudioObserver.audioField(net.minecraft.client.audio.SoundManager.class,manager,"playingSoundPoolEntries","field_148627_j");
            net.minecraft.client.audio.SoundPoolEntry selected=(net.minecraft.client.audio.SoundPoolEntry)entries.get(sound);
            require(playing.get(current)==sound&&EVENT.equals(sound.getPositionedSoundLocation().toString())&&selected!=null&&SAMPLE.equals(selected.getSoundPoolEntryLocation().toString())&&sound.canRepeat()&&sound.getRepeatDelay()==0&&!selected.func_148648_d(),"Tactile original event/sample/repeat binding differs");
            paulscode.sound.Library library;
            synchronized(paulscode.sound.SoundSystemConfig.THREAD_SYNC){
                library=(paulscode.sound.Library)TrainAudioObserver.audioField(paulscode.sound.SoundSystem.class,system,"soundLibrary");if(library==null)return;
                require(TrainAudioObserver.supportedOpenALClass(library.getClass(),paulscode.sound.libraries.LibraryLWJGLOpenAL.class,"com.mitchej123.hodgepodge.client.sound.LibraryHodgepodgeOpenAL"),"Tactile requires actual OpenAL library");
                paulscode.sound.Source currentSource=(paulscode.sound.Source)library.getSources().get(current);
                if(currentSource==null||currentSource.channel==null||currentSource.channel.attachedSource!=currentSource)return;
                require(currentSource.getClass()==paulscode.sound.libraries.SourceLWJGLOpenAL.class&&TrainAudioObserver.supportedOpenALClass(currentSource.channel.getClass(),paulscode.sound.libraries.ChannelLWJGLOpenAL.class,"com.mitchej123.hodgepodge.client.sound.LibraryHodgepodgeOpenAL$1")&&currentSource.toLoop&&!currentSource.toStream&&SAMPLE.equals(currentSource.filenameURL.getFilename()),"Tactile native source/channel/sample differs");
                long context=TrainAudioObserver.ownedAudioContext();int id=((java.nio.IntBuffer)TrainAudioObserver.audioField(paulscode.sound.libraries.ChannelLWJGLOpenAL.class,currentSource.channel,"ALSource")).get(0),buffer=((java.nio.IntBuffer)TrainAudioObserver.audioField(currentSource.getClass(),currentSource,"myBuffer")).get(0);
                require(id>0&&buffer>0&&TrainAudioObserver.sourceInt(id,0x1009)==buffer,"Tactile native source/buffer differs");
                float listener=((Number)Class.forName("org.lwjgl.openal.AL10").getMethod("alGetListenerf",int.class).invoke(null,0x100A)).floatValue();
                LoopGate.Sample value=new LoopGate.Sample(current,sound,currentSource,currentSource.channel,context,id,buffer,TrainAudioObserver.sourceInt(id,0x1010),TrainAudioObserver.sourceInt(id,0x1007),TrainAudioObserver.sourceFloat(id,0x100A),listener,TrainAudioObserver.sourceFloat(id,0x1003),TrainAudioObserver.bufferInt(buffer,0x2004),TrainAudioObserver.bufferInt(buffer,0x2001),TrainAudioObserver.bufferInt(buffer,0x2002),TrainAudioObserver.bufferInt(buffer,0x2003),TrainAudioObserver.sourceInt(id,0x1025),TrainAudioObserver.sourceFloat(id,0x1024),System.nanoTime(),tick);
                if(!value.valid())return;
                require(sound.getXPosF()==4&&sound.getYPosF()==5&&sound.getZPosF()==82&&Math.abs(currentSource.position.x-4)<.01&&Math.abs(currentSource.position.y-5)<.01&&Math.abs(currentSource.position.z-82)<.01,"Original tactile block-entity tick did not supply native source position");
                boolean first=uuid==null;if(first){uuid=current;source=currentSource;channel=currentSource.channel;}
                require(uuid.equals(current)&&source==currentSource&&channel==currentSource.channel,"Tactile epoch changed native source identity");
                boolean before=gate.accepted;if(restart==null)gate.observe(value);else restart.observe(value);last=value;
                if(first||!before&&gate.accepted){JsonObject row=new JsonObject();row.addProperty("epoch",restart==null?0:1);row.addProperty("client_tick",tick);row.addProperty("at_nanos",value.at);row.addProperty("channel_uuid",uuid);row.addProperty("source_identity",System.identityHashCode(source));row.addProperty("original_sound_identity",System.identityHashCode(sound));row.addProperty("owned_context",Long.toHexString(context));row.addProperty("AL_source",id);row.addProperty("AL_buffer",buffer);row.addProperty("AL_state",value.state);row.addProperty("AL_looping",value.loop);row.addProperty("AL_gain",value.gain);row.addProperty("AL_listener_gain",listener);row.addProperty("AL_pitch",value.pitch);row.addProperty("AL_SIZE",value.size);row.addProperty("AL_FREQUENCY",value.frequency);row.addProperty("AL_BITS",value.bits);row.addProperty("AL_CHANNELS",value.channels);row.addProperty("AL_SAMPLE_OFFSET",value.offset);row.addProperty("AL_SEC_OFFSET",value.seconds);row.addProperty("original_block_tick_native_position",true);samples.add(row);}
            }
            if(!gate.accepted)return;
            if(restart==null){
                restart=new RestartGate(last);net.minecraft.client.audio.SoundEventAccessorComposite before=client.getSoundHandler().getSound(new net.minecraft.util.ResourceLocation(EVENT));
                client.refreshResources();net.minecraft.client.audio.SoundEventAccessorComposite after=client.getSoundHandler().getSound(new net.minecraft.util.ResourceLocation(EVENT));
                require(after!=null&&after!=before&&SAMPLE.equals(after.func_148720_g().getSoundPoolEntryLocation().toString()),"Tactile resource refresh did not replace original event/sample");
                boolean mapsAbsent=TrainAudioObserver.ownerAbsent(manager,sound,uuid),sourceAbsent=library.getSources()==null||!library.getSources().containsKey(uuid),nativeStopped=TrainAudioObserver.oldSourceStopped(last,source,channel);
                require(restart.cleanup(mapsAbsent,sourceAbsent,nativeStopped),"Tactile resource refresh retained old native sound ownership");
                result.addProperty("old_channel_uuid",uuid);result.addProperty("all_old_manager_entries_absent",mapsAbsent);result.addProperty("old_paulscode_source_absent",sourceAbsent);result.addProperty("old_native_playback_stopped",nativeStopped);result.addProperty("event_accessor_replaced",true);
                gate=restart.progress;uuid=null;source=null;channel=null;
            }else{
                accepted=true;result.addProperty("accepted",true);result.addProperty("event",EVENT);result.addProperty("sample",SAMPLE);result.addProperty("original_block_entity_callback",true);result.addProperty("resource_refresh_count",1);result.addProperty("playback_restart_after_refresh_accepted",true);result.addProperty("synthetic_play_stop_or_setPos_calls",0);result.addProperty("physical_audibility_accepted",false);result.add("native_samples",samples);
            }
        }
    }

    /** Records every still-playing MTR event before the actual native world unload. */
    private static final class AudioUnloadObserver {
        private static final class Owned {
            final net.minecraft.client.audio.ISound sound;final String uuid;final paulscode.sound.Library library;final paulscode.sound.Source source;final paulscode.sound.Channel channel;final LoopGate.Sample nativeSample;
            Owned(net.minecraft.client.audio.ISound sound,String uuid,paulscode.sound.Library library,paulscode.sound.Source source,paulscode.sound.Channel channel,LoopGate.Sample nativeSample){this.sound=sound;this.uuid=uuid;this.library=library;this.source=source;this.channel=channel;this.nativeSample=nativeSample;}
        }
        private final List<Owned> owned=new ArrayList<>();
        private Object manager;
        private Thread thread;
        private int stable,lastTick;
        boolean started,accepted;
        final JsonObject result=new JsonObject();
        void capture(Minecraft client)throws Exception {
            require(!started,"Audio unload was already started");manager=TrainAudioObserver.manager(client);thread=Thread.currentThread();paulscode.sound.SoundSystem system=TrainAudioObserver.system(manager);require(system!=null,"Audio unload lacks original sound system");
            synchronized(paulscode.sound.SoundSystemConfig.THREAD_SYNC){
                paulscode.sound.Library library=(paulscode.sound.Library)TrainAudioObserver.audioField(paulscode.sound.SoundSystem.class,system,"soundLibrary");require(library!=null,"Audio unload lacks original sound library");
                Map<?,?> inverse=(Map<?,?>)TrainAudioObserver.audioField(net.minecraft.client.audio.SoundManager.class,manager,"invPlayingSounds","field_148630_i");
                for(Map.Entry<?,?> entry:inverse.entrySet()){
                    net.minecraft.client.audio.ISound sound=(net.minecraft.client.audio.ISound)entry.getKey();String domain=sound.getPositionedSoundLocation().getResourceDomain();if(!domain.equals("mtr")&&!domain.equals("mtr_compat"))continue;
                    String uuid=(String)entry.getValue();paulscode.sound.Source source=(paulscode.sound.Source)library.getSources().get(uuid);paulscode.sound.Channel channel=source==null?null:source.channel;LoopGate.Sample sample=null;
                    if(channel!=null){long context=TrainAudioObserver.ownedAudioContext();int id=((java.nio.IntBuffer)TrainAudioObserver.audioField(paulscode.sound.libraries.ChannelLWJGLOpenAL.class,channel,"ALSource")).get(0);require(id>0,"Owned unload channel lacks native source");sample=new LoopGate.Sample(uuid,sound,source,channel,context,id,0,0,0,0,0,0,0,0,0,0,0,0,0,0);}
                    owned.add(new Owned(sound,uuid,library,source,channel,sample));
                }
            }
            require(!owned.isEmpty()&&owned.stream().anyMatch(value->value.sound==InitClient.TACTILE_MAP_SOUND_INSTANCE),"Native unload must include the accepted original tactile loop");
            started=true;result.addProperty("actual_native_loadWorld_null",true);result.addProperty("owned_mtr_sources_before_unload",owned.size());result.addProperty("synthetic_stop_calls",0);result.addProperty("audio_queue_mutations",0);
        }
        boolean observe(Minecraft client,int tick)throws Exception {
            require(started&&thread==Thread.currentThread()&&manager==TrainAudioObserver.manager(client)&&client.theWorld==null,"Audio unload observer changed thread/manager or rejoined a world");
            require(tick>lastTick,"Audio unload requires fresh native client ticks");lastTick=tick;JsonArray rows=new JsonArray();boolean clean=true;
            synchronized(paulscode.sound.SoundSystemConfig.THREAD_SYNC){
                for(Owned value:owned){
                    boolean mapsAbsent=TrainAudioObserver.ownerAbsent(manager,value.sound,value.uuid),sourceAbsent=value.library.getSources()==null||!value.library.getSources().containsKey(value.uuid),nativeStopped=value.nativeSample==null||TrainAudioObserver.oldSourceStopped(value.nativeSample,value.source,value.channel);
                    clean&=mapsAbsent&&sourceAbsent&&nativeStopped;JsonObject row=new JsonObject();row.addProperty("event",value.sound.getPositionedSoundLocation().toString());row.addProperty("channel_uuid",value.uuid);row.addProperty("all_native_manager_entries_absent",mapsAbsent);row.addProperty("paulscode_source_absent",sourceAbsent);row.addProperty("original_native_playback_stopped",nativeStopped);rows.add(row);
                }
            }
            stable=clean?stable+1:0;result.add("sources",rows);result.addProperty("last_client_tick",tick);result.addProperty("consecutive_clean_client_ticks",stable);accepted=stable>=3;result.addProperty("accepted",accepted);return accepted;
        }
    }

    // BEGIN_ISOLATED_NATIVE_LOOP_GATE
    static final class LoopGate {
        private Sample first;
        boolean accepted;
        static final class Sample {
            final String uuid;final Object sound,source,channel;final long context,at;final int id,buffer,state,loop,size,frequency,bits,channels,offset,tick;final float gain,listenerGain,pitch,seconds;
            Sample(String uuid,Object sound,Object source,Object channel,long context,int id,int buffer,int state,int loop,float gain,float listenerGain,float pitch,int size,int frequency,int bits,int channels,int offset,float seconds,long at,int tick){this.uuid=uuid;this.sound=sound;this.source=source;this.channel=channel;this.context=context;this.id=id;this.buffer=buffer;this.state=state;this.loop=loop;this.gain=gain;this.listenerGain=listenerGain;this.pitch=pitch;this.size=size;this.frequency=frequency;this.bits=bits;this.channels=channels;this.offset=offset;this.seconds=seconds;this.at=at;this.tick=tick;}
            boolean valid(){return uuid!=null&&!uuid.isEmpty()&&sound!=null&&source!=null&&channel!=null&&context!=0&&id>0&&buffer>0&&state==0x1012&&loop==1&&finite(gain)&&gain>0&&finite(listenerGain)&&listenerGain>0&&finite(pitch)&&Math.abs(pitch-1)<.001F&&size>0&&frequency>0&&(bits==8||bits==16)&&(channels==1||channels==2)&&size%(bits/8*channels)==0&&offset>=0&&offset<frames()&&finite(seconds)&&seconds>=0&&seconds<duration()+.01;}
            int frames(){return size/(bits/8*channels);}
            double duration(){return (double)frames()/frequency;}
            private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
        }
        boolean observe(Sample value) {
            if(accepted)return true;if(!value.valid())return false;if(first==null){first=value;return false;}
            if(!first.uuid.equals(value.uuid)||first.sound!=value.sound||first.source!=value.source||first.channel!=value.channel||first.context!=value.context||first.id!=value.id||first.buffer!=value.buffer||first.size!=value.size||first.frequency!=value.frequency||first.bits!=value.bits||first.channels!=value.channels)throw new AssertionError("Native loop source/channel/context/format rebound");
            long elapsed=value.at-first.at;
            if(elapsed>1000000000L){first=value;return false;}
            double duration=first.duration(),seconds=(value.seconds-first.seconds+duration)%duration;
            int frames=(value.offset-first.offset+first.frames())%first.frames();
            accepted=value.tick>first.tick&&elapsed>=10000000L&&frames>0&&seconds>0&&Math.abs((double)frames/first.frequency-seconds)<.02;
            return accepted;
        }
        static boolean clean(float volume,boolean managerAbsent,boolean sourceAbsent,boolean detached){return !Float.isNaN(volume)&&!Float.isInfinite(volume)&&volume<=0&&managerAbsent&&sourceAbsent&&detached;}
    }
    static final class RestartGate {
        final LoopGate progress=new LoopGate();
        private final LoopGate.Sample previous;
        private boolean retired;
        RestartGate(LoopGate.Sample previous){this.previous=previous;}
        boolean cleanup(boolean managerAbsent,boolean sourceAbsent,boolean nativeStopped){retired=managerAbsent&&sourceAbsent&&nativeStopped;return retired;}
        boolean observe(LoopGate.Sample value){
            require(retired,"Restart before complete old-source cleanup");
            require(previous.sound==value.sound&&!previous.uuid.equals(value.uuid)&&previous.source!=value.source,"Resource reload did not restart the original consumer with a fresh UUID/source");
            return progress.observe(value);
        }
    }
    // END_ISOLATED_NATIVE_LOOP_GATE

}
