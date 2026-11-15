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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.*;
import net.minecraft.world.chunk.*;
import org.mtr.core.Main;
import org.mtr.core.data.*;
import org.mtr.core.operation.*;
import org.mtr.core.serializer.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.*;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.org.msgpack.core.*;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.tool.*;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.client.*;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.item.ItemBlockClickingBase;
import org.mtr.mod.item.ItemSignalModifier;
import org.mtr.mod.packet.*;
import org.mtr.mod.render.RenderSignalBase;
import org.mtr.mod.resource.VehicleResource;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.*;

/** Test-only SP signal traffic acceptance. Creates and reloads only its own UUID save. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_signals_traffic_probe",name="MTR GTNH native two-train signal traffic",version="0",dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class SignalsTrafficProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT=new File(requiredProbePath("mtr.probeClient"));
    private static final int[][] NODES={{0,0},{0,24},{0,56},{0,80},{16,96},{32,80},{32,48},{32,24},{32,0},{16,-16}};
    private static final int[] SIGNAL_Z={24,56};
    private static final int WHITE=ItemSignalModifier.COLORS[0];
    private static final String CAR="e44_mini_cab_3",A_NAME="Signal Probe A",B_NAME="Signal Probe B",D_NAME="Signal Probe Depot",R_NAME="Signal Probe A to B";
    private enum Stage {MENU,WORLD,SETUP,CHUNKS,DASHBOARD,LINK,AREAS,PLATFORMS,ROUTE,SIDING,DEPOT,GENERATE,TRAFFIC,QUIET,SAVE_CLOSE,RELOAD_MENU,RELOAD_WORLD,RELOAD_READBACK,CLOSE,FINISHED}
    private volatile Stage stage=Stage.MENU,clientAcknowledged;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private volatile long stationAId,stationBId,depotId,routeId,sidingId,platformAId,platformBId,idleVehicleId,generationRequestedAt,generationPreviousMillis,plannedDepartureMillis,plannedDepartureAbsolute;
    private volatile long startedAt,stageStartedAt;
    private volatile ClientSample clientSample;
    private volatile CoreSample coreSample;
    private volatile Simulator simulator;
    private final AtomicBoolean coreReadPending=new AtomicBoolean();
    private boolean copyVerified,dashboardObserved,linked,initialClear,blockingCaptured,firstRed,secondRed,firstExited,secondExited,reloaded,readbackCaptured;
    private int menuTicks,clientTicks,phaseTicks,readyChunkTicks,setupTeleports,stillTicks;
    private long firstId,secondId,waitingSince,waitingObservedMillis,reloadIdleId;
    private Main initialMain;
    private MinecraftClientData beforeReloadData;
    private String runId,saveName;
    private File saveDirectory;
    private volatile int shotRequest,shotAcknowledged;
    private volatile String shotLabel;
    private int renderedFrames;
    private final JsonObject report=new JsonObject();
    private final JsonArray timeline=new JsonArray(),transitions=new JsonArray(),screenshots=new JsonArray(),placements=new JsonArray(),trafficEvents=new JsonArray();
    private final Set<Long> scheduledIds=new LinkedHashSet<>(),physicallyObserved=new LinkedHashSet<>();
    private final List<StoredBlock> expectedSavedBlocks=new ArrayList<>();
    private final Map<String,JsonObject> expectedCore=new LinkedHashMap<>();

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=stageStartedAt=System.currentTimeMillis();
        for(ModContainer mod:Loader.instance().getModList())if(mod.getModId().startsWith("mtr_gtnh_")&&mod.getModId().endsWith("probe"))require(mod.getModId().equals("mtr_gtnh_signals_traffic_probe"),"Another gameplay probe is active: "+mod.getModId());
        FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null||stage==Stage.CLOSE){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<15*60*1000L,"Signals traffic total deadline in "+stage);
            if(stage==Stage.MENU) {
                if(!atMenu(client)){menuTicks=0;return;}if(++menuTicks<60)return;
                reserveNewSave(client);client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=0;client.gameSettings.renderDistanceChunks=8;client.gameSettings.saveOptions();
                advance(Stage.WORLD);WorldSettings settings=new WorldSettings(0x5349474E414C5350L,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT).enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName,"MTR signals traffic "+runId,settings);return;
            }
            if(stage==Stage.SAVE_CLOSE){saveCloseAndVerify(client);return;}
            if(stage==Stage.RELOAD_MENU) {
                if(!atMenu(client)){menuTicks=0;return;}if(++menuTicks<40)return;
                require(client.getSaveLoader().canLoadWorld(saveName),"Native loader refused the exact new owned save");assertSaveMarker();
                beforeReloadData=MinecraftClientData.getInstance();advance(Stage.RELOAD_WORLD);
                FMLClientHandler.instance().tryLoadExistingWorld(new GuiSelectWorld(new GuiMainMenu()),saveName,"MTR signals traffic "+runId);return;
            }
            if(client.getIntegratedServer()!=null)assertOwnedServer(client.getIntegratedServer());
            if(stage==Stage.WORLD) {
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                playerId=client.thePlayer.getUniqueID();initialMain=actualMain();report.addProperty("player_uuid",playerId.toString());advance(Stage.SETUP);return;
            }
            if(stage==Stage.RELOAD_WORLD) {
                require(System.currentTimeMillis()-stageStartedAt<90000,"Owned reload world deadline");
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                ClientWorld initialized=(ClientWorld)diagnosticField(InitClient.class,"lastClientWorld");if(initialized==null||initialized.data!=client.theWorld)return;
                Main next=actualMain();if(next==null||next==initialMain)return;
                require(MinecraftClientData.getInstance()!=beforeReloadData,"Owned native join did not reset client data");
                require(playerId.equals(client.thePlayer.getUniqueID()),"Reloaded owned player UUID changed");simulator=actualSimulator();coreSample=null;clientSample=null;
                reloaded=true;report.addProperty("reloaded_main_identity",System.identityHashCode(next));report.addProperty("reloaded_client_data_identity",System.identityHashCode(MinecraftClientData.getInstance()));advance(Stage.RELOAD_READBACK);
            }
            require(client.theWorld!=null&&client.thePlayer!=null&&client.getIntegratedServer()!=null,"Owned signal world disappeared");
            if(stage==Stage.DASHBOARD&&client.currentScreen instanceof org.mtr.mod.screen.DashboardScreen){dashboardObserved=true;report.addProperty("dashboard_native_screen_class",client.currentScreen.getClass().getName());}
            if(client.currentScreen instanceof GuiDownloadTerrain)return;
            if(client.currentScreen!=null)client.displayGuiScreen(null);KeyBinding.unPressAllKeys();
            if(clientAcknowledged!=stage&&configureClient(stage))clientAcknowledged=stage;
            clientSample=new ClientSample(client,++clientTicks);
            if(clientTicks%100==0)System.out.println("MTR_SIGNALS_TRAFFIC_PROBE_PROGRESS "+stage+" tick="+clientTicks);
        }catch(Throwable problem){fail(problem);}
    }
    private static boolean atMenu(Minecraft client) {
        if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.getIntegratedServer()!=null||client.currentScreen==null)return false;
        String screen=client.currentScreen.getClass().getName();return screen.equals("net.minecraft.client.gui.GuiMainMenu")||screen.equals("lumien.custommainmenu.gui.GuiCustom");
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null)return;Stage current=stage;
        if(current==Stage.MENU||current==Stage.WORLD||current==Stage.SAVE_CLOSE||current==Stage.RELOAD_MENU||current==Stage.RELOAD_WORLD||current==Stage.CLOSE||current==Stage.FINISHED)return;
        try {
            MinecraftServer server=MinecraftServer.getServer();assertOwnedServer(server);EntityPlayerMP player=actualPlayer(server);if(player==null){require(current==Stage.SETUP&&++phaseTicks<200,"Owned observer missing");return;}
            WorldServer world=server.worldServerForDimension(0);require(player.worldObj==world,"Observer in foreign dimension");
            require(System.currentTimeMillis()-stageStartedAt<(current==Stage.TRAFFIC?300000:current==Stage.CHUNKS||current==Stage.RELOAD_READBACK?90000:60000),"Signals traffic phase deadline in "+current);++phaseTicks;
            if(current==Stage.SETUP) {
                player.setGameType(WorldSettings.GameType.CREATIVE);player.capabilities.isFlying=false;player.sendPlayerAbilities();
                require(setupTeleports==0&&!PlayerPhysics.authorized(player),"Observer already moved or riding");player.playerNetServerHandler.setPlayerLocation(-28.5,4,40.5,-90,0);setupTeleports++;advance(Stage.CHUNKS);return;
            }
            require(!PlayerPhysics.authorized(player)&&!player.noClip&&!player.capabilities.isFlying,"Signals observer acquired rider/flight privilege");
            if(current==Stage.CHUNKS) {
                boolean ready=true;for(int x=-2;x<=2;x++)for(int z=-1;z<=6;z++)ready&=chunkStatus(world,x,z).get("ready").getAsBoolean();
                ClientSample client=clientSample;ready&=client!=null&&Math.abs(client.x+28.5)<.2&&Math.abs(client.z-40.5)<.2;
                readyChunkTicks=ready?readyChunkTicks+1:0;if(readyChunkTicks<2)return;
                buildStand(world,player);initialMain=actualMain();simulator=actualSimulator();report.addProperty("initial_main_identity",System.identityHashCode(initialMain));advance(Stage.DASHBOARD);
                Item item=originalItem("dashboard",org.mtr.mod.Items.RAILWAY_DASHBOARD.get().data);ItemStack stack=new ItemStack(item);player.inventory.mainInventory[0]=stack;item.onItemRightClick(stack,world,player);return;
            }
            readCoreSoon();ClientSample client=clientSample;CoreSample core=coreSample;if(client==null||core==null)return;
            require(System.currentTimeMillis()-core.at<5000,"Original simulator stopped publishing read-only traffic samples");
            require(!client.riding&&!client.noClip&&!client.flying,"Native observer began riding");
            SignalSample nativeSignals=signals(world,false);if(phaseTicks%5==0)observe(world,player,client,core,nativeSignals);
            if(current==Stage.DASHBOARD&&core.railsComplete&&client.fixtureReady&&dashboardObserved&&client.dashboardSavedRailsReady) {
                sidingId=core.siding;platformAId=core.platformA;platformBId=core.platformB;require(sidingId!=0&&platformAId!=0&&platformBId!=0,"Original saved rail IDs missing");
                player.inventory.mainInventory[0]=null;advance(Stage.LINK);
            } else if(current==Stage.LINK) {
                if(!linked){linkWhiteRails(world,player);linked=true;return;}
                if(clientAcknowledged!=current||!core.whiteLinked||!client.whiteLinked||!nativeSignals.configured||!client.signals.configured||!client.aspectsReady)return;
                if(!nativeSignals.zero||!client.signals.zero||!core.reservationOwners.isEmpty())return;
                initialClear=true;report.add("initial_clear_signals",nativeSignals.json());if(captureDone("initial-clear"))advance(Stage.AREAS);
            } else if(current==Stage.AREAS&&clientAcknowledged==current&&core.areasConfigured)advance(Stage.PLATFORMS);
            else if(current==Stage.PLATFORMS&&clientAcknowledged==current&&core.platformsConfigured)advance(Stage.ROUTE);
            else if(current==Stage.ROUTE&&clientAcknowledged==current&&core.routeConfigured)advance(Stage.SIDING);
            else if(current==Stage.SIDING&&clientAcknowledged==current&&core.sidingConfigured)advance(Stage.DEPOT);
            else if(current==Stage.DEPOT&&clientAcknowledged==current&&core.depotConfigured)advance(Stage.GENERATE);
            else if(current==Stage.GENERATE&&clientAcknowledged==current) {
                require(!core.generationFailed,"Original loop route generation failed: "+core.depotJson);if(!core.generated)return;
                report.add("generated_original_depot",core.depotJson);report.add("generated_siding_paths",core.sidingJson);advance(Stage.TRAFFIC);
            } else if(current==Stage.TRAFFIC) {
                monitorTraffic(core,client,nativeSignals);
                if(blockingCaptured&&firstRed&&secondRed&&firstExited&&secondExited&&physicallyObserved.size()==2){report.addProperty("two_distinct_scheduled_trains_accepted",true);advance(Stage.QUIET);}
            } else if(current==Stage.QUIET) {
                boolean quiet=core.allIdle&&core.vehicles.size()==1&&core.reservationOwners.isEmpty()&&nativeSignals.zero&&client.signals.zero&&!client.whiteOccupied;
                stillTicks=quiet?stillTicks+1:0;if(stillTicks<100)return;
                if(!captureDone("final-clear"))return;idleVehicleId=core.vehicles.get(0).id;snapshotStand(world);expectedCore.putAll(core.fullData);report.add("pre_save_core",core.json());report.add("pre_save_signals",nativeSignals.json());advance(Stage.SAVE_CLOSE);
            } else if(current==Stage.RELOAD_READBACK) {
                if(!core.railsComplete||!core.whiteLinked||!core.areasConfigured||!core.platformsConfigured||!core.routeConfigured||!core.sidingConfigured||!core.depotConfigured||!core.generated||!core.allIdle||core.vehicles.size()!=1||!core.reservationOwners.isEmpty()||!client.fixtureReady||!client.whiteLinked||!client.aspectsReady||client.whiteOccupied||!nativeSignals.configured||!nativeSignals.zero||!client.signals.configured||!client.signals.zero)return;
                verifyRuntimeBlocks(world);if(!client.blocksMatch)return;verifyReloadCore(core);
                VehicleSample idle=core.vehicles.get(0);if(!client.vehicleIds.contains(idle.id))return;
                stillTicks++;if(stillTicks<40)return;report.add("reloaded_native_signals",nativeSignals.json());report.add("reloaded_core",core.json());
                if(!captureDone("reload-readback"))return;readbackCaptured=true;advance(Stage.CLOSE);
            }
        }catch(Throwable problem){fail(problem);}
    }
    private void monitorTraffic(CoreSample core,ClientSample client,SignalSample nativeSignals) {
        require(core.whiteLinked&&nativeSignals.configured&&client.signals.configured,"Active signal block configuration changed");
        require(core.reservationOwners.size()<=1&&core.physicalOwners.size()<=1,"Two trains conflict inside white signal block");
        for(VehicleSample value:core.vehicles)if(value.onRoute) {
            if(scheduledIds.add(value.id)){require(scheduledIds.size()<=2,"More than two natural scheduled trains");event("natural-departure",value.json());}
            if(value.departureIndex==0){require(firstId==0||firstId==value.id,"First departure identity changed");firstId=value.id;}
            else if(value.departureIndex==1){require(secondId==0||secondId==value.id,"Second departure identity changed");secondId=value.id;}
            else throw new AssertionError("Unexpected original departure index "+value.departureIndex);
        }
        for(long owner:core.physicalOwners) {
            require(scheduledIds.contains(owner),"Physical block owner was never naturally scheduled");
            if(physicallyObserved.add(owner))event("physical-white-block-entry",core.json());
            if(core.reservationOwners.contains(owner)&&nativeSignals.fifteen&&client.signals.fifteen&&client.whiteOccupied&&client.vehicleIds.contains(owner)) {
                if(owner==firstId&&!firstRed){firstRed=true;event("first-owner-native-power-15",nativeSignals.json());}
                if(owner==secondId&&!secondRed){secondRed=true;event("second-owner-native-power-15",nativeSignals.json());}
            }
        }
        VehicleSample first=core.find(firstId),second=core.find(secondId);
        if(first!=null&&second!=null&&first.stoppedPlatform==platformBId&&!first.moving&&first.inWhite&&second.onRoute&&!second.moving&&!second.inWhite&&second.front>=second.whiteStart-3&&second.front<=second.whiteStart+.05&&first.rear-second.front>30&&core.reservationOwners.size()==1&&core.reservationOwners.contains(first.id)&&nativeSignals.fifteen) {
            if(waitingSince==0)waitingSince=System.currentTimeMillis();waitingObservedMillis=Math.max(waitingObservedMillis,System.currentTimeMillis()-waitingSince);
            if(waitingObservedMillis>=6000&&!blockingCaptured&&captureDone("second-waits-for-first")){blockingCaptured=true;event("second-held-by-signal-over-6s",core.json());}
        } else waitingSince=0;
        if(firstId!=0&&physicallyObserved.contains(firstId)&&(first==null||!first.inWhite&&(!first.onRoute||first.front>first.whiteEnd+first.length))&&!firstExited){firstExited=true;event("first-left-white-block",core.json());}
        if(secondId!=0&&physicallyObserved.contains(secondId)&&(second==null||!second.inWhite&&(!second.onRoute||second.front>second.whiteEnd+second.length))&&!secondExited){secondExited=true;event("second-left-white-block",core.json());}
    }
    private void event(String name,JsonObject evidence){JsonObject value=new JsonObject();value.addProperty("event",name);value.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt);value.add("evidence",evidence);trafficEvents.add(value);}
    private static void send(UpdateDataRequest request){InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(request));}
    private boolean configureClient(Stage current) {
        MinecraftClientData data=MinecraftClientData.getDashboardInstance();
        if(current==Stage.LINK) {
            for(int z:SIGNAL_Z){IntAVLTreeSet colors=new IntAVLTreeSet();colors.add(WHITE);InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateSignalConfig(new BlockPos(2,4,z),false,true,colors,false));}return true;
        } else if(current==Stage.AREAS) {
            Station a=new Station(data),b=new Station(data);Depot d=new Depot(TransportMode.TRAIN,data);
            a.setName(A_NAME);a.setColor(0x3366CC);a.setCorners(new Position(-5,Long.MIN_VALUE,-2),new Position(3,Long.MAX_VALUE,26));
            b.setName(B_NAME);b.setColor(0x33AA66);b.setCorners(new Position(-5,Long.MIN_VALUE,54),new Position(3,Long.MAX_VALUE,82));
            d.setName(D_NAME);d.setColor(0xAA6633);d.setCorners(new Position(28,Long.MIN_VALUE,22),new Position(36,Long.MAX_VALUE,50));
            stationAId=a.getId();stationBId=b.getId();depotId=d.getId();send(new UpdateDataRequest(data).addStation(a).addStation(b).addDepot(d));return true;
        } else if(current==Stage.PLATFORMS) {
            Platform a=data.platformIdMap.get(platformAId),b=data.platformIdMap.get(platformBId);if(a==null||b==null)return false;
            a.setName("1");b.setName("1");a.setDwellTime(2000);b.setDwellTime(20000);send(new UpdateDataRequest(data).addPlatform(a).addPlatform(b));return true;
        } else if(current==Stage.ROUTE) {
            if(!data.platformIdMap.containsKey(platformAId)||!data.platformIdMap.containsKey(platformBId))return false;
            Route route=new Route(TransportMode.TRAIN,data);route.setName(R_NAME);route.setColor(0x3366CC);route.setCircularState(Route.CircularState.NONE);
            for(long id:new long[]{platformAId,platformBId}){RoutePlatformData row=new RoutePlatformData(id);route.getRoutePlatforms().add(row);row.writePlatformCache(route,data.platformIdMap);}routeId=route.getId();send(new UpdateDataRequest(data).addRoute(route));return true;
        } else if(current==Stage.SIDING) {
            Siding siding=data.sidingIdMap.get(sidingId);if(siding==null)return false;final VehicleResource[] selected={null};CustomResourceLoader.getVehicleById(TransportMode.TRAIN,CAR,details->{if(CAR.equals(details.left().getId()))selected[0]=details.left();});
            if(selected[0]==null||selected[0].getCachedVehicleResource(0,1,false)==null)return false;VehicleResource resource=selected[0];
            ObjectArrayList<VehicleCar> cars=new ObjectArrayList<>();cars.add(new VehicleCar(resource.getId(),resource.getLength(),resource.getWidth(),resource.getBogie1Position(),resource.getBogie2Position(),resource.getCouplingPadding1(),resource.getCouplingPadding2()));
            require(Math.abs(Siding.getTotalVehicleLength(cars)-9)<1E-6,"Stock E44 length changed");siding.setVehicleCars(cars);siding.setMaxVehicles(2);siding.setAcceleration(Siding.ACCELERATION_DEFAULT);siding.setDeceleration(Siding.ACCELERATION_DEFAULT);send(new UpdateDataRequest(data).addSiding(siding));report.add("configured_original_car",full(cars.get(0)));return true;
        } else if(current==Stage.DEPOT) {
            Depot depot=data.depotIdMap.get(depotId);if(depot==null||!data.routeIdMap.containsKey(routeId))return false;
            depot.getRouteIds().clear();depot.getRouteIds().add(routeId);depot.setRepeatInfinitely(false);depot.setUseRealTime(true);
            plannedDepartureAbsolute=(System.currentTimeMillis()+120000)/1000*1000;plannedDepartureMillis=Math.floorMod(plannedDepartureAbsolute,86400000L);
            depot.getRealTimeDepartures().clear();depot.getRealTimeDepartures().add(plannedDepartureMillis);depot.getRealTimeDepartures().add(Math.floorMod(plannedDepartureMillis+16000,86400000L));send(new UpdateDataRequest(data).addDepot(depot));
            report.addProperty("planned_departure_absolute_ms",plannedDepartureAbsolute);report.addProperty("departure_interval_ms",16000);report.addProperty("planned_departure_millis_of_day",plannedDepartureMillis);report.addProperty("schedule_timezone",TimeZone.getDefault().getID());return true;
        } else if(current==Stage.GENERATE) {
            if(coreSample==null)return false;generationPreviousMillis=coreSample.generationMillis;generationRequestedAt=System.currentTimeMillis();DepotOperationByIds operation=new DepotOperationByIds();operation.addDepotId(depotId);InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDepotGenerate(operation));return true;
        }
        return false;
    }
    private static Position node(int i){return new Position(NODES[i][0],4,NODES[i][1]);}
    private static String edgeId(int i){return TwoPositionsBase.getHexId(node(i),node((i+1)%NODES.length));}
    private void buildStand(WorldServer world,EntityPlayerMP player) {
        world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.setWorldTime(6000);world.difficultySetting=EnumDifficulty.PEACEFUL;
        for(int i=0;i<NODES.length;i++) {
            int x=NODES[i][0],z=NODES[i][1];world.setBlock(x,3,z,net.minecraft.init.Blocks.stone);player.rotationYaw=i==4||i==9?90:0;
            ItemStack stack=new ItemStack(originalItem("rail",Item.getItemFromBlock(org.mtr.mod.Blocks.RAIL_NODE.get().data)));use(world,player,stack,x,3,z,1,.5F);require(stack.stackSize==0,"Original train node not consumed");
            require(Math.abs(BlockNode.getAngle(state(world,x,4,z))-(i==4||i==9?0:90))<.01,"Original node axis differs");
        }
        player.rotationYaw=-90;
        for(int start:new int[]{0,56})for(int z=start;z<=start+24;z++)for(int x=-4;x<=-2;x++) {
            ItemStack stack=new ItemStack(originalItem("platform",Item.getItemFromBlock(org.mtr.mod.Blocks.PLATFORM.get().data)));use(world,player,stack,x,3,z,1,.5F);require(stack.stackSize==0,"Original platform not consumed");
        }
        player.rotationYaw=0;
        for(int z:SIGNAL_Z) {
            world.setBlock(2,3,z,net.minecraft.init.Blocks.stone);ItemStack stack=new ItemStack(originalItem("signal_light_1",Item.getItemFromBlock(org.mtr.mod.Blocks.SIGNAL_LIGHT_2_ASPECT_1.get().data)));use(world,player,stack,2,3,z,1,.5F);
            require(stack.stackSize==0&&world.getTileEntity(2,4,z) instanceof BlockSignalBase.BlockEntityBase&&Math.abs(BlockSignalBase.getAngle(state(world,2,4,z)))<.01,"Original two-aspect signal state differs");
        }
        for(int i=0;i<NODES.length;i++) {
            String name=i==0||i==2?"rail_connector_platform":i==6?"rail_connector_siding":"rail_connector_20_one_way";Item item=(Item)Item.itemRegistry.getObject("mtr:"+name);require(item instanceof org.mtr.mod.item.ItemRailModifier,"Original one-way train connector missing");
            connect(world,player,new ItemStack(item),i);
        }
        player.inventory.mainInventory[0]=null;player.inventory.currentItem=0;report.addProperty("original_native_node_count",10);report.addProperty("original_native_platform_block_count",150);report.addProperty("original_native_signal_count",2);
    }
    private void linkWhiteRails(WorldServer world,EntityPlayerMP player) {
        require(stage==Stage.LINK&&coreSample!=null&&coreSample.railsComplete,"Signal connector requires acknowledged original rails");
        Item item=(Item)Item.itemRegistry.getObject("mtr:signal_connector_white");require(item instanceof ItemSignalModifier,"Original white ItemSignalModifier missing");
        connect(world,player,new ItemStack(item),1);connect(world,player,new ItemStack(item),2);player.inventory.mainInventory[0]=null;
        report.addProperty("original_signal_modifier_click_pairs",2);report.addProperty("white_signal_color",WHITE);
    }
    private void connect(WorldServer world,EntityPlayerMP player,ItemStack stack,int edge) {
        Position a=node(edge),b=node((edge+1)%NODES.length);use(world,player,stack,(int)a.getX(),4,(int)a.getZ(),1,.5F);
        require(stack.hasTagCompound()&&stack.getTagCompound().getLong(ItemBlockClickingBase.TAG_POS)==new BlockPos((int)a.getX(),4,(int)a.getZ()).asLong(),"Original connector first-click state missing");
        use(world,player,stack,(int)b.getX(),4,(int)b.getZ(),1,.5F);require(!stack.getTagCompound().hasKey(ItemBlockClickingBase.TAG_POS)&&stack.stackSize==1,"Original reusable connector pair failed");
    }
    private static boolean completeRails(Iterable<Rail> rails) {
        boolean[] seen=new boolean[10];int count=0;
        for(Rail rail:rails) {
            int edge=-1;for(int i=0;i<NODES.length;i++)if(edgeId(i).equals(rail.getHexId()))edge=i;
            if(edge<0||seen[edge]||rail.getTransportMode()!=TransportMode.TRAIN||!rail.isValid()||rail.isSiding()!=(edge==6)||rail.isPlatform()!=(edge==0||edge==2)||rail.canTurnBack())return false;
            if(edge!=0&&edge!=2&&edge!=6){boolean reversed=node(edge).compareTo(node((edge+1)%NODES.length))>0;if(rail.getSpeedLimitKilometersPerHour(reversed)!=20||rail.getSpeedLimitKilometersPerHour(!reversed)!=0)return false;}
            seen[edge]=true;count++;
        }return count==10;
    }
    private static boolean whiteLinked(Iterable<Rail> rails) {
        int count=0;for(Rail rail:rails){boolean protectedRail=edgeId(1).equals(rail.getHexId())||edgeId(2).equals(rail.getHexId());if(protectedRail){if(rail.getSignalColors().size()!=1||!rail.getSignalColors().contains(WHITE))return false;count++;}else if(!rail.getSignalColors().isEmpty())return false;}return count==2;
    }
    private static boolean fixtureReady(net.minecraft.world.World world) {
        try {
            for(int i=0;i<NODES.length;i++)if(world.getBlock(NODES[i][0],4,NODES[i][1])!=org.mtr.mod.Blocks.RAIL_NODE.get().data||!"true".equals(state(world,NODES[i][0],4,NODES[i][1]).data.getSerializedProperties().get("is_connected")))return false;
            for(int start:new int[]{0,56})for(int z=start;z<=start+24;z++)for(int x=-4;x<=-2;x++)if(world.getBlock(x,4,z)!=org.mtr.mod.Blocks.PLATFORM.get().data)return false;
            for(int z:SIGNAL_Z)if(world.getBlock(2,4,z)!=org.mtr.mod.Blocks.SIGNAL_LIGHT_2_ASPECT_1.get().data)return false;return true;
        }catch(IllegalArgumentException|IllegalStateException waiting){return false;}
    }
    private static boolean isSignal(int x,int y,int z){return x==2&&y==4&&(z==24||z==56);}
    private static SignalSample signals(net.minecraft.world.World world,boolean client) {return new SignalSample(world,client);}
    private static final class SignalSample {
        final boolean configured,zero,fifteen;final JsonArray values=new JsonArray();
        SignalSample(net.minecraft.world.World world,boolean client) {
            boolean configured=true,zero=true,fifteen=true;
            for(int z:SIGNAL_Z) {
                JsonObject value=new JsonObject();value.addProperty("x",2);value.addProperty("y",4);value.addProperty("z",z);TileEntity tile=world.getTileEntity(2,4,z);Block block=world.getBlock(2,4,z);
                boolean exact=block==org.mtr.mod.Blocks.SIGNAL_LIGHT_2_ASPECT_1.get().data&&tile instanceof BlockSignalBase.BlockEntityBase;
                if(exact) {
                    BlockSignalBase.BlockEntityBase entity=(BlockSignalBase.BlockEntityBase)tile;Map<String,String> properties=state(world,2,4,z).data.getSerializedProperties();int power=Integer.parseInt(properties.get("power"));int weak=block.isProvidingWeakPower(world,2,4,z,2);
                    boolean config=!entity.getAcceptRedstone()&&entity.getOutputRedstone()&&!entity.isDoubleSided&&entity.getSignalColors(false).size()==1&&entity.getSignalColors(false).contains(WHITE)&&entity.getSignalColors(true).isEmpty()&&Math.abs(BlockSignalBase.getAngle(state(world,2,4,z)))<.01;
                    configured&=config;zero&=power==0&&weak==0;fifteen&=power==15&&weak==15;
                    value.addProperty("configured",config);value.addProperty("native_power",power);value.addProperty("native_weak_power",weak);value.addProperty("angle",BlockSignalBase.getAngle(state(world,2,4,z)));NBTTagCompound tag=new NBTTagCompound();tile.writeToNBT(tag);value.addProperty("native_nbt",tag.toString());
                } else {configured=zero=fifteen=false;value.addProperty("missing_exact_tile",true);}values.add(value);
            }
            this.configured=configured;this.zero=zero;this.fifteen=fifteen;
        }
        JsonObject json(){JsonObject value=new JsonObject();value.addProperty("configured",configured);value.addProperty("both_native_power_0",zero);value.addProperty("both_native_power_15",fifteen);value.add("signals",values);return value;}
    }
    private final class ClientSample {
        final int tick;final double x,z;final boolean riding,noClip,flying,fixtureReady,dashboardSavedRailsReady,whiteLinked,aspectsReady,whiteOccupied,blocksMatch;final SignalSample signals;
        final Set<Long> vehicleIds=new LinkedHashSet<>();final JsonArray vehicles=new JsonArray(),aspects=new JsonArray();
        ClientSample(Minecraft client,int tick)throws Exception {
            this.tick=tick;x=client.thePlayer.posX;z=client.thePlayer.posZ;noClip=client.thePlayer.noClip;flying=client.thePlayer.capabilities.isFlying;
            MinecraftClientData data=MinecraftClientData.getInstance();fixtureReady=fixtureReady(client.theWorld);whiteLinked=whiteLinked(data.rails);dashboardSavedRailsReady=MinecraftClientData.getDashboardInstance().platforms.size()==2&&MinecraftClientData.getDashboardInstance().sidings.size()==1;
            boolean anyRider=false;for(VehicleExtension value:data.vehicles){require(vehicleIds.add(value.getId())&&vehicleIds.size()<=2,"Duplicate or extra client train");require(correctCar(value),"Client original E44 changed");anyRider|=VehicleRidingMovement.isRiding(value.getId());vehicles.add(Utilities.getJsonObjectFromData(value));}riding=anyRider;
            signals=signals(client.theWorld,true);boolean ready=true,occupied=true;
            for(int signalZ:SIGNAL_Z) {
                RenderSignalBase.AspectState aspect=RenderSignalBase.getAspectState(new BlockPos(2,4,signalZ),90);JsonObject entry=new JsonObject();entry.addProperty("z",signalZ);
                if(aspect==null){ready=false;occupied=false;entry.addProperty("missing",true);}else {
                    IntAVLTreeSet colors=(IntAVLTreeSet)diagnosticField(aspect,"occupiedColors");Collection<?> rails=(Collection<?>)diagnosticField(aspect,"railIds");String expected=edgeId(signalZ==24?1:2);
                    ready&=aspect.detectedColors.contains(WHITE)&&rails.contains(expected);occupied&=colors.contains(WHITE);entry.addProperty("white_detected",aspect.detectedColors.contains(WHITE));entry.addProperty("white_occupied",colors.contains(WHITE));entry.addProperty("expected_adjacent_rail",expected);entry.addProperty("expected_adjacent_rail_present",rails.contains(expected));entry.addProperty("node_blocked",(Boolean)diagnosticField(aspect,"nodeBlocked"));
                }aspects.add(entry);
            }
            aspectsReady=ready;whiteOccupied=occupied;
            boolean matches=false;if(reloaded)try{verifyRuntimeBlocks(client.theWorld);matches=true;}catch(AssertionError|IllegalArgumentException|IllegalStateException waiting){}blocksMatch=matches;
        }
        JsonObject json(){JsonObject value=new JsonObject();value.addProperty("tick",tick);value.addProperty("x",x);value.addProperty("z",z);value.addProperty("riding",riding);value.addProperty("white_rails_linked",whiteLinked);value.addProperty("aspects_ready",aspectsReady);value.addProperty("both_white_aspects_occupied",whiteOccupied);value.add("signals",signals.json());value.add("aspects",aspects);value.add("vehicles",vehicles);return value;}
    }
    private final class VehicleSample {
        final long id,departureIndex,stoppedPlatform;final boolean onRoute,moving,inWhite;final double front,rear,length,whiteStart,whiteEnd;final JsonObject serialized;
        VehicleSample(Vehicle vehicle) {
            require(correctCar(vehicle),"Original core E44 changed");id=vehicle.getId();onRoute=vehicle.getIsOnRoute();moving=vehicle.isMoving();serialized=Utilities.getJsonObjectFromData(vehicle);departureIndex=vehicle.getDepartureIndex();front=serialized.get("railProgress").getAsDouble();
            length=vehicle.vehicleExtraData.getTotalVehicleLength();rear=front-length;double start=Double.POSITIVE_INFINITY,end=Double.NEGATIVE_INFINITY;boolean physical=false;
            for(PathData path:vehicle.vehicleExtraData.immutablePath)if(edgeId(1).equals(path.getRail().getHexId())||edgeId(2).equals(path.getRail().getHexId())){start=Math.min(start,path.getStartDistance());end=Math.max(end,path.getEndDistance());physical|=onRoute&&front>path.getStartDistance()+.01&&rear<path.getEndDistance()-.01;}
            whiteStart=start;whiteEnd=end;inWhite=physical;if(onRoute)require(Double.isFinite(start)&&Math.abs(end-start-56)<.01,"Generated original path does not cross the exact56m white block");stoppedPlatform=stoppedPlatform(vehicle.vehicleExtraData.immutablePath,serialized.get("nextStoppingIndexAto").getAsInt(),front);
            final int[] riders={0};vehicle.vehicleExtraData.iterateRidingEntities(row->riders[0]++);require(riders[0]==0,"Signal traffic unexpectedly acquired passenger");
        }
        JsonObject json(){JsonObject value=new JsonObject();value.addProperty("id",Long.toString(id));value.addProperty("departure_index",departureIndex);value.addProperty("on_route",onRoute);value.addProperty("moving",moving);value.addProperty("physically_in_white_block",inWhite);value.addProperty("front",front);value.addProperty("rear",rear);value.addProperty("length",length);if(Double.isFinite(whiteStart)){value.addProperty("white_start",whiteStart);value.addProperty("white_end",whiteEnd);}value.addProperty("stopped_platform",Long.toString(stoppedPlatform));value.add("original_vehicle",serialized);return value;}
    }
    private final class CoreSample {
        final long at=System.currentTimeMillis(),siding,platformA,platformB,generationMillis;
        final boolean railsComplete,whiteLinked,areasConfigured,platformsConfigured,routeConfigured,sidingConfigured,depotConfigured,generated,generationFailed,allIdle;
        final JsonObject depotJson,sidingJson,reservations=new JsonObject();final Map<String,JsonObject> fullData=new LinkedHashMap<>();final List<VehicleSample> vehicles=new ArrayList<>();
        final Set<Long> reservationOwners=new LinkedHashSet<>(),physicalOwners=new LinkedHashSet<>();
        CoreSample(Simulator data)throws Exception {
            railsComplete=completeRails(data.rails);whiteLinked=whiteLinked(data.rails);Platform a=null,b=null;Siding s=null;Station sa=null,sb=null;Depot d=null;Route r=null;
            for(Platform value:data.platforms){if(value.containsPos(node(0))&&value.containsPos(node(1)))a=value;if(value.containsPos(node(2))&&value.containsPos(node(3)))b=value;}
            for(Siding value:data.sidings)if(value.containsPos(node(6))&&value.containsPos(node(7)))s=value;
            for(Station value:data.stations){if(value.getId()==stationAId)sa=value;if(value.getId()==stationBId)sb=value;}for(Depot value:data.depots)if(value.getId()==depotId)d=value;for(Route value:data.routes)if(value.getId()==routeId)r=value;
            siding=s==null?0:s.getId();platformA=a==null?0:a.getId();platformB=b==null?0:b.getId();
            areasConfigured=sa!=null&&sb!=null&&d!=null&&a!=null&&b!=null&&s!=null&&a.area==sa&&b.area==sb&&s.area==d&&sa.getName().equals(A_NAME)&&sb.getName().equals(B_NAME)&&d.getName().equals(D_NAME)&&data.stations.size()==2&&data.depots.size()==1;
            platformsConfigured=areasConfigured&&a.getDwellTime()==2000&&b.getDwellTime()==20000;
            routeConfigured=r!=null&&r.getTransportMode()==TransportMode.TRAIN&&r.getRoutePlatforms().size()==2&&r.getRoutePlatforms().get(0).getPlatform()!=null&&r.getRoutePlatforms().get(1).getPlatform()!=null&&r.getRoutePlatforms().get(0).getPlatform().getId()==platformAId&&r.getRoutePlatforms().get(1).getPlatform().getId()==platformBId&&r.getCircularState()==Route.CircularState.NONE&&data.routes.size()==1;
            sidingConfigured=s!=null&&s.getMaxVehicles()==2&&!s.getIsManual()&&s.getVehicleCars().size()==1&&CAR.equals(s.getVehicleCars().get(0).getVehicleId())&&Math.abs(Siding.getTotalVehicleLength(s.getVehicleCars())-9)<1E-6;
            depotConfigured=d!=null&&d.getRouteIds().size()==1&&d.getRouteIds().getLong(0)==routeId&&!d.getRepeatInfinitely()&&d.getUseRealTime()&&d.getRealTimeDepartures().size()==2&&d.getRealTimeDepartures().contains(plannedDepartureMillis)&&d.getRealTimeDepartures().contains(Math.floorMod(plannedDepartureMillis+16000,86400000L));
            depotJson=d==null?new JsonObject():full(d);sidingJson=s==null?new JsonObject():full(s);generationMillis=d==null?0:d.getLastGeneratedMillis();
            generated=d!=null&&s!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()==Depot.GeneratedStatus.SUCCESSFUL&&!d.getPath().isEmpty()&&!((Collection<?>)diagnosticField(s,"pathSidingToMainRoute")).isEmpty()&&!((Collection<?>)diagnosticField(s,"pathMainRouteToSiding")).isEmpty();
            generationFailed=d!=null&&generationRequestedAt>0&&generationMillis>0&&generationMillis!=generationPreviousMillis&&d.getLastGeneratedStatus()!=Depot.GeneratedStatus.SUCCESSFUL;
            boolean idle=true;if(s!=null)for(Object value:(Iterable<?>)diagnosticField(s,"vehicles")){VehicleSample sample=new VehicleSample((Vehicle)value);require(vehicles.size()<2,"Original siding exceeds maxVehicles2");vehicles.add(sample);idle&=!sample.onRoute&&!sample.moving;if(sample.inWhite)physicalOwners.add(sample.id);}allIdle=idle;
            for(Rail rail:data.rails)if(edgeId(1).equals(rail.getHexId())||edgeId(2).equals(rail.getHexId())) {
                JsonObject maps=new JsonObject();for(String field:new String[]{"preBlockedVehicleIds","currentlyBlockedVehicleIds","preBlockedVehicleIdsOld","currentlyBlockedVehicleIdsOld"}) {
                    JsonObject entries=new JsonObject();for(Map.Entry<?,?> entry:((Map<?,?>)diagnosticField(rail,field)).entrySet()){require(((Number)entry.getKey()).intValue()==WHITE,"Unexpected signal reservation color");long owner=((Number)entry.getValue()).longValue();require(owner!=0,"Manual reservation unexpectedly present");reservationOwners.add(owner);entries.addProperty(entry.getKey().toString(),Long.toString(owner));}maps.add(field,entries);
                }reservations.add(rail.getHexId(),maps);
            }
            require(reservationOwners.size()<=1&&physicalOwners.size()<=1,"Actual simulator has conflicting white block owners");
            for(Station value:data.stations)fullData.put("stations/"+value.getHexId(),full(value));for(Platform value:data.platforms)fullData.put("platforms/"+value.getHexId(),full(value));for(Siding value:data.sidings)fullData.put("sidings/"+value.getHexId(),full(value));for(Route value:data.routes)fullData.put("routes/"+value.getHexId(),full(value));for(Depot value:data.depots)fullData.put("depots/"+value.getHexId(),full(value));for(Rail value:data.rails)fullData.put("rails/"+value.getHexId(),full(value));
        }
        VehicleSample find(long id){for(VehicleSample value:vehicles)if(value.id==id)return value;return null;}
        JsonObject json(){JsonObject value=new JsonObject();value.addProperty("rails_complete",railsComplete);value.addProperty("white_linked",whiteLinked);value.addProperty("areas_configured",areasConfigured);value.addProperty("platforms_configured",platformsConfigured);value.addProperty("route_configured",routeConfigured);value.addProperty("siding_configured",sidingConfigured);value.addProperty("depot_configured",depotConfigured);value.addProperty("generated",generated);value.addProperty("all_idle",allIdle);value.add("reservations",reservations);JsonArray owners=new JsonArray();for(long id:reservationOwners)owners.add(new JsonPrimitive(Long.toString(id)));value.add("reservation_owners",owners);JsonArray physical=new JsonArray();for(long id:physicalOwners)physical.add(new JsonPrimitive(Long.toString(id)));value.add("physical_owners",physical);JsonArray rows=new JsonArray();for(VehicleSample row:vehicles)rows.add(row.json());value.add("vehicles",rows);return value;}
    }
    private static long stoppedPlatform(List<PathData> path,int index,double progress){if(index<0||index>=path.size())return 0;PathData stop=path.get(index);return stop.getDwellTime()>0&&Math.abs(stop.getEndDistance()-progress)<.001?stop.getSavedRailBaseId():0;}
    private static Main actualMain()throws Exception{return (Main)diagnosticField(Init.class,"main");}
    private static Simulator actualSimulator()throws Exception {
        Main main=actualMain();require(main!=null,"Original server Main missing");for(Object candidate:(Iterable<?>)diagnosticField(main,"simulators")){Simulator value=(Simulator)candidate;if("minecraft/overworld".equals(value.dimension))return value;}throw new AssertionError("Original overworld simulator missing");
    }
    private void readCoreSoon() {
        Simulator target=simulator;if(target==null||!coreReadPending.compareAndSet(false,true))return;
        target.run(()->{try{CoreSample sample=new CoreSample(target);if(simulator==target)coreSample=sample;}catch(Throwable problem){fail(problem);}finally{coreReadPending.set(false);}});
    }
    private void observe(WorldServer world,EntityPlayerMP player,ClientSample client,CoreSample core,SignalSample signals) {
        JsonObject value=new JsonObject();value.addProperty("phase",stage.name());value.addProperty("server_world_tick",world.getTotalWorldTime());value.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt);value.add("client",client.json());value.add("core",core.json());value.add("native_signals",signals.json());value.add("observer",playerPosition(player));timeline.add(value);
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null||shotRequest==shotAcknowledged)return;
        try{Minecraft client=Minecraft.getMinecraft();if(client.theWorld==null||client.thePlayer==null||++renderedFrames<4)return;require(client.currentScreen==null,"World framebuffer not rendered");String name="mtr-signals-traffic-probe-"+runId+"-"+shotLabel+".png";ScreenShotHelper.saveScreenshot(client.mcDataDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());File file=new File(new File(client.mcDataDir,"screenshots"),name);require(file.isFile()&&file.length()>100,"Actual signal framebuffer missing");JsonObject value=new JsonObject();value.addProperty("phase",shotLabel);value.addProperty("path",file.getCanonicalPath());value.addProperty("render_frames_after_request",renderedFrames);screenshots.add(value);shotAcknowledged=shotRequest;}catch(Throwable problem){fail(problem);}
    }
    private void advance(Stage next){JsonObject value=new JsonObject();value.addProperty("from",stage.name());value.addProperty("to",next.name());value.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt);transitions.add(value);phaseTicks=stillTicks=0;stageStartedAt=System.currentTimeMillis();stage=next;}
    private void reserveNewSave(Minecraft client)throws Exception {
        require(client.mcDataDir.getCanonicalFile().equals(COPIED_CLIENT.getCanonicalFile()),"Refusing client outside disposable copy");require(client.theWorld==null&&client.getIntegratedServer()==null,"Foreign world is open");
        runId=UUID.randomUUID().toString();saveName="MTR-signals-traffic-"+runId;Path saves=client.mcDataDir.toPath().resolve("saves");Files.createDirectories(saves);require(!Files.isSymbolicLink(saves)&&saves.toRealPath().getParent().equals(client.mcDataDir.toPath().toRealPath()),"Saves escaped copied client");
        Path reserved=Files.createDirectory(saves.resolve(saveName));saveDirectory=reserved.toFile();Files.write(reserved.resolve("mtr-signals-traffic-probe-owner.txt"),runId.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);copyVerified=true;
        report.addProperty("run_id",runId);report.addProperty("save_directory",saveDirectory.getCanonicalPath());report.addProperty("new_uuid_save_reserved",true);report.addProperty("foreign_existing_save_loaded",false);report.addProperty("scenario","original white ItemSignalModifier + original signal config DTO + two natural scheduled trains + native renderer power + owned save/reload");
    }
    private void assertSaveMarker()throws Exception {
        require(copyVerified&&saveDirectory!=null&&!Files.isSymbolicLink(saveDirectory.toPath()),"Foreign or symlinked save");Path marker=saveDirectory.toPath().resolve("mtr-signals-traffic-probe-owner.txt");require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&runId.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Owned signal save marker changed");
    }
    private void assertOwnedServer(MinecraftServer server)throws Exception{require(server!=null&&saveName!=null&&saveName.equals(server.getFolderName()),"Refusing foreign server");assertSaveMarker();}
    private void snapshotStand(WorldServer world) {
        require(expectedSavedBlocks.isEmpty(),"Signal expectations already captured");for(int[] node:NODES)expectedSavedBlocks.add(snapshot(world,node[0],4,node[1]));for(int start:new int[]{0,56})for(int z=start;z<=start+24;z++)for(int x=-4;x<=-2;x++)expectedSavedBlocks.add(snapshot(world,x,4,z));for(int z:SIGNAL_Z)expectedSavedBlocks.add(snapshot(world,2,4,z));require(expectedSavedBlocks.size()==162,"Incomplete signal Anvil expectations");
    }
    private void closeOwned(Minecraft client)throws Exception {
        assertOwnedServer(client.getIntegratedServer());if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());require(client.theWorld==null&&client.getIntegratedServer()==null,"Native stop did not finish");assertSaveMarker();
    }
    private void saveCloseAndVerify(Minecraft client)throws Exception {
        require(initialClear&&blockingCaptured&&firstRed&&secondRed&&firstExited&&secondExited&&scheduledIds.size()==2&&physicallyObserved.size()==2,"Two-train traffic evidence incomplete");
        closeOwned(client);simulator=null;verifySavedAnvil();verifySavedCore();report.addProperty("saved_and_closed_normally",true);report.addProperty("first_save_disk_readback_passed",true);coreSample=null;clientSample=null;menuTicks=0;advance(Stage.RELOAD_MENU);
    }
    private void verifyRuntimeBlocks(net.minecraft.world.World world) {
        require(expectedSavedBlocks.size()==162,"Missing owned Anvil expectations");for(StoredBlock expected:expectedSavedBlocks){StoredBlock actual=snapshotWorld(world,expected.x,expected.y,expected.z);require(expected.id==actual.id&&expected.metadata==actual.metadata&&expected.properties.equals(actual.properties),"Reloaded native block/state changed");if(isSignal(expected.x,expected.y,expected.z))require(Objects.equals(expected.tag,actual.tag),"Reloaded complete native signal NBT changed");}
    }
    private static StoredBlock snapshotWorld(net.minecraft.world.World world,int x,int y,int z){TileEntity tile=world.getTileEntity(x,y,z);NBTTagCompound tag=null;if(tile!=null){tag=new NBTTagCompound();tile.writeToNBT(tag);}return new StoredBlock(x,y,z,Block.getIdFromBlock(world.getBlock(x,y,z)),world.getBlockMetadata(x,y,z),tag,state(world,x,y,z).data.getSerializedProperties());}
    private void verifyReloadCore(CoreSample core) {
        require(expectedCore.size()==17&&core.fullData.keySet().equals(expectedCore.keySet()),"Reloaded original17payload set differs");
        for(Map.Entry<String,JsonObject> entry:expectedCore.entrySet())require(entry.getValue().equals(core.fullData.get(entry.getKey())),"Reloaded exact original core payload differs: "+entry.getKey());
        long id=core.vehicles.get(0).id;require(id==idleVehicleId&&(reloadIdleId==0||reloadIdleId==id),"Owned quick reload changed idle vehicle identity");reloadIdleId=id;
    }
    private void finish(Minecraft client) {
        if(stage==Stage.FINISHED)return;report.addProperty("terminal_phase",stage.name());stage=Stage.FINISHED;boolean safe=copyVerified&&client.theWorld==null&&client.getIntegratedServer()==null;
        try{if(copyVerified)KeyBinding.unPressAllKeys();if(client.getIntegratedServer()!=null){assertOwnedServer(client.getIntegratedServer());safe=true;closeOwned(client);}if(failure==null){require(reloaded&&readbackCaptured&&reloadIdleId==idleVehicleId,"Owned native reload evidence incomplete");verifySavedAnvil();verifySavedCore();}}catch(Throwable problem){fail(problem);}
        report.addProperty("passed",failure==null);report.addProperty("setup_teleport_count",setupTeleports);report.addProperty("probe_motion_writes",0);report.addProperty("rider_motion_writes",0);report.addProperty("direct_signal_power_writes",0);report.addProperty("instant_deploy_used",false);report.addProperty("single_player",true);report.addProperty("owned_save_reopened",reloaded);report.addProperty("reload_readback_passed",readbackCaptured);report.addProperty("signal_wait_observed_ms",waitingObservedMillis);report.addProperty("first_train_id",Long.toString(firstId));report.addProperty("second_train_id",Long.toString(secondId));report.addProperty("final_idle_vehicle_id",Long.toString(idleVehicleId));report.add("traffic_events",trafficEvents);report.add("timeline",timeline);report.add("transitions",transitions);report.add("screenshots",screenshots);report.add("original_one_shot_item_uses",placements);
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try{if(copyVerified)Files.write(new File(client.mcDataDir,"mtr-signals-traffic-probe-"+runId+".json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);System.out.println((failure==null?"MTR_SIGNALS_TRAFFIC_PROBE_PASS ":"MTR_SIGNALS_TRAFFIC_PROBE_FAIL ")+report);if(safe)client.shutdown();}catch(Throwable problem){problem.printStackTrace();throw new IllegalStateException("Could not write native signals traffic result",problem);}
    }
    private static Object diagnosticField(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type = owner instanceof Class<?> ? (Class<?>) owner : owner.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner instanceof Class<?> ? null : owner);
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
    private static boolean correctCar(Vehicle value) {
        if(value.vehicleExtraData.immutableVehicleCars.size()!=1)return false;VehicleCar car=value.vehicleExtraData.immutableVehicleCars.get(0);JsonObject serialized=full(car);
        return CAR.equals(car.getVehicleId())&&Math.abs(car.getLength()-9)<1E-6&&Math.abs(car.getWidth()-2)<1E-6&&Math.abs(serialized.get("couplingPadding1").getAsDouble()-1)<1E-6&&Math.abs(serialized.get("couplingPadding2").getAsDouble()-1)<1E-6&&Math.abs(car.getBogie1Position())<1E-6&&Math.abs(car.getBogie2Position())<1E-6;
    }
    private static final class StoredBlock {
        final int x,y,z,id,metadata;final NBTTagCompound tag;final Map<String,String> properties;
        StoredBlock(int x,int y,int z,int id,int metadata,NBTTagCompound tag,Map<String,String> properties){this.x=x;this.y=y;this.z=z;this.id=id;this.metadata=metadata;this.tag=tag==null?null:(NBTTagCompound)tag.copy();this.properties=properties;}
    }

    private void verifySavedAnvil()throws Exception {
        require(new File(saveDirectory,"level.dat").isFile()&&expectedSavedBlocks.size()==162,"Actual new save/train expectations missing");
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
            if(isSignal(expected.x,expected.y,expected.z))require(Objects.equals(expected.tag,tag),"Complete native signal NBT changed during save");
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
        String[] kinds={"stations","platforms","sidings","routes","depots","rails"};int[] counts={2,2,1,1,1,10};Set<String> seen=new HashSet<>();JsonObject persisted=new JsonObject();
        require(expectedCore.size()==17&&coreSample!=null&&coreSample.allIdle&&coreSample.vehicles.size()==1,"Final original core expectations are incomplete or occupied");
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
                    JsonObject train=vehicles.get(0).getAsJsonObject(),idle=expectedVehicles.get(0).getAsJsonObject();require(savedVehicleMatches(train,idle,idleVehicleId),"Saved original core train identity/state differs");
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


}
