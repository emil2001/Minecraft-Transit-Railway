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

/** Explicit test-only full-MTR cable car acceptance; never opens a pre-existing world. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_cable_probe", name="MTR GTNH original continuous cable car acceptance", version="0", dependencies="required-after:mtr", acceptableRemoteVersions="*")
public final class CableProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT=new File(requiredProbePath("mtr.probeClient"));
    private static final TransportMode MODE=TransportMode.CABLE_CAR;
    // Directed first-click -> second-click loop. y=12; type: station=0, lower=1, upper=2.
    private static final int[][] NODES={{0,0,0},{0,16,0},{0,32,0},{0,48,1},{0,64,0},{0,80,0},{0,96,0},{16,112,0},{32,96,0},{32,80,2},{32,64,0},{32,48,0},{32,24,1},{32,0,0},{16,-16,0}};
    private static volatile JsonObject expectedCarData;
    private volatile long candidateVehicleId;
    private static final String CAR="ngong_ping_360_normal_red_lht", A_NAME="Probe Station A", B_NAME="Probe Station B", D_NAME="Probe Depot", R_NAME="Probe A to B";
    private enum Stage {MENU,WORLD,SETUP,CHUNKS,DASHBOARD,AREAS,PLATFORMS,ROUTE,SIDING,DEPOT,GENERATE,SCHEDULE,PICKUP_A,BOARD,DEPART,RIDE,STOP_B,EXIT,DISMOUNT,GRAVITY,CLOSE,FINISHED}
    private volatile Stage stage=Stage.MENU,clientAcknowledged;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private volatile long stationAId,stationBId,depotId,routeId,sidingId,platformAId,platformBId,vehicleId,generationRequestedAt,generationPreviousMillis;
    private volatile long startedAt,stageStartedAt;
    private volatile ClientSample clientSample;
    private volatile CoreSample coreSample;
    private final AtomicBoolean coreReadPending=new AtomicBoolean();
    private final AtomicInteger s08=new AtomicInteger(),c03=new AtomicInteger(),c17=new AtomicInteger(),fmlProxy=new AtomicInteger(),mtrFrames=new AtomicInteger(),ridingInbound=new AtomicInteger(),ridingRejections=new AtomicInteger();
    private final ConcurrentLinkedQueue<JsonObject> corrections=new ConcurrentLinkedQueue<>(),inboundFrames=new ConcurrentLinkedQueue<>(),rejectionReasons=new ConcurrentLinkedQueue<>();
    private volatile JsonObject lastC03,lastRidingInbound,lastRidingRejection;
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
    private final JsonArray timeline=new JsonArray(),transitions=new JsonArray(),screenshots=new JsonArray(),placements=new JsonArray();
    private final List<StoredBlock> expectedSavedBlocks=new ArrayList<>();
    private final Map<String,JsonObject> expectedCore=new LinkedHashMap<>();

    private volatile Vector pickupPose;
    private boolean exitAligned;
    private int exitSettleTicks;
    private double boardedZ,jumpBase;
    private boolean movingCaptured,gravityFalling;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=stageStartedAt=System.currentTimeMillis();
        require(!Loader.isModLoaded("mtr_gtnh_probe")&&!Loader.isModLoaded("mtr_gtnh_world_probe")&&!Loader.isModLoaded("mtr_gtnh_lift_probe")&&!Loader.isModLoaded("mtr_gtnh_train_probe")&&!Loader.isModLoaded("mtr_gtnh_boat_probe"),"CableProbe must be the only selected probe");
        FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null||stage==Stage.CLOSE){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<15*60*1000L,"Cable probe total deadline in "+stage);
            if(stage==Stage.MENU) {
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();
                if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                reserveNewSave(client);report.addProperty("menu_class",screen);
                client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=1;client.gameSettings.renderDistanceChunks=8;client.gameSettings.saveOptions();
                advance(Stage.WORLD);
                WorldSettings settings=new WorldSettings(0x4D5452545241494EL,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT).enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName,"MTR cable acceptance "+runId,settings);return;
            }
            if(client.getIntegratedServer()!=null)assertOwnedServer(client.getIntegratedServer());
            if(stage==Stage.WORLD) {
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                playerId=client.thePlayer.getUniqueID();report.addProperty("player_uuid",playerId.toString());advance(Stage.SETUP);return;
            }
            require(client.theWorld!=null&&client.thePlayer!=null&&client.getIntegratedServer()!=null,"Owned cable world/player disappeared");
            if(client.gameSettings.pauseOnLostFocus){client.gameSettings.pauseOnLostFocus=false;client.gameSettings.saveOptions();}
            if(stage==Stage.DASHBOARD&&client.currentScreen instanceof org.mtr.mod.screen.DashboardScreen){dashboardObserved=true;report.addProperty("dashboard_native_screen_class",client.currentScreen.getClass().getName());}
            if(client.currentScreen!=null)client.displayGuiScreen(null);
            KeyBinding.unPressAllKeys();
            Stage current=stage;
            ClientSample previous=clientSample;
            if(current==Stage.BOARD) {
                if(previous!=null&&previous.vehiclePresent) {
                    if(!clientBoardingAuthorized||client.thePlayer.posX>previous.centerX+.5)key(client.gameSettings.keyBindForward,client.thePlayer.posX>previous.centerX-.2);
                    if(!clientBoardingAuthorized&&client.thePlayer.posX<=previous.centerX-.2)key(client.gameSettings.keyBindBack,true);
                }
            } else if(current==Stage.EXIT&&previous!=null) {
                if(previous.riding&&!exitAligned) {
                    if(previous.doorway!=null) {
                        // A pulse is followed by quiet native input ticks before the next decision.
                        boolean quiet=Math.abs(previous.nativeInput.get("entity_move_strafing").getAsDouble())<.01
                            &&Math.abs(previous.nativeInput.get("entity_move_forward").getAsDouble())<.01;
                        exitSettleTicks=quiet?exitSettleTicks+1:0;
                        if(exitSettleTicks>=2) {
                            int strafe=exitStrafeDirection(previous.originalRiding.get("ridingVehicleZ").getAsDouble(),
                                previous.doorway.getMinZMapped(),previous.doorway.getMaxZMapped(),RenderVehicleHelper.HALF_PLAYER_WIDTH);
                            if(strafe==0)exitAligned=true;
                            else key(strafe>0?client.gameSettings.keyBindLeft:client.gameSettings.keyBindRight,true);
                            exitSettleTicks=0;
                        }
                    }
                } else if(client.thePlayer.posX<previous.centerX+2.4)key(client.gameSettings.keyBindBack,true);
            }
            else if(current==Stage.GRAVITY&&jumpClientTicks++<2)key(client.gameSettings.keyBindJump,true);
            if(clientAcknowledged!=current&&configureClient(current))clientAcknowledged=current;
            clientSample=new ClientSample(client,++clientTicks);
            if(clientTicks%100==0)System.out.println("MTR_CABLE_PROBE_PROGRESS "+stage+" tick="+clientTicks);
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null)return;
        Stage current=stage;if(current==Stage.MENU||current==Stage.WORLD||current==Stage.CLOSE||current==Stage.FINISHED)return;
        try {
            MinecraftServer server=MinecraftServer.getServer();assertOwnedServer(server);EntityPlayerMP player=actualPlayer(server);
            if(player==null){require(current==Stage.SETUP&&++phaseTicks<200,"Actual server passenger missing");return;}
            WorldServer world=server.worldServerForDimension(0);require(player.worldObj==world,"Passenger/stand world mismatch");
            require(System.currentTimeMillis()-stageStartedAt<((current==Stage.SCHEDULE||current==Stage.PICKUP_A||current==Stage.RIDE)?180000:current==Stage.CHUNKS?90000:60000),"Cable phase deadline in "+current);++phaseTicks;
            if(current==Stage.SETUP) {
                if(!packetObserverRequested){report.add("initial_server_player",playerPosition(player));player.setGameType(WorldSettings.GameType.CREATIVE);player.capabilities.isFlying=false;player.sendPlayerAbilities();installPacketObserver(player.playerNetServerHandler.netManager);packetObserverRequested=true;return;}
                if(!packetObserverReady)return;setupTeleport(player,16.5,4,48.5,0);advance(Stage.CHUNKS);return;
            }
            if(current==Stage.CHUNKS) {
                boolean ready=true;JsonArray chunks=new JsonArray();for(int x=-1;x<=2;x++)for(int z=-1;z<=7;z++){JsonObject chunk=chunkStatus(world,x,z);chunks.add(chunk);ready&=chunk.get("ready").getAsBoolean();}
                report.add("stand_chunk_last_wait",chunks);ClientSample client=clientSample;
                ready&=client!=null&&Math.abs(client.x-16.5)<.2&&Math.abs(client.z-48.5)<.2;
                readyChunkTicks=ready?readyChunkTicks+1:0;if(readyChunkTicks<2)return;
                buildStand(world,player);simulator=actualSimulator();advance(Stage.DASHBOARD);
                Item item=originalItem("dashboard_3",org.mtr.mod.Items.CABLE_CAR_DASHBOARD.get().data);ItemStack stack=new ItemStack(item);player.inventory.mainInventory[0]=stack;item.onItemRightClick(stack,world,player);return;
            }
            readCoreSoon();ClientSample client=clientSample;CoreSample core=coreSample;require(client!=null,"Actual client sample missing");
            if(core!=null)require(System.currentTimeMillis()-core.at<5000,"Original simulator stopped publishing read-only samples");
            observe(world,player,client,core);
            if(core==null)return;
            if(current==Stage.DASHBOARD&&core.railsComplete&&client.fixtureReady&&dashboardObserved&&client.dashboardSavedRailsReady){sidingId=core.siding;platformAId=core.platformA;platformBId=core.platformB;require(sidingId!=0&&platformAId!=0&&platformBId!=0,"Original saved rail IDs missing");if(!captureDone("stand"))return;player.inventory.mainInventory[0]=null;advance(Stage.AREAS);}
            else if(current==Stage.AREAS&&clientAcknowledged==current&&core.areasConfigured)advance(Stage.PLATFORMS);
            else if(current==Stage.PLATFORMS&&clientAcknowledged==current&&core.platformsConfigured)advance(Stage.ROUTE);
            else if(current==Stage.ROUTE&&clientAcknowledged==current&&core.routeConfigured)advance(Stage.SIDING);
            else if(current==Stage.SIDING&&clientAcknowledged==current&&core.sidingConfigured)advance(Stage.DEPOT);
            else if(current==Stage.DEPOT&&clientAcknowledged==current&&core.depotConfigured)advance(Stage.GENERATE);
            else if(current==Stage.GENERATE&&clientAcknowledged==current) {
                if(core.generationFailed)throw new AssertionError("Original route generation failed: "+core.depotJson);
                if(!core.generated)return;report.add("generated_original_depot",core.depotJson);report.add("generated_siding_paths",core.sidingJson);advance(Stage.SCHEDULE);
            } else if(current==Stage.SCHEDULE) {
                if(!atPlatform(client,core,platformAId)||client.vehicle!=candidateVehicleId||client.door<.95||client.platformRemaining<8||!client.resourceReady||!client.shouldRender)return;vehicleId=core.vehicle;
                report.addProperty("naturally_selected_continuous_cabin_id",Long.toString(vehicleId));report.addProperty("natural_continuous_startup_observed",true);advance(Stage.PICKUP_A);
            } else if(current==Stage.PICKUP_A) {
                if(!atPlatform(client,core,platformAId)||!client.resourceReady||!client.shouldRender||client.door<.95)return;
                if(setupTeleports<2){require(!client.riding&&!PlayerPhysics.authorized(player),"Final setup would move an existing rider");pickupPose=client.pickup;require(pickupPose!=null&&Math.abs(pickupPose.y-7)<.02&&pickupPose.x>1&&pickupPose.x<=4&&pickupPose.z>=0&&pickupPose.z<=17,"Actual model floor differs from physical platform");setupTeleport(player,pickupPose.x,pickupPose.y,pickupPose.z,90);report.add("pickup_actual_geometry",client.geometry());return;}
                if(Math.abs(client.x-pickupPose.x)>.15||Math.abs(client.y-pickupPose.y)>.15||Math.abs(client.z-pickupPose.z)>.15)return;
                if(!client.emptyHand||player.getHeldItem()!=null)return;
                if(!captureDone("pickupA"))return;boardingS08=s08.get();advance(Stage.BOARD);
            } else if(current==Stage.BOARD) {
                require(atPlatform(client,core,platformAId)&&client.door>.2,"Natural boarding/authorization missed original platform A door window");
                require(client.emptyHand&&player.getHeldItem()==null,"Natural boarding requires empty hands");
                if(!client.riding||!core.rider||!PlayerPhysics.authorized(player))return;
                if(Math.abs(client.x-client.centerX)>=.9)return;
                boardAnchor=true;motionS08=s08.get();boardedZ=client.z;report.addProperty("natural_render_boarding_authorized",true);advance(Stage.DEPART);
            } else if(current==Stage.DEPART) {
                assertPassenger(player,client,core);if(client.activePlatform!=platformAId&&core.activePlatform!=platformAId&&client.moving&&core.moving&&Math.abs(client.centerZ-boardedZ)>1)advance(Stage.RIDE);
            } else if(current==Stage.RIDE) {
                assertPassenger(player,client,core);
                if(!movingCaptured&&client.moving&&Math.abs(client.centerZ-boardedZ)>2)movingCaptured=captureDone("moving");
                if(atPlatform(client,core,platformBId)&&client.door>.95){require(movingCaptured&&Math.abs(client.z-boardedZ)>8,"No captured real passenger journey");advance(Stage.STOP_B);}
            } else if(current==Stage.STOP_B) {
                assertPassenger(player,client,core);require(atPlatform(client,core,platformBId),"Cable cabin left destination before exit");if(captureDone("arrivalB"))advance(Stage.EXIT);
            } else if(current==Stage.EXIT) {
                require(atPlatform(client,core,platformBId)&&client.door>.2,"Natural exit missed original destination door window");
                if(!client.anyRiding&&client.x>client.centerX+1.9)advance(Stage.DISMOUNT);
            } else if(current==Stage.DISMOUNT) {
                if(client.anyRiding||core.allPlayerRiders!=0||PlayerPhysics.authorized(player)||player.noClip||client.noClip)return;
                require(Math.abs(PlayerPhysics.gravity(.08,player)-.08)<1E-10&&!client.flying&&!player.capabilities.isFlying,"Native dismount did not restore ordinary player physics");
                jumpBase=client.y;jumpPeak=client.y;serverJumpPeak=player.boundingBox.minY;jumpClientTicks=0;advance(Stage.GRAVITY);
            } else if(current==Stage.GRAVITY) {
                require(!client.anyRiding&&core.allPlayerRiders==0&&!PlayerPhysics.authorized(player)&&!player.noClip&&!client.flying&&!player.capabilities.isFlying,"Rider flags returned after native exit");
                jumpPeak=Math.max(jumpPeak,client.y);serverJumpPeak=Math.max(serverJumpPeak,player.boundingBox.minY);
                if(client.y<jumpPeak-.1&&client.motionY<-.02)gravityFalling=true;
                if(phaseTicks>10&&gravityFalling&&client.onGround&&player.onGround){
                    require(jumpPeak>jumpBase+.2&&serverJumpPeak>jumpBase+.2,"No real client/server native jump after dismount");
                    if(!captureDone("dismounted"))return;
                    require(!client.anyRiding&&core.allPlayerRiders==0&&!PlayerPhysics.authorized(player),"Passenger reattached to continuous traffic after exit");
                    snapshotStand(world);expectedCore.putAll(core.fullData);report.add("pre_save_core",core.json());advance(Stage.CLOSE);
                }
            }
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null||shotRequest==shotAcknowledged)return;
        try {Minecraft client=Minecraft.getMinecraft();if(client.theWorld==null||client.thePlayer==null||++renderedFrames<4)return;
            if("moving".equals(shotLabel)&&(clientSample==null||!clientSample.moving))return;
            capture(client,shotLabel);shotAcknowledged=shotRequest;
        }catch(Throwable problem){fail(problem);}
    }
    private void capture(Minecraft client,String label)throws Exception {
        require(client.currentScreen==null,"World framebuffer is not rendered");String name="mtr-cable-probe-"+runId+"-"+label+".png";
        ScreenShotHelper.saveScreenshot(client.mcDataDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());File file=new File(new File(client.mcDataDir,"screenshots"),name);
        require(file.isFile()&&file.length()>100,"Actual train framebuffer was not written");JsonObject value=new JsonObject();value.addProperty("phase",label);value.addProperty("path",file.getCanonicalPath());value.addProperty("render_frames_after_request",renderedFrames);value.addProperty("client_tick",clientTicks);screenshots.add(value);
    }
    private void advance(Stage next) {
        JsonObject value=new JsonObject();value.addProperty("from",stage.name());value.addProperty("to",next.name());value.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt);transitions.add(value);
        phaseTicks=stillTicks=0;stageStartedAt=System.currentTimeMillis();stage=next;
    }
    private void setupTeleport(EntityPlayerMP player,double x,double y,double z,float yaw) {
        require((stage==Stage.SETUP||stage==Stage.PICKUP_A)&&setupTeleports<2&&!PlayerPhysics.authorized(player),"Cable teleport forbidden outside strict setup");
        player.playerNetServerHandler.setPlayerLocation(x,y,z,yaw,10);setupTeleports++;
    }
    private static void key(KeyBinding binding,boolean down){KeyBinding.setKeyBindState(binding.getKeyCode(),down);}
    private static void send(UpdateDataRequest request){InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(request));}
    private boolean configureClient(Stage current)throws Exception {
        MinecraftClientData data=MinecraftClientData.getDashboardInstance();
        if(current==Stage.AREAS) {
            Station a=new Station(data),b=new Station(data);Depot d=new Depot(TransportMode.CABLE_CAR,data);
            a.setName(A_NAME);a.setColor(0x3366CC);a.setCorners(new Position(-4,Long.MIN_VALUE,-4),new Position(4,Long.MAX_VALUE,20));
            b.setName(B_NAME);b.setColor(0x33AA66);b.setCorners(new Position(-4,Long.MIN_VALUE,76),new Position(4,Long.MAX_VALUE,100));
            d.setName(D_NAME);d.setColor(0xAA6633);d.setCorners(new Position(28,Long.MIN_VALUE,44),new Position(36,Long.MAX_VALUE,68));
            stationAId=a.getId();stationBId=b.getId();depotId=d.getId();send(new UpdateDataRequest(data).addStation(a).addStation(b).addDepot(d));return true;
        } else if(current==Stage.PLATFORMS) {
            Platform a=data.platformIdMap.get(platformAId),b=data.platformIdMap.get(platformBId);if(a==null||b==null)return false;
            a.setName("1");b.setName("1");a.setDwellTime(20000);b.setDwellTime(20000);require(a.getDwellTime()==1&&b.getDwellTime()==1,"Original continuous platform dwell must be 1");send(new UpdateDataRequest(data).addPlatform(a).addPlatform(b));return true;
        } else if(current==Stage.ROUTE) {
            if(!data.platformIdMap.containsKey(platformAId)||!data.platformIdMap.containsKey(platformBId))return false;
            Route route=new Route(TransportMode.CABLE_CAR,data);route.setName(R_NAME);route.setColor(0x3366CC);route.setCircularState(Route.CircularState.NONE);
            for(long id:new long[]{platformAId,platformBId}){RoutePlatformData row=new RoutePlatformData(id);route.getRoutePlatforms().add(row);row.writePlatformCache(route,data.platformIdMap);}
            routeId=route.getId();send(new UpdateDataRequest(data).addRoute(route));return true;
        } else if(current==Stage.SIDING) {
            Siding siding=data.sidingIdMap.get(sidingId);if(siding==null)return false;final VehicleResource[] selected={null};
            CustomResourceLoader.getVehicleById(TransportMode.CABLE_CAR,CAR,details->{if(CAR.equals(details.left().getId()))selected[0]=details.left();});
            if(selected[0]==null)return false;VehicleResource resource=selected[0];if(resource.getCachedVehicleResource(0,1,false)==null)return false;
            ObjectArrayList<VehicleCar> cars=new ObjectArrayList<>();cars.add(new VehicleCar(resource.getId(),resource.getLength(),resource.getWidth(),resource.getBogie1Position(),resource.getBogie2Position(),resource.getCouplingPadding1(),resource.getCouplingPadding2()));
            expectedCarData=full(cars.get(0));report.add("original_resource_vehicle_car",copyJson(expectedCarData));require(correctCarProfile(cars.get(0)),"Selected stock cable dimensions differ");
            siding.setVehicleCars(cars);siding.setMaxVehicles(0);siding.setAcceleration(Siding.ACCELERATION_DEFAULT);siding.setDeceleration(Siding.ACCELERATION_DEFAULT);
            send(new UpdateDataRequest(data).addSiding(siding));return true;
        } else if(current==Stage.DEPOT) {
            Depot depot=data.depotIdMap.get(depotId);if(depot==null||!data.routeIdMap.containsKey(routeId))return false;
            depot.getRouteIds().clear();depot.getRouteIds().add(routeId);depot.setRepeatInfinitely(false);depot.setUseRealTime(false);depot.getRealTimeDepartures().clear();
            require(Depot.CONTINUOUS_MOVEMENT_FREQUENCY==8000,"Original continuous auto departure frequency differs");
            send(new UpdateDataRequest(data).addDepot(depot));report.addProperty("continuous_auto_departure_interval_ms",Depot.CONTINUOUS_MOVEMENT_FREQUENCY);report.addProperty("continuous_effective_platform_dwell",1);return true;
        } else if(current==Stage.GENERATE) {
            if(coreSample==null)return false;generationPreviousMillis=coreSample.generationMillis;generationRequestedAt=System.currentTimeMillis();DepotOperationByIds operation=new DepotOperationByIds();operation.addDepotId(depotId);InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDepotGenerate(operation));return true;
        }
        return false;
    }
    private static Position nodePosition(int index){return new Position(NODES[index][0],12,NODES[index][1]);}
    private static Block nodeBlock(int index){return NODES[index][2]==1?org.mtr.mod.Blocks.CABLE_CAR_NODE_LOWER.get().data:NODES[index][2]==2?org.mtr.mod.Blocks.CABLE_CAR_NODE_UPPER.get().data:org.mtr.mod.Blocks.CABLE_CAR_NODE_STATION.get().data;}
    private void buildStand(WorldServer world,EntityPlayerMP player) {
        world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.setWorldTime(6000);world.difficultySetting=EnumDifficulty.PEACEFUL;
        for(int z=-16;z<=114;z++)for(int x=-4;x<=36;x++)for(int y=4;y<=14;y++)world.setBlockToAir(x,y,z);
        player.rotationYaw=90;
        for(int start:new int[]{0,80})for(int z=start;z<=start+16;z++)for(int x=1;x<=3;x++){
            world.setBlock(x,4,z,net.minecraft.init.Blocks.stone);world.setBlock(x,5,z,net.minecraft.init.Blocks.stone);
            ItemStack stack=new ItemStack(originalItem("platform",Item.getItemFromBlock(org.mtr.mod.Blocks.PLATFORM.get().data)));
            use(world,player,stack,x,5,z,1,.5F);require(stack.stackSize==0,"Original cable dock item did not consume one block");
        }
        for(int i=0;i<NODES.length;i++){
            int x=NODES[i][0],z=NODES[i][1];world.setBlock(x,11,z,net.minecraft.init.Blocks.stone);player.rotationYaw=i==7||i==14?90:0;
            String name=NODES[i][2]==1?"cable_car_node_lower":NODES[i][2]==2?"cable_car_node_upper":"cable_car_node_station";
            ItemStack stack=new ItemStack(originalItem(name,Item.getItemFromBlock(nodeBlock(i))));use(world,player,stack,x,11,z,1,.5F);
            require(stack.stackSize==0&&world.getBlock(x,12,z)==nodeBlock(i),"Original cable node item did not place exact stock node");
            require(Math.abs(BlockNode.getAngle(state(world,x,12,z))-(i==7||i==14?0:90))<.001,"Original continuous node yaw/axis differs");
            world.setBlockToAir(x,11,z);require(world.isAirBlock(x,11,z),"Temporary node placement support survived");
        }
        for(int edge=0;edge<NODES.length;edge++){
            int next=(edge+1)%NODES.length;String name=edge==0||edge==5?"rail_connector_platform":edge==10?"rail_connector_siding":"rail_connector_cable_car";
            Item expected=edge==0||edge==5?org.mtr.mod.Items.RAIL_CONNECTOR_PLATFORM.get().data:edge==10?org.mtr.mod.Items.RAIL_CONNECTOR_SIDING.get().data:org.mtr.mod.Items.RAIL_CONNECTOR_CABLE_CAR.get().data;
            Item item=originalItem(name,expected);require(item instanceof org.mtr.mod.item.ItemRailModifier,"Original continuous connector missing");ItemStack connector=new ItemStack(item);
            use(world,player,connector,NODES[edge][0],12,NODES[edge][1],1,.5F);
            require(connector.hasTagCompound()&&connector.getTagCompound().getLong(ItemBlockClickingBase.TAG_POS)==new BlockPos(NODES[edge][0],12,NODES[edge][1]).asLong(),"Original cable connector first-click state missing");
            use(world,player,connector,NODES[next][0],12,NODES[next][1],1,.5F);
            require(!connector.getTagCompound().hasKey(ItemBlockClickingBase.TAG_POS)&&connector.stackSize==1,"Original cable connector pair failed");
        }
        player.inventory.mainInventory[0]=null;player.inventory.currentItem=0;
        report.addProperty("original_native_node_count",15);report.addProperty("original_native_platform_block_count",102);report.addProperty("temporary_supports_removed",15);
    }
    private static boolean completeRails(Iterable<Rail> rails) {
        boolean[] seen=new boolean[15];int count=0;
        for(Rail rail:rails){int edge=-1;for(int i=0;i<15;i++)if(rail.getHexId().equals(TwoPositionsBase.getHexId(nodePosition(i),nodePosition((i+1)%15))))edge=i;
            if(edge<0||seen[edge]||rail.getTransportMode()!=MODE||!rail.isValid()||rail.isSiding()!=(edge==10)||rail.isPlatform()!=(edge==0||edge==5)||rail.canTurnBack())return false;
            if(edge==0||edge==5||edge==10){if(Math.abs(rail.railMath.getLength()-16)>.01)return false;}
            else {double forward=rail.getSpeedLimitMetersPerMillisecond(nodePosition(edge)),backward=rail.getSpeedLimitMetersPerMillisecond(nodePosition((edge+1)%15));double speed=(NODES[edge][2]==0&&NODES[(edge+1)%15][2]==0?2:30)/3600D;
                if(Math.abs(forward-speed)>1E-9||Math.abs(backward)>1E-9)return false;}
            seen[edge]=true;count++;
        }return count==15;
    }
    private static Vector forwards(PositionAndRotation pose,Vector point){return pose.transformForwards(point,Vector::rotateX,Vector::rotateY,Vector::add);}
    private static Vector backwards(PositionAndRotation pose,Vector point){return pose.transformBackwards(point,Vector::rotateX,Vector::rotateY,Vector::add);}
    private static Vector pickupPoint(PositionAndRotation pose,Box door) {
        double x=(door.getMinXMapped()+door.getMaxXMapped())/2,z=(door.getMinZMapped()+door.getMaxZMapped())/2;
        return forwards(pose,new Vector(x+Math.copySign(.75,x),door.getMaxYMapped(),z));
    }
    // Setup yaw90 (or equivalent -270) faces west; left strafe decreases cabin-local Z at A/B.
    static int exitStrafeDirection(double localZ,double minZ,double maxZ,double halfPlayerWidth) {
        if(!Double.isFinite(localZ)||!Double.isFinite(minZ)||!Double.isFinite(maxZ)||!Double.isFinite(halfPlayerWidth)
            ||halfPlayerWidth<=0||maxZ-minZ<=halfPlayerWidth*2)throw new IllegalArgumentException("Invalid cable exit doorway");
        double target=(minZ+maxZ)/2,clearance=(maxZ-minZ)/2-halfPlayerWidth;
        return localZ>target+clearance?1:localZ<target-clearance?-1:0;
    }
    private static PathData activeSegment(Vehicle vehicle) {
        double progress=Utilities.getJsonObjectFromData(vehicle).get("railProgress").getAsDouble();
        for(PathData path:vehicle.vehicleExtraData.immutablePath)if(progress>=path.getStartDistance()&&progress<path.getEndDistance())return path;
        return null;
    }
    private static long activePlatform(Vehicle vehicle){PathData path=activeSegment(vehicle);return path!=null&&path.getDwellTime()>0?path.getSavedRailBaseId():0;}
    private static double platformRemaining(Vehicle vehicle){PathData path=activeSegment(vehicle);return path==null||path.getDwellTime()<=0?0:path.getEndDistance()-Utilities.getJsonObjectFromData(vehicle).get("railProgress").getAsDouble();}
    private static boolean atPlatform(ClientSample client,CoreSample core,long platform) {
        // Continuous vehicles keep moving: board/exit is defined by the active platform segment and door.
        return platform!=0&&client.carConfigured&&core.carConfigured&&client.vehiclePresent&&core.vehiclePresent&&client.vehicle==core.vehicle&&core.onRoute&&client.activePlatform==platform&&core.activePlatform==platform;
    }
    private void assertPassenger(EntityPlayerMP player,ClientSample client,CoreSample core) {
        require(client.carConfigured&&core.carConfigured,"Actual passenger train lost original Ngong Ping 360 cable car");
        require(client.emptyHand&&player.getHeldItem()==null,"Natural train passenger must have empty hands");
        require(client.riding&&core.rider&&client.vehicle==vehicleId&&core.vehicle==vehicleId&&PlayerPhysics.authorized(player),"Original train passenger lost client/core/server binding");
        require(player.noClip&&Math.abs(PlayerPhysics.gravity(.08,player))<1E-10&&!client.flying&&!player.capabilities.isFlying,"Passenger native physics/flight mismatch");
        require(Math.abs(client.y-client.floorY)<.4&&Math.abs(player.boundingBox.minY-client.floorY)<1.5&&Math.abs(player.posX-client.x)<1&&Math.abs(player.posZ-client.z)<1,"Actual client/server passenger stopped following original car");
        require(s08.get()-motionS08<=2,"Repeated native S08 correction during train ride");
    }
    private void observe(WorldServer world,EntityPlayerMP player,ClientSample client,CoreSample core) {
        clientBoardingAuthorized=PlayerPhysics.authorized(player);JsonObject value=client.json();value.addProperty("phase",stage.name());value.addProperty("phase_tick",phaseTicks);value.addProperty("server_world_tick",world.getTotalWorldTime());value.add("server_player",playerPosition(player));
        value.addProperty("server_authorized",clientBoardingAuthorized);value.addProperty("server_gravity_for_0_08",PlayerPhysics.gravity(.08,player));value.add("server_riding_view",serverRidingDiagnostics(player.getUniqueID(),vehicleId==0?client.vehicle:vehicleId,world.getTotalWorldTime(),player.dimension));
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
                actual.pipeline().addBefore("packet_handler", "mtr_cable_probe_observer", new ChannelDuplexHandler() {
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
        ridingLogObserver = new org.apache.logging.log4j.core.appender.AbstractAppender("mtr_cable_probe_riding_" + System.identityHashCode(this), null, null) {
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
        runId = UUID.randomUUID().toString(); saveName = "MTR-cable-acceptance-" + runId;
        Path saves = client.mcDataDir.toPath().resolve("saves"); Files.createDirectories(saves);
        require(!Files.isSymbolicLink(saves) && saves.toRealPath().getParent().equals(client.mcDataDir.toPath().toRealPath()), "Copied saves directory escaped client");
        Path reserved = Files.createDirectory(saves.resolve(saveName));
        saveDirectory = reserved.toFile();
        Files.write(reserved.resolve("mtr-cable-probe-owner.txt"), runId.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        copyVerified = true;
        report.addProperty("run_id", runId); report.addProperty("save_directory", saveDirectory.getCanonicalPath());
        report.addProperty("client_directory", client.mcDataDir.getCanonicalPath());
        report.addProperty("new_uuid_save_reserved", true); report.addProperty("existing_save_loaded", false);
        report.addProperty("scenario", "original cable items + Dashboard DTO packets; directed continuous loop; stable cabin A-to-B; natural render rider/exit; owned Anvil + core MessagePack");
    }

    private void assertOwnedServer(MinecraftServer server) throws Exception {
        require(copyVerified && server != null && saveDirectory != null && saveName.equals(server.getFolderName()), "Refusing to mutate/close a foreign server");
        Path marker = saveDirectory.toPath().resolve("mtr-cable-probe-owner.txt");
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
            for(int i=0;i<NODES.length;i++){int x=NODES[i][0],z=NODES[i][1];if(world.getBlock(x,12,z)!=nodeBlock(i)||!"true".equals(state(world,x,12,z).data.getSerializedProperties().get("is_connected"))||!world.isAirBlock(x,11,z))return false;}
            for(int start:new int[]{0,80})for(int z=start;z<=start+16;z++)for(int x=1;x<=3;x++)if(world.getBlock(x,6,z)!=org.mtr.mod.Blocks.PLATFORM.get().data)return false;
            return true;
        }catch(IllegalArgumentException|IllegalStateException waitingForNativeState){return false;}
    }
    private final class ClientSample {
        final int tick,cabinCount;final long vehicle,activePlatform;final VehicleExtension sourceVehicle;final double x,y,z,motionY,centerX,centerY,centerZ,yaw,pitch,floorY,door,platformRemaining;
        final boolean onGround,noClip,flying,riding,anyRiding,moving,vehiclePresent,resourceReady,shouldRender,fixtureReady,dashboardSavedRailsReady,emptyHand,carConfigured;
        final Vector pickup;final JsonObject nativeInput,originalRiding,vehicleJson,interest;final Box doorway;
        ClientSample(Minecraft client,int tick) {
            this.tick=tick;x=client.thePlayer.posX;y=client.thePlayer.boundingBox.minY;z=client.thePlayer.posZ;motionY=client.thePlayer.motionY;
            onGround=client.thePlayer.onGround;noClip=client.thePlayer.noClip;flying=client.thePlayer.capabilities.isFlying;emptyHand=client.thePlayer.getHeldItem()==null;
            fixtureReady=fixtureReady(client.theWorld);dashboardSavedRailsReady=MinecraftClientData.getDashboardInstance().platforms.size()==2&&MinecraftClientData.getDashboardInstance().sidings.size()==1;
            nativeInput=new JsonObject();nativeInput.addProperty("entity_move_forward",client.thePlayer.moveForward);nativeInput.addProperty("entity_move_strafing",client.thePlayer.moveStrafing);
            if(client.thePlayer.movementInput!=null){nativeInput.addProperty("movement_input_move_forward",client.thePlayer.movementInput.moveForward);nativeInput.addProperty("movement_input_move_strafe",client.thePlayer.movementInput.moveStrafe);}
            nativeInput.addProperty("rotation_yaw",client.thePlayer.rotationYaw);nativeInput.addProperty("native_focus",client.inGameHasFocus);nativeInput.addProperty("native_paused",client.isGamePaused());
            originalRiding=originalRidingDiagnostics();interest=new JsonObject();BlockPos camera=org.mtr.mapping.holder.MinecraftClient.getInstance().getGameRendererMapped().getCamera().getBlockPos();
            interest.addProperty("render_distance_chunks",MinecraftClientHelper.getRenderDistance());interest.addProperty("original_data_request_radius",MinecraftClientHelper.getRenderDistance()*16L);interest.addProperty("camera_x",camera.getX());interest.addProperty("camera_y",camera.getY());interest.addProperty("camera_z",camera.getZ());
            VehicleExtension found=null;int cabins=0;boolean attached=originalRiding.has("ridingVehicleId")&&originalRiding.get("ridingVehicleId").getAsLong()!=0;long target=vehicleId==0?candidateVehicleId:vehicleId;
            for(VehicleExtension value:MinecraftClientData.getInstance().vehicles){cabins++;attached|=VehicleRidingMovement.isRiding(value.getId());if(value.getId()==target)found=value;}
            if(vehicleId==0&&(found==null||activePlatform(found)!=platformAId||platformRemaining(found)<8)){
                found=null;
                for(VehicleExtension value:MinecraftClientData.getInstance().vehicles)if(correctCar(value)&&value.getIsOnRoute()&&activePlatform(value)==platformAId&&platformRemaining(value)>=9&&platformRemaining(value)<=13&&value.persistentVehicleData.getDoorValue()>.95&&(found==null||value.getId()<found.getId()))found=value;
                candidateVehicleId=found==null?0:found.getId();
            }
            cabinCount=cabins;anyRiding=attached;sourceVehicle=found;vehiclePresent=found!=null;
            if(found==null){vehicle=activePlatform=0;centerX=centerY=centerZ=yaw=pitch=floorY=door=platformRemaining=0;riding=moving=resourceReady=shouldRender=carConfigured=false;pickup=null;doorway=null;vehicleJson=new JsonObject();}
            else {
                final VehicleExtension actual=found;carConfigured=correctCar(actual);vehicle=actual.getId();activePlatform=activePlatform(actual);platformRemaining=platformRemaining(actual);riding=VehicleRidingMovement.isRiding(vehicle);moving=actual.isMoving();door=actual.persistentVehicleData.getDoorValue();vehicleJson=Utilities.getJsonObjectFromData(actual);
                shouldRender=actual.persistentVehicleData.rayTracing.length>0&&actual.persistentVehicleData.rayTracing[0];
                final PositionAndRotation[] pose={null};
                // Read-only unsmoothed getter, with the same pitch flags used by the original renderer.
                actual.getVehicleCarsAndPositions().stream().findFirst().ifPresent(car->{ObjectArrayList<PositionAndRotation> bogies=new ObjectArrayList<>();car.right().forEach(pair->bogies.add(new PositionAndRotation(pair.left(),pair.right(),true)));pose[0]=new PositionAndRotation(bogies,car.left(),MODE.hasPitchAscending||MODE.hasPitchDescending);});
                final VehicleResourceCache[] cache={null};CustomResourceLoader.getVehicleById(MODE,CAR,details->{if(CAR.equals(details.left().getId()))cache[0]=details.left().getCachedVehicleResource(0,1,false);});
                Box west=null;Vector outside=null;double westX=Double.POSITIVE_INFINITY;
                if(pose[0]!=null&&cache[0]!=null&&!cache[0].floors.isEmpty())for(Box candidate:cache[0].doorways){Vector point=pickupPoint(pose[0],candidate);if(point.x<westX&&RenderVehicleHelper.canOpenDoors(candidate,pose[0],door*2)){westX=point.x;west=candidate;outside=point;}}
                doorway=west;pickup=outside;resourceReady=carConfigured&&pose[0]!=null&&cache[0]!=null&&!cache[0].floors.isEmpty()&&west!=null;
                centerX=pose[0]==null?0:pose[0].position.x;centerY=pose[0]==null?0:pose[0].position.y;centerZ=pose[0]==null?0:pose[0].position.z;yaw=pose[0]==null?0:pose[0].yaw;pitch=pose[0]==null?0:pose[0].pitch;
                double actualFloor=Double.NaN,nearest=Double.POSITIVE_INFINITY;
                if(pose[0]!=null&&cache[0]!=null){Vector local=backwards(pose[0],new Vector(x,y,z));for(Box floor:cache[0].floors){double dx=Math.max(floor.getMinXMapped()-local.x,Math.max(0,local.x-floor.getMaxXMapped())),dz=Math.max(floor.getMinZMapped()-local.z,Math.max(0,local.z-floor.getMaxZMapped()));double distance=dx*dx+dz*dz;if(distance<nearest){nearest=distance;actualFloor=forwards(pose[0],new Vector(local.x,floor.getMaxYMapped(),local.z)).y;}}}
                floorY=actualFloor;
            }
        }
        JsonObject geometry() {
            JsonObject result=new JsonObject();result.addProperty("source","original_getVehicleCarsAndPositions_unsmoothed_and_cached_floor");result.addProperty("center_x",centerX);result.addProperty("center_y",centerY);result.addProperty("center_z",centerZ);result.addProperty("yaw",yaw);result.addProperty("pitch",pitch);if(Double.isFinite(floorY))result.addProperty("floor_y",floorY);
            if(doorway!=null){JsonObject box=new JsonObject();box.addProperty("min_x",doorway.getMinXMapped());box.addProperty("max_x",doorway.getMaxXMapped());box.addProperty("min_z",doorway.getMinZMapped());box.addProperty("max_z",doorway.getMaxZMapped());box.addProperty("y",doorway.getMaxYMapped());result.add("original_local_doorway",box);}
            if(pickup!=null){JsonObject value=new JsonObject();value.addProperty("x",pickup.x);value.addProperty("feet_y",pickup.y);value.addProperty("z",pickup.z);result.add("derived_external_setup_pose",value);}return result;
        }
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("client_tick",tick);result.addProperty("client_x",x);result.addProperty("client_feet_y",y);result.addProperty("client_z",z);result.addProperty("client_motion_y",motionY);result.addProperty("client_on_ground",onGround);result.addProperty("client_no_clip",noClip);result.addProperty("client_flying",flying);result.addProperty("client_riding",riding);result.addProperty("client_riding_any_cabin",anyRiding);result.addProperty("client_cabin_count",cabinCount);result.addProperty("empty_hand",emptyHand);result.addProperty("vehicle_id",Long.toString(vehicle));result.addProperty("active_platform",Long.toString(activePlatform));result.addProperty("platform_remaining_m",platformRemaining);result.addProperty("vehicle_moving",moving);result.addProperty("actual_ngong_ping_360_cable_car_configured",carConfigured);result.addProperty("door",door);result.addProperty("original_renderer_should_render",shouldRender);result.addProperty("actual_resource_ready",resourceReady);result.add("car_geometry",geometry());result.add("native_input",nativeInput);result.add("original_riding_state",originalRiding);result.add("actual_client_interest",interest);result.add("original_client_vehicle",vehicleJson);return result;}
    }
    private final class CoreSample {
        final long at=System.currentTimeMillis(),siding,platformA,platformB,vehicle,thisPlatform,nextPlatform,activePlatform,generationMillis;
        final boolean railsComplete,areasConfigured,platformsConfigured,routeConfigured,sidingConfigured,depotConfigured,generated,generationFailed,vehiclePresent,moving,onRoute,closeToDepot,rider,carConfigured;
        final int riderCount,cabinCount,allPlayerRiders;final JsonObject depotJson,sidingJson,vehicleJson;final Map<String,JsonObject> fullData=new LinkedHashMap<>();
        CoreSample(Simulator data)throws Exception {
            railsComplete=completeRails(data.rails);Platform a=null,b=null;Siding s=null;Station sa=null,sb=null;Depot d=null;Route r=null;
            for(Platform value:data.platforms){if(value.containsPos(nodePosition(0))&&value.containsPos(nodePosition(1)))a=value;if(value.containsPos(nodePosition(5))&&value.containsPos(nodePosition(6)))b=value;}
            for(Siding value:data.sidings)if(value.containsPos(nodePosition(10))&&value.containsPos(nodePosition(11)))s=value;
            for(Station value:data.stations){if(value.getId()==stationAId)sa=value;if(value.getId()==stationBId)sb=value;}
            for(Depot value:data.depots)if(value.getId()==depotId)d=value;for(Route value:data.routes)if(value.getId()==routeId)r=value;
            siding=s==null?0:s.getId();platformA=a==null?0:a.getId();platformB=b==null?0:b.getId();
            areasConfigured=sa!=null&&sb!=null&&d!=null&&a!=null&&b!=null&&s!=null&&a.area==sa&&b.area==sb&&s.area==d&&sa.getName().equals(A_NAME)&&sb.getName().equals(B_NAME)&&d.getName().equals(D_NAME)&&data.stations.size()==2&&data.depots.size()==1;
            platformsConfigured=areasConfigured&&a.getDwellTime()==1&&b.getDwellTime()==1;
            routeConfigured=r!=null&&r.getTransportMode()==MODE&&r.getRoutePlatforms().size()==2&&r.getRoutePlatforms().get(0).getPlatform()!=null&&r.getRoutePlatforms().get(1).getPlatform()!=null&&r.getRoutePlatforms().get(0).getPlatform().getId()==platformAId&&r.getRoutePlatforms().get(1).getPlatform().getId()==platformBId&&r.getCircularState()==Route.CircularState.NONE&&data.routes.size()==1;
            sidingConfigured=s!=null&&s.getTransportMode()==MODE&&s.getMaxVehicles()==0&&!s.getIsManual()&&s.getVehicleCars().size()==1&&correctCarProfile(s.getVehicleCars().get(0))&&expectedCarData!=null&&expectedCarData.equals(full(s.getVehicleCars().get(0)));
            depotConfigured=d!=null&&d.getTransportMode()==MODE&&d.getRouteIds().size()==1&&d.getRouteIds().getLong(0)==routeId&&!d.getRepeatInfinitely()&&!d.getUseRealTime()&&d.getRealTimeDepartures().isEmpty();
            depotJson=d==null?new JsonObject():full(d);sidingJson=s==null?new JsonObject():full(s);
            generationMillis=d==null?0:d.getLastGeneratedMillis();
            generated=d!=null&&s!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()==Depot.GeneratedStatus.SUCCESSFUL&&!d.getPath().isEmpty()&&!((Collection<?>)diagnosticField(s,"pathSidingToMainRoute")).isEmpty()&&!((Collection<?>)diagnosticField(s,"pathMainRouteToSiding")).isEmpty();
            generationFailed=d!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()!=Depot.GeneratedStatus.SUCCESSFUL;
            Vehicle found=null;int cabins=0;final int[] playerRiders={0};long target=vehicleId==0?candidateVehicleId:vehicleId;
            if(s!=null)for(Object value:(Iterable<?>)diagnosticField(s,"vehicles")){Vehicle cabin=(Vehicle)value;cabins++;require(correctCar(cabin),"Continuous siding emitted a different vehicle profile");if(cabin.getId()==target)found=cabin;cabin.vehicleExtraData.iterateRidingEntities(row->{if(row.uuid.equals(playerId))playerRiders[0]++;});}
            cabinCount=cabins;allPlayerRiders=playerRiders[0];vehiclePresent=found!=null;
            if(found==null){vehicle=thisPlatform=nextPlatform=activePlatform=0;moving=onRoute=closeToDepot=rider=carConfigured=false;riderCount=0;vehicleJson=new JsonObject();}
            else {carConfigured=correctCar(found);vehicle=found.getId();moving=found.isMoving();onRoute=found.getIsOnRoute();closeToDepot=found.closeToDepot();thisPlatform=found.vehicleExtraData.getThisPlatformId();nextPlatform=found.vehicleExtraData.getNextPlatformId();vehicleJson=Utilities.getJsonObjectFromData(found);activePlatform=activePlatform(found);final int[] count={0};final boolean[] actual={false};found.vehicleExtraData.iterateRidingEntities(row->{count[0]++;if(row.uuid.equals(playerId)&&row.getRidingCar()==0)actual[0]=true;});riderCount=count[0];rider=actual[0]&&riderCount==1&&allPlayerRiders==1;}
            for(Station value:data.stations)fullData.put("stations/"+value.getHexId(),full(value));for(Platform value:data.platforms)fullData.put("platforms/"+value.getHexId(),full(value));for(Siding value:data.sidings)fullData.put("sidings/"+value.getHexId(),full(value));for(Route value:data.routes)fullData.put("routes/"+value.getHexId(),full(value));for(Depot value:data.depots)fullData.put("depots/"+value.getHexId(),full(value));for(Rail value:data.rails)fullData.put("rails/"+value.getHexId(),full(value));
        }
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("rails_complete",railsComplete);result.addProperty("areas_configured",areasConfigured);result.addProperty("platforms_configured",platformsConfigured);result.addProperty("route_configured",routeConfigured);result.addProperty("siding_configured",sidingConfigured);result.addProperty("depot_configured",depotConfigured);result.addProperty("generated",generated);result.addProperty("vehicle_id",Long.toString(vehicle));result.addProperty("vehicle_moving",moving);result.addProperty("actual_ngong_ping_360_cable_car_configured",carConfigured);result.addProperty("on_route",onRoute);result.addProperty("close_to_depot",closeToDepot);result.addProperty("rider_count",riderCount);result.addProperty("all_cabins_player_rider_count",allPlayerRiders);result.addProperty("core_cabin_count",cabinCount);result.addProperty("actual_passenger",rider);result.addProperty("this_platform",Long.toString(thisPlatform));result.addProperty("next_platform",Long.toString(nextPlatform));result.addProperty("active_platform",Long.toString(activePlatform));result.add("vehicle",vehicleJson);return result;}
    }
    private static boolean correctCarProfile(VehicleCar car){return CAR.equals(car.getVehicleId())&&Math.abs(car.getLength()-2)<1E-6&&Math.abs(car.getWidth()-2)<1E-6&&Math.abs(car.getBogie1Position())<1E-6&&Math.abs(car.getBogie2Position())<1E-6;}
    private static boolean correctCar(Vehicle value) {
        if(value.getTransportMode()!=MODE||value.vehicleExtraData.immutableVehicleCars.size()!=1||expectedCarData==null)return false;
        VehicleCar car=value.vehicleExtraData.immutableVehicleCars.get(0);return correctCarProfile(car)&&expectedCarData.equals(full(car));
    }
    private static final class StoredBlock {
        final int x,y,z,id,metadata;final NBTTagCompound tag;final Map<String,String> properties;
        StoredBlock(int x,int y,int z,int id,int metadata,NBTTagCompound tag,Map<String,String> properties){this.x=x;this.y=y;this.z=z;this.id=id;this.metadata=metadata;this.tag=tag==null?null:(NBTTagCompound)tag.copy();this.properties=properties;}
    }

    private void snapshotStand(WorldServer world) {
        require(expectedSavedBlocks.isEmpty(),"Native cable expectations already captured");
        for(int i=0;i<NODES.length;i++){int x=NODES[i][0],z=NODES[i][1];expectedSavedBlocks.add(snapshot(world,x,12,z));require(world.isAirBlock(x,11,z),"Temporary cable support returned");expectedSavedBlocks.add(snapshot(world,x,11,z));}
        for(int start:new int[]{0,80})for(int z=start;z<=start+16;z++)for(int x=1;x<=3;x++)expectedSavedBlocks.add(snapshot(world,x,6,z));
        require(expectedSavedBlocks.size()==132,"Incomplete native cable Anvil expectations");
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
                Path marker=saveDirectory.toPath().resolve("mtr-cable-probe-owner.txt");
                require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&runId.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Owned train save marker changed after shutdown");
                require(boardAnchor&&movingCaptured&&gravityFalling,"Full actual train scenario was not completed");
                verifySavedAnvil();verifySavedCore();
            }
        }catch(Throwable problem){fail(problem);}
        closeRidingLogObserver();report.addProperty("passed",failure==null);report.addProperty("setup_teleport_count",setupTeleports);
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
        report.add("timeline",timeline);report.add("transitions",transitions);report.add("screenshots",screenshots);report.add("original_one_shot_item_uses",placements);
        report.addProperty("dashboard_dto_packets_used",true);report.addProperty("dashboard_mouse_layout_accepted",false);report.addProperty("save_reopened",false);report.addProperty("audio_accepted",false);report.addProperty("remote_multiplayer_accepted",false);
        report.addProperty("fixed_vehicle_profile",CAR);report.addProperty("continuous_mode",true);report.addProperty("cable_audio_gate_applied",false);
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try {
            if(copyVerified)Files.write(new File(client.mcDataDir,"mtr-cable-probe-"+runId+".json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            System.out.println((failure==null?"MTR_CABLE_PROBE_PASS ":"MTR_CABLE_PROBE_FAIL ")+report);if(safeShutdown)client.shutdown();
        }catch(Throwable problem){problem.printStackTrace();throw new IllegalStateException("Could not write cable acceptance result",problem);}
    }
    private void verifySavedAnvil()throws Exception {
        require(new File(saveDirectory,"level.dat").isFile()&&expectedSavedBlocks.size()==132,"Actual new save/train expectations missing");
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
            Map<String,String> properties=storedProperties(block,metadata,tag);require(expected.properties.equals(properties),"Saved train state properties changed through original codec");
            if(definition.requiresTileEntity())require(expected.tag.getCompoundTag("mtr_block_state").equals(tag.getCompoundTag("mtr_block_state")),"Saved extended train state differs");
            if(tag!=null){TileEntity restored=TileEntity.createAndLoadEntity(tag);require(restored instanceof BlockStateTileEntity,"Actual saved train tile ID cannot decode original state");require(properties.equals(((BlockStateTileEntity)restored).readMtrState(definition,metadata).data.getSerializedProperties()),"Saved train TileEntity codec disagrees with original definition");}
            JsonObject value=new JsonObject();value.addProperty("x",expected.x);value.addProperty("y",expected.y);value.addProperty("z",expected.z);value.addProperty("block",Block.blockRegistry.getNameForObject(block));value.addProperty("metadata",metadata);value.addProperty("has_native_tile",tag!=null);value.addProperty("extended_state_required",definition.requiresTileEntity());value.addProperty("native_nbt",tag==null?"absent":tag.toString());persisted.add(value);
        }
        for(Map.Entry<Path,byte[]> entry:before.entrySet())require(Arrays.equals(entry.getValue(),sha256(entry.getKey())),"Read-only Anvil check changed actual saved bytes");
        report.add("persisted_anvil_blocks",persisted);report.addProperty("saved_native_block_slots_verified",persisted.size());report.addProperty("saved_mtr_fixture_blocks_verified",117);report.addProperty("saved_temporary_support_absences_verified",15);report.addProperty("saved_native_chunks_verified",levels.size());report.addProperty("anvil_open_mode","r");report.addProperty("saved_anvil_readonly_bytes_unchanged",true);
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
        String[] kinds={"stations","platforms","sidings","routes","depots","rails"};int[] counts={2,2,1,1,1,15};Set<String> seen=new HashSet<>();JsonObject persisted=new JsonObject();
        require(expectedCore.size()==22&&coreSample!=null&&coreSample.allPlayerRiders==0,"Final original cable core expectations are incomplete or occupied");
        for(int kind=0;kind<kinds.length;kind++) {
            Path directory=saveDirectory.toPath().resolve("mtr/minecraft/overworld/"+kinds[kind]);require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"Actual original core "+kinds[kind]+" save directory missing");
            require(directory.toRealPath().startsWith(saveDirectory.toPath().toRealPath()),"Core train save escaped owned save");List<Path> files=new ArrayList<>();
            try(Stream<Path> paths=Files.walk(directory)){paths.forEach(path->{require(!Files.isSymbolicLink(path),"Symlink in owned core train save");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))files.add(path);});}
            require(files.size()==counts[kind],"Unexpected saved core "+kinds[kind]+" count: "+files.size());
            for(Path file:files) {
                String key=kinds[kind]+"/"+file.getFileName();JsonObject expected=expectedCore.get(key);require(expected!=null&&seen.add(key),"Saved core train ID differs: "+key);JsonObject actual=readSavedCoreReadOnly(saveDirectory,file.toFile());
                if(kinds[kind].equals("sidings")) {
                    JsonObject a=copyJson(actual),e=copyJson(expected);a.remove("vehicles");e.remove("vehicles");require(e.equals(a),"Saved siding identity/configuration/generated paths differ");
                    JsonArray vehicles=actual.getAsJsonArray("vehicles");require(vehicles!=null&&vehicles.size()>0,"Saved continuous fleet is empty");
                    Set<Long> ids=new HashSet<>();boolean selectedSaved=false,riderField=false;
                    for(JsonElement element:vehicles){JsonObject cabin=element.getAsJsonObject();long id=cabin.get("id").getAsLong();require(id!=0&&ids.add(id),"Saved continuous fleet has duplicate/zero identity");require(Double.isFinite(cabin.get("railProgress").getAsDouble())&&Double.isFinite(cabin.get("speed").getAsDouble())&&cabin.get("speed").getAsDouble()>=0,"Saved continuous cabin has invalid motion");selectedSaved|=id==vehicleId;if(cabin.has("ridingEntities")){riderField=true;require(cabin.getAsJsonArray("ridingEntities").size()==0,"Saved cable cabin still has riders");}}
                    require(selectedSaved,"Saved continuous fleet lost the selected A-to-B cabin");
                    // VehicleSchema persists motion, not necessarily VehicleExtraData. Revoke is proved live across every cabin before shutdown.
                    report.addProperty("saved_vehicle_rider_field_present",riderField);report.addProperty("empty_core_riders_verified_before_shutdown",true);report.addProperty("saved_continuous_cabin_count",vehicles.size());report.add("saved_original_continuous_fleet",vehicles);
                } else require(expected.equals(actual),"Saved original core identity/configuration differs: "+key);
                persisted.add(key,actual);
            }
        }
        require(seen.size()==expectedCore.size(),"Saved original core payload set differs");report.add("saved_original_core_payloads",persisted);report.addProperty("saved_core_payload_count",seen.size());report.addProperty("saved_core_readonly_bytes_unchanged",true);
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
}
