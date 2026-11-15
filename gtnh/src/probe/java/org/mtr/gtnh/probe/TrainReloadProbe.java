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
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.*;
import org.mtr.core.Main;
import org.mtr.core.data.*;
import org.mtr.core.serializer.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.org.msgpack.core.*;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mapping.tool.BlockStateDefinition;
import org.mtr.mapping.tool.PlayerPhysics;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.*;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.resource.VehicleResourceCache;
import java.io.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.*;

/** Test-only read-only reload observations; native load/stop may legitimately save the admitted world. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_train_reload_probe",name="MTR GTNH accepted train reload snapshot",version="0",
    dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class TrainReloadProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final Path TASK=Paths.get(requiredProbePath("mtr.probeTaskRoot"));
    private static final Path COPIED=Paths.get(requiredProbePath("mtr.probeClient"));
    private static final String ACCEPTED="bffc3c5c-45e0-4e02-80ec-71f2efb670e6";
    private static final String SAVE_NAME="MTR-train-acceptance-"+ACCEPTED;
    private static final Path SAVE=COPIED.resolve("saves").resolve(SAVE_NAME),WORK=TASK.resolve("work/train-reload-probe");
    private static final Path SOURCE=TASK.resolve("work/mtr-port/mtr/gtnh/src/probe/java/org/mtr/gtnh/probe/TrainReloadProbe.java");
    private static final String EXPECTED_SHA="4ae6a35a5d23058467b3ab78b7f290dfd29d0d6bd45f5d6f1a6af2b0c182592d";
    private static final UUID PLAYER=UUID.fromString("6901a56c-c6c4-4350-ab76-1a2f4579791f");
    private static final String CAR="e44_mini_cab_3";
    private enum Stage { MENU,WORLD,VERIFY,SNAPSHOT,CLOSE,FINISHED }
    private volatile Stage stage=Stage.MENU;
    private volatile Throwable failure;
    private volatile ClientSample clientSample;
    private volatile CoreSample coreSample;
    private volatile int clientTicks;
    private volatile boolean shotDone;
    private final AtomicBoolean pending=new AtomicBoolean();
    private final RenderGate renderGate=new RenderGate();
    private final JsonObject report=new JsonObject();
    private final JsonArray transitions=new JsonArray();
    private JsonObject expected,admission;
    private Contract.Session session;
    private long nativeLogStart;
    private String nativeLogPrefixSha;
    private List<StoredBlock> baselineBlocks;
    private Main loadedMain;
    private Simulator simulator;
    private MinecraftClientData beforeData,loadedData;
    private MinecraftServer loadedServer;
    private Object loadedWorld;
    private Path resultDirectory;
    private long startedAt,stageAt,consumedFrame;
    private int menuTicks,stableTicks,shotFrames;
    private boolean admitted;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        onlyProbe();startedAt=stageAt=System.currentTimeMillis();FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<600000,"Train reload total deadline");
            if(stage==Stage.MENU) {
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.getIntegratedServer()!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();
                if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                preflight(client);advance(Stage.WORLD);
                FMLClientHandler.instance().tryLoadExistingWorld(new GuiSelectWorld(new GuiMainMenu()),SAVE_NAME,"MTR train acceptance "+ACCEPTED);return;
            }
            require(System.currentTimeMillis()-stageAt<90000,"Train reload phase deadline: "+stage);
            if(stage==Stage.CLOSE){finish(client);return;}
            if(stage==Stage.WORLD) {
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                owned(client.getIntegratedServer());
                // MTR resets client data on the first START for this world; freeze after that reset.
                ClientWorld initialized=(ClientWorld)field(InitClient.class,"lastClientWorld");
                if(initialized==null||initialized.data!=client.theWorld)return;
                loadedServer=client.getIntegratedServer();loadedWorld=client.theWorld;
                loadedMain=actualMain();require(loadedMain!=null,"Native start did not create original Main");simulator=actualSimulator(loadedMain);
                loadedData=MinecraftClientData.getInstance();require(loadedData!=beforeData,"Native join did not replace original client data");
                report.addProperty("new_original_main_identity",System.identityHashCode(loadedMain));
                report.addProperty("new_original_simulator_identity",System.identityHashCode(simulator));
                report.addProperty("new_original_client_data_identity",System.identityHashCode(loadedData));
                report.addProperty("loaded_existing_save",true);advance(Stage.VERIFY);
            }
            assertClient(client);
            if(client.currentScreen instanceof GuiDownloadTerrain)return;
            require(client.currentScreen==null,"Unexpected native screen after accepted existing-world load");
            KeyBinding.unPressAllKeys();clientTicks++;
        } catch(Throwable problem){fail(problem);}
    }
    private void preflight(Minecraft client)throws Exception {
        require(client.theWorld==null&&client.getIntegratedServer()==null,"Refusing open world/server");onlyProbe();paths(client.mcDataDir.toPath());
        require(!client.gameSettings.pauseOnLostFocus&&MinecraftClientHelper.getRenderDistance()>=8,"Copied options must already disable background pause and provide render distance>=8");
        expected=Contract.readPinned(WORK.resolve("expected.json"),EXPECTED_SHA,2*1024*1024);
        require(ACCEPTED.equals(expected.get("accepted_run_id").getAsString()),"Foreign accepted reference");
        Contract.tree(SAVE,expected);NBTTagCompound level=readNbt(SAVE.resolve("level.dat"));
        require(!level.getCompoundTag("FML").hasKey("ModItemData"),"Legacy conversion outside reload scope");
        require(("MTR train acceptance "+ACCEPTED).equals(level.getCompoundTag("Data").getString("LevelName")),"Wrong saved level");
        assertSavedPlayer(readNbt(SAVE.resolve("playerdata/"+PLAYER+".dat")));
        require(client.getSaveLoader().canLoadWorld(SAVE_NAME),"Native save loader refused exact accepted world");
        baselineBlocks=loadBlocks();verifyDiskBlocks(baselineBlocks);verifyDiskCore(expected.getAsJsonObject("core"));
        require(actualMain()==null,"Fresh JVM required; original Main already exists");beforeData=MinecraftClientData.getInstance();
        Path jar=Contract.artifact(TrainReloadProbe.class.getProtectionDomain().getCodeSource().getLocation(),COPIED.resolve("mods/MTR-GTNH-4.0.5-gtnh.0.jar"));
        admission=Contract.admission(WORK, SOURCE, jar, COPIED, SAVE,EXPECTED_SHA,System.currentTimeMillis());
        // The complete pinned tree above and verified immutable backup cover these exact settings bytes.
        String settingsPath="mtr/minecraft/overworld/settings/00/0000000000000000";
        Contract.pinnedMember(SAVE,expected,settingsPath);
        session=new Contract.Session(expected,Contract.firstMap(SAVE,SAVE.resolve(settingsPath)),admission.get("issued_at_epoch_ms").getAsLong(),System.currentTimeMillis());
        byte[] log=Contract.logBytes(COPIED.resolve("logs/fml-client-latest.log"));nativeLogStart=log.length;nativeLogPrefixSha=Contract.bytesSha(log);
        Contract.tree(SAVE,expected);Contract.claim(WORK,admission,System.currentTimeMillis());admitted=true;
        resultDirectory=Files.createDirectory(WORK.resolve("run-"+admission.get("run_id").getAsString()));
        report.add("parent_admission",admission);report.addProperty("accepted_run_id",ACCEPTED);report.addProperty("save_directory",SAVE.toString());
        report.addProperty("expected_sha256",EXPECTED_SHA);report.addProperty("setup_teleport_count",0);report.addProperty("probe_motion_writes",0);
        report.addProperty("fixture_configuration_writes",0);report.addProperty("simulator_clock_writes",0);report.addProperty("instant_deploy_used",false);
        report.addProperty("next_saved_departure_millis_of_day",58935000L);
        // Keep the complete accepted file tree as the last observation before the native existing-save entrypoint.
        Contract.tree(SAVE,expected);
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null||!(stage==Stage.VERIFY||stage==Stage.SNAPSHOT))return;
        try {
            MinecraftServer server=MinecraftServer.getServer();owned(server);
            require(server==loadedServer&&actualMain()==loadedMain&&actualSimulator(loadedMain)==simulator,"Loaded server/core identity replaced");
            EntityPlayerMP player=null;for(Object row:server.getConfigurationManager().playerEntityList){EntityPlayerMP p=(EntityPlayerMP)row;if(PLAYER.equals(p.getUniqueID()))player=p;}
            require(player!=null&&player.worldObj==server.worldServerForDimension(0)&&player.dimension==0,"Missing/foreign restored native server player");
            WorldServer world=server.worldServerForDimension(0);if(!chunksReady(world))return;
            // Native join ACK can clear onGround before collision; require five fresh grounded VERIFY samples.
            if(stage==Stage.VERIFY&&!player.onGround){stableTicks=0;return;}
            assertPlayer(player.posX,player.boundingBox.minY,player.posZ,player.onGround,player.noClip,player.capabilities.isFlying,player.getHeldItem()==null);
            require(emptyInventory(player),"Restored native server inventory differs");
            require(!PlayerPhysics.authorized(player)&&Math.abs(PlayerPhysics.gravity(.08,player)-.08)<1E-10,"Restored player has rider physics");
            verifyBlocks(world,baselineBlocks);readCoreSoon();ClientSample sample=clientSample;CoreSample core=coreSample;
            if(sample==null||core==null||sample.tick!=clientTicks||sample.frame<=consumedFrame||System.nanoTime()-sample.atNanos>250000000L)return;
            require(System.nanoTime()-core.atNanos<5000000000L,"Stale loaded core snapshot");consumedFrame=sample.frame;
            if(!catchupWitness())return;session.acceptCore(core.data);
            require(session.sameVehicle(core.vehicle)&&core.riderCount==0&&!core.onRoute&&core.closeToDepot,"Loaded original vehicle not exact caught-up idle session vehicle");
            if(!sample.blocksMatch||!sample.playerRestored||!sample.vehicleReady)return;
            require(session.sameVehicle(sample.vehicle),"Original client vehicle differs from all12session fields");
            if(stage==Stage.VERIFY) {
                if(++stableTicks<5)return;
                report.add("loaded_original_core_payloads",core.data);report.add("loaded_original_core_vehicle",core.vehicle);report.add("loaded_actual_client_vehicle",sample.vehicle);
                report.addProperty("native_loaded_blocks_verified",baselineBlocks.size());report.addProperty("native_restored_chunks_verified",7);
                report.addProperty("native_player_restored",true);report.addProperty("new_original_core_objects_verified",true);
                report.add("actual_client_resource_geometry",sample.geometry);advance(Stage.SNAPSHOT);
            } else if(shotDone)advance(Stage.CLOSE);
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if(!admitted||failure!=null||!(stage==Stage.VERIFY||stage==Stage.SNAPSHOT))return;
        try {
            Minecraft client=Minecraft.getMinecraft();assertClient(client);
            boolean eligible=client.currentScreen==null&&!client.isGamePaused()&&!client.skipRenderWorld&&client.renderViewEntity==client.thePlayer&&client.displayWidth>0&&client.displayHeight>0;
            long rendered=eligible?((Number)field(org.mtr.mod.render.MainRenderer.class,"lastRenderedMillis")).longValue():-1;
            ClientSample value=renderGate.sample(event.phase,eligible,loadedWorld,client.thePlayer,loadedData,clientTicks,rendered,()->new ClientSample(client,clientTicks,renderGate.frame));
            if(value==null)return;clientSample=value;
            if(stage==Stage.SNAPSHOT&&!shotDone&&value.blocksMatch&&value.vehicleReady&&++shotFrames>=4) {
                ScreenShotHelper.saveScreenshot(resultDirectory.toFile(),"reloaded-platform-B.png",client.displayWidth,client.displayHeight,client.getFramebuffer());
                Path shot=resultDirectory.resolve("screenshots/reloaded-platform-B.png");require(Files.isRegularFile(shot)&&Files.size(shot)>100,"Native reload framebuffer missing");
                report.addProperty("actual_framebuffer",shot.toString());report.addProperty("actual_framebuffer_sha256",Contract.sha(shot));report.addProperty("actual_frame",value.frame);
                report.addProperty("model_visual_acceptance_pending",true);shotDone=true;
            }
        } catch(Throwable problem){fail(problem);}
    }
    private void readCoreSoon() {
        if(!pending.compareAndSet(false,true))return;
        simulator.run(()->{try{coreSample=new CoreSample(simulator);}catch(Throwable problem){fail(problem);}finally{pending.set(false);}});
    }
    private boolean catchupWitness()throws Exception {
        if(session.witnessed())return true;
        byte[] log=Contract.logBytes(COPIED.resolve("logs/fml-client-latest.log"));
        String appended=Contract.logAppend(log,nativeLogStart,nativeLogPrefixSha);long now=System.currentTimeMillis();
        if(!session.witness(appended,now))return false;
        JsonObject witness=new JsonObject();witness.addProperty("log_relative_path","logs/fml-client-latest.log");witness.addProperty("start_offset",nativeLogStart);witness.addProperty("end_offset",log.length);
        witness.addProperty("prefix_sha256",nativeLogPrefixSha);witness.addProperty("append_sha256",Contract.bytesSha(Arrays.copyOfRange(log,(int)nativeLogStart,log.length)));witness.addProperty("observed_epoch_ms",now);witness.addProperty("line",session.witnessLine());
        report.add("native_core_catchup_witness",witness);return true;
    }
    private final class CoreSample {
        final long atNanos=System.nanoTime();final JsonObject data=new JsonObject(),vehicle;final int riderCount;final boolean onRoute,closeToDepot;
        CoreSample(Simulator original)throws Exception {
            for(Station x:original.stations)data.add("stations/"+x.getHexId(),full(x));for(Platform x:original.platforms)data.add("platforms/"+x.getHexId(),full(x));
            for(Siding x:original.sidings)data.add("sidings/"+x.getHexId(),full(x));for(Route x:original.routes)data.add("routes/"+x.getHexId(),full(x));
            for(Depot x:original.depots)data.add("depots/"+x.getHexId(),full(x));for(Rail x:original.rails)data.add("rails/"+x.getHexId(),full(x));
            require(data.entrySet().size()==13&&original.sidings.size()==1&&original.lifts.isEmpty(),"Loaded domain payload set differs: "+data);
            Vehicle found=null;for(Object row:(Iterable<?>)objectField(original.sidings.iterator().next(),"vehicles")){require(found==null,"Multiple loaded core vehicles");found=(Vehicle)row;}
            require(found!=null&&correctCar(found),"Original Siding did not restore the saved Mini E44: "+data);vehicle=Utilities.getJsonObjectFromData(found);
            final int[] count={0};found.vehicleExtraData.iterateRidingEntities(row->count[0]++);riderCount=count[0];onRoute=found.getIsOnRoute();closeToDepot=found.closeToDepot();
        }
    }
    private final class ClientSample {
        final long atNanos=System.nanoTime(),frame;final int tick;final boolean blocksMatch,playerRestored,vehicleReady;final JsonObject vehicle,geometry=new JsonObject();
        ClientSample(Minecraft client,int tick,long frame) {
            this.tick=tick;this.frame=frame;boolean blocks=false,restored=false;
            try{verifyBlocks(client.theWorld,baselineBlocks);blocks=true;}catch(AssertionError|RuntimeException waiting){geometry.addProperty("native_state_wait",waiting.toString());}
            try{assertClientPlayer(client);restored=true;}catch(AssertionError waiting){geometry.addProperty("player_wait",waiting.toString());}
            blocksMatch=blocks;playerRestored=restored;
            VehicleExtension found=null;for(VehicleExtension x:loadedData.vehicles){require(found==null,"Multiple original client vehicles");found=x;}
            if(found==null){vehicle=new JsonObject();vehicleReady=false;return;}
            vehicle=Utilities.getJsonObjectFromData(found);require(clientNotRiding(),"Restored client is riding a vehicle");
            final int[] clientRiders={0};found.vehicleExtraData.iterateRidingEntities(row->clientRiders[0]++);require(clientRiders[0]==0,"Restored client vehicle has riders");
            if(!session.bound()){vehicleReady=false;return;}
            require(found.getId()==session.id(),"Foreign original client session vehicle: "+vehicle);
            final PositionAndRotation[] pose={null};found.getVehicleCarsAndPositions().stream().findFirst().ifPresent(car->{ObjectArrayList<PositionAndRotation> bogies=new ObjectArrayList<>();car.right().forEach(pair->bogies.add(new PositionAndRotation(pair.left(),pair.right(),true)));pose[0]=new PositionAndRotation(bogies,car.left(),false);});
            final VehicleResourceCache[] cache={null};CustomResourceLoader.getVehicleById(TransportMode.TRAIN,CAR,details->{if(CAR.equals(details.left().getId()))cache[0]=details.left().getCachedVehicleResource(0,1,false);});
            vehicleReady=correctCar(found)&&pose[0]!=null&&cache[0]!=null&&!cache[0].floors.isEmpty()&&!cache[0].doorways.isEmpty();
            geometry.addProperty("original_car_cache_ready",vehicleReady);geometry.addProperty("getter","original_getVehicleCarsAndPositions_unsmoothed");
            geometry.addProperty("door_opening_required",false);geometry.addProperty("original_door_value",found.persistentVehicleData.getDoorValue());
            if(pose[0]!=null){geometry.addProperty("center_x",pose[0].position.x);geometry.addProperty("center_y",pose[0].position.y);geometry.addProperty("center_z",pose[0].position.z);geometry.addProperty("yaw",pose[0].yaw);}
        }
    }
    private static boolean correctCar(Vehicle value) {
        if(value.vehicleExtraData.immutableVehicleCars.size()!=1)return false;VehicleCar car=value.vehicleExtraData.immutableVehicleCars.get(0);JsonObject json=full(car);
        return CAR.equals(car.getVehicleId())&&car.getLength()==9&&car.getWidth()==2&&car.getBogie1Position()==0&&car.getBogie2Position()==0&&json.get("couplingPadding1").getAsDouble()==1&&json.get("couplingPadding2").getAsDouble()==1;
    }
    private static JsonObject full(SerializedDataBase value){JsonObject result=new JsonObject();value.serializeFullData(new JsonWriter(result));return result;}
    private void assertPlayer(double x,double y,double z,boolean ground,boolean clip,boolean flying,boolean empty) {
        require(Contract.player(expected.getAsJsonArray("player_position"),x,y,z,ground,clip,flying,empty),"Player did not restore at original platform B position/physics: "+x+","+y+","+z+" ground="+ground+" clip="+clip+" flying="+flying+" empty="+empty);
    }
    private void assertClientPlayer(Minecraft client) {
        assertPlayer(client.thePlayer.posX,client.thePlayer.boundingBox.minY,client.thePlayer.posZ,client.thePlayer.onGround,client.thePlayer.noClip,client.thePlayer.capabilities.isFlying,client.thePlayer.getHeldItem()==null);
        require(clientNotRiding()&&emptyInventory(client.thePlayer),"Restored client inventory/riding differs");
    }
    private static boolean clientNotRiding(){try{return ((Number)field(VehicleRidingMovement.class,"ridingVehicleId")).longValue()==0;}catch(Exception problem){throw new IllegalStateException(problem);}}
    private static boolean emptyInventory(net.minecraft.entity.player.EntityPlayer p){for(net.minecraft.item.ItemStack x:p.inventory.mainInventory)if(x!=null)return false;for(net.minecraft.item.ItemStack x:p.inventory.armorInventory)if(x!=null)return false;return true;}
    private void assertSavedPlayer(NBTTagCompound player) {
        NBTTagList pos=player.getTagList("Pos",6);require(pos.tagCount()==3&&player.getInteger("Dimension")==0,"Saved player/dimension differs");
        assertPlayer(pos.func_150309_d(0),pos.func_150309_d(1),pos.func_150309_d(2),player.getBoolean("OnGround"),false,player.getCompoundTag("abilities").getBoolean("flying"),player.getTagList("Inventory",10).tagCount()==0);
        require(new UUID(player.getLong("UUIDMost"),player.getLong("UUIDLeast")).equals(PLAYER),"Saved native player UUID differs");
    }
    private void assertClient(Minecraft client)throws Exception {
        owned(client.getIntegratedServer());
        require(client.theWorld==loadedWorld,"Loaded native client world replaced: expected="+System.identityHashCode(loadedWorld)+" actual="+System.identityHashCode(client.theWorld));
        require(client.thePlayer!=null,"Missing loaded native client player");
        require(PLAYER.equals(client.thePlayer.getUniqueID()),"Loaded native client player UUID changed: "+client.thePlayer.getUniqueID());
        require(client.thePlayer.dimension==0,"Loaded native client dimension changed: "+client.thePlayer.dimension);
        MinecraftClientData data=MinecraftClientData.getInstance();
        require(data==loadedData,"Loaded original client data replaced: expected="+System.identityHashCode(loadedData)+" actual="+System.identityHashCode(data));
        require(!client.gameSettings.pauseOnLostFocus&&MinecraftClientHelper.getRenderDistance()>=8,"Copied observation options changed");
    }
    private void owned(MinecraftServer server)throws Exception {
        require(admitted&&server!=null&&SAVE_NAME.equals(server.getFolderName()),"Foreign native server");paths(Minecraft.getMinecraft().mcDataDir.toPath());
        require(Contract.checked(server.worldServerForDimension(0).getSaveHandler().getWorldDirectory().toPath()).equals(Contract.checked(SAVE)),"Native SaveHandler root differs");
        if(loadedServer!=null)require(server==loadedServer,"Native server identity replaced");
    }
    private static void paths(Path client)throws Exception {
        require(Contract.checked(client).equals(Contract.checked(COPIED))&&Contract.checked(SAVE).equals(Contract.checked(COPIED).resolve("saves").resolve(SAVE_NAME)),"Foreign copied client/save");
        require(ACCEPTED.equals(new String(Files.readAllBytes(Contract.checked(SAVE.resolve("mtr-train-probe-owner.txt"))),StandardCharsets.UTF_8)),"Wrong original owner marker");
    }
    private static void onlyProbe(){for(ModContainer mod:Loader.instance().getActiveModList()){String id=mod.getModId();require(!id.startsWith("mtr_gtnh_")||!id.endsWith("_probe")||id.equals("mtr_gtnh_train_reload_probe"),"Coexisting probe: "+id);}}
    private static Object field(Class<?> owner,String name)throws Exception {Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static Object objectField(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Main actualMain()throws Exception{return (Main)field(Init.class,"main");}
    private static Simulator actualSimulator(Main main)throws Exception {
        Field f=Main.class.getDeclaredField("simulators");f.setAccessible(true);Simulator selected=null;
        for(Object row:(Iterable<?>)f.get(main)){Simulator s=(Simulator)row;if("minecraft/overworld".equals(s.dimension)){require(selected==null,"Duplicate overworld simulator");selected=s;}}
        require(selected!=null,"Missing original overworld simulator");return selected;
    }
    private void advance(Stage next){JsonObject value=new JsonObject();value.addProperty("from",stage.name());value.addProperty("to",next.name());value.addProperty("elapsed_ms",System.currentTimeMillis()-startedAt);transitions.add(value);stageAt=System.currentTimeMillis();stage=next;}
    private synchronized void fail(Throwable problem){if(failure==null)failure=problem;}
    private static void require(boolean value,String message){Contract.require(value,message);}
    private void finish(Minecraft client) {
        if(stage==Stage.FINISHED)return;report.addProperty("terminal_phase",stage.name());boolean complete=stage==Stage.CLOSE&&failure==null,boundary=false,readback=false,safe=false;stage=Stage.FINISHED;
        CoreSample lastCore=coreSample;if(lastCore!=null){report.add("last_original_core_payloads",lastCore.data);report.add("last_original_core_vehicle",lastCore.vehicle);report.addProperty("last_original_core_rider_count",lastCore.riderCount);}
        if(session!=null)report.add("core_session_contract",session.proof());
        ClientSample lastClient=clientSample;if(lastClient!=null){report.add("last_actual_client_vehicle",lastClient.vehicle);report.add("last_actual_client_geometry",lastClient.geometry);report.addProperty("last_client_blocks_match",lastClient.blocksMatch);report.addProperty("last_client_player_restored",lastClient.playerRestored);}
        try {
            if(client.getIntegratedServer()!=null){owned(client.getIntegratedServer());KeyBinding.unPressAllKeys();if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());require(client.theWorld==null&&client.getIntegratedServer()==null,"Native stop did not return");boundary=safe=true;}
            if(complete&&failure==null){verifyDiskBlocks(baselineBlocks);verifyDiskCore(session.core());assertSavedPlayer(readNbt(SAVE.resolve("playerdata/"+PLAYER+".dat")));report.addProperty("saved_core_session_id",session.id());readback=true;}
        }catch(Throwable problem){fail(problem);}
        Contract.outcome(report,complete&&readback&&boundary&&failure==null);report.addProperty("native_stop_boundary_returned",boundary);report.addProperty("save_reopened",admitted&&loadedMain!=null);
        report.addProperty("departure_observed",false);report.addProperty("trip_after_reload_accepted",false);report.addProperty("dashboard_mouse_gui_accepted",false);report.add("transitions",transitions);
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try{Path output=resultDirectory==null?WORK.resolve("rejected-"+UUID.randomUUID()+".json"):resultDirectory.resolve("train-reload-probe.json");Files.write(output,report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);System.out.println("MTR_TRAIN_RELOAD_PROBE_"+(failure==null&&readback?"PROVISIONAL":"FAIL")+" "+output);if(safe)client.shutdown();}
        catch(Throwable problem){throw new IllegalStateException("Could not write train reload evidence",problem);}
    }
    private List<StoredBlock> loadBlocks()throws Exception {
        List<StoredBlock> result=new ArrayList<>();Set<String> positions=new HashSet<>();
        for(JsonElement row:expected.getAsJsonArray("blocks")){JsonObject b=row.getAsJsonObject();int x=b.get("x").getAsInt(),y=b.get("y").getAsInt(),z=b.get("z").getAsInt();require(positions.add(x+","+y+","+z),"Duplicate expected native block");Block block=(Block)Block.blockRegistry.getObject(b.get("block").getAsString());require(block!=null,"Original expected block not registered");NBTTagCompound tag=null;if(b.get("has_native_tile").getAsBoolean()){NBTBase decoded=JsonToNBT.func_150315_a(b.get("native_nbt").getAsString());require(decoded instanceof NBTTagCompound,"Expected tile is not compound");tag=(NBTTagCompound)decoded;}result.add(new StoredBlock(x,y,z,Block.getIdFromBlock(block),b.get("metadata").getAsInt(),tag));}
        require(result.size()==157,"Expected complete157block fixture");return result;
    }
    private boolean chunksReady(World world) {
        Set<String> seen=new HashSet<>();for(StoredBlock b:baselineBlocks){int x=Math.floorDiv(b.x,16),z=Math.floorDiv(b.z,16);if(!seen.add(x+","+z))continue;if(!world.getChunkProvider().chunkExists(x,z))return false;if(world instanceof WorldServer){Chunk c=(Chunk)((WorldServer)world).theChunkProviderServer.loadedChunkHashMap.getValueByKey(net.minecraft.world.ChunkCoordIntPair.chunkXZ2Int(x,z));if(c==null||c instanceof EmptyChunk||!c.isChunkLoaded)return false;}}require(seen.size()==7,"Expected7original chunks");return true;
    }
    private void verifyBlocks(World world,List<StoredBlock> blocks){require(chunksReady(world),"Waiting original native chunks");for(StoredBlock b:blocks){TileEntity tile=world.getTileEntity(b.x,b.y,b.z);NBTTagCompound tag=null;if(tile!=null){tag=new NBTTagCompound();tile.writeToNBT(tag);}b.same(new StoredBlock(b.x,b.y,b.z,Block.getIdFromBlock(world.getBlock(b.x,b.y,b.z)),world.getBlockMetadata(b.x,b.y,b.z),tag));}}
    private static final class StoredBlock {
        final int x,y,z,id,metadata;final NBTTagCompound tag;final Map<String,String> properties;
        StoredBlock(int x,int y,int z,int id,int metadata,NBTTagCompound tag){this.x=x;this.y=y;this.z=z;this.id=id;this.metadata=metadata;this.tag=tag;Block block=Block.getBlockById(id);require(block!=null&&(tag!=null)==block.hasTileEntity(metadata),"Native block/tile contract differs");BlockStateDefinition def=BlockStateDefinition.forBlock(block);require(!def.requiresTileEntity()||tag!=null&&tag.hasKey("mtr_block_state",10),"Native extended state missing");properties=def.read(metadata,tag==null?new NBTTagCompound():tag).getSerializedProperties();}
        void same(StoredBlock b){require(x==b.x&&y==b.y&&z==b.z&&id==b.id&&metadata==b.metadata&&Objects.equals(tag,b.tag)&&properties.equals(b.properties),"Original native block/full tile differs: "+x+","+y+","+z+" expected="+id+"/"+metadata+" "+tag+" actual="+b.id+"/"+b.metadata+" "+b.tag);}
    }
    private void verifyDiskBlocks(List<StoredBlock> blocks)throws Exception {
        Map<String,NBTTagCompound> chunks=new HashMap<>();Map<Path,String> hashes=new HashMap<>();
        for(StoredBlock b:blocks){int cx=Math.floorDiv(b.x,16),cz=Math.floorDiv(b.z,16);String key=cx+","+cz;Path file=SAVE.resolve("region/r."+Math.floorDiv(cx,32)+"."+Math.floorDiv(cz,32)+".mca");if(!chunks.containsKey(key)){hashes.put(file,Contract.sha(file));chunks.put(key,readChunk(file,cx,cz));}b.same(savedBlock(chunks.get(key),b.x,b.y,b.z));}
        for(Map.Entry<Path,String> hash:hashes.entrySet())require(hash.getValue().equals(Contract.sha(hash.getKey())),"Read-only Anvil decoder changed bytes");require(chunks.size()==7,"Saved fixture chunk count differs");report.addProperty("saved_native_blocks_verified",blocks.size());report.addProperty("saved_anvil_readonly_bytes_unchanged",true);
    }
    private void verifyDiskCore(JsonObject expectedCore)throws Exception {
        JsonObject actual=new JsonObject();String[] kinds={"stations","platforms","sidings","routes","depots","rails"};
        for(String kind:kinds){Path dir=Contract.checked(SAVE.resolve("mtr/minecraft/overworld/"+kind));try(Stream<Path> paths=Files.walk(dir)){for(Iterator<Path> it=paths.iterator();it.hasNext();){Path file=it.next();require(!Files.isSymbolicLink(file),"Symlink core payload");if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))actual.add(kind+"/"+file.getFileName(),Contract.firstMap(SAVE,file));}}}
        require(actual.entrySet().size()==13&&Contract.same(expectedCore,actual),"Saved13core payloads/config/session vehicle differ");report.addProperty("saved_core_payload_count",13);report.addProperty("saved_core_readonly_bytes_unchanged",true);
    }
    private static NBTTagCompound readNbt(Path file)throws Exception {Path path=Contract.checked(file);require(path.startsWith(Contract.checked(SAVE)),"NBT escaped saved world");String before=Contract.sha(path);NBTTagCompound result;try(InputStream input=Files.newInputStream(path)){result=CompressedStreamTools.readCompressed(input);}require(before.equals(Contract.sha(path)),"Read-only NBT check changed bytes");return result;}
    private static NBTTagCompound readChunk(Path file,int cx,int cz)throws Exception {
        require(Contract.checked(file).startsWith(Contract.checked(SAVE)),"Anvil escaped owned world");try(RandomAccessFile r=new RandomAccessFile(file.toFile(),"r")){require(r.length()>=8192&&r.length()%4096==0,"Malformed native region");r.seek(4L*(Math.floorMod(cx,32)+32*Math.floorMod(cz,32)));int loc=r.readInt(),sector=loc>>>8,count=loc&255;require(sector>=2&&count>0&&((long)sector+count)*4096<=r.length(),"Invalid native chunk sectors");r.seek((long)sector*4096);int length=r.readInt(),compression=r.readUnsignedByte();require(length>1&&length<=count*4096-4&&(compression==1||compression==2),"Malformed native chunk payload");byte[] raw=new byte[length-1];r.readFully(raw);try(InputStream source=new ByteArrayInputStream(raw);InputStream expanded=compression==1?new GZIPInputStream(source):new InflaterInputStream(source);DataInputStream input=new DataInputStream(expanded)){NBTTagCompound level=CompressedStreamTools.read(input).getCompoundTag("Level");require(level.hasKey("xPos",3)&&level.hasKey("zPos",3)&&level.getInteger("xPos")==cx&&level.getInteger("zPos")==cz,"Wrong saved chunk coordinates");return level;}}
    }
    private static StoredBlock savedBlock(NBTTagCompound chunk,int x,int y,int z) {
        NBTTagCompound section=null;NBTTagList sections=chunk.getTagList("Sections",10);for(int i=0;i<sections.tagCount();i++){NBTTagCompound s=sections.getCompoundTagAt(i);if((s.getByte("Y")&255)==y>>4){require(section==null,"Duplicate Anvil section");section=s;}}require(section!=null,"Missing native section");int slot=((y&15)<<8)|((z&15)<<4)|(x&15);
        byte[] b=array(section,"Blocks",4096,false),a=array(section,"Add",2048,true),h=array(section,"BlocksB2Hi",2048,true),hh=array(section,"BlocksB3",4096,true),d=array(section,"Data",2048,false),dh=array(section,"Data1High",2048,true),dhh=array(section,"Data2",4096,true);
        int id=(b[slot]&255)|nibble(a,slot)<<8|nibble(h,slot)<<12|(hh.length==0?0:(hh[slot]&255)<<16),meta=nibble(d,slot)|nibble(dh,slot)<<4|(dhh.length==0?0:(dhh[slot]&255)<<8);NBTTagCompound tag=null;NBTTagList tiles=chunk.getTagList("TileEntities",10);for(int i=0;i<tiles.tagCount();i++){NBTTagCompound t=tiles.getCompoundTagAt(i);if(t.getInteger("x")==x&&t.getInteger("y")==y&&t.getInteger("z")==z){require(tag==null,"Duplicate native tile");tag=t;}}return new StoredBlock(x,y,z,id,meta,tag);
    }
    private static byte[] array(NBTTagCompound c,String key,int size,boolean optional){if(!c.hasKey(key)){require(optional,"Missing native array: "+key);return new byte[0];}require(c.hasKey(key,7),"Wrong native array type");byte[] a=c.getByteArray(key);require(a.length==size,"Wrong native array size");return a;}
    private static int nibble(byte[] a,int slot){return a.length==0?0:(a[slot>>1]>>((slot&1)*4))&15;}
    private static final class RenderGate {
        long frame,before;int tick;boolean armed;Object world,player,data;
        <T>T sample(TickEvent.Phase phase,boolean eligible,Object world,Object player,Object data,int tick,long rendered,java.util.function.Supplier<T> read){if(phase==TickEvent.Phase.START){frame++;armed=eligible;this.world=world;this.player=player;this.data=data;this.tick=tick;before=rendered;return null;}boolean complete=armed&&eligible&&this.world==world&&this.player==player&&this.data==data&&this.tick==tick&&rendered>before;armed=false;return complete?read.get():null;}
    }

    // BEGIN_ISOLATED_RELOAD_CONTRACT
    static final class Contract {
        static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
        /** Only the two proven upstream idle catch-up changes may differ from the immutable disk reference. */
        static final class Session {
            static final String SIDING="sidings/2BC462161D5F60AB",SETTINGS="mtr/minecraft/overworld/settings/00/0000000000000000";
            static final long SAVED_MILLIS=1792859005646L;
            private final JsonObject savedCore,savedVehicle;
            private final long issued,opened,gap;
            private JsonObject runtimeCore,runtimeVehicle;
            private String witnessLine;
            private long sessionId;
            Session(JsonObject expected,JsonObject settings,long issued,long opened) {
                require(settings.entrySet().size()==1&&exactLong(settings.get("lastSimulationMillis"))==SAVED_MILLIS,"Pinned original simulation settings differ");
                this.issued=issued;this.opened=opened;gap=issued-SAVED_MILLIS;
                require(gap>3600000&&opened>=issued&&opened-issued<=900000,"Idle catch-up requires proven gap strictly above one hour");
                savedCore=copy(expected.getAsJsonObject("core"));savedVehicle=copy(expected.getAsJsonObject("vehicle"));
                require(savedCore.entrySet().size()==13&&Contract.sameVehicle(savedVehicle,vehicle(savedCore),4765715864643052956L),"Original13payload/12field disk vehicle differs");
            }
            synchronized boolean witness(String appended,long now) {
                require(now>=opened,"Catch-up witness predates native open");String found=null;
                java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("\\[[0-9:]+\\] \\[Server thread/INFO\\] \\[TransportSimulationCore/\\]: Simulation difference of ([0-9]+)h([0-9]+)m for minecraft/overworld caught up with ([0-9]+) ticks in [0-9]+(?:\\.[0-9]+)? second\\(s\\)");
                String[] lines=appended.split("\\r?\\n",-1);
                for(int i=0;i<lines.length-1;i++) {
                    java.util.regex.Matcher match=pattern.matcher(lines[i]);if(!match.matches())continue;
                    long hours=Long.parseLong(match.group(1)),minutes=Long.parseLong(match.group(2)),ticks=Long.parseLong(match.group(3));
                    long total=Math.addExact(Math.multiplyExact(hours,60),minutes);
                    require(minutes<60&&ticks>=3600&&total>=gap/60000&&total<=(now-SAVED_MILLIS)/60000,"Native catch-up witness does not match original elapsed time");
                    require(found==null,"Multiple native overworld catch-up witnesses");found=lines[i];
                }
                if(found==null)return false;require(witnessLine==null||witnessLine.equals(found),"Native catch-up witness replaced");witnessLine=found;return true;
            }
            synchronized void acceptCore(JsonObject actual) {
                require(witnessLine!=null,"Missing fresh native catch-up witness");JsonObject candidate=vehicle(actual);long id=exactLong(candidate.get("id"));
                JsonObject wanted=copy(savedVehicle);wanted.addProperty("id",id);wanted.addProperty("elapsedDwellTime",0L);
                require(Contract.sameVehicle(candidate,wanted,id),"Caught-up idle vehicle differs outside exact id/dwell paths");
                JsonObject canonical=copy(savedCore);JsonArray vehicles=new JsonArray();vehicles.add(wanted);canonical.getAsJsonObject(SIDING).add("vehicles",vehicles);
                require(same(canonical,actual),"Native13core payloads differ outside exact id/dwell paths");
                if(runtimeCore!=null)require(id==sessionId&&same(runtimeCore,actual),"Native session vehicle rebound or changed");
                else {sessionId=id;runtimeCore=canonical;runtimeVehicle=wanted;}
            }
            synchronized boolean sameVehicle(JsonObject actual){return runtimeVehicle!=null&&Contract.sameVehicle(actual,runtimeVehicle,sessionId);}
            synchronized boolean bound(){return runtimeCore!=null;}
            synchronized boolean witnessed(){return witnessLine!=null;}
            synchronized String witnessLine(){require(witnessLine!=null,"No native catch-up witness");return witnessLine;}
            synchronized long id(){require(bound(),"Session vehicle not bound");return sessionId;}
            synchronized JsonObject core(){require(bound(),"Session vehicle not bound");return copy(runtimeCore);}
            synchronized JsonObject proof() {
                JsonObject p=new JsonObject();p.addProperty("saved_last_simulation_millis",SAVED_MILLIS);p.addProperty("admission_issued_epoch_ms",issued);p.addProperty("pre_open_epoch_ms",opened);p.addProperty("gap_at_admission_ms",gap);
                p.addProperty("threshold_ms",3600000);p.addProperty("actual_catchup_verified",witnessed());p.addProperty("binding_count",bound()?1:0);p.addProperty("expected_idle_dwell",0);
                JsonArray paths=new JsonArray();paths.add(new JsonPrimitive("/"+SIDING+"/vehicles/0/id"));paths.add(new JsonPrimitive("/"+SIDING+"/vehicles/0/elapsedDwellTime"));p.add("runtime_exception_paths",paths);
                if(bound())p.addProperty("session_vehicle_id",sessionId);if(witnessed())p.addProperty("catchup_witness_line",witnessLine);return p;
            }
            private static JsonObject vehicle(JsonObject core){JsonArray a=core.getAsJsonObject(SIDING).getAsJsonArray("vehicles");require(a.size()==1,"Exactly one native session vehicle required");return a.get(0).getAsJsonObject();}
            private static JsonObject copy(JsonObject value){return new JsonParser().parse(value.toString()).getAsJsonObject();}
            private static long exactLong(JsonElement value){require(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber(),"Expected exact numeric signed long");return new BigDecimal(value.getAsString()).longValueExact();}
        }
        static void pinnedMember(Path root,JsonObject expected,String member)throws Exception {
            JsonObject found=null;for(JsonElement row:expected.getAsJsonArray("files")){JsonObject f=row.getAsJsonObject();if(member.equals(f.get("path").getAsString())){require(found==null,"Duplicate pinned settings member");found=f;}}
            Path file=checked(root.resolve(member));require(found!=null&&file.startsWith(checked(root))&&Files.size(file)==found.get("size").getAsLong()&&sha(file).equals(found.get("sha256").getAsString()),"Pinned original settings member differs");
        }
        static byte[] logBytes(Path log)throws Exception {Path p=checked(log);require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)<=128*1024*1024,"Invalid native log");byte[] bytes=Files.readAllBytes(p);require(bytes.length<=128*1024*1024,"Native log grew beyond bound");return bytes;}
        static String logAppend(byte[] log,long start,String prefixSha)throws Exception {require(start>=0&&start<=log.length&&log.length-start<=16*1024*1024&&bytesSha(Arrays.copyOf(log,(int)start)).equals(prefixSha),"Native log prefix changed or append exceeded bound");return new String(Arrays.copyOfRange(log,(int)start,log.length),StandardCharsets.UTF_8);}
        static boolean same(JsonElement a,JsonElement b) {
            if(a==null||b==null)return a==b;if(a.isJsonNull()||b.isJsonNull())return a.isJsonNull()&&b.isJsonNull();
            if(a.isJsonObject()&&b.isJsonObject()){JsonObject x=a.getAsJsonObject(),y=b.getAsJsonObject();if(x.entrySet().size()!=y.entrySet().size())return false;for(Map.Entry<String,JsonElement> e:x.entrySet())if(!y.has(e.getKey())||!same(e.getValue(),y.get(e.getKey())))return false;return true;}
            if(a.isJsonArray()&&b.isJsonArray()){JsonArray x=a.getAsJsonArray(),y=b.getAsJsonArray();if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
            if(!a.isJsonPrimitive()||!b.isJsonPrimitive())return false;JsonPrimitive x=a.getAsJsonPrimitive(),y=b.getAsJsonPrimitive();
            if(x.isNumber()&&y.isNumber())return new BigDecimal(x.getAsString()).compareTo(new BigDecimal(y.getAsString()))==0;
            return x.isString()==y.isString()&&x.isBoolean()==y.isBoolean()&&x.equals(y);
        }
        static boolean sameVehicle(JsonObject actual,JsonObject expected,long id){return actual.entrySet().size()==12&&expected.entrySet().size()==12&&actual.get("id").getAsLong()==id&&expected.get("id").getAsLong()==id&&same(actual,expected)&&actual.get("speed").getAsDouble()==0;}
        static boolean player(JsonArray pos,double x,double y,double z,boolean ground,boolean clip,boolean flying,boolean empty){return pos.size()==3&&Math.abs(x-pos.get(0).getAsDouble())<.08&&Math.abs(y-pos.get(1).getAsDouble())<.08&&Math.abs(z-pos.get(2).getAsDouble())<.08&&ground&&!clip&&!flying&&empty;}
        static void outcome(JsonObject report,boolean complete){report.addProperty("scenario_readback_success",complete);report.addProperty("passed",false);report.addProperty("native_full_save_verified",false);report.addProperty("saved_and_closed_normally",false);report.addProperty("runtime_acceptance_pending",true);}
        static Path checked(Path value)throws Exception {Path absolute=value.toAbsolutePath().normalize();for(Path p=absolute;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p),"Symlink path");return absolute.toRealPath();}
        static String sha(Path value)throws Exception {return bytesSha(Files.readAllBytes(checked(value)));}
        static String bytesSha(byte[] value)throws Exception {byte[] sum=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder s=new StringBuilder();for(byte b:sum)s.append(String.format("%02x",b&255));return s.toString();}
        static JsonObject readPinned(Path file,String sha,int max)throws Exception {Path p=checked(file);require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)>0&&Files.size(p)<=max&&sha(p).equals(sha),"Pinned input differs");return new JsonParser().parse(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)).getAsJsonObject();}
        static void tree(Path root,JsonObject expected)throws Exception {
            root=checked(root);Set<String> actual=new HashSet<>();try(Stream<Path> paths=Files.walk(root)){Iterator<Path> it=paths.iterator();while(it.hasNext()){Path p=it.next();require(!Files.isSymbolicLink(p),"Symlink in accepted tree");if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))continue;require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"Nonregular accepted file");actual.add(root.relativize(p).toString().replace(File.separatorChar,'/'));require(actual.size()<=2000,"Accepted tree size exceeded");}}
            Set<String> admitted=new HashSet<>();long bytes=0;StringBuilder manifest=new StringBuilder();JsonArray files=expected.getAsJsonArray("files");require(files.size()==592,"Accepted592file baseline required");
            for(JsonElement row:files){JsonObject f=row.getAsJsonObject();String rel=f.get("path").getAsString();Path relative=Paths.get(rel);require(!relative.isAbsolute()&&relative.normalize().equals(relative)&&!rel.contains("\\")&&!rel.equals(".")&&admitted.add(rel),"Unsafe/duplicate admitted file");Path file=checked(root.resolve(relative));require(file.startsWith(root)&&!file.equals(root)&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"Admitted file escaped root");long size=Files.size(file);String hash=sha(file);require(size==f.get("size").getAsLong()&&hash.equals(f.get("sha256").getAsString()),"Accepted save bytes differ: "+rel);bytes+=size;manifest.append(hash).append("  ").append(size).append("  ").append(rel).append('\n');}
            require(actual.equals(admitted)&&bytes==29584096L&&bytes==expected.get("total_bytes").getAsLong()&&bytesSha(manifest.toString().getBytes(StandardCharsets.UTF_8)).equals(expected.get("tree_sha256").getAsString()),"Accepted complete tree differs");
        }
        static Path artifact(java.net.URL url,Path allowed)throws Exception {if("jar".equals(url.getProtocol())){java.net.JarURLConnection c=(java.net.JarURLConnection)url.openConnection();String entry=c.getEntryName();require(entry==null||entry.isEmpty()||entry.equals("org/mtr/gtnh/probe/TrainReloadProbe.class"),"Unexpected CodeSource entry");url=c.getJarFileURL();}java.net.URI uri=url.toURI();require("file".equals(uri.getScheme())&&uri.getAuthority()==null&&uri.getQuery()==null&&uri.getFragment()==null,"CodeSource must be local file JAR");Path p=checked(Paths.get(uri));require(p.equals(checked(allowed))&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"Foreign installed artifact");return p;}
        static JsonObject admission(Path work,Path source,Path jar,Path client,Path save,String expectedSha,long now)throws Exception {
            work=checked(work);Path marker=checked(work.resolve("parent-admission.json"));require(Files.size(marker)>0&&Files.size(marker)<65536,"Invalid parent admission size");JsonObject a=new JsonParser().parse(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)).getAsJsonObject();
            require("mtr-train-native-reload-admission-v1".equals(a.get("schema").getAsString())&&"bffc3c5c-45e0-4e02-80ec-71f2efb670e6".equals(a.get("accepted_run_id").getAsString()),"Wrong admission scope");String run=a.get("run_id").getAsString();require(UUID.fromString(run).toString().equals(run),"Noncanonical reload UUID");
            require(client.toString().equals(a.get("client_directory").getAsString())&&save.toString().equals(a.get("save_directory").getAsString()),"Foreign admitted paths");
            require(a.get("issued_at_epoch_ms").getAsLong()<=now&&now<=a.get("expires_at_epoch_ms").getAsLong()&&a.get("expires_at_epoch_ms").getAsLong()-a.get("issued_at_epoch_ms").getAsLong()<=900000,"Expired/future/unbounded admission");
            require(sha(source).equals(a.get("probe_source_sha256").getAsString())&&sha(jar).equals(a.get("probe_jar_sha256").getAsString())&&expectedSha.equals(a.get("expected_sha256").getAsString()),"Probe/reference pins differ");
            Path backup=checked(Paths.get(a.get("backup_archive").getAsString()));require(backup.equals(work.resolve("accepted-save-before-native-reload.tar.gz"))&&!backup.startsWith(client)&&Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS)&&Files.size(backup)>0&&((Number)Files.getAttribute(backup,"unix:nlink")).longValue()==1&&(((Number)Files.getAttribute(backup,"unix:mode")).intValue()&0222)==0&&sha(backup).equals(a.get("backup_sha256").getAsString()),"Invalid immutable external backup");
            require(a.get("backup_verified").isJsonPrimitive()&&a.get("backup_verified").getAsJsonPrimitive().isBoolean()&&a.get("backup_verified").getAsBoolean(),"Parent has not verified backup");
            JsonObject proof=readPinned(work.resolve("parent-backup-verification.json"),a.get("backup_verification_sha256").getAsString(),2*1024*1024);
            require(proof.get("verified").getAsBoolean()&&proof.get("files_count").getAsInt()==592&&proof.get("total_bytes").getAsLong()==29584096L&&"1a50baf1d3e86f9aa3521746d91e37f01b6cf69317cb482a2f37ac2b73eeab52".equals(proof.get("source_tree_sha256").getAsString())&&proof.get("archive_sha256").getAsString().equals(a.get("backup_sha256").getAsString()),"Parent backup verification does not cover accepted tree/archive");
            require(!Files.exists(work.resolve("parent-admission.used-"+run+".json"),LinkOption.NOFOLLOW_LINKS),"Admission already spent");a.addProperty("_marker_sha256",sha(marker));return a;
        }
        static void claim(Path work,JsonObject a,long now)throws Exception {require(a.get("issued_at_epoch_ms").getAsLong()<=now&&now<=a.get("expires_at_epoch_ms").getAsLong(),"Admission expired before claim");Path marker=checked(work.resolve("parent-admission.json"));require(sha(marker).equals(a.get("_marker_sha256").getAsString()),"Admission changed before claim");Files.move(marker,work.resolve("parent-admission.used-"+a.get("run_id").getAsString()+".json"));}
        static JsonObject firstMap(Path root,Path file)throws Exception {Path p=checked(file);require(p.startsWith(checked(root))&&!p.equals(checked(root))&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)>0&&Files.size(p)<=16*1024*1024,"Invalid owned core payload");String before=sha(p);JsonObject result;try(MessageUnpacker unpacker=MessagePack.newDefaultUnpacker(Files.newInputStream(p))){org.mtr.libraries.org.msgpack.value.Value v=unpacker.unpackValue();require(v.isMapValue(),"Core payload must begin with one complete map");result=new JsonParser().parse(v.toJson()).getAsJsonObject();}require(before.equals(sha(p)),"Read-only first-map decoder changed bytes");return result;}
    }
    // END_ISOLATED_RELOAD_CONTRACT
}
