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

/** Explicit test-only full-MTR boat acceptance; never opens a pre-existing world. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_boat_probe", name="MTR GTNH original scheduled boat acceptance", version="0", dependencies="required-after:mtr", acceptableRemoteVersions="*")
public final class BoatProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT=new File(requiredProbePath("mtr.probeClient"));
    private static final int[] NODES={0,16,24,48,64,88,112};
    private static final String CAR="oak_boat_small", A_NAME="Probe Station A", B_NAME="Probe Station B", D_NAME="Probe Depot", R_NAME="Probe A to B";
    private enum Stage {MENU,WORLD,SETUP,CHUNKS,DASHBOARD,AREAS,PLATFORMS,ROUTE,SIDING,DEPOT,GENERATE,SCHEDULE,PICKUP_A,BOARD,DEPART,RIDE,STOP_B,EXIT,DISMOUNT,GRAVITY,RETURN_IDLE,CLOSE,FINISHED}
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
    private volatile JsonObject lastC03,lastRidingInbound,lastRidingRejection;
    private org.apache.logging.log4j.core.Logger ridingLogger;
    private org.apache.logging.log4j.core.config.LoggerConfig ridingObserverConfig;
    private org.apache.logging.log4j.Level previousRidingLevel;
    private org.apache.logging.log4j.core.appender.AbstractAppender ridingLogObserver;
    private boolean ridingLogRestored;
    private volatile boolean packetObserverReady,clientBoardingAuthorized,dashboardObserved;
    private boolean packetObserverRequested,copyVerified,boardAnchor;
    private int menuTicks,clientTicks,phaseTicks,readyChunkTicks,setupTeleports,fixtureSetupMoves,waterItemUses,boardingS08,motionS08,stillTicks,jumpClientTicks;
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
    private double boardedZ,jumpBase;
    private boolean movingCaptured,gravityFalling;
    private static volatile JsonObject originalBoatCar;
    private static volatile double boatTotalLength;
    private volatile boolean boatResourcesVerified;
    private static final net.minecraft.util.ResourceLocation BOAT_TEXTURE=new net.minecraft.util.ResourceLocation("minecraft","textures/entity/boat/oak.png");
    private static final String BOAT_TEXTURE_SHA="5a6376463476293f06cea363b37864cade05d62b84403a75624b4206f70c9c70";

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=stageStartedAt=System.currentTimeMillis();
        require(!Loader.isModLoaded("mtr_gtnh_probe")&&!Loader.isModLoaded("mtr_gtnh_world_probe")&&!Loader.isModLoaded("mtr_gtnh_lift_probe"),"BoatProbe must be the only selected probe");
        FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null||stage==Stage.CLOSE){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<15*60*1000L,"Boat probe total deadline in "+stage);
            if(stage==Stage.MENU) {
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();
                if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                reserveNewSave(client);report.addProperty("menu_class",screen);
                client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=1;client.gameSettings.renderDistanceChunks=8;client.gameSettings.saveOptions();
                advance(Stage.WORLD);
                WorldSettings settings=new WorldSettings(0x4D5452545241494EL,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT).enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName,"MTR boat acceptance "+runId,settings);return;
            }
            if(client.getIntegratedServer()!=null)assertOwnedServer(client.getIntegratedServer());
            if(stage==Stage.WORLD) {
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                playerId=client.thePlayer.getUniqueID();report.addProperty("player_uuid",playerId.toString());advance(Stage.SETUP);return;
            }
            require(client.theWorld!=null&&client.thePlayer!=null&&client.getIntegratedServer()!=null,"Owned boat world/player disappeared");
            if(!boatResourcesVerified && !verifyBoatResources(client))return;
            if(client.gameSettings.pauseOnLostFocus){client.gameSettings.pauseOnLostFocus=false;client.gameSettings.saveOptions();}
            if(stage==Stage.DASHBOARD&&client.currentScreen instanceof org.mtr.mod.screen.DashboardScreen){dashboardObserved=true;report.addProperty("dashboard_native_screen_class",client.currentScreen.getClass().getName());}
            if(client.currentScreen!=null)client.displayGuiScreen(null);
            KeyBinding.unPressAllKeys();
            Stage current=stage;
            ClientSample previous=clientSample;
            if(current==Stage.BOARD) {
                if(previous!=null&&previous.vehiclePresent) {
                    if(!clientBoardingAuthorized)key(client.gameSettings.keyBindForward,client.thePlayer.posX<previous.centerX+.2);
                    if(!clientBoardingAuthorized&&client.thePlayer.posX>=previous.centerX+.2)key(client.gameSettings.keyBindBack,true);
                }
            } else if(current==Stage.EXIT&&previous!=null&&client.thePlayer.posX>previous.centerX-2.4)key(client.gameSettings.keyBindBack,true);
            else if(current==Stage.GRAVITY&&jumpClientTicks++<2)key(client.gameSettings.keyBindJump,true);
            if(clientAcknowledged!=current&&configureClient(current))clientAcknowledged=current;
            clientSample=new ClientSample(client,++clientTicks);
            if(clientTicks%100==0)System.out.println("MTR_BOAT_PROBE_PROGRESS "+stage+" tick="+clientTicks);
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null)return;
        Stage current=stage;if(current==Stage.MENU||current==Stage.WORLD||current==Stage.CLOSE||current==Stage.FINISHED)return;
        try {
            MinecraftServer server=MinecraftServer.getServer();assertOwnedServer(server);EntityPlayerMP player=actualPlayer(server);
            if(player==null){require(current==Stage.SETUP&&++phaseTicks<200,"Actual server passenger missing");return;}
            WorldServer world=server.worldServerForDimension(0);require(player.worldObj==world,"Passenger/stand world mismatch");
            require(System.currentTimeMillis()-stageStartedAt<(current==Stage.SCHEDULE?180000:current==Stage.CHUNKS?90000:60000),"Boat phase deadline in "+current);++phaseTicks;
            if(current==Stage.SETUP) {
                if(!packetObserverRequested){report.add("initial_server_player",playerPosition(player));player.setGameType(WorldSettings.GameType.CREATIVE);player.capabilities.isFlying=false;player.sendPlayerAbilities();installPacketObserver(player.playerNetServerHandler.netManager);packetObserverRequested=true;return;}
                if(!packetObserverReady)return;setupTeleport(player,4.5,4,32.5,0);advance(Stage.CHUNKS);return;
            }
            if(current==Stage.CHUNKS) {
                boolean ready=true;JsonArray chunks=new JsonArray();for(int z=0;z<=7;z++){JsonObject chunk=chunkStatus(world,0,z);chunks.add(chunk);ready&=chunk.get("ready").getAsBoolean();}
                report.add("stand_chunk_last_wait",chunks);ClientSample client=clientSample;
                ready&=client!=null&&Math.abs(client.x-4.5)<.2&&Math.abs(client.z-32.5)<.2;
                readyChunkTicks=ready?readyChunkTicks+1:0;if(readyChunkTicks<2)return;
                buildStand(world,player);simulator=actualSimulator();advance(Stage.DASHBOARD);
                Item item=originalItem("dashboard_2",org.mtr.mod.Items.BOAT_DASHBOARD.get().data);ItemStack stack=new ItemStack(item);player.inventory.mainInventory[0]=stack;item.onItemRightClick(stack,world,player);return;
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
                if(!core.vehiclePresent||!core.onRoute||!client.vehiclePresent)return;vehicleId=core.vehicle;require(client.vehicle==vehicleId,"Client/core scheduled vehicle identity mismatch");
                report.addProperty("naturally_scheduled_vehicle_id",Long.toString(vehicleId));report.addProperty("natural_scheduled_startup_observed",true);advance(Stage.PICKUP_A);
            } else if(current==Stage.PICKUP_A) {
                if(!atPlatform(client,core,platformAId,24,48)||!client.resourceReady||!client.shouldRender||client.door<.95)return;
                if(setupTeleports<2){require(!client.riding&&!PlayerPhysics.authorized(player),"Final setup would move an existing rider");pickupPose=client.pickup;require(pickupPose!=null&&Math.abs(pickupPose.y-4)<.02,"Actual model floor differs from physical platform");setupTeleport(player,pickupPose.x,pickupPose.y,pickupPose.z,-90);report.add("pickup_actual_geometry",client.geometry());return;}
                if(Math.abs(client.x-pickupPose.x)>.15||Math.abs(client.y-pickupPose.y)>.15||Math.abs(client.z-pickupPose.z)>.15)return;
                if(!client.emptyHand||player.getHeldItem()!=null)return;
                if(!captureDone("pickupA"))return;boardingS08=s08.get();advance(Stage.BOARD);
            } else if(current==Stage.BOARD) {
                require(atPlatform(client,core,platformAId,24,48)&&client.door>.2,"Natural boarding/authorization missed original platform A door window");
                require(client.emptyHand&&player.getHeldItem()==null,"Natural boarding requires empty hands");
                if(!client.riding||!core.rider||!PlayerPhysics.authorized(player))return;
                require(Math.abs(client.x-client.centerX)<.9,"Boarded passenger is outside safe central car width");
                boardAnchor=true;motionS08=s08.get();boardedZ=client.z;report.addProperty("natural_render_boarding_authorized",true);advance(Stage.DEPART);
            } else if(current==Stage.DEPART) {
                assertPassenger(player,client,core);if(client.moving&&core.moving&&Math.abs(client.centerZ-boardedZ)>1)advance(Stage.RIDE);
            } else if(current==Stage.RIDE) {
                assertPassenger(player,client,core);
                if(!movingCaptured&&client.moving&&Math.abs(client.centerZ-boardedZ)>2)movingCaptured=captureDone("moving");
                if(atPlatform(client,core,platformBId,64,88)&&client.door>.95){require(movingCaptured&&Math.abs(client.z-boardedZ)>8,"No captured real passenger journey");advance(Stage.STOP_B);}
            } else if(current==Stage.STOP_B) {
                assertPassenger(player,client,core);require(atPlatform(client,core,platformBId,64,88),"Boat left destination before exit");if(captureDone("arrivalB"))advance(Stage.EXIT);
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
                if(stillTicks<20)return;snapshotStand(world);expectedCore.putAll(core.fullData);report.add("pre_save_core",core.json());advance(Stage.CLOSE);
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
        require(client.currentScreen==null,"World framebuffer is not rendered");String name="mtr-boat-probe-"+runId+"-"+label+".png";
        ScreenShotHelper.saveScreenshot(client.mcDataDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());File file=new File(new File(client.mcDataDir,"screenshots"),name);
        require(file.isFile()&&file.length()>100,"Actual boat framebuffer was not written");JsonObject value=new JsonObject();value.addProperty("phase",label);value.addProperty("path",file.getCanonicalPath());value.addProperty("render_frames_after_request",renderedFrames);value.addProperty("client_tick",clientTicks);screenshots.add(value);
    }
    private void advance(Stage next) {
        JsonObject value=new JsonObject();value.addProperty("from",stage.name());value.addProperty("to",next.name());value.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt);transitions.add(value);
        phaseTicks=stillTicks=0;stageStartedAt=System.currentTimeMillis();stage=next;
    }
    private void setupTeleport(EntityPlayerMP player,double x,double y,double z,float yaw) {
        require((stage==Stage.SETUP||stage==Stage.PICKUP_A)&&setupTeleports<2&&!PlayerPhysics.authorized(player),"Boat teleport forbidden outside strict setup");
        player.playerNetServerHandler.setPlayerLocation(x,y,z,yaw,10);setupTeleports++;
    }
    private static void key(KeyBinding binding,boolean down){KeyBinding.setKeyBindState(binding.getKeyCode(),down);}
    private static void send(UpdateDataRequest request){InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(request));}
    private boolean configureClient(Stage current) {
        MinecraftClientData data=MinecraftClientData.getDashboardInstance();
        if(current==Stage.AREAS) {
            Station a=new Station(data),b=new Station(data);Depot d=new Depot(TransportMode.BOAT,data);
            a.setName(A_NAME);a.setColor(0x3366CC);a.setCorners(new Position(0,Long.MIN_VALUE,20),new Position(16,Long.MAX_VALUE,52));
            b.setName(B_NAME);b.setColor(0x33AA66);b.setCorners(new Position(0,Long.MIN_VALUE,60),new Position(16,Long.MAX_VALUE,92));
            d.setName(D_NAME);d.setColor(0xAA6633);d.setCorners(new Position(0,Long.MIN_VALUE,-4),new Position(16,Long.MAX_VALUE,18));
            stationAId=a.getId();stationBId=b.getId();depotId=d.getId();send(new UpdateDataRequest(data).addStation(a).addStation(b).addDepot(d));return true;
        } else if(current==Stage.PLATFORMS) {
            Platform a=data.platformIdMap.get(platformAId),b=data.platformIdMap.get(platformBId);if(a==null||b==null)return false;
            a.setName("1");b.setName("1");a.setDwellTime(20000);b.setDwellTime(20000);send(new UpdateDataRequest(data).addPlatform(a).addPlatform(b));return true;
        } else if(current==Stage.ROUTE) {
            if(!data.platformIdMap.containsKey(platformAId)||!data.platformIdMap.containsKey(platformBId))return false;
            Route route=new Route(TransportMode.BOAT,data);route.setName(R_NAME);route.setColor(0x3366CC);route.setCircularState(Route.CircularState.NONE);
            for(long id:new long[]{platformAId,platformBId}){RoutePlatformData row=new RoutePlatformData(id);route.getRoutePlatforms().add(row);row.writePlatformCache(route,data.platformIdMap);}
            routeId=route.getId();send(new UpdateDataRequest(data).addRoute(route));return true;
        } else if(current==Stage.SIDING) {
            Siding siding=data.sidingIdMap.get(sidingId);if(siding==null)return false;final VehicleResource[] selected={null};
            CustomResourceLoader.getVehicleById(TransportMode.BOAT,CAR,details->{if(CAR.equals(details.left().getId()))selected[0]=details.left();});
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
            if(coreSample==null)return false;generationPreviousMillis=coreSample.generationMillis;generationRequestedAt=System.currentTimeMillis();DepotOperationByIds operation=new DepotOperationByIds();operation.addDepotId(depotId);InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDepotGenerate(operation));return true;
        }
        return false;
    }
    private void buildStand(WorldServer world,EntityPlayerMP player) {
        require(stage==Stage.CHUNKS&&boatResourcesVerified&&!PlayerPhysics.authorized(player),"Boat fixture is setup-only");
        world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.setWorldTime(6000);world.difficultySetting=EnumDifficulty.PEACEFUL;
        for(int z=0;z<=112;z++)for(int x=5;x<=10;x++) {
            world.setBlock(x,2,z,net.minecraft.init.Blocks.stone);
            for(int y=3;y<=10;y++)world.setBlockToAir(x,y,z);
            world.setBlock(x,3,z,x==8?net.minecraft.init.Blocks.water:net.minecraft.init.Blocks.stone,0,3);
        }
        for(int z:NODES) {
            fixtureMove(player,8.5,4,z+.5,0,90);
            Item item=originalItem("boat_node",org.mtr.mod.Items.BOAT_NODE.get().data);ItemStack stack=new ItemStack(item);player.inventory.mainInventory[0]=stack;
            JsonObject value=new JsonObject();value.addProperty("item","mtr:boat_node");value.addProperty("item_class",item.getClass().getName());value.addProperty("original_onItemRightClick",true);
            value.addProperty("x",8);value.addProperty("y",3);value.addProperty("z",z);value.addProperty("fixture_setup_move",fixtureSetupMoves);value.add("source_before",blockStatus(world,8,3,z));value.add("before",blockStatus(world,8,4,z));placements.add(value);
            require((world.getBlock(8,3,z)==net.minecraft.init.Blocks.water||world.getBlock(8,3,z)==net.minecraft.init.Blocks.flowing_water)&&world.getBlockMetadata(8,3,z)==0&&world.isAirBlock(8,4,z),"Water item needs a still source and air above");
            ItemStack result=item.onItemRightClick(stack,world,player);waterItemUses++;
            value.addProperty("returned_same_stack",result==stack);value.addProperty("stack_after",stack.stackSize);value.add("after",blockStatus(world,8,4,z));
            require(result==stack&&stack.stackSize==1&&world.getBlock(8,4,z)==org.mtr.mod.Blocks.BOAT_NODE.get().data,"Original creative water-item raytrace did not place the boat node");
            require(Math.abs(BlockNode.getAngle(state(world,8,4,z))-90)<.01,"Original boat node placement has the wrong Z axis");
        }
        fixtureMove(player,4.5,4,32.5,0,10);
        player.rotationYaw=-90;
        for(int start:new int[]{24,64})for(int z=start;z<=start+24;z++)for(int x=5;x<=7;x++) {
            world.setBlockToAir(x,3,z);ItemStack stack=new ItemStack(originalItem("platform",Item.getItemFromBlock(org.mtr.mod.Blocks.PLATFORM.get().data)));
            use(world,player,stack,x,2,z,1,.5F);require(stack.stackSize==0,"Original boat pier item did not consume one block");
        }
        player.rotationYaw=0;
        for(int edge=0;edge<6;edge++) {
            String name=edge==0?"rail_connector_siding":edge==2||edge==4?"rail_connector_platform":edge==5?"rail_connector_turn_back":"rail_connector_20";
            Item item=(Item)Item.itemRegistry.getObject("mtr:"+name);require(item instanceof org.mtr.mod.item.ItemRailModifier,"Original boat connector missing");ItemStack connector=new ItemStack(item);
            use(world,player,connector,8,4,NODES[edge],1,.5F);
            require(connector.hasTagCompound()&&connector.getTagCompound().getLong(ItemBlockClickingBase.TAG_POS)==new BlockPos(8,4,NODES[edge]).asLong(),"Original boat first-click state missing");
            use(world,player,connector,8,4,NODES[edge+1],1,.5F);require(!connector.getTagCompound().hasKey(ItemBlockClickingBase.TAG_POS)&&connector.stackSize==1,"Original reusable boat connector click pair failed");
        }
        require(waterItemUses==7&&fixtureSetupMoves==8,"Incomplete original water-item fixture visits");
        player.inventory.mainInventory[0]=null;player.inventory.currentItem=0;report.addProperty("original_native_node_count",7);report.addProperty("original_native_platform_block_count",150);
    }
    private void fixtureMove(EntityPlayerMP player,double x,double y,double z,float yaw,float pitch) {
        require(stage==Stage.CHUNKS&&fixtureSetupMoves<8&&!boardAnchor&&!PlayerPhysics.authorized(player)&&!player.noClip,"Fixture visit cannot move a rider");
        player.playerNetServerHandler.setPlayerLocation(x,y,z,yaw,pitch);fixtureSetupMoves++;
    }
    private boolean verifyBoatResources(Minecraft client)throws Exception {
        final VehicleResource[] selected={null};CustomResourceLoader.getVehicleById(TransportMode.BOAT,CAR,details->{if(CAR.equals(details.left().getId()))selected[0]=details.left();});
        if(selected[0]==null)return false;VehicleResource resource=selected[0];VehicleResourceCache cache=resource.getCachedVehicleResource(0,1,false);
        if(cache==null||cache.floors.isEmpty()||cache.doorways.isEmpty())return false;
        require(resource.getLength()==2&&resource.getWidth()==2&&resource.getBogie1Position()==-1&&resource.getBogie2Position()==1,"Stock boat resource dimensions changed");
        for(Box doorway:cache.doorways)require(Math.abs(doorway.getMaxYMapped())<.01,"Stock boat doorway floor is no longer rail-height");
        VehicleCar car=new VehicleCar(resource.getId(),resource.getLength(),resource.getWidth(),resource.getBogie1Position(),resource.getBogie2Position(),resource.getCouplingPadding1(),resource.getCouplingPadding2());
        originalBoatCar=full(car);ObjectArrayList<VehicleCar> cars=new ObjectArrayList<>();cars.add(car);boatTotalLength=Siding.getTotalVehicleLength(cars);
        net.minecraft.client.resources.IResource input=client.getResourceManager().getResource(BOAT_TEXTURE);byte[] bytes;
        try(InputStream stream=input.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()) {byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))>=0)output.write(buffer,0,n);bytes=output.toByteArray();}
        finally {LegacyResources.close(input);}
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        require(BOAT_TEXTURE_SHA.equals(hash.toString()),"Runtime oak boat texture differs from pinned original bytes");
        java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));require(image!=null&&image.getWidth()==128&&image.getHeight()==64,"Runtime boat texture has the wrong modern UV size");
        report.add("original_stock_boat_car",copyJson(originalBoatCar));report.addProperty("runtime_boat_texture",BOAT_TEXTURE.toString());report.addProperty("runtime_boat_texture_sha256",hash.toString());
        report.addProperty("runtime_boat_texture_width",128);report.addProperty("runtime_boat_texture_height",64);boatResourcesVerified=true;return true;
    }
    private static boolean nativeBoatTextureReady(Minecraft client) {
        net.minecraft.client.renderer.texture.ITextureObject texture=client.getTextureManager().getTexture(BOAT_TEXTURE);
        return texture!=null&&texture!=net.minecraft.client.renderer.texture.TextureUtil.missingTexture;
    }
    private static boolean completeRails(Iterable<Rail> rails) {
        boolean[] seen=new boolean[6];int count=0;
        for(Rail rail:rails){int edge=-1;for(int i=0;i<6;i++)if(rail.getHexId().equals(TwoPositionsBase.getHexId(new Position(8,4,NODES[i]),new Position(8,4,NODES[i+1]))))edge=i;
            if(edge<0||seen[edge]||rail.getTransportMode()!=TransportMode.BOAT||Math.abs(rail.railMath.getLength()-(NODES[edge+1]-NODES[edge]))>.01||rail.isSiding()!=(edge==0)||rail.isPlatform()!=(edge==2||edge==4)||rail.canTurnBack()!=(edge==5))return false;
            if((edge==1||edge==3)&&(rail.getSpeedLimitKilometersPerHour(true)!=20||rail.getSpeedLimitKilometersPerHour(false)!=20))return false;seen[edge]=true;count++;
        }return count==6;
    }
    private static Vector pickupPoint(Vector center,double yaw,Box door) {
        double x=(door.getMinXMapped()+door.getMaxXMapped())/2,z=(door.getMinZMapped()+door.getMaxZMapped())/2;
        return new Vector(x+Math.copySign(.75,x),door.getMaxYMapped(),z).rotateY(yaw).add(center);
    }
    // Fixed stock oak boat / flat route: floor elevations come from the resource cache even away from a platform.
    static double cachedBoatFloorY(Vector center,Iterable<Box> floors) {
        double result=Double.NaN;
        for(Box floor:floors){double value=center.y+floor.getMaxYMapped();if(Double.isNaN(result))result=value;else require(Math.abs(value-result)<1E-6,"Stock boat cache has inconsistent floor elevations");}
        return result;
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
    private void assertPassenger(EntityPlayerMP player,ClientSample client,CoreSample core) {
        require(client.carConfigured&&core.carConfigured,"Actual passenger boat lost original stock oak boat car");
        require(client.emptyHand&&player.getHeldItem()==null,"Natural boat passenger must have empty hands");
        require(client.riding&&core.rider&&client.vehicle==vehicleId&&core.vehicle==vehicleId&&PlayerPhysics.authorized(player),"Original boat passenger lost client/core/server binding");
        require(player.noClip&&Math.abs(PlayerPhysics.gravity(.08,player))<1E-10&&!client.flying&&!player.capabilities.isFlying,"Passenger native physics/flight mismatch");
        require(Math.abs(client.y-client.floorY)<.4&&Math.abs(player.boundingBox.minY-client.floorY)<1.5&&Math.abs(player.posX-client.x)<1&&Math.abs(player.posZ-client.z)<1,"Actual client/server passenger stopped following original car");
        require(s08.get()-motionS08<=2,"Repeated native S08 correction during boat ride");
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
                actual.pipeline().addBefore("packet_handler", "mtr_boat_probe_observer", new ChannelDuplexHandler() {
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
        ridingLogObserver = new org.apache.logging.log4j.core.appender.AbstractAppender("mtr_boat_probe_riding_" + System.identityHashCode(this), null, null) {
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
        runId = UUID.randomUUID().toString(); saveName = "MTR-boat-acceptance-" + runId;
        Path saves = client.mcDataDir.toPath().resolve("saves"); Files.createDirectories(saves);
        require(!Files.isSymbolicLink(saves) && saves.toRealPath().getParent().equals(client.mcDataDir.toPath().toRealPath()), "Copied saves directory escaped client");
        Path reserved = Files.createDirectory(saves.resolve(saveName));
        saveDirectory = reserved.toFile();
        Files.write(reserved.resolve("mtr-boat-probe-owner.txt"), runId.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        copyVerified = true;
        report.addProperty("run_id", runId); report.addProperty("save_directory", saveDirectory.getCanonicalPath());
        report.addProperty("client_directory", client.mcDataDir.getCanonicalPath());
        report.addProperty("new_uuid_save_reserved", true); report.addProperty("existing_save_loaded", false);
        report.addProperty("scenario", "original boat items + Dashboard DTO packets; scheduled A-to-B; natural render rider/exit; owned Anvil + core MessagePack");
    }

    private void assertOwnedServer(MinecraftServer server) throws Exception {
        require(copyVerified && server != null && saveDirectory != null && saveName.equals(server.getFolderName()), "Refusing to mutate/close a foreign server");
        Path marker = saveDirectory.toPath().resolve("mtr-boat-probe-owner.txt");
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
        require((tag != null) == block.hasTileEntity(metadata), "Native boat TileEntity presence differs from original block contract");
        if (definition.requiresTileEntity()) require(tag != null && tag.hasKey("mtr_block_state", 10), "Extended boat state is missing");
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
            for(int z:NODES)if(world.getBlock(8,4,z)!=org.mtr.mod.Blocks.BOAT_NODE.get().data||!"true".equals(state(world,8,4,z).data.getSerializedProperties().get("is_connected")))return false;
            for(int start:new int[]{24,64})for(int z=start;z<=start+24;z++)for(int x=5;x<=7;x++)if(world.getBlock(x,3,z)!=org.mtr.mod.Blocks.PLATFORM.get().data)return false;
            return true;
        }catch(IllegalArgumentException|IllegalStateException waitingForNativeState){return false;}
    }
    private static final class ClientSample {
        final int tick;final long vehicle;final double x,y,z,motionY,centerX,centerY,centerZ,yaw,floorY,door;
        final boolean onGround,noClip,flying,riding,moving,vehiclePresent,resourceReady,shouldRender,fixtureReady,dashboardSavedRailsReady,emptyHand,carConfigured;
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
            VehicleExtension found=null;for(VehicleExtension value:MinecraftClientData.getInstance().vehicles){require(found==null,"Owned client has multiple unexpected boats");found=value;}
            vehiclePresent=found!=null;
            if(found==null){vehicle=0;centerX=centerY=centerZ=yaw=floorY=door=0;riding=moving=resourceReady=shouldRender=carConfigured=false;pickup=null;doorway=null;vehicleJson=new JsonObject();}
            else {
                final VehicleExtension actual=found;carConfigured=correctCar(actual);vehicle=actual.getId();riding=VehicleRidingMovement.isRiding(vehicle);moving=actual.isMoving();door=actual.persistentVehicleData.getDoorValue();vehicleJson=Utilities.getJsonObjectFromData(actual);
                shouldRender=actual.persistentVehicleData.rayTracing.length>0&&actual.persistentVehicleData.rayTracing[0];
                final PositionAndRotation[] pose={null};
                // Unlike getSmoothedVehicleCarsAndPositions, this original getter never temporarily assigns railProgress.
                actual.getVehicleCarsAndPositions().stream().findFirst().ifPresent(car->{ObjectArrayList<PositionAndRotation> bogies=new ObjectArrayList<>();car.right().forEach(pair->bogies.add(new PositionAndRotation(pair.left(),pair.right(),true)));pose[0]=new PositionAndRotation(bogies,car.left(),false);});
                final VehicleResourceCache[] cache={null};CustomResourceLoader.getVehicleById(TransportMode.BOAT,CAR,details->{if(CAR.equals(details.left().getId()))cache[0]=details.left().getCachedVehicleResource(0,1,false);});
                Box west=null;Vector outside=null;double westX=Double.POSITIVE_INFINITY;
                if(pose[0]!=null&&cache[0]!=null&&!cache[0].floors.isEmpty())for(Box candidate:cache[0].doorways){Vector point=pickupPoint(pose[0].position,pose[0].yaw,candidate);if(point.x<westX&&RenderVehicleHelper.canOpenDoors(candidate,pose[0],door*2)){westX=point.x;west=candidate;outside=point;}}
                doorway=west;pickup=outside;resourceReady=carConfigured&&pose[0]!=null&&cache[0]!=null&&west!=null&&nativeBoatTextureReady(client);
                centerX=pose[0]==null?0:pose[0].position.x;centerY=pose[0]==null?0:pose[0].position.y;centerZ=pose[0]==null?0:pose[0].position.z;yaw=pose[0]==null?0:pose[0].yaw;
                floorY=pose[0]==null||cache[0]==null?Double.NaN:cachedBoatFloorY(pose[0].position,cache[0].floors);
            }
        }
        JsonObject geometry() {
            JsonObject result=new JsonObject();result.addProperty("source","original_getVehicleCarsAndPositions_unsmoothed");result.addProperty("center_x",centerX);result.addProperty("center_y",centerY);result.addProperty("center_z",centerZ);result.addProperty("yaw",yaw);if(Double.isFinite(floorY))result.addProperty("floor_y",floorY);else result.add("floor_y",JsonNull.INSTANCE);
            if(doorway!=null){JsonObject box=new JsonObject();box.addProperty("min_x",doorway.getMinXMapped());box.addProperty("max_x",doorway.getMaxXMapped());box.addProperty("min_z",doorway.getMinZMapped());box.addProperty("max_z",doorway.getMaxZMapped());box.addProperty("y",doorway.getMaxYMapped());result.add("original_local_doorway",box);}
            if(pickup!=null){JsonObject value=new JsonObject();value.addProperty("x",pickup.x);value.addProperty("feet_y",pickup.y);value.addProperty("z",pickup.z);result.add("derived_external_setup_pose",value);}return result;
        }
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("client_tick",tick);result.addProperty("client_x",x);result.addProperty("client_feet_y",y);result.addProperty("client_z",z);result.addProperty("client_motion_y",motionY);result.addProperty("client_on_ground",onGround);result.addProperty("client_no_clip",noClip);result.addProperty("client_flying",flying);result.addProperty("client_riding",riding);result.addProperty("empty_hand",emptyHand);result.addProperty("vehicle_id",Long.toString(vehicle));result.addProperty("vehicle_moving",moving);result.addProperty("actual_stock_oak_boat_car_configured",carConfigured);result.addProperty("door",door);result.addProperty("original_renderer_should_render",shouldRender);result.addProperty("actual_resource_ready",resourceReady);result.add("car_geometry",geometry());result.add("native_input",nativeInput);result.add("original_riding_state",originalRiding);result.add("actual_client_interest",interest);result.add("original_client_vehicle",vehicleJson);return result;}
    }
    private final class CoreSample {
        final long at=System.currentTimeMillis(),siding,platformA,platformB,vehicle,thisPlatform,nextPlatform,stoppedPlatform,generationMillis;
        final boolean railsComplete,areasConfigured,platformsConfigured,routeConfigured,sidingConfigured,depotConfigured,generated,generationFailed,vehiclePresent,moving,onRoute,closeToDepot,rider,carConfigured;
        final int riderCount;final JsonObject depotJson,sidingJson,vehicleJson;final Map<String,JsonObject> fullData=new LinkedHashMap<>();
        CoreSample(Simulator data)throws Exception {
            railsComplete=completeRails(data.rails);Platform a=null,b=null;Siding s=null;Station sa=null,sb=null;Depot d=null;Route r=null;
            for(Platform value:data.platforms){if(value.containsPos(new Position(8,4,24))&&value.containsPos(new Position(8,4,48)))a=value;if(value.containsPos(new Position(8,4,64))&&value.containsPos(new Position(8,4,88)))b=value;}
            for(Siding value:data.sidings)if(value.containsPos(new Position(8,4,0))&&value.containsPos(new Position(8,4,16)))s=value;
            for(Station value:data.stations){if(value.getId()==stationAId)sa=value;if(value.getId()==stationBId)sb=value;}
            for(Depot value:data.depots)if(value.getId()==depotId)d=value;for(Route value:data.routes)if(value.getId()==routeId)r=value;
            siding=s==null?0:s.getId();platformA=a==null?0:a.getId();platformB=b==null?0:b.getId();
            areasConfigured=sa!=null&&sb!=null&&d!=null&&a!=null&&b!=null&&s!=null&&a.area==sa&&b.area==sb&&s.area==d&&sa.getName().equals(A_NAME)&&sb.getName().equals(B_NAME)&&d.getName().equals(D_NAME)&&data.stations.size()==2&&data.depots.size()==1;
            platformsConfigured=areasConfigured&&a.getDwellTime()==20000&&b.getDwellTime()==20000;
            routeConfigured=r!=null&&r.getTransportMode()==TransportMode.BOAT&&r.getRoutePlatforms().size()==2&&r.getRoutePlatforms().get(0).getPlatform()!=null&&r.getRoutePlatforms().get(1).getPlatform()!=null&&r.getRoutePlatforms().get(0).getPlatform().getId()==platformAId&&r.getRoutePlatforms().get(1).getPlatform().getId()==platformBId&&r.getCircularState()==Route.CircularState.NONE&&data.routes.size()==1;
            sidingConfigured=s!=null&&s.getMaxVehicles()==1&&!s.getIsManual()&&s.getVehicleCars().size()==1&&CAR.equals(s.getVehicleCars().get(0).getVehicleId())&&Math.abs(Siding.getTotalVehicleLength(s.getVehicleCars())-boatTotalLength)<1E-6;
            depotConfigured=d!=null&&d.getRouteIds().size()==1&&d.getRouteIds().getLong(0)==routeId&&!d.getRepeatInfinitely()&&d.getUseRealTime()&&d.getRealTimeDepartures().size()==1&&d.getRealTimeDepartures().getLong(0)==plannedDepartureMillis;
            depotJson=d==null?new JsonObject():full(d);sidingJson=s==null?new JsonObject():full(s);
            generationMillis=d==null?0:d.getLastGeneratedMillis();
            generated=d!=null&&s!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()==Depot.GeneratedStatus.SUCCESSFUL&&!d.getPath().isEmpty()&&!((Collection<?>)diagnosticField(s,"pathSidingToMainRoute")).isEmpty()&&!((Collection<?>)diagnosticField(s,"pathMainRouteToSiding")).isEmpty();
            generationFailed=d!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()!=Depot.GeneratedStatus.SUCCESSFUL;
            Vehicle found=null;if(s!=null)for(Object value:(Iterable<?>)diagnosticField(s,"vehicles")){require(found==null,"Owned core has unexpected multiple vehicles");found=(Vehicle)value;}
            vehiclePresent=found!=null;
            if(found==null){vehicle=thisPlatform=nextPlatform=stoppedPlatform=0;moving=onRoute=closeToDepot=rider=carConfigured=false;riderCount=0;vehicleJson=new JsonObject();}
            else {carConfigured=correctCar(found);vehicle=found.getId();moving=found.isMoving();onRoute=found.getIsOnRoute();closeToDepot=found.closeToDepot();thisPlatform=found.vehicleExtraData.getThisPlatformId();nextPlatform=found.vehicleExtraData.getNextPlatformId();vehicleJson=Utilities.getJsonObjectFromData(found);stoppedPlatform=stoppedPlatform(found.vehicleExtraData.immutablePath,vehicleJson.get("nextStoppingIndexAto").getAsInt(),vehicleJson.get("railProgress").getAsDouble());final int[] count={0};final boolean[] actual={false};found.vehicleExtraData.iterateRidingEntities(row->{count[0]++;if(row.uuid.equals(playerId)&&row.getRidingCar()==0)actual[0]=true;});riderCount=count[0];rider=actual[0]&&riderCount==1;}
            for(Station value:data.stations)fullData.put("stations/"+value.getHexId(),full(value));for(Platform value:data.platforms)fullData.put("platforms/"+value.getHexId(),full(value));for(Siding value:data.sidings)fullData.put("sidings/"+value.getHexId(),full(value));for(Route value:data.routes)fullData.put("routes/"+value.getHexId(),full(value));for(Depot value:data.depots)fullData.put("depots/"+value.getHexId(),full(value));for(Rail value:data.rails)fullData.put("rails/"+value.getHexId(),full(value));
        }
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("rails_complete",railsComplete);result.addProperty("areas_configured",areasConfigured);result.addProperty("platforms_configured",platformsConfigured);result.addProperty("route_configured",routeConfigured);result.addProperty("siding_configured",sidingConfigured);result.addProperty("depot_configured",depotConfigured);result.addProperty("generated",generated);result.addProperty("vehicle_id",Long.toString(vehicle));result.addProperty("vehicle_moving",moving);result.addProperty("actual_stock_oak_boat_car_configured",carConfigured);result.addProperty("on_route",onRoute);result.addProperty("close_to_depot",closeToDepot);result.addProperty("rider_count",riderCount);result.addProperty("actual_passenger",rider);result.addProperty("this_platform",Long.toString(thisPlatform));result.addProperty("next_platform",Long.toString(nextPlatform));result.addProperty("stopped_platform",Long.toString(stoppedPlatform));result.add("vehicle",vehicleJson);return result;}
    }
    private static boolean correctCar(Vehicle value) {
        return originalBoatCar!=null&&value.vehicleExtraData.immutableVehicleCars.size()==1&&originalBoatCar.equals(full(value.vehicleExtraData.immutableVehicleCars.get(0)));
    }
    private static final class StoredBlock {
        final int x,y,z,id,metadata;final NBTTagCompound tag;final Map<String,String> properties;
        StoredBlock(int x,int y,int z,int id,int metadata,NBTTagCompound tag,Map<String,String> properties){this.x=x;this.y=y;this.z=z;this.id=id;this.metadata=metadata;this.tag=tag==null?null:(NBTTagCompound)tag.copy();this.properties=properties;}
    }

    private void snapshotStand(WorldServer world) {
        require(expectedSavedBlocks.isEmpty(),"Native boat expectations already captured");
        for(int z:NODES){expectedSavedBlocks.add(snapshot(world,8,4,z));require((world.getBlock(8,3,z)==net.minecraft.init.Blocks.water||world.getBlock(8,3,z)==net.minecraft.init.Blocks.flowing_water)&&world.getBlockMetadata(8,3,z)==0,"Boat source water changed");expectedSavedBlocks.add(snapshot(world,8,3,z));}
        for(int start:new int[]{24,64})for(int z=start;z<=start+24;z++)for(int x=5;x<=7;x++)expectedSavedBlocks.add(snapshot(world,x,3,z));
        require(expectedSavedBlocks.size()==164,"Incomplete native boat Anvil expectations");
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
                Path marker=saveDirectory.toPath().resolve("mtr-boat-probe-owner.txt");
                require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&runId.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Owned boat save marker changed after shutdown");
                require(boardAnchor&&movingCaptured&&gravityFalling&&boatResourcesVerified&&waterItemUses==7&&fixtureSetupMoves==8,"Full actual boat scenario was not completed");
                verifySavedAnvil();verifySavedCore();
            }
        }catch(Throwable problem){fail(problem);}
        closeRidingLogObserver();report.addProperty("passed",failure==null);report.addProperty("setup_teleport_count",setupTeleports);
        report.addProperty("fixture_setup_packet_moves",fixtureSetupMoves);report.addProperty("original_water_item_right_clicks",waterItemUses);report.addProperty("boat_resources_verified",boatResourcesVerified);
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
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try {
            if(copyVerified)Files.write(new File(client.mcDataDir,"mtr-boat-probe-"+runId+".json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            System.out.println((failure==null?"MTR_BOAT_PROBE_PASS ":"MTR_BOAT_PROBE_FAIL ")+report);if(safeShutdown)client.shutdown();
        }catch(Throwable problem){problem.printStackTrace();throw new IllegalStateException("Could not write boat acceptance result",problem);}
    }
    private void verifySavedAnvil()throws Exception {
        require(new File(saveDirectory,"level.dat").isFile()&&expectedSavedBlocks.size()==164,"Actual new save/boat expectations missing");
        Map<String,NBTTagCompound> levels=new LinkedHashMap<>();Map<Path,byte[]> before=new LinkedHashMap<>();JsonArray persisted=new JsonArray();
        for(StoredBlock expected:expectedSavedBlocks) {
            int chunkX=Math.floorDiv(expected.x,16),chunkZ=Math.floorDiv(expected.z,16);String chunk=chunkX+","+chunkZ;
            if(!levels.containsKey(chunk)) {
                Path file=saveDirectory.toPath().resolve("region/r."+Math.floorDiv(chunkX,32)+"."+Math.floorDiv(chunkZ,32)+".mca");
                require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Actual saved boat Anvil region missing");
                Path real=file.toRealPath();require(real.startsWith(saveDirectory.toPath().toRealPath()),"Anvil path escaped owned save");
                if(!before.containsKey(file))before.put(file,sha256(real));levels.put(chunk,readSavedChunkReadOnly(saveDirectory,file.toFile(),chunkX,chunkZ));
            }
            NBTTagCompound level=levels.get(chunk),section=null;int slot=((expected.y&15)<<8)|((expected.z&15)<<4)|(expected.x&15);NBTTagList sections=level.getTagList("Sections",10);
            for(int index=0;index<sections.tagCount();index++){NBTTagCompound value=sections.getCompoundTagAt(index);if((value.getByte("Y")&255)==expected.y>>4)section=value;}
            require(section!=null,"Saved boat section missing");
            byte[] low=savedArray(section,"Blocks",4096,false),high=savedArray(section,"Add",2048,true),higher=savedArray(section,"BlocksB2Hi",2048,true),highest=savedArray(section,"BlocksB3",4096,true);
            int id=(low[slot]&255)|(high.length==0?0:nibble(high,slot)<<8)|(higher.length==0?0:nibble(higher,slot)<<12)|(highest.length==0?0:(highest[slot]&255)<<16);
            byte[] data=savedArray(section,"Data",2048,false),dataHigh=savedArray(section,"Data1High",2048,true),dataHighest=savedArray(section,"Data2",4096,true);
            int metadata=nibble(data,slot)|(dataHigh.length==0?0:nibble(dataHigh,slot)<<4)|(dataHighest.length==0?0:(dataHighest[slot]&255)<<8);
            require(id==expected.id&&metadata==expected.metadata,"Saved native boat ID/metadata differs");
            NBTTagCompound tag=null;NBTTagList tiles=level.getTagList("TileEntities",10);
            for(int index=0;index<tiles.tagCount();index++){NBTTagCompound value=tiles.getCompoundTagAt(index);if(value.getInteger("x")==expected.x&&value.getInteger("y")==expected.y&&value.getInteger("z")==expected.z){require(tag==null,"Duplicate saved native boat tile");tag=value;}}
            Block block=Block.getBlockById(id);BlockStateDefinition definition=BlockStateDefinition.forBlock(block);require((tag!=null)==(expected.tag!=null),"Saved boat TileEntity presence changed");
            Map<String,String> properties=storedProperties(block,metadata,tag);require(expected.properties.equals(properties),"Saved boat state properties changed through original codec");
            if(definition.requiresTileEntity())require(expected.tag.getCompoundTag("mtr_block_state").equals(tag.getCompoundTag("mtr_block_state")),"Saved extended boat state differs");
            if(tag!=null){TileEntity restored=TileEntity.createAndLoadEntity(tag);require(restored instanceof BlockStateTileEntity,"Actual saved boat tile ID cannot decode original state");require(properties.equals(((BlockStateTileEntity)restored).readMtrState(definition,metadata).data.getSerializedProperties()),"Saved boat TileEntity codec disagrees with original definition");}
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
                NBTTagCompound level=CompressedStreamTools.read(input).getCompoundTag("Level");require(level.hasKey("xPos",3)&&level.hasKey("zPos",3)&&level.getInteger("xPos")==chunkX&&level.getInteger("zPos")==chunkZ,"Saved chunk location differs from requested boat fixture");return level;
            }
        }
    }
    private void verifySavedCore()throws Exception {
        String[] kinds={"stations","platforms","sidings","routes","depots","rails"};int[] counts={2,2,1,1,1,6};Set<String> seen=new HashSet<>();JsonObject persisted=new JsonObject();
        require(expectedCore.size()==13&&coreSample!=null&&coreSample.riderCount==0&&!coreSample.onRoute&&!coreSample.moving,"Final original core expectations are incomplete or occupied");
        for(int kind=0;kind<kinds.length;kind++) {
            Path directory=saveDirectory.toPath().resolve("mtr/minecraft/overworld/"+kinds[kind]);require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"Actual original core "+kinds[kind]+" save directory missing");
            require(directory.toRealPath().startsWith(saveDirectory.toPath().toRealPath()),"Core boat save escaped owned save");List<Path> files=new ArrayList<>();
            try(Stream<Path> paths=Files.walk(directory)){paths.forEach(path->{require(!Files.isSymbolicLink(path),"Symlink in owned core boat save");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))files.add(path);});}
            require(files.size()==counts[kind],"Unexpected saved core "+kinds[kind]+" count: "+files.size());
            for(Path file:files) {
                String key=kinds[kind]+"/"+file.getFileName();JsonObject expected=expectedCore.get(key);require(expected!=null&&seen.add(key),"Saved core boat ID differs: "+key);JsonObject actual=readSavedCoreReadOnly(saveDirectory,file.toFile());
                if(kinds[kind].equals("sidings")) {
                    JsonObject a=copyJson(actual),e=copyJson(expected);a.remove("vehicles");e.remove("vehicles");require(e.equals(a),"Saved siding identity/configuration/generated paths differ");
                    JsonArray vehicles=actual.getAsJsonArray("vehicles"),expectedVehicles=expected.getAsJsonArray("vehicles");require(vehicles!=null&&vehicles.size()==1&&expectedVehicles!=null&&expectedVehicles.size()==1,"Saved original core boat count differs");
                    JsonObject boat=vehicles.get(0).getAsJsonObject(),idle=expectedVehicles.get(0).getAsJsonObject();require(savedVehicleMatches(boat,idle,vehicleId),"Saved original core boat identity/state differs");
                    // VehicleSchema persists motion, not VehicleExtraData; report that boundary explicitly.
                    boolean hasRiders=boat.has("ridingEntities");if(hasRiders)require(boat.getAsJsonArray("ridingEntities").size()==0,"Saved original boat still has riders");report.addProperty("saved_vehicle_rider_field_present",hasRiders);report.addProperty("empty_core_riders_verified_before_shutdown",true);report.add("saved_original_core_boat",boat);
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


}
