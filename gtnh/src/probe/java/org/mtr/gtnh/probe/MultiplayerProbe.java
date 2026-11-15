package org.mtr.gtnh.probe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.Mod;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import org.mtr.core.Main;
import org.mtr.core.data.Lift;
import org.mtr.core.data.LiftDirection;
import org.mtr.core.data.LiftFloor;
import org.mtr.core.data.Position;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.Direction;
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
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateLiftTrackFloorConfig;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mod.block.BlockEscalatorStep;
import org.mtr.mod.block.BlockEscalatorSide;
import org.mtr.mod.item.ItemEscalator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.attribute.BasicFileAttributes;

/** Test-only localhost three-process scenario; no production callbacks are replaced. */
@Mod(modid="mtr_gtnh_multiplayer_probe",name="MTR owned localhost multiplayer acceptance",version="0",
    dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class MultiplayerProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final Path BASE=Paths.get(requiredProbePath("mtr.probeMultiplayerRoot"));
    private LiftScenario lift;
    private Path root,node,coord;
    private WorldServer ownedWorld;
    private String runId,role,epoch,worldName,artifactHash,phase="JOIN",error;
    private int ticks,phaseTicks,serial,pendingClick,modeBeforeClick,sampleTicks,clientTicks,connectGeneration;
    private int lastClickSerial=-1;
    private String clientPhase="";
    private boolean prepared,built,clientConnecting,clientDisconnected,requestSent;
    private boolean measuring,clientMeasuring;
    private final Plan.InputLedger inputLedger=new Plan.InputLedger();
    private JsonObject reconnectExpected;
    private NBTTagList reconnectInventory;
    private volatile Object lastManager;
    private final AtomicLong packetRequests=new AtomicLong(),packetResponses=new AtomicLong();
    private final AtomicLong requestsA=new AtomicLong(),requestsB=new AtomicLong();
    private final JsonArray trajectory=new JsonArray(),actions=new JsonArray();
    private final JsonObject finalPlayers=new JsonObject();
    private final Map<String,JsonObject> firstSamples=new HashMap<>();
    private final Set<Integer> aTicks=new HashSet<>(),bTicks=new HashSet<>();
    private String shotPhase="";
    private JsonObject nativePeerDiagnostics;
    private String nativePeerDiagnosticError;
    // BEGIN BOOTSTRAP FIELDS
    private int bootstrapStage,bootstrapWait,bootstrapStartTick,bootstrapStageTick,bootstrapQuiet,bootstrapSeenA=-1,bootstrapSeenB=-1;
    private double bootstrapStartZ;
    private final JsonObject bootstrapReceipt=new JsonObject();
    private final JsonArray bootstrapActions=new JsonArray();
    private boolean bootstrapClientActive;
    private int bootstrapProducedStage,bootstrapOutConsumed,bootstrapLastServerTick=-1;
    private long bootstrapSeenNanos;
    // END BOOTSTRAP FIELDS

    static final class Plan {
        // BEGIN BOOTSTRAP POLICY
        static final class Bootstrap {
            static final int WAIT=0;
            static final int LAND=1;
            static final int OUT=2;
            static final int QUIET=3;
            static final int DONE=4;
            static final int WAIT_TICKS=40;
            static final int MAX_PHASE_START=2000;
            static final int MAX_TOTAL=400;
            static final int LAND_TICKS=80;
            static final int OUT_TICKS=120;
            static final int QUIET_TICKS=200;
            static final int MAX_INPUT=120;
            static final int DISTANCE=9;
            static final int ZERO_TICKS=12;
            static boolean canStart(boolean first,boolean initial,boolean peers,boolean eligible,boolean measuring,int wait,int phaseTicks) {return first&&initial&&!peers&&eligible&&!measuring&&wait>=WAIT_TICKS&&phaseTicks<=MAX_PHASE_START;}
            static int nextStage(int stage,boolean landed,boolean out,int quiet,boolean peers) {return stage==LAND&&landed?OUT:stage==OUT&&out?QUIET:stage==QUIET&&quiet>=ZERO_TICKS&&peers?DONE:stage;}
            static boolean outReached(double startZ,double z) {return Double.isFinite(startZ)&&Double.isFinite(z)&&z-startZ>=DISTANCE;}
            static boolean corridor(double x,double z) {return Double.isFinite(x)&&Double.isFinite(z)&&Math.abs(x-.6)<=.1&&z>=32&&z<=47;}
            static boolean inputAllowed(boolean first,boolean initial,boolean roleB,int stage,boolean synced,int mode,boolean fresh,boolean grounded,boolean flying,int consumed,boolean measuring) {return first&&initial&&roleB&&stage==OUT&&synced&&mode==2&&fresh&&grounded&&!flying&&consumed<MAX_INPUT&&!measuring;}
            static boolean budget(int total,int stage,int leg) {return total>=0&&total<MAX_TOTAL&&leg>=0&&stage>=LAND&&stage<=QUIET&&(stage!=LAND||leg<LAND_TICKS)&&(stage!=OUT||leg<OUT_TICKS)&&(stage!=QUIET||leg<QUIET_TICKS);}
            static int consumed(int count,boolean produced,boolean forward) {return produced&&forward?count+1:count;}
            static int quietCount(int count,boolean fresh,boolean good) {return fresh?(good?count+1:0):count;}
        }
        // END BOOTSTRAP POLICY
        static final String[] LIFT_PHASES={"JOIN","LOAD","LIFT_LABELS","LIFT_GENERATE","LIFT_CONFIGURE","LIFT_READY","LIFT_CALL","LIFT_PICKUP","LIFT_BOARD","LIFT_UP_REQUEST","LIFT_UP","LIFT_TOP","LIFT_DOWN_REQUEST","LIFT_DOWN","LIFT_BOTTOM","LIFT_EXIT_A","LIFT_GRAVITY_A","LIFT_REOPEN","LIFT_EXIT_B","LIFT_GRAVITY_B","LIFT_IDLE","DISCONNECT_ALL","COMPLETE","LIFT_APPROACH"};
        static final String[] LIFT_MEASURED={"LIFT_BOARD","LIFT_UP","LIFT_TOP","LIFT_DOWN","LIFT_EXIT_A","LIFT_EXIT_B"};
        static final String[] PHASES={"JOIN","LOAD","INITIAL_STOP","SETTLE_INITIAL","FORWARD","BACKWARD","STOPPED","DISCONNECT_A","RECONNECT_A","POST_RECONNECT","WALK","FINAL_STOP","DISCONNECT_ALL","COMPLETE"};
        static String name(String role) {
            if("a".equals(role))return "MtrProbeA";
            if("b".equals(role))return "MtrProbeB";
            throw new IllegalArgumentException("Expected client role a/b");
        }
        static String node(String role) {
            if("server".equals(role))return "server";
            return "prism-data/instances/MTR-MP-"+("a".equals(role)?"A":"b".equals(role)?"B":name(role))+"/.minecraft";
        }
        static int mode(String phase) {
            return "FORWARD".equals(phase)||"POST_RECONNECT".equals(phase)?0:"BACKWARD".equals(phase)?1:2;
        }
        static boolean brushReady(boolean completeStand,boolean heldBrush,boolean targetStep,int z,double distanceSq,double reach) {
            return completeStand&&heldBrush&&targetStep&&z>=2&&z<=65&&Double.isFinite(distanceSq)&&distanceSq>=0&&Double.isFinite(reach)&&reach>0&&distanceSq<=reach*reach;
        }
        static int walkMode(String epoch) {
            if("first".equals(epoch))return 0;
            if("restart".equals(epoch))return 1;
            throw new IllegalArgumentException("Expected first/restart epoch");
        }
        static String next(String phase) {
            for(int i=0;i<PHASES.length-1;i++)if(PHASES[i].equals(phase))return PHASES[i+1];
            throw new IllegalArgumentException("No next phase for "+phase);
        }
        static final double RECONNECT_XZ_TOLERANCE=.05,RECONNECT_Y_TOLERANCE=.1;
        static boolean restored(double[] before,double[] after,Object inventory,Object actualInventory,int selected,int actualSelected){
            if(before.length!=4||after.length!=4||inventory==null||!inventory.equals(actualInventory)||selected!=actualSelected||before[3]!=after[3])return false;
            for(int i=0;i<3;i++)if(!Double.isFinite(before[i])||!Double.isFinite(after[i])||Math.abs(before[i]-after[i])>(i==1?RECONNECT_Y_TOLERANCE:RECONNECT_XZ_TOLERANCE))return false;
            return true;
        }
        static boolean liftReadyBaseline(String phase,boolean measuring,boolean observerReady,boolean setupAck,boolean grounded,boolean inputZero,boolean flying,boolean noClip,boolean riding,double x,double feetY,double z) {
            return "LIFT_READY".equals(phase)&&!measuring&&observerReady&&setupAck&&grounded&&inputZero&&!flying&&!noClip&&!riding
                &&Double.isFinite(x)&&Double.isFinite(feetY)&&Double.isFinite(z)&&x>7.2&&x<8.8&&Math.abs(feetY-72)<.1&&z>3.5&&z<4.9;
        }
        static boolean zeroRequired(String phase,String role){return Arrays.asList("FORWARD","BACKWARD","STOPPED","POST_RECONNECT","FINAL_STOP").contains(phase)||"WALK".equals(phase)&&"b".equals(role);}
        static final class InputLedger {
            int ticks,violations;
            void reset(){ticks=violations=0;}
            void record(boolean required,boolean zero){ticks++;if(required&&!zero)violations++;}
            boolean valid(){return violations==0;}
        }
        static boolean validArtifact(String artifact){return artifact.matches("[A-Za-z0-9._+-]+\\.jar");}
        static void checkTree(Path root)throws IOException{Files.walkFileTree(root,guardVisitor(root));}
        static FileVisitor<Path> guardVisitor(Path root){return new SimpleFileVisitor<Path>(){
            private void reject(Path path,BasicFileAttributes attrs)throws IOException{
                if(attrs.isSymbolicLink()||path.getFileName().toString().equals("accounts.json"))throw new IOException("Symlink/account store in MP root: "+path);
            }
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException{reject(path,attrs);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException{reject(path,attrs);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFileFailed(Path path,IOException failure)throws IOException{
                Path parent=path.getParent();
                boolean activeCoord=parent!=null&&(parent.equals(root.resolve("coord/first"))||parent.equals(root.resolve("coord/restart")));
                boolean atomicTemp=Arrays.asList("control.json.tmp","a.json.tmp","b.json.tmp","a-failure.json.tmp","b-failure.json.tmp","server-failure.json.tmp","expected.json.tmp","normal-stop.json.tmp").contains(path.getFileName().toString());
                // Atomic publish may remove an enumerated temp before its attributes are read.
                if(failure instanceof NoSuchFileException&&activeCoord&&atomicTemp)return FileVisitResult.CONTINUE;
                throw failure;
            }
        };}
        static boolean known(String phase){return java.util.Arrays.asList(PHASES).contains(phase)||"FAILED".equals(phase);}
        // shortcut: first fixture witnesses only unfragmented GET_DATA; use full transport tests for large core worlds.
        static String packetType(byte[] bytes) {
            if(bytes.length<20)return null;
            try {
                java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
                in.readLong();if(in.readInt()!=0||in.readInt()!=1)return null;
                int length=in.readInt();if(length<1||length>256||length>(bytes.length-20)/2)return null;
                StringBuilder type=new StringBuilder();for(int i=0;i<length;i++)type.append(in.readChar());
                return type.toString();
            }catch(java.io.IOException invalid){return null;}
        }
    }

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        try {
            role=required("mtr.mp.role");runId=required("mtr.mp.runId");epoch=required("mtr.mp.epoch");
            require(UUID.fromString(runId).toString().equals(runId),"Noncanonical run UUID");
            require(epoch.equals("first")||epoch.equals("restart"),"Expected first/restart epoch");
            root=Paths.get(required("mtr.mp.root")).toAbsolutePath().normalize();
            require(root.equals(BASE.resolve(runId)),"MP root is outside its fixed task directory");
            require(root.toRealPath().equals(root),"MP root resolves elsewhere");
            Plan.checkTree(root);
            Properties owner=properties(root.resolve("mtr-mp-owner.properties"));
            require(runId.equals(owner.getProperty("run_id")),"MP root owner mismatch");
            String scenario=System.getProperty("mtr.mp.scenario","escalator");
            require((scenario.equals("escalator")||scenario.equals("lift"))&&scenario.equals(owner.getProperty("scenario","escalator")),"MP scenario differs from frozen owner");
            if(scenario.equals("lift"))lift=new LiftScenario();
            worldName=owner.getProperty("world");
            require(worldName!=null&&worldName.matches("mtr-(dedicated|mp)-[0-9a-f-]{36}"),"Unexpected owned world name");
            node=root.resolve(Plan.node(role));coord=root.resolve("coord").resolve(epoch);
            require(Files.isDirectory(coord,LinkOption.NOFOLLOW_LINKS),"Controller must prepare epoch coord directory");
            require((runId+":"+role).equals(readText(node.resolve("mtr-mp-node-owner.txt")).trim()),"Node owner mismatch");
            String artifact=owner.getProperty("mtr_artifact","");artifactHash=owner.getProperty("mtr_sha256","");
            require(Plan.validArtifact(artifact)&&artifactHash.matches("[0-9a-f]{64}"),"Missing frozen MTR artifact identity");
            require(artifactHash.equals(hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(node.resolve("mods").resolve(artifact))))),"Node MTR artifact hash differs");
            Path server=root.resolve("server");
            Properties config=properties(server.resolve("server.properties"));
            require("127.0.0.1".equals(config.getProperty("server-ip"))&&"25579".equals(config.getProperty("server-port"))
                &&"false".equals(config.getProperty("online-mode"))&&"false".equals(config.getProperty("enable-rcon"))
                &&"false".equals(config.getProperty("enable-query"))&&worldName.equals(config.getProperty("level-name")),"Server is not the expected offline localhost node");
            require(readJson(server.resolve("config/mtr.json")).getAsJsonObject("server").get("webserverPort").getAsInt()==0,"Unexpected MTR HTTP listener setting");
            require(runId.equals(readText(server.resolve(worldName).resolve("mtr-mp-world-owner.txt")).trim()),"World is not the pinned owned descendant");
            if(epoch.equals("restart")) {
                JsonObject receipt=readJson(root.resolve("coord/first/normal-stop.json"));
                require(runId.equals(string(receipt,"run_id"))&&worldName.equals(string(receipt,"world"))
                    &&artifactHash.equals(string(receipt,"mtr_sha256"))&&receipt.get("exit_code").getAsInt()==0&&receipt.get("saved_and_verified").getAsBoolean(),"Restart needs controller's verified normal-stop receipt");
            }
            require(event.getSide().isServer()==role.equals("server"),"Probe role does not match native side");
            require(Paths.get("").toRealPath().equals(node),"Unexpected node cwd; gameDir alone is not an ownership guard");
            require(!Files.exists(coord.resolve(role.equals("server")?"control.json":role+".json")),"Epoch node already ran; controller must reserve fresh epoch paths");
            FMLCommonHandler.instance().bus().register(this);
            registerPackets(org.mtr.mod.Init.REGISTRY,this);
            prepared=true;
            if(role.equals("server"))publish();
        }catch(Exception problem){throw new IllegalStateException("MP probe preflight rejected",problem);}
    }

    private static String required(String key){String value=System.getProperty(key);if(value==null)throw new IllegalArgumentException("Missing "+key);return value;}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Properties properties(Path p)throws IOException{Properties v=new Properties();try(Reader in=Files.newBufferedReader(p,StandardCharsets.UTF_8)){v.load(in);}return v;}
    private static String readText(Path p)throws IOException{return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    private static JsonObject readJson(Path p)throws IOException{return JsonParser.parseString(readText(p)).getAsJsonObject();}
    private static String string(JsonObject o,String key){return o.has(key)?o.get(key).getAsString():"";}
    private JsonObject envelope(){JsonObject o=new JsonObject();o.addProperty("run_id",runId);o.addProperty("epoch",epoch);o.addProperty("role",role);o.addProperty("phase",phase);o.addProperty("mtr_sha256",artifactHash);if(lift!=null)o.addProperty("scenario","lift");return o;}
    private void write(String filename,JsonObject value)throws IOException {
        Path file=coord.resolve(filename),temp=coord.resolve(filename+".tmp");
        require(!Files.isSymbolicLink(file)&&!Files.isSymbolicLink(temp)&&coord.toRealPath().equals(coord),"Coord path escaped owned root");
        Files.write(temp,value.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void publish()throws IOException {JsonObject o=envelope();if(lift!=null){o.addProperty("measurement_active",measuring);o.addProperty("lift_id",Long.toString(lift.liftId));o.addProperty("approach_input",lift.approachArmed);if(lift.approachMissingProof!=null)o.add("initialization_approach",lift.approachMissingProof);write("control.json",o);return;}if(nativePeerDiagnostics!=null)o.add("native_peer_diagnostics",nativePeerDiagnostics);if(nativePeerDiagnosticError!=null)o.addProperty("native_peer_diagnostic_error",nativePeerDiagnosticError);o.addProperty("click_serial",serial);o.addProperty("actor",phase.equals("POST_RECONNECT")||phase.equals("FINAL_STOP")||phase.equals("WALK")?"b":"a");o.addProperty("click_pending",pendingClick!=0);o.addProperty("measurement_active",measuring);if(bootstrapStage!=Plan.Bootstrap.WAIT)o.add("initialization_bootstrap",bootstrapControl());write("control.json",o);}
    private JsonObject ack(String who)throws IOException {
        Path path=coord.resolve(who+".json");if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return null;
        JsonObject o=readJson(path);require(runId.equals(string(o,"run_id"))&&epoch.equals(string(o,"epoch"))&&who.equals(string(o,"role")),"Foreign client ACK");
        require(artifactHash.equals(string(o,"mtr_sha256")),"Client artifact identity differs");if(lift!=null)require("lift".equals(string(o,"scenario")),"Client lift scenario differs");
        return phase.equals(string(o,"phase"))?o:null;
    }
    private void next()throws IOException{phase=lift==null?Plan.next(phase):lift.nextPhase();phaseTicks=sampleTicks=0;pendingClick=0;measuring=false;firstSamples.clear();aTicks.clear();bTicks.clear();publish();}
    private void failed(Throwable problem) {
        String failedPhase=phase;error=problem.toString();phase="FAILED";
        try{JsonObject o=envelope();o.addProperty("error",error);o.addProperty("failed_phase",failedPhase);if(nativePeerDiagnostics!=null)o.add("native_peer_diagnostics",nativePeerDiagnostics);if(nativePeerDiagnosticError!=null)o.addProperty("native_peer_diagnostic_error",nativePeerDiagnosticError);if(bootstrapStage!=Plan.Bootstrap.WAIT)o.add("initialization_bootstrap",bootstrapReceipt);if(bootstrapOutConsumed>0)o.addProperty("bootstrap_out_input_ticks",bootstrapOutConsumed);o.addProperty("input_ticks",inputLedger.ticks);o.addProperty("input_violations",inputLedger.violations);o.add("trajectory",trajectory);o.add("actions",actions);o.add("final_players",finalPlayers);write(role+"-failure.json",o);if(role.equals("server"))publish();}
        catch(Exception outputFailure){outputFailure.printStackTrace();}
        problem.printStackTrace();
    }
    private static UUID uuid(String who){return UUID.nameUUIDFromBytes(("OfflinePlayer:"+Plan.name(who)).getBytes(StandardCharsets.UTF_8));}
    private static BlockState state(net.minecraft.world.World w,int x,int y,int z){return new org.mtr.mapping.holder.World(w).getBlockState(new BlockPos(x,y,z));}
    private static int mode(net.minecraft.world.World w){Map<String,String> p=state(w,1,71,33).data.getSerializedProperties();return !"true".equals(p.get("status"))?2:"true".equals(p.get("direction"))?0:1;}
    private static boolean states(net.minecraft.world.World w,int wanted) {
        for(int z=2;z<=65;z++)for(int x=0;x<2;x++)for(int above=0;above<2;above++) {
            BlockState s=state(w,x,71+above,z);Map<String,String> p=s.data.getSerializedProperties();
            if(above==0?!(s.getBlock().data instanceof BlockEscalatorStep):!(s.getBlock().data instanceof BlockEscalatorSide))return false;
            if(!"south".equals(p.get("facing"))||!(x==1?"left":"right").equals(p.get("side")))return false;
            if(!(z==2?"landing_bottom":z==65?"landing_top":"flat").equals(p.get("orientation")))return false;
            if(above==0&&(!Boolean.toString(wanted!=2).equals(p.get("status"))||!Boolean.toString(wanted==0).equals(p.get("direction"))))return false;
        }
        return true;
    }
    private static JsonObject position(net.minecraft.entity.player.EntityPlayer player){JsonObject o=new JsonObject();o.addProperty("x",player.posX);o.addProperty("y",player.boundingBox.minY);o.addProperty("z",player.posZ);o.addProperty("uuid",player.getUniqueID().toString());o.addProperty("on_ground",player.onGround);return o;}
    private static boolean connected(JsonObject ack){return ack!=null&&ack.has("connected")&&ack.get("connected").getAsBoolean();}
    private static boolean ready(JsonObject ack){return connected(ack)&&ack.get("states_ok").getAsBoolean()&&ack.get("peer_visible").getAsBoolean();}

    // Read-only native evidence. These snapshots never change the peer gate or native tracker.
    private static JsonObject nativeEntity(net.minecraft.entity.Entity entity) {
        JsonObject value=new JsonObject();value.addProperty("entity_id",entity.getEntityId());value.addProperty("uuid",entity.getUniqueID().toString());
        value.addProperty("class",entity.getClass().getName());value.addProperty("x",entity.posX);value.addProperty("y",entity.boundingBox.minY);value.addProperty("z",entity.posZ);
        value.addProperty("dimension",entity.dimension);value.addProperty("chunk_x",entity.chunkCoordX);value.addProperty("chunk_z",entity.chunkCoordZ);
        value.addProperty("added_to_chunk",entity.addedToChunk);value.addProperty("dead",entity.isDead);value.addProperty("world_identity",System.identityHashCode(entity.worldObj));return value;
    }
    private static JsonArray nativePlayers(net.minecraft.world.World world) {
        JsonArray values=new JsonArray();for(Object player:world.playerEntities)values.add(nativeEntity((net.minecraft.entity.Entity)player));return values;
    }
    // BEGIN NATIVE TRACKER ENTRY DIAGNOSTICS
    private static Object nativeTrackerField(net.minecraft.entity.EntityTracker tracker,String mcp,String srg)throws ReflectiveOperationException {
        java.lang.reflect.Field field;
        try{field=net.minecraft.entity.EntityTracker.class.getDeclaredField(mcp);}
        catch(NoSuchFieldException missingMcp){field=net.minecraft.entity.EntityTracker.class.getDeclaredField(srg);}
        field.setAccessible(true);return field.get(tracker);
    }
    private static JsonObject nativeTrackerEntry(net.minecraft.entity.EntityTrackerEntry entry,Object mapped,EntityPlayerMP observer,EntityPlayerMP target) {
        JsonObject value=new JsonObject();value.addProperty("entry_identity",System.identityHashCode(entry));value.addProperty("entry_class",entry.getClass().getName());
        value.addProperty("same_as_id_lookup_entry",entry==mapped);value.addProperty("my_entity_is_target",entry.myEntity==target);
        value.add("entry_entity",entry.myEntity==null?JsonNull.INSTANCE:nativeEntity(entry.myEntity));
        int centerX=entry.lastScaledXPosition/32,centerZ=entry.lastScaledZPosition/32,threshold=entry.blocksDistanceThreshold;
        double dx=observer.posX-(double)centerX,dz=observer.posZ-(double)centerZ;
        value.addProperty("last_scaled_x",entry.lastScaledXPosition);value.addProperty("last_scaled_z",entry.lastScaledZPosition);
        value.addProperty("blocks_distance_threshold",threshold);value.addProperty("range_center_x",centerX);value.addProperty("range_center_z",centerZ);
        value.addProperty("range_dx",dx);value.addProperty("range_dz",dz);
        value.addProperty("native_range_pass",dx>=(double)(-threshold)&&dx<=(double)threshold&&dz>=(double)(-threshold)&&dz<=(double)threshold);return value;
    }
    private static JsonObject nativeTrackerSnapshot(WorldServer world,EntityPlayerMP observer,EntityPlayerMP target) {
        JsonObject value=new JsonObject();
        try {
            net.minecraft.entity.EntityTracker tracker=world.getEntityTracker();
            net.minecraft.util.IntHashMap ids=(net.minecraft.util.IntHashMap)nativeTrackerField(tracker,"trackedEntityIDs","field_72794_c");
            Set<?> entries=(Set<?>)nativeTrackerField(tracker,"trackedEntities","field_72793_b");Object mapped=ids.lookup(target.getEntityId());
            value.addProperty("entry_by_id_present",mapped!=null);boolean mappedInSet=false;JsonArray matches=new JsonArray();
            for(Object member:entries) {
                net.minecraft.entity.EntityTrackerEntry candidate=(net.minecraft.entity.EntityTrackerEntry)member;if(candidate==mapped)mappedInSet=true;
                if(candidate.myEntity==target||candidate.myEntity!=null&&candidate.myEntity.getEntityId()==target.getEntityId())matches.add(nativeTrackerEntry(candidate,mapped,observer,target));
            }
            value.addProperty("entry_by_id_in_tracked_set_identity",mapped!=null&&mappedInSet);
            value.add("id_lookup_entry",mapped==null?JsonNull.INSTANCE:nativeTrackerEntry((net.minecraft.entity.EntityTrackerEntry)mapped,mapped,observer,target));
            value.add("tracked_set_target_or_id_entries",matches);
        }catch(Throwable diagnostic){value.addProperty("diagnostic_error",diagnostic.toString());}
        return value;
    }
    // END NATIVE TRACKER ENTRY DIAGNOSTICS
    private JsonObject serverPeerSnapshot(WorldServer world,EntityPlayerMP a,EntityPlayerMP b) {
        JsonObject value=new JsonObject();value.addProperty("phase",phase);value.addProperty("server_tick",world.getTotalWorldTime());value.addProperty("phase_ticks",phaseTicks);
        value.add("world_player_entities",nativePlayers(world));JsonObject players=new JsonObject();
        players.add("a",a==null?JsonNull.INSTANCE:nativeEntity(a));players.add("b",b==null?JsonNull.INSTANCE:nativeEntity(b));value.add("server_players",players);
        if(a!=null&&b!=null) {
            JsonObject directions=new JsonObject();EntityPlayerMP[] observers={a,b},targets={b,a};String[] keys={"a_tracks_b","b_tracks_a"};
            for(int i=0;i<2;i++) {
                EntityPlayerMP observer=observers[i],target=targets[i];JsonObject direction=new JsonObject();
                direction.addProperty("tracking_set_contains_observer",world.getEntityTracker().getTrackingPlayers(target).contains(observer));
                direction.addProperty("observer_watches_target_chunk",world.getPlayerManager().isPlayerWatchingChunk(observer,target.chunkCoordX,target.chunkCoordZ));
                direction.addProperty("target_chunk_pending_for_observer",observer.loadedChunks.contains(new net.minecraft.world.ChunkCoordIntPair(target.chunkCoordX,target.chunkCoordZ)));
                direction.addProperty("target_chunk_exists",world.getChunkProvider().chunkExists(target.chunkCoordX,target.chunkCoordZ));
                if(phase.equals("INITIAL_STOP"))direction.add("tracker_entry",nativeTrackerSnapshot(world,observer,target));
                direction.addProperty("server_entity_by_id_is_target",world.getEntityByID(target.getEntityId())==target);direction.addProperty("target_force_spawn",target.forceSpawn);directions.add(keys[i],direction);
            }value.add("directions",directions);
        }return value;
    }
    private static JsonObject clientPeerSnapshot(net.minecraft.world.World world,JsonObject control,String role) {
        JsonObject value=new JsonObject();String peer=role.equals("a")?"b":"a";UUID wanted=uuid(peer);
        value.addProperty("expected_peer_uuid",wanted.toString());value.addProperty("client_world_identity",System.identityHashCode(world));value.add("world_player_entities",nativePlayers(world));
        JsonArray matches=new JsonArray();for(Object player:world.playerEntities)if(((net.minecraft.entity.player.EntityPlayer)player).getUniqueID().equals(wanted))matches.add(nativeEntity((net.minecraft.entity.Entity)player));
        value.add("same_uuid_lookup_matches",matches);value.addProperty("same_uuid_lookup_visible",matches.size()>0);
        if(control.has("native_peer_diagnostics")) {
            JsonObject server=control.getAsJsonObject("native_peer_diagnostics");value.add("server_snapshot",server);
            JsonObject players=server.getAsJsonObject("server_players");
            if(players!=null&&players.has(peer)&&!players.get(peer).isJsonNull()) {
                int id=players.getAsJsonObject(peer).get("entity_id").getAsInt();value.addProperty("server_peer_entity_id",id);
                net.minecraft.entity.Entity entity=world.getEntityByID(id);value.add("entity_by_server_peer_id",entity==null?JsonNull.INSTANCE:nativeEntity(entity));
            }
        }return value;
    }

    // BEGIN BOOTSTRAP HELPERS
    private JsonObject bootstrapControl() {
        JsonObject value=new JsonObject();value.addProperty("stage",bootstrapStage);value.addProperty("server_tick",ticks);value.addProperty("start_z",bootstrapStartZ);return value;
    }
    private static boolean bootstrapReady(JsonObject ack) {
        return connected(ack)&&ack.get("states_ok").getAsBoolean()&&ack.get("mode").getAsInt()==2&&ack.get("packet_roundtrip").getAsBoolean();
    }
    private static boolean bootstrapGrounded(EntityPlayerMP a,EntityPlayerMP b,JsonObject aa,JsonObject bb) {
        return a.onGround&&b.onGround&&aa.get("on_ground").getAsBoolean()&&bb.get("on_ground").getAsBoolean()
            &&Math.abs(a.boundingBox.minY-71.9375)<.1&&Math.abs(b.boundingBox.minY-71.9375)<.1&&Math.abs(aa.get("y").getAsDouble()-71.9375)<.1&&Math.abs(bb.get("y").getAsDouble()-71.9375)<.1
            &&aa.has("bootstrap_flying")&&bb.has("bootstrap_flying")&&!aa.get("bootstrap_flying").getAsBoolean()&&!bb.get("bootstrap_flying").getAsBoolean();
    }
    private void bootstrapRecord(String step,EntityPlayerMP a,EntityPlayerMP b,JsonObject aa,JsonObject bb)throws IOException {
        bootstrapReceipt.addProperty("run_id",runId);bootstrapReceipt.addProperty("epoch",epoch);bootstrapReceipt.addProperty("mtr_sha256",artifactHash);
        bootstrapReceipt.addProperty("last_step",step);bootstrapReceipt.addProperty("quiet_fresh_ticks",bootstrapQuiet);
        JsonObject action=new JsonObject();action.addProperty("step",step);action.addProperty("server_tick",ticks);action.addProperty("phase_ticks",phaseTicks);
        action.add("server_a",position(a));action.add("server_b",position(b));action.addProperty("server_a_flying",a.capabilities.isFlying);action.addProperty("server_b_flying",b.capabilities.isFlying);
        JsonObject[] inputs={aa,bb};String[] roles={"a","b"};
        for(int i=0;i<2;i++) {
            JsonObject input=new JsonObject();for(String key:new String[]{"client_tick","input_zero","move_forward","move_strafe","jump","sneak","on_ground","bootstrap_flying","bootstrap_out_input_ticks","peer_visible","states_ok","mode","packet_roundtrip"})if(inputs[i].has(key))input.add(key,inputs[i].get(key));
            action.add("client_"+roles[i],input);
        }
        bootstrapActions.add(action);bootstrapReceipt.add("actions",bootstrapActions);write("initialization-bootstrap.json",bootstrapReceipt);
    }
    private boolean initialBootstrap(EntityPlayerMP a,EntityPlayerMP b,JsonObject aa,JsonObject bb)throws IOException {
        if(!epoch.equals("first")||!phase.equals("INITIAL_STOP")||bootstrapStage==Plan.Bootstrap.DONE)return true;
        boolean peers=ready(aa)&&ready(bb);
        if(bootstrapStage==Plan.Bootstrap.WAIT) {
            if(peers){bootstrapStage=Plan.Bootstrap.DONE;bootstrapReceipt.addProperty("used",false);bootstrapRecord("SKIPPED",a,b,aa,bb);return true;}
            boolean eligible=bootstrapReady(aa)&&bootstrapReady(bb)&&aa.get("input_zero").getAsBoolean()&&bb.get("input_zero").getAsBoolean()&&pendingClick==0;
            bootstrapWait=eligible?bootstrapWait+1:0;
            if(bootstrapWait>=Plan.Bootstrap.WAIT_TICKS)require(phaseTicks<=Plan.Bootstrap.MAX_PHASE_START,"Insufficient original INITIAL_STOP budget for bootstrap");
            if(!Plan.Bootstrap.canStart(true,true,peers,eligible,measuring,bootstrapWait,phaseTicks))return false;
            require(Math.abs(a.posX-1.4)<=.1&&Math.abs(a.posZ-33.5)<=.1&&Math.abs(b.posX-.6)<=.1&&Math.abs(b.posZ-33.5)<=.1,"Bootstrap did not start at original JOIN positions");
            bootstrapStartZ=b.posZ;bootstrapStage=Plan.Bootstrap.LAND;bootstrapStartTick=bootstrapStageTick=ticks;bootstrapReceipt.addProperty("used",true);
            for(EntityPlayerMP player:new EntityPlayerMP[]{a,b}){player.capabilities.isFlying=false;player.sendPlayerAbilities();}
            bootstrapRecord("LAND",a,b,aa,bb);publish();return false;
        }
        require(!measuring&&Plan.Bootstrap.budget(ticks-bootstrapStartTick,bootstrapStage,ticks-bootstrapStageTick),"Bounded initialization bootstrap timed out");
        require(bootstrapReady(aa)&&bootstrapReady(bb),"Bootstrap lost real STOP states or packet roundtrip");
        require(!a.capabilities.isFlying&&!b.capabilities.isFlying,"Bootstrap must use ordinary native landing/walking");
        require(aa.get("input_zero").getAsBoolean()&&bb.get("move_strafe").getAsDouble()==0&&!bb.get("jump").getAsBoolean()&&!bb.get("sneak").getAsBoolean()
            &&bb.get("move_forward").getAsDouble()>=0&&bb.get("move_forward").getAsDouble()<=1,"Bootstrap received foreign input");
        require(Plan.Bootstrap.corridor(b.posX,b.posZ)&&Math.abs(a.posX-1.4)<=.1&&Math.abs(a.posZ-33.5)<=.1&&Math.abs(b.rotationYaw)<.1,"Bootstrap left its stopped same-chunk corridor");
        require(bootstrapStage!=Plan.Bootstrap.LAND||bb.get("input_zero").getAsBoolean(),"Walking began before confirmed ordinary landing");
        if(bootstrapStage!=Plan.Bootstrap.LAND)require(Math.abs(a.boundingBox.minY-71.9375)<.1&&Math.abs(b.boundingBox.minY-71.9375)<.1,"Bootstrap left original flat stopped surface");
        require(!bb.has("bootstrap_out_input_ticks")||bb.get("bootstrap_out_input_ticks").getAsInt()<=Plan.Bootstrap.MAX_INPUT,"Bootstrap exceeded consumed native input cap");
        boolean landed=bootstrapGrounded(a,b,aa,bb),out=Plan.Bootstrap.outReached(bootstrapStartZ,b.posZ);
        if(bootstrapStage==Plan.Bootstrap.OUT&&out)require(bb.has("bootstrap_out_input_ticks")&&bb.get("bootstrap_out_input_ticks").getAsInt()>0,"Out displacement lacks consumed native walking input");
        if(bootstrapStage==Plan.Bootstrap.QUIET) {
            int at=aa.get("client_tick").getAsInt(),bt=bb.get("client_tick").getAsInt();boolean fresh=at>bootstrapSeenA&&bt>bootstrapSeenB;
            boolean good=landed&&aa.get("input_zero").getAsBoolean()&&bb.get("input_zero").getAsBoolean()&&out
                &&aa.has("bootstrap_stage")&&bb.has("bootstrap_stage")&&aa.get("bootstrap_stage").getAsInt()==Plan.Bootstrap.QUIET&&bb.get("bootstrap_stage").getAsInt()==Plan.Bootstrap.QUIET;
            bootstrapQuiet=Plan.Bootstrap.quietCount(bootstrapQuiet,fresh,good);if(fresh){bootstrapSeenA=at;bootstrapSeenB=bt;}
        }
        int next=Plan.Bootstrap.nextStage(bootstrapStage,landed,out,bootstrapQuiet,peers);
        if(next!=bootstrapStage){bootstrapStage=next;bootstrapStageTick=ticks;bootstrapRecord(next==Plan.Bootstrap.OUT?"OUT":next==Plan.Bootstrap.QUIET?"QUIET":"DONE",a,b,aa,bb);}
        publish();return bootstrapStage==Plan.Bootstrap.DONE;
    }
    @SideOnly(Side.CLIENT) private void releaseBootstrapKeys(net.minecraft.client.Minecraft client) {
        if(!bootstrapClientActive&&bootstrapProducedStage!=Plan.Bootstrap.OUT)return;
        net.minecraft.client.settings.KeyBinding.setKeyBindState(client.gameSettings.keyBindForward.getKeyCode(),false);
        if(client.thePlayer!=null){client.thePlayer.movementInput.moveForward=0;client.thePlayer.movementInput.moveStrafe=0;client.thePlayer.movementInput.jump=false;client.thePlayer.movementInput.sneak=false;}
        bootstrapClientActive=false;bootstrapProducedStage=Plan.Bootstrap.WAIT;
    }
    @SideOnly(Side.CLIENT) private int bootstrapClientStep(net.minecraft.client.Minecraft client,JsonObject control,boolean synced,int mode) {
        bootstrapOutConsumed=Plan.Bootstrap.consumed(bootstrapOutConsumed,bootstrapProducedStage==Plan.Bootstrap.OUT,client.thePlayer.movementInput.moveForward!=0);
        require(bootstrapOutConsumed<=Plan.Bootstrap.MAX_INPUT,"Bootstrap exceeded 120 consumed native input ticks");bootstrapProducedStage=Plan.Bootstrap.WAIT;
        if(!epoch.equals("first")||!phase.equals("INITIAL_STOP")||!control.has("initialization_bootstrap")){releaseBootstrapKeys(client);return Plan.Bootstrap.WAIT;}
        JsonObject value=control.getAsJsonObject("initialization_bootstrap");int stage=value.get("stage").getAsInt();
        if(stage==Plan.Bootstrap.DONE){releaseBootstrapKeys(client);return Plan.Bootstrap.WAIT;}
        bootstrapClientActive=true;require(stage>=Plan.Bootstrap.LAND&&stage<=Plan.Bootstrap.QUIET&&!control.get("measurement_active").getAsBoolean(),"Invalid bootstrap control scope");
        int serverTick=value.get("server_tick").getAsInt();long now=System.nanoTime();
        require(serverTick>=bootstrapLastServerTick,"Bootstrap control tick moved backwards");
        if(serverTick!=bootstrapLastServerTick){bootstrapLastServerTick=serverTick;bootstrapSeenNanos=now;}
        boolean fresh=now-bootstrapSeenNanos<=2000000000L;require(fresh,"Stale bootstrap control; native walking released");
        require(synced&&mode==2,"Bootstrap client lost full stopped state");client.thePlayer.capabilities.isFlying=false;
        if(stage==Plan.Bootstrap.OUT&&role.equals("b")) {
            require(Plan.Bootstrap.corridor(client.thePlayer.posX,client.thePlayer.posZ),"Bootstrap client left same-chunk corridor");
            boolean pending=!Plan.Bootstrap.outReached(value.get("start_z").getAsDouble(),client.thePlayer.posZ);
            if(pending)require(bootstrapOutConsumed<Plan.Bootstrap.MAX_INPUT,"Bootstrap native input budget exhausted");
            if(pending&&Plan.Bootstrap.inputAllowed(true,true,true,stage,synced,mode,fresh,client.thePlayer.onGround,client.thePlayer.capabilities.isFlying,bootstrapOutConsumed,control.get("measurement_active").getAsBoolean()))bootstrapProducedStage=Plan.Bootstrap.OUT;
        }
        return bootstrapProducedStage;
    }
    // END BOOTSTRAP HELPERS
    static void registerPackets(org.mtr.mapping.registry.Registry registry,Object observer)throws ReflectiveOperationException {
        // Read the existing original channel; never create or replace a network/channel/callback.
        java.lang.reflect.Field networkField=org.mtr.mapping.registry.Registry.class.getDeclaredField("network");networkField.setAccessible(true);
        Object network=networkField.get(registry);require(network!=null,"Original MTR packet network is not initialized");
        java.lang.reflect.Field channelField=network.getClass().getDeclaredField("channel");channelField.setAccessible(true);
        ((cpw.mods.fml.common.network.FMLEventChannel)channelField.get(network)).register(observer);
    }
    static NBTTagList serializeInventory(net.minecraft.entity.player.InventoryPlayer inventory){return inventory.writeToNBT(new NBTTagList());}
    private static JsonObject finalPlayerSnapshot(EntityPlayerMP player)throws IOException {
        JsonObject o=new JsonObject();o.addProperty("uuid",player.getUniqueID().toString());o.addProperty("dimension",player.dimension);
        JsonArray pos=new JsonArray();for(double coordinate:new double[]{player.posX,player.posY,player.posZ}){require(Double.isFinite(coordinate),"Nonfinite final server player position");pos.add(coordinate);}o.add("pos",pos);
        o.addProperty("selected_slot",player.inventory.currentItem);
        NBTTagCompound inventory=new NBTTagCompound();inventory.setTag("Inventory",serializeInventory(player.inventory));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(bytes)){CompressedStreamTools.write(inventory,output);}
        o.addProperty("inventory_nbt_base64",Base64.getEncoder().encodeToString(bytes.toByteArray()));return o;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public void finalLogout(PlayerEvent.PlayerLoggedOutEvent event){
        if(!prepared||!"server".equals(role)||!"DISCONNECT_ALL".equals(phase)||error!=null||!(event.player instanceof EntityPlayerMP))return;
        EntityPlayerMP player=(EntityPlayerMP)event.player;if(player.worldObj!=null&&player.worldObj.isRemote)return;
        try {
            String who=uuid("a").equals(player.getUniqueID())?"a":uuid("b").equals(player.getUniqueID())?"b":null;
            require(who!=null&&Plan.name(who).equals(player.getCommandSenderName()),"Foreign final logout identity");
            require(ownedWorld!=null&&player.worldObj==ownedWorld&&!ownedWorld.isRemote&&ownedWorld.provider!=null&&ownedWorld.provider.dimensionId==0&&player.dimension==0,"Final logout is outside owned native world/dimension");
            require(!finalPlayers.has(who),"Duplicate final logout snapshot");finalPlayers.add(who,finalPlayerSnapshot(player));
        }catch(Throwable problem){failed(problem);}
    }
    private JsonObject finalPlayersForExpected(){
        require("DISCONNECT_ALL".equals(phase)&&finalPlayers.size()==2&&finalPlayers.has("a")&&finalPlayers.has("b"),"Missing both actual final logout player snapshots");return finalPlayers;
    }
    private static JsonObject playerSnapshot(EntityPlayerMP player,NBTTagList inventory){
        JsonObject o=position(player);o.addProperty("server_y",player.posY);o.addProperty("dimension",player.dimension);o.addProperty("selected_slot",player.inventory.currentItem);o.addProperty("inventory_nbt",inventory.toString());return o;
    }
    private static double[] reconnectPoint(JsonObject snapshot){return new double[]{snapshot.get("x").getAsDouble(),snapshot.get("server_y").getAsDouble(),snapshot.get("z").getAsDouble(),snapshot.get("dimension").getAsDouble()};}
    private void verifyReconnect(EntityPlayerMP player){
        require(reconnectExpected!=null&&reconnectInventory!=null,"Missing actual pre-disconnect player snapshot");
        NBTTagList inventory=serializeInventory(player.inventory);JsonObject actual=playerSnapshot(player,inventory);
        boolean restored=Plan.restored(reconnectPoint(reconnectExpected),reconnectPoint(actual),reconnectInventory,inventory,reconnectExpected.get("selected_slot").getAsInt(),player.inventory.currentItem);
        JsonObject evidence=envelope();evidence.addProperty("event","reconnect_player_state");evidence.add("expected",reconnectExpected);evidence.add("actual",actual);
        // STOP belt on the same flat collision: allow small native settle/rounding, never a different stand position.
        evidence.addProperty("xz_tolerance",Plan.RECONNECT_XZ_TOLERANCE);evidence.addProperty("y_tolerance",Plan.RECONNECT_Y_TOLERANCE);evidence.addProperty("accepted",restored);actions.add(evidence);
        require(restored,"Native reconnect changed A position/dimension/selected slot/serialized inventory");
    }

    @SubscribeEvent public void serverPacket(FMLNetworkEvent.ServerCustomPacketEvent event){
        if(!"server".equals(role))return;
        UUID id=((net.minecraft.network.NetHandlerPlayServer)event.handler).playerEntity.getUniqueID();
        if(lift!=null&&"mtr:packet".equals(event.packet.channel())){byte[] bytes=new byte[event.packet.payload().readableBytes()];event.packet.payload().getBytes(event.packet.payload().readerIndex(),bytes);if("org.mtr.mod.packet.PacketPressLiftButton".equals(Plan.packetType(bytes))){if(id.equals(uuid("a")))lift.pressA.incrementAndGet();else if(id.equals(uuid("b")))lift.pressB.incrementAndGet();}}
        observePacket(event.packet.channel(),event.packet.payload(),id.equals(uuid("a"))?requestsA:id.equals(uuid("b"))?requestsB:packetRequests);
    }
    @SubscribeEvent @SideOnly(Side.CLIENT) public void clientPacket(FMLNetworkEvent.ClientCustomPacketEvent event){
        if(event.manager==lastManager)observePacket(event.packet.channel(),event.packet.payload(),packetResponses);
    }
    private void observePacket(String channel,io.netty.buffer.ByteBuf frame,AtomicLong counter) {
        if(!prepared||!"mtr:packet".equals(channel))return;
        byte[] bytes=new byte[frame.readableBytes()];frame.getBytes(frame.readerIndex(),bytes);
        if("org.mtr.mod.packet.PacketRequestData".equals(Plan.packetType(bytes)))counter.incrementAndGet();
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||!prepared||!role.equals("server")||error!=null||phase.equals("COMPLETE"))return;
        if(lift!=null){lift.serverTick();return;}
        try {
            MinecraftServer server=MinecraftServer.getServer();require(server!=null&&server.isDedicatedServer(),"Expected actual dedicated server");
            require(worldName.equals(server.getFolderName()),"Dedicated world changed");
            require(++ticks<36000&&++phaseTicks<(phase.equals("JOIN")?18000:2400),"MP phase timed out: "+phase);
            require(!Files.exists(coord.resolve("a-failure.json"))&&!Files.exists(coord.resolve("b-failure.json")),"Client helper failed; read its owned failure report");
            WorldServer world=server.worldServerForDimension(0);ownedWorld=world;
            EntityPlayerMP a=null,b=null;
            for(Object candidate:server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP p=(EntityPlayerMP)candidate;
                if(p.getUniqueID().equals(uuid("a")))a=p;else if(p.getUniqueID().equals(uuid("b")))b=p;else throw new AssertionError("Unexpected player on owned server");
            }
            JsonObject aa=ack("a"),bb=ack("b");
            if((phase.equals("JOIN")||phase.equals("LOAD")||phase.equals("INITIAL_STOP"))
                &&(phaseTicks==1||ticks%20==0||phase.equals("JOIN")&&a!=null&&b!=null&&connected(aa)&&connected(bb))) {
                try {nativePeerDiagnostics=serverPeerSnapshot(world,a,b);publish();}
                catch(Throwable diagnostic){nativePeerDiagnosticError=diagnostic.toString();}
            }
            if(phase.equals("JOIN")) {
                if(a==null||b==null||!connected(aa)||!connected(bb))return;
                require(a.worldObj==world&&b.worldObj==world,"Players joined another dimension");
                if(epoch.equals("first"))for(EntityPlayerMP p:new EntityPlayerMP[]{a,b}) {
                    p.setGameType(WorldSettings.GameType.CREATIVE);p.capabilities.isFlying=true;p.sendPlayerAbilities();
                    // Hover clear of MTR collision while chunks/clients load; ride setup happens only after STOP.
                    p.playerNetServerHandler.setPlayerLocation(p==a?1.4:.6,74.05,33.5,0,20);
                }
                next();return;
            }
            if(phase.equals("DISCONNECT_ALL")) {
                if(a!=null||b!=null||aa==null||bb==null||connected(aa)||connected(bb))return;
                writeExpected(world);next();return;
            }
            if(phase.equals("DISCONNECT_A")) {
                require(b!=null,"Client B disappeared while A disconnected");
                if(a!=null||aa==null||connected(aa)||bb==null||bb.get("peer_visible").getAsBoolean())return;
                next();return;
            }
            if(phase.equals("RECONNECT_A")) {
                if(a==null||b==null||!ready(aa)||!ready(bb))return;
                require(aa.get("connections").getAsInt()==2,"A did not establish a new native connection");
                verifyReconnect(a);
                next();return;
            }
            require(a!=null&&b!=null,"A real rider disconnected outside the disconnect phase");
            if(phase.equals("LOAD")) {
                if(!loaded(world))return;
                if(epoch.equals("first"))build(world,a,b);
                else {
                    require(states(world,2),"Restart did not restore the stopped original stand");
                    JsonObject saved=readJson(root.resolve("coord/first/expected.json"));
                    require(string(saved,"state_hash").equals(stateHash(world)),"Restart state differs from first normal-stop expectation");
                }
                built=true;next();return;
            }
            int wanted=Plan.mode(phase);
            if(!ensureMode(world,wanted))return;
            require(states(world,wanted),"Server original stand or brush propagation changed");
            if(!initialBootstrap(a,b,aa,bb))return;
            if(!ready(aa)||!ready(bb))return;
            require(aa.get("mode").getAsInt()==wanted&&bb.get("mode").getAsInt()==wanted,"Client acknowledged another mode");
            if(phase.equals("INITIAL_STOP")) {
                for(EntityPlayerMP p:new EntityPlayerMP[]{a,b}) {
                    if(epoch.equals("first"))p.playerNetServerHandler.setPlayerLocation(p==a?1.4:.6,71.9475,33.5,0,20);
                    p.capabilities.isFlying=false;p.sendPlayerAbilities();
                }
                next();return;
            }
            if(phase.equals("SETTLE_INITIAL")) {
                if(phaseTicks<24||!a.onGround||!b.onGround||!aa.get("on_ground").getAsBoolean()||!bb.get("on_ground").getAsBoolean())return;
                next();return;
            }
            require(!a.capabilities.isFlying&&!b.capabilities.isFlying,"Actual ride used flight");
            if(!measuring){if(++sampleTicks>=12){measuring=true;sampleTicks=0;publish();}return;}
            if(!aa.get("measurement_active").getAsBoolean()||!bb.get("measurement_active").getAsBoolean())return;
            boolean walking=phase.equals("WALK");
            int riderMode=walking?Plan.walkMode(epoch):wanted;
            require(aa.get("input_ticks").getAsInt()>0&&bb.get("input_ticks").getAsInt()>0&&aa.get("input_violations").getAsInt()==0&&bb.get("input_violations").getAsInt()==0,"Consumed input violation persisted in measurement interval");
            require(bb.get("input_zero").getAsBoolean()&&(walking?(riderMode==0?aa.get("move_forward").getAsDouble()>.9:aa.get("move_forward").getAsDouble()<-.9):aa.get("input_zero").getAsBoolean()),
                walking?"Native walking input is missing":"Carry phase received movement keys/input; standing carry is not proved");
            JsonObject sa=position(a),sb=position(b);
            if(firstSamples.isEmpty()){firstSamples.put("sa",sa);firstSamples.put("sb",sb);firstSamples.put("a",aa);firstSamples.put("b",bb);}
            JsonObject sample=envelope();sample.addProperty("server_tick",world.getTotalWorldTime());sample.add("server_a",sa);sample.add("server_b",sb);sample.add("client_a",aa);sample.add("client_b",bb);trajectory.add(sample);
            aTicks.add(aa.get("client_tick").getAsInt());bTicks.add(bb.get("client_tick").getAsInt());
            for(JsonObject p:new JsonObject[]{sa,sb,aa,bb})require(Math.abs(p.get("y").getAsDouble()-71.9375)<.1&&p.get("z").getAsDouble()>5&&p.get("z").getAsDouble()<61,"Actual rider left flat surface/interior");
            if(++sampleTicks<24)return;
            require(aTicks.size()>=10&&bTicks.size()>=10,"Too few distinct native client ticks");
            verifyMotion("sa",sa,riderMode,-1);verifyMotion("sb",sb,wanted,-1);
            verifyMotion("a",aa,riderMode,wanted);verifyMotion("b",bb,wanted,riderMode);
            require(requestsA.get()>0&&requestsB.get()>0&&aa.get("packet_roundtrip").getAsBoolean()&&bb.get("packet_roundtrip").getAsBoolean(),"Original MTR GET_DATA roundtrip missing for a native connection");
            if(!aa.get("screenshot").getAsBoolean()||!bb.get("screenshot").getAsBoolean())return;
            JsonObject result=envelope();result.addProperty("server_requests_a",requestsA.get());result.addProperty("server_requests_b",requestsB.get());actions.add(result);
            if(phase.equals("STOPPED")){
                require(a.onGround,"Pre-disconnect A is not settled on STOP belt");
                reconnectInventory=serializeInventory(a.inventory);reconnectExpected=playerSnapshot(a,reconnectInventory);result.add("reconnect_expected",reconnectExpected);
            }
            next();
        }catch(Throwable problem){failed(problem);}
    }

    private void verifyMotion(String key,JsonObject now,int wanted,int peerMode) {
        JsonObject before=firstSamples.get(key);double dz=now.get("z").getAsDouble()-before.get("z").getAsDouble();
        require(wanted==0?dz>.5:wanted==1?dz<-.5:Math.abs(dz)<.12,"Wrong actual rider motion in "+key);
        require(Math.abs(now.get("x").getAsDouble()-before.get("x").getAsDouble())<.1,"Rider crossed toward seam/rail");
        if(peerMode>=0){double remote=now.get("peer_z").getAsDouble()-before.get("peer_z").getAsDouble();require(peerMode==0?remote>.3:peerMode==1?remote<-.3:Math.abs(remote)<.2,"Other-client entity did not reflect motion");}
    }
    private boolean ensureMode(WorldServer world,int wanted)throws IOException {
        int actual=mode(world);
        if(actual==wanted){if(pendingClick!=0){pendingClick=0;publish();}return true;}
        if(pendingClick!=0&&actual!=modeBeforeClick){pendingClick=0;publish();}
        if(pendingClick==0){modeBeforeClick=actual;pendingClick=++serial;publish();}
        return false;
    }
    private static boolean loaded(WorldServer world) {
        for(int x=-1;x<=0;x++)for(int z=0;z<=4;z++) {
            Object c=world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(net.minecraft.world.ChunkCoordIntPair.chunkXZ2Int(x,z));
            if(!(c instanceof net.minecraft.world.chunk.Chunk)||c instanceof net.minecraft.world.chunk.EmptyChunk||!((net.minecraft.world.chunk.Chunk)c).isChunkLoaded)return false;
        }
        return true;
    }
    private void build(WorldServer world,EntityPlayerMP a,EntityPlayerMP b) {
        require(!built,"Fixture must never rebuild");
        world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.difficultySetting=net.minecraft.world.EnumDifficulty.PEACEFUL;
        for(int x=-2;x<=4;x++)for(int z=0;z<=67;z++){world.setBlock(x,70,z,net.minecraft.init.Blocks.stone,0,3);for(int y=71;y<=75;y++)world.setBlockToAir(x,y,z);}
        Item escalator=(Item)Item.itemRegistry.getObject("mtr:escalator");require(escalator instanceof ItemEscalator&&escalator==org.mtr.mod.Items.ESCALATOR.get().data,"Original escalator item missing");
        a.inventory.currentItem=0;a.rotationYaw=0;
        for(int z=2;z<=65;z++){ItemStack stack=new ItemStack(escalator);a.inventory.mainInventory[0]=stack;require(escalator.onItemUse(stack,a,world,1,70,z,1,.5F,1,.5F)&&stack.stackSize==0,"Original item placement rejected row "+z);}
        require(states(world,0),"Original item did not form the256 expected states");
        Item brush=(Item)Item.itemRegistry.getObject("mtr:brush");require(brush==org.mtr.mod.Items.BRUSH.get().data,"Original brush missing");
        for(EntityPlayerMP p:new EntityPlayerMP[]{a,b}){p.inventory.currentItem=0;p.inventory.mainInventory[0]=new ItemStack(brush);p.inventoryContainer.detectAndSendChanges();}
    }
    private String stateHash(WorldServer world)throws Exception {
        StringBuilder value=new StringBuilder();
        for(int z=2;z<=65;z++)for(int x=0;x<2;x++)for(int y=71;y<=72;y++)value.append(x).append(',').append(y).append(',').append(z).append(':').append(state(world,x,y,z).data.getSerializedProperties()).append('\n');
        return hex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static String hex(byte[] hash){StringBuilder value=new StringBuilder();for(byte b:hash)value.append(String.format("%02x",b&255));return value.toString();}
    private void writeExpected(WorldServer world)throws Exception {
        JsonObject players=finalPlayersForExpected();
        require(states(world,2),"Final stand is not stopped/intact");JsonObject o=envelope();o.addProperty("world",worldName);o.addProperty("state_hash",stateHash(world));
        JsonArray blocks=new JsonArray();
        for(int z=2;z<=65;z++)for(int x=0;x<2;x++)for(int y=71;y<=72;y++) {
            JsonObject item=new JsonObject();item.addProperty("x",x);item.addProperty("y",y);item.addProperty("z",z);item.addProperty("id",net.minecraft.block.Block.getIdFromBlock(world.getBlock(x,y,z)));item.addProperty("metadata",world.getBlockMetadata(x,y,z));
            JsonObject props=new JsonObject();for(Map.Entry<String,String> p:state(world,x,y,z).data.getSerializedProperties().entrySet())props.addProperty(p.getKey(),p.getValue());item.add("properties",props);
            NBTTagCompound tag=new NBTTagCompound();world.getTileEntity(x,y,z).writeToNBT(tag);item.addProperty("mtr_block_state",tag.getCompoundTag("mtr_block_state").toString());blocks.add(item);
        }
        o.add("blocks",blocks);o.add("trajectory",trajectory);o.add("actions",actions);o.add("final_players",players);o.addProperty("ready_for_normal_stop",true);write("expected.json",o);
    }

    @SubscribeEvent @SideOnly(Side.CLIENT) public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||!prepared||role.equals("server"))return;
        net.minecraft.client.Minecraft client=net.minecraft.client.Minecraft.getMinecraft();
        if(error!=null){releaseBootstrapKeys(client);return;}
        if(lift!=null){lift.clientTick(client);return;}
        try {
            require(client.mcDataDir.getCanonicalFile().toPath().equals(node),"Client gameDir is not the owned node");
            require(client.getIntegratedServer()==null,"MP helper must never use an integrated server");
            require(Plan.name(role).equals(client.getSession().getUsername()),"Client did not use its distinct offline identity");
            if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||!Files.exists(coord.resolve("control.json"))){if(bootstrapClientActive){releaseBootstrapKeys(client);throw new AssertionError("Bootstrap control unavailable");}return;}
            JsonObject control=readJson(coord.resolve("control.json"));
            require(runId.equals(string(control,"run_id"))&&epoch.equals(string(control,"epoch")),"Foreign server control");
            phase=string(control,"phase");clientTicks++;
            require(Plan.known(phase)&&artifactHash.equals(string(control,"mtr_sha256")),"Unknown phase or server artifact identity");
            if(!phase.equals(clientPhase)){clientPhase=phase;clientConnecting=false;clientDisconnected=false;clientMeasuring=false;inputLedger.reset();}
            require(!phase.equals("FAILED"),"Dedicated MP probe reported failure");
            client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=1;client.gameSettings.renderDistanceChunks=4;
            net.minecraft.client.settings.KeyBinding.unPressAllKeys();
            boolean disconnect=phase.equals("DISCONNECT_ALL")||phase.equals("DISCONNECT_A")&&role.equals("a");
            boolean connect=phase.equals("JOIN")||phase.equals("RECONNECT_A")&&role.equals("a");
            if(disconnect&&client.theWorld!=null&&!clientDisconnected) {
                client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);
                client.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());clientDisconnected=true;
            }
            if(connect&&client.theWorld==null&&!clientConnecting) {
                cpw.mods.fml.client.FMLClientHandler.instance().connectToServerAtStartup("127.0.0.1",25579);
                clientConnecting=true;
            }
            JsonObject o=envelope();o.addProperty("client_tick",clientTicks);
            boolean online=client.theWorld!=null&&client.thePlayer!=null&&client.getNetHandler()!=null&&client.getNetHandler().getNetworkManager().isChannelOpen();
            if(!online&&bootstrapClientActive){releaseBootstrapKeys(client);throw new AssertionError("Native bootstrap connection disappeared");}
            o.addProperty("connected",online);o.addProperty("connections",connectGeneration);
            o.addProperty("mtr_webserver_port",org.mtr.mod.InitClient.getServerPort());
            if(online) {
                Object manager=client.getNetHandler().getNetworkManager();
                require(client.theWorld.isRemote&&client.thePlayer.getUniqueID().equals(uuid(role)),"Wrong native remote world/player UUID");
                require(client.getNetHandler().getNetworkManager().getSocketAddress() instanceof java.net.InetSocketAddress,"Expected TCP localhost channel");
                java.net.InetSocketAddress address=(java.net.InetSocketAddress)client.getNetHandler().getNetworkManager().getSocketAddress();
                require(address.getAddress().isLoopbackAddress()&&address.getPort()==25579,"Client connected outside owned localhost listener");
                if(manager!=lastManager){lastManager=manager;connectGeneration++;packetResponses.set(0);requestSent=false;}
                o.addProperty("connections",connectGeneration);
                if(client.currentScreen!=null)client.displayGuiScreen(null);
                // Record the input consumed by this native tick before clearing any bindings for the next one.
                o.addProperty("move_forward",client.thePlayer.movementInput.moveForward);o.addProperty("move_strafe",client.thePlayer.movementInput.moveStrafe);
                o.addProperty("jump",client.thePlayer.movementInput.jump);o.addProperty("sneak",client.thePlayer.movementInput.sneak);
                boolean inputZero=client.thePlayer.movementInput.moveForward==0&&client.thePlayer.movementInput.moveStrafe==0
                    &&!client.thePlayer.movementInput.jump&&!client.thePlayer.movementInput.sneak;
                o.addProperty("input_zero",inputZero);
                if(control.get("measurement_active").getAsBoolean()){
                    if(!clientMeasuring){inputLedger.reset();clientMeasuring=true;}
                    inputLedger.record(Plan.zeroRequired(phase,role),inputZero);
                    require(inputLedger.valid(),"Nonzero consumed input during "+phase+" measurement; violations="+inputLedger.violations);
                }
                o.addProperty("measurement_active",clientMeasuring);o.addProperty("input_ticks",inputLedger.ticks);o.addProperty("input_violations",inputLedger.violations);
                if(!phase.equals("JOIN")&&!phase.equals("LOAD")&&!phase.equals("INITIAL_STOP"))client.thePlayer.capabilities.isFlying=false;
                JsonObject pos=position(client.thePlayer);for(Map.Entry<String,JsonElement> entry:pos.entrySet())o.add(entry.getKey(),entry.getValue());
                o.addProperty("step_height",client.thePlayer.stepHeight);
                boolean peer=false;
                for(Object value:client.theWorld.playerEntities) {
                    net.minecraft.entity.player.EntityPlayer other=(net.minecraft.entity.player.EntityPlayer)value;
                    if(other.getUniqueID().equals(uuid(role.equals("a")?"b":"a"))){peer=true;o.addProperty("peer_x",other.posX);o.addProperty("peer_y",other.boundingBox.minY);o.addProperty("peer_z",other.posZ);}
                }
                o.addProperty("peer_visible",peer);
                if(phase.equals("INITIAL_STOP")) {
                    try {nativePeerDiagnostics=clientPeerSnapshot(client.theWorld,control,role);o.add("native_peer_diagnostics",nativePeerDiagnostics);}
                    catch(Throwable diagnostic){nativePeerDiagnosticError=diagnostic.toString();}
                    if(nativePeerDiagnosticError!=null)o.addProperty("native_peer_diagnostic_error",nativePeerDiagnosticError);
                }
                int wanted=Plan.mode(phase);
                boolean synced=false;int actual=-1;
                try{actual=mode(client.theWorld);synced=states(client.theWorld,wanted);}catch(IllegalStateException|IllegalArgumentException pending){/* Native block/tile packets can arrive on different ticks. */}
                o.addProperty("states_ok",synced);o.addProperty("mode",actual);
                int bootstrapCommand=bootstrapClientStep(client,control,synced,actual);
                if(control.has("initialization_bootstrap")) {
                    o.addProperty("bootstrap_stage",control.getAsJsonObject("initialization_bootstrap").get("stage").getAsInt());o.addProperty("bootstrap_out_input_ticks",bootstrapOutConsumed);o.addProperty("bootstrap_flying",client.thePlayer.capabilities.isFlying);
                }
                if((phase.equals("WALK")&&role.equals("a")&&synced)||bootstrapCommand==Plan.Bootstrap.OUT) {
                    net.minecraft.client.settings.KeyBinding.setKeyBindState((Plan.walkMode(epoch)==0?client.gameSettings.keyBindForward:client.gameSettings.keyBindBack).getKeyCode(),true);
                }else {
                    client.thePlayer.movementInput.moveForward=0;client.thePlayer.movementInput.moveStrafe=0;
                    client.thePlayer.movementInput.jump=false;client.thePlayer.movementInput.sneak=false;
                }
                if(!requestSent) {
                    org.mtr.mod.InitClient.REGISTRY_CLIENT.sendPacketToServer(new org.mtr.mod.packet.PacketRequestData(
                        new org.mtr.core.operation.DataRequest(client.thePlayer.getUniqueID(),org.mtr.mod.Init.blockPosToPosition(new BlockPos((int)Math.floor(client.thePlayer.posX),71,(int)Math.floor(client.thePlayer.posZ))),64)));
                    requestSent=true;
                }
                o.addProperty("packet_roundtrip",requestSent&&packetResponses.get()>0);o.addProperty("mtr_data_responses",packetResponses.get());
                int click=control.get("click_serial").getAsInt();
                if(control.get("click_pending").getAsBoolean()&&role.equals(string(control,"actor"))&&click!=lastClickSerial) {
                    ItemStack held=client.thePlayer.getCurrentEquippedItem();
                    boolean heldBrush=held!=null&&held.getItem()==org.mtr.mod.Items.BRUSH.get().data;
                    int z=(int)Math.floor(client.thePlayer.posZ),x=role.equals("a")?1:0;
                    boolean completeBeforeClick=false;
                    try{if(actual>=0&&actual<=2)completeBeforeClick=states(client.theWorld,actual);}
                    catch(IllegalStateException|IllegalArgumentException pending){/* Wait for the complete current stand, before changing its mode. */}
                    boolean targetStep=client.theWorld.getBlock(x,71,z) instanceof BlockEscalatorStep;
                    double distanceSq=client.thePlayer.getDistanceSq(x+.5,71.9375,z+.5),reach=client.playerController.getBlockReachDistance();
                    if(Plan.brushReady(completeBeforeClick,heldBrush,targetStep,z,distanceSq,reach)) {
                        require(client.theWorld.getBlock(x,71,z) instanceof BlockEscalatorStep,"Native brush target is not the shared stand");
                        require(client.playerController.onPlayerRightClick(client.thePlayer,client.theWorld,held,x,71,z,1,
                            net.minecraft.util.Vec3.createVectorHelper(x+.5,71.9375,z+.5)),"Native client brush C08 was rejected");
                        lastClickSerial=click;
                    }
                }
            }else{o.addProperty("peer_visible",false);o.addProperty("states_ok",false);o.addProperty("packet_roundtrip",false);}
            o.addProperty("screenshot",shotPhase.equals(phase));write(role+".json",o);
            if(phase.equals("COMPLETE"))client.shutdown();
        }catch(Throwable problem){releaseBootstrapKeys(client);failed(problem);}
    }

    @SubscribeEvent @SideOnly(Side.CLIENT) public void renderTick(TickEvent.RenderTickEvent event) {
        if(lift!=null){lift.renderTick(event);return;}
        if(event.phase!=TickEvent.Phase.END||!prepared||role.equals("server")||error!=null||shotPhase.equals(phase))return;
        if(!phase.equals("FORWARD")&&!phase.equals("BACKWARD")&&!phase.equals("STOPPED")&&!phase.equals("POST_RECONNECT")&&!phase.equals("WALK")&&!phase.equals("FINAL_STOP"))return;
        net.minecraft.client.Minecraft client=net.minecraft.client.Minecraft.getMinecraft();
        if(client.theWorld==null||client.thePlayer==null||client.currentScreen!=null)return;
        try {
            try{if(!states(client.theWorld,Plan.mode(phase)))return;}
            catch(IllegalStateException|IllegalArgumentException pending){return;}
            String filename="mtr-mp-"+epoch+"-"+role+"-"+phase.toLowerCase(Locale.ROOT)+".png";
            net.minecraft.util.ScreenShotHelper.saveScreenshot(client.mcDataDir,filename,client.displayWidth,client.displayHeight,client.getFramebuffer());
            require(Files.isRegularFile(node.resolve("screenshots").resolve(filename),LinkOption.NOFOLLOW_LINKS),"Native MP screenshot missing");shotPhase=phase;
        }catch(Throwable problem){failed(problem);}
    }
    /** Fixed opt-in lift path; owns no processes and uses the outer MP transport/stop protocol. */
    private final class LiftScenario {
        private final int[] FLOOR_Y={72,80,88};
        private final String[] DESCRIPTIONS={"Lower","Middle","Upper"};
        private long liftId,phaseStartedNanos=System.nanoTime(),phasePressA,phasePressB;
        private int setupTeleports,still,jumpTicks,renderFrame,baselineS08=-1,peerWait,approachInputTicks,approachQuiet;
        private boolean approachUsed,approachArmed,approachProduced;
        private JsonObject approachMissingProof;
        private boolean standBuilt,jumped,fell,renderArmed;
        private double jumpPeak,serverJumpPeak,previousJumpY;
        private String sentPhase="",renderPhase="";
        private Object renderWorld,renderPlayer,renderLift;
        private long renderedBefore;
        private JsonObject rendered;
        private final AtomicLong s08=new AtomicLong(),pressA=new AtomicLong(),pressB=new AtomicLong();
        private volatile boolean packetObserverReady;
        private Simulator simulator;
        private volatile CoreSample coreSample;
        private final java.util.concurrent.atomic.AtomicBoolean coreReadPending=new java.util.concurrent.atomic.AtomicBoolean();
        
        private String nextPhase(){for(int i=0;i<Plan.LIFT_PHASES.length-1;i++)if(Plan.LIFT_PHASES[i].equals(phase))return Plan.LIFT_PHASES[i+1];throw new AssertionError("Unknown lift phase");}
        private void advance()throws IOException{phasePressA=pressA.get();phasePressB=pressB.get();still=0;phaseStartedNanos=System.nanoTime();next();}
        private boolean measured(){return Arrays.asList(Plan.LIFT_MEASURED).contains(phase);}
        private boolean command(JsonObject ack){return ack!=null&&phase.equals(string(ack,"lift_command_sent"));}
        private boolean bool(JsonObject o,String key){return o.has(key)&&o.get(key).getAsBoolean();}
        private double num(JsonObject o,String key){return o.get(key).getAsDouble();}
        private JsonObject sample(JsonObject ack){return ack.getAsJsonObject("lift");}
        private boolean fresh(JsonObject ack){return ready(ack)&&ack.has("lift")&&bool(sample(ack),"rendered")&&num(ack,"client_tick")-num(sample(ack),"tick")<=3;}
        private boolean stopped(CoreSample core,int floor){return core!=null&&core.id==liftId&&Math.abs(core.progress-(FLOOR_Y[floor]-72))<.03&&Math.abs(core.speed)<1E-8;}
        private JsonObject nativePosition(EntityPlayerMP p){JsonObject o=position(p);o.addProperty("authorized",PlayerPhysics.authorized(p));o.addProperty("no_clip",p.noClip);o.addProperty("gravity",PlayerPhysics.gravity(.08,p));return o;}
        private void rider(EntityPlayerMP p,JsonObject ack){JsonObject value=sample(ack);require(bool(value,"riding")&&PlayerPhysics.authorized(p)&&p.noClip&&Math.abs(PlayerPhysics.gravity(.08,p))<1E-10,"Lift rider authorization lost");require(!p.capabilities.isFlying&&!bool(value,"flying"),"Flight replaced lift carry");require(Math.abs(num(ack,"y")-num(value,"cabin_y"))<.35&&Math.abs(p.boundingBox.minY-num(value,"cabin_y"))<1.5,"Lift rider diverged from original cabin");}
        private boolean out(EntityPlayerMP p,JsonObject ack){return !bool(sample(ack),"riding")&&!PlayerPhysics.authorized(p)&&!p.noClip&&!bool(sample(ack),"no_clip")&&p.onGround&&bool(ack,"on_ground")&&num(ack,"z")<=4.4&&Math.abs(num(ack,"y")-72)<.1&&Math.abs(p.boundingBox.minY-72)<.1&&Math.abs(PlayerPhysics.gravity(.08,p)-.08)<1E-10;}
        private void readCoreSoon(){if(simulator==null||!coreReadPending.compareAndSet(false,true))return;simulator.run(()->{try{coreSample=new CoreSample(simulator);}catch(Throwable problem){failed(problem);}finally{coreReadPending.set(false);}});}
        private Set<String> coreRiders(CoreSample core){Set<String> ids=new HashSet<>();for(JsonElement value:core.json.getAsJsonArray("ridingEntities"))require(ids.add(value.getAsJsonObject().get("clientId").getAsString()),"Duplicate original lift rider UUID");return ids;}
        private boolean measure(WorldServer world,EntityPlayerMP a,EntityPlayerMP b,JsonObject aa,JsonObject bb,CoreSample core)throws IOException{
            if(!measuring){if(++still>=12){measuring=true;publish();}return false;}
            if(!bool(aa,"measurement_active")||!bool(bb,"measurement_active"))return false;
            for(JsonObject ack:new JsonObject[]{aa,bb})require(bool(ack,"input_zero")&&num(ack,"input_ticks")>0&&num(ack,"input_violations")==0,"Nonzero consumed lift carry/exit input");
            JsonObject row=envelope();row.addProperty("server_tick",world.getTotalWorldTime());row.add("server_a",nativePosition(a));row.add("server_b",nativePosition(b));row.add("client_a",aa);row.add("client_b",bb);row.add("core",core.json);trajectory.add(row);
            aTicks.add(aa.get("client_tick").getAsInt());bTicks.add(bb.get("client_tick").getAsInt());sampleTicks++;
            if(sampleTicks<24||aTicks.size()<10||bTicks.size()<10||!bool(aa,"screenshot")||!bool(bb,"screenshot"))return false;
            return true;
        }
        private void phaseAction(){JsonObject action=envelope();action.addProperty("server_requests_a",requestsA.get());action.addProperty("server_requests_b",requestsB.get());actions.add(action);}
        private void requestAck(String actor,int y){JsonObject value=envelope();value.addProperty("actor",actor);value.addProperty("destination_y",y);value.addProperty("original_packet_seen",true);value.addProperty("queue_ack",true);actions.add(value);}

        private void serverTick(){try{
            MinecraftServer server=MinecraftServer.getServer();require(server!=null&&server.isDedicatedServer()&&worldName.equals(server.getFolderName()),"Foreign dedicated lift world");
            require(++ticks<36000&&++phaseTicks<(phase.equals("JOIN")?18000:2400),"Lift phase timeout: "+phase);
            require(!Files.exists(coord.resolve("a-failure.json"))&&!Files.exists(coord.resolve("b-failure.json")),"Native lift client failed");
            WorldServer world=server.worldServerForDimension(0);ownedWorld=world;
            EntityPlayerMP a=null,b=null;for(Object value:server.getConfigurationManager().playerEntityList){EntityPlayerMP p=(EntityPlayerMP)value;if(p.getUniqueID().equals(uuid("a")))a=p;else if(p.getUniqueID().equals(uuid("b")))b=p;else throw new AssertionError("Foreign lift player");}
            JsonObject aa=ack("a"),bb=ack("b");
            if(phase.equals("JOIN")){
                if(a==null||b==null||!connected(aa)||!connected(bb))return;
                require(a.worldObj==world&&b.worldObj==world,"Lift players in foreign world");
                if(epoch.equals("first"))for(EntityPlayerMP p:new EntityPlayerMP[]{a,b}){p.setGameType(WorldSettings.GameType.CREATIVE);p.capabilities.isFlying=true;p.sendPlayerAbilities();p.playerNetServerHandler.setPlayerLocation(p==a?7.65:8.35,74.05,4.5,0,20);setupTeleports++;}
                advance();return;
            }
            if(phase.equals("DISCONNECT_ALL")){
                if(a!=null||b!=null||aa==null||bb==null||connected(aa)||connected(bb))return;
                writeLiftExpected(world);advance();return;
            }
            require(a!=null&&b!=null&&a.worldObj==world&&b.worldObj==world,"Dedicated lift rider disconnected/changed world");
            if(phase.equals("LOAD")){
                Object chunk=world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(net.minecraft.world.ChunkCoordIntPair.chunkXZ2Int(0,0));
                if(!(chunk instanceof net.minecraft.world.chunk.Chunk)||!((net.minecraft.world.chunk.Chunk)chunk).isChunkLoaded||!readyForSetup(aa)||!readyForSetup(bb))return;
                simulator=actualSimulator();
                if(epoch.equals("first")){buildStand(world,a);advance();}
                else {assertStand(world);JsonObject first=readJson(root.resolve("coord/first/expected.json"));liftId=Long.parseLong(string(first,"lift_id"));require(string(first,"state_hash").equals(liftStateHash(world)),"Restart lift states changed");phase="LIFT_READY";phaseTicks=0;phaseStartedNanos=System.nanoTime();publish();}
                return;
            }
            readCoreSoon();CoreSample core=coreSample;if(core==null)return;
            require(System.nanoTime()-core.atNanos<5000000000L,"Stale dedicated lift core sample");
            if(phase.equals("LIFT_LABELS")){if(!command(aa)||!labelsMatch(world))return;use(world,a,new ItemStack(originalItem("lift_refresher",org.mtr.mod.Items.LIFT_REFRESHER.get().data)),8,72,8,1,.5F);advance();return;}
            if(phase.equals("LIFT_GENERATE")){if(core.count==0)return;require(core.count==1&&core.floorCount==3&&core.floorGeometry,"Wrong generated lift geometry");liftId=core.id;advance();return;}
            if(phase.equals("LIFT_CONFIGURE")){
                if(!command(aa)||!core.configured)return;connectAndUnlock(world,a);
                for(EntityPlayerMP p:new EntityPlayerMP[]{a,b}){p.inventory.currentItem=0;p.inventory.mainInventory[0]=null;p.inventory.mainInventory[1]=new ItemStack(org.mtr.mod.Items.BRUSH.get().data);p.inventoryContainer.detectAndSendChanges();p.capabilities.isFlying=false;p.sendPlayerAbilities();p.playerNetServerHandler.setPlayerLocation(p==a?7.65:8.35,72,4.5,0,20);setupTeleports++;}
                advance();return;
            }
            if(phase.equals("LIFT_APPROACH")){
                require(epoch.equals("first")&&approachUsed&&!measuring&&phaseTicks<Plan.Bootstrap.MAX_TOTAL,"Lift initialization budget/epoch differs");
                require(readyForSetup(aa)&&readyForSetup(bb)&&bool(aa,"states_ok")&&bool(bb,"states_ok")&&!PlayerPhysics.authorized(a)&&!PlayerPhysics.authorized(b)&&coreRiders(core).isEmpty(),"Lift approach lost setup/zero-rider precondition");
                if(!approachArmed){if(b.onGround&&bool(bb,"on_ground")&&Math.abs(b.posZ+4.5)<.15&&Math.abs(num(bb,"z")+4.5)<.15){approachArmed=true;publish();}return;}
                require(Math.abs(b.posX-8.35)<.15&&b.posZ>=-4.7&&b.posZ<4.9&&!b.capabilities.isFlying,"B left initialization corridor");
                int consumed=bb.has("approach_input_ticks")?bb.get("approach_input_ticks").getAsInt():0;require(consumed<=Plan.Bootstrap.MAX_INPUT,"Lift approach consumed input budget");
                if(b.posZ>=4.5&&num(bb,"z")>=4.5&&bool(bb,"input_zero")&&bool(aa,"peer_visible")&&bool(bb,"peer_visible")){if(++approachQuiet<12)return;JsonObject proof=envelope();proof.addProperty("event","initialization_approach");proof.addProperty("actor","b");proof.addProperty("started_only_when_native_peer_missing",true);proof.addProperty("start_z",-4.5);proof.addProperty("end_z",num(bb,"z"));proof.addProperty("consumed_input_ticks",consumed);proof.add("preconditions",approachMissingProof);actions.add(proof);phase="LIFT_READY";phaseTicks=still=0;phaseStartedNanos=System.nanoTime();publish();}else approachQuiet=0;
                return;
            }
            if(phase.equals("LIFT_READY")&&epoch.equals("first")&&!approachUsed&&readyForSetup(aa)&&readyForSetup(bb)&&bool(aa,"states_ok")&&bool(bb,"states_ok")&&a.onGround&&b.onGround&&bool(aa,"on_ground")&&bool(bb,"on_ground")&&(!bool(aa,"peer_visible")||!bool(bb,"peer_visible"))){
                require(!measuring&&!PlayerPhysics.authorized(a)&&!PlayerPhysics.authorized(b)&&coreRiders(core).isEmpty(),"Lift calibration is outside zero-rider setup");
                if(!aa.has("lift")||!bb.has("lift")||bool(sample(aa),"riding")||bool(sample(bb),"riding"))return;
                if(++peerWait<Plan.Bootstrap.WAIT_TICKS)return;
                require(chunkStatus(world,0,0).get("ready").getAsBoolean()&&chunkStatus(world,0,-1).get("ready").getAsBoolean(),"Initialization corridor chunks not loaded");
                approachUsed=true;approachMissingProof=envelope();approachMissingProof.add("client_a",aa);approachMissingProof.add("client_b",bb);approachMissingProof.addProperty("riders",0);approachMissingProof.addProperty("chunks_ready",true);
                b.playerNetServerHandler.setPlayerLocation(8.35,72,-4.5,0,20);setupTeleports++;phase="LIFT_APPROACH";phaseTicks=0;publish();return;
            }
            if(!fresh(aa)||!fresh(bb))return;
            require(core.id==liftId&&core.configured&&core.floorGeometry,"Original lift ID/config changed");
            if(phase.equals("LIFT_READY")){
                require(coreRiders(core).isEmpty()&&!PlayerPhysics.authorized(a)&&!PlayerPhysics.authorized(b)&&!bool(sample(aa),"riding")&&!bool(sample(bb),"riding"),"Rider appeared before natural BOARD");
                if(!stopped(core,0)||!a.onGround||!b.onGround||!bool(aa,"on_ground")||!bool(bb,"on_ground")||num(aa,"z")>4.9||num(bb,"z")>4.9)return;
                if(epoch.equals("restart")){JsonObject first=readJson(root.resolve("coord/first/expected.json"));require(core.json.equals(first.getAsJsonObject("final_lift")),"Restart original lift full map changed");require(setupTeleports==0,"Restart teleported players");}
                if(++still<12)return;assertStand(world);advance();return;
            }
            for(JsonObject ack:new JsonObject[]{aa,bb})require(num(sample(ack),"s08_after_setup")==0,"Native S08 correction after setup ACK");
            require(!a.capabilities.isFlying&&!b.capabilities.isFlying,"Lift phase used flight");
            if(phase.equals("LIFT_CALL")||phase.equals("LIFT_REOPEN")){if(!command(aa))return;if(phase.equals("LIFT_REOPEN")&&(!stopped(core,0)||num(sample(aa),"door")<.95||num(sample(bb),"door")<.95||num(sample(bb),"landing_door")<.70))return;advance();return;}
            if(phase.equals("LIFT_PICKUP")){if(stopped(core,0)&&num(sample(aa),"door")>.95&&num(sample(bb),"door")>.95&&num(sample(aa),"landing_door")>.70&&num(sample(bb),"landing_door")>.70)advance();return;}
            if(phase.equals("LIFT_BOARD")){
                if(!bool(sample(aa),"riding")||!bool(sample(bb),"riding")||!PlayerPhysics.authorized(a)||!PlayerPhysics.authorized(b))return;
                rider(a,aa);rider(b,bb);if(!coreRiders(core).equals(new HashSet<>(Arrays.asList(uuid("a").toString(),uuid("b").toString()))))return;
                if(measure(world,a,b,aa,bb,core)){phaseAction();advance();}return;
            }
            if(phase.equals("LIFT_UP_REQUEST")||phase.equals("LIFT_DOWN_REQUEST")){
                rider(a,aa);rider(b,bb);boolean up=phase.equals("LIFT_UP_REQUEST");JsonObject actor=up?aa:bb;
                if(core.atNanos<phaseStartedNanos||!command(actor)||(up?pressA.get()<=phasePressA:pressB.get()<=phasePressB)||!(up?core.topInstruction:core.bottomInstruction))return;
                requestAck(up?"a":"b",up?88:72);advance();return;
            }
            if(phase.equals("LIFT_UP")||phase.equals("LIFT_DOWN")){
                rider(a,aa);rider(b,bb);boolean up=phase.equals("LIFT_UP");
                if(stopped(core,up?2:0)){require(sampleTicks>=24&&aTicks.size()>=10&&bTicks.size()>=10&&bool(aa,"screenshot")&&bool(bb,"screenshot"),"Lift travel lacks distinct moving samples/screenshots");phaseAction();advance();return;}
                if((up?core.speed>0:core.speed<0)&&core.progress>.5&&core.progress<15.5&&num(sample(aa),"door")<=.02&&num(sample(bb),"door")<=.02)measure(world,a,b,aa,bb,core);return;
            }
            if(phase.equals("LIFT_TOP")){rider(a,aa);rider(b,bb);require(stopped(core,2),"Lift left floor3 before B request");if(measure(world,a,b,aa,bb,core)){phaseAction();advance();}return;}
            if(phase.equals("LIFT_BOTTOM")){rider(a,aa);rider(b,bb);if(stopped(core,0)&&num(sample(aa),"door")>.95&&num(sample(aa),"landing_door")>.70)advance();return;}
            if(phase.equals("LIFT_EXIT_A")||phase.equals("LIFT_EXIT_B")){
                boolean exitA=phase.equals("LIFT_EXIT_A");if(exitA)rider(b,bb);
                if(!out(exitA?a:b,exitA?aa:bb))return;
                Set<String> wanted=exitA?Collections.singleton(uuid("b").toString()):Collections.<String>emptySet();if(!coreRiders(core).equals(wanted))return;
                if(measure(world,a,b,aa,bb,core)){phaseAction();jumped=fell=false;jumpPeak=serverJumpPeak=previousJumpY=72;advance();}return;
            }
            if(phase.equals("LIFT_GRAVITY_A")||phase.equals("LIFT_GRAVITY_B")){
                boolean actorA=phase.equals("LIFT_GRAVITY_A");EntityPlayerMP p=actorA?a:b;JsonObject ack=actorA?aa:bb;if(actorA)rider(b,bb);
                require(!PlayerPhysics.authorized(p)&&!p.noClip&&!bool(sample(ack),"riding")&&Math.abs(PlayerPhysics.gravity(.08,p)-.08)<1E-10,"Gravity check regained passenger rights");
                double y=num(ack,"y");jumpPeak=Math.max(jumpPeak,y);serverJumpPeak=Math.max(serverJumpPeak,p.boundingBox.minY);if(y>72.15)jumped=true;if(jumped&&y<previousJumpY-.01)fell=true;previousJumpY=y;
                if(jumped&&fell&&jumpPeak>72.25&&serverJumpPeak>72.25&&out(p,ack)){JsonObject value=envelope();value.addProperty("event","native_gravity");value.addProperty("actor",actorA?"a":"b");value.addProperty("jump_peak",jumpPeak);value.addProperty("server_jump_peak",serverJumpPeak);value.addProperty("fell",true);value.addProperty("landed",true);actions.add(value);advance();}return;
            }
            if(phase.equals("LIFT_IDLE")){if(!stopped(core,0)||core.cooldown||!core.emptyQueue||!coreRiders(core).isEmpty()||!out(a,aa)||!out(b,bb))return;assertStand(world);advance();return;}

        }catch(Throwable problem){failed(problem);}}
        private boolean readyForSetup(JsonObject ack){return connected(ack)&&ack.has("uuid")&&bool(ack,"packet_roundtrip");}

        @SideOnly(Side.CLIENT) void clientTick(net.minecraft.client.Minecraft client){try{
            require(client.mcDataDir.getCanonicalFile().toPath().equals(node)&&client.getIntegratedServer()==null&&Plan.name(role).equals(client.getSession().getUsername()),"Foreign lift client");
            if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||!Files.exists(coord.resolve("control.json")))return;
            JsonObject control=readJson(coord.resolve("control.json"));require(runId.equals(string(control,"run_id"))&&epoch.equals(string(control,"epoch"))&&artifactHash.equals(string(control,"mtr_sha256"))&&"lift".equals(string(control,"scenario")),"Foreign lift control");
            phase=string(control,"phase");clientTicks++;require(Arrays.asList(Plan.LIFT_PHASES).contains(phase),"Unknown lift phase");
            if(!phase.equals(clientPhase)){clientPhase=phase;clientMeasuring=false;inputLedger.reset();jumpTicks=0;}
            client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=1;client.gameSettings.renderDistanceChunks=4;
            net.minecraft.client.settings.KeyBinding.unPressAllKeys();
            if(phase.equals("DISCONNECT_ALL")&&client.theWorld!=null&&!clientDisconnected){client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());clientDisconnected=true;}
            if(phase.equals("JOIN")&&client.theWorld==null&&!clientConnecting){cpw.mods.fml.client.FMLClientHandler.instance().connectToServerAtStartup("127.0.0.1",25579);clientConnecting=true;}
            JsonObject o=envelope();o.addProperty("client_tick",clientTicks);o.addProperty("mtr_webserver_port",org.mtr.mod.InitClient.getServerPort());
            boolean online=client.theWorld!=null&&client.thePlayer!=null&&client.getNetHandler()!=null&&client.getNetHandler().getNetworkManager().isChannelOpen();o.addProperty("connected",online);o.addProperty("connections",connectGeneration);
            if(online){
                net.minecraft.network.NetworkManager manager=client.getNetHandler().getNetworkManager();require(client.theWorld.isRemote&&client.thePlayer.getUniqueID().equals(uuid(role)),"Wrong remote lift UUID/world");
                require(manager.getSocketAddress() instanceof java.net.InetSocketAddress,"Lift connection is not TCP");java.net.InetSocketAddress address=(java.net.InetSocketAddress)manager.getSocketAddress();require(address.getAddress().isLoopbackAddress()&&address.getPort()==25579,"Foreign lift TCP listener");
                if(manager!=lastManager){require(lastManager==null,"Lift scenario reconnected unexpectedly");lastManager=manager;connectGeneration++;installS08Observer(manager);}
                o.addProperty("connections",connectGeneration);if(client.currentScreen!=null)client.displayGuiScreen(null);
                for(Map.Entry<String,JsonElement> e:position(client.thePlayer).entrySet())o.add(e.getKey(),e.getValue());
                boolean zero=client.thePlayer.movementInput.moveForward==0&&client.thePlayer.movementInput.moveStrafe==0&&!client.thePlayer.movementInput.jump&&!client.thePlayer.movementInput.sneak;
                o.addProperty("input_zero",zero);o.addProperty("move_forward",client.thePlayer.movementInput.moveForward);o.addProperty("move_strafe",client.thePlayer.movementInput.moveStrafe);
                if(bool(control,"measurement_active")){if(!clientMeasuring){inputLedger.reset();clientMeasuring=true;}inputLedger.record(true,zero);require(inputLedger.valid(),"Nonzero consumed lift measurement input");}
                o.addProperty("measurement_active",clientMeasuring);o.addProperty("input_ticks",inputLedger.ticks);o.addProperty("input_violations",inputLedger.violations);
                boolean peer=false;for(Object value:client.theWorld.playerEntities){net.minecraft.entity.player.EntityPlayer p=(net.minecraft.entity.player.EntityPlayer)value;if(p.getUniqueID().equals(uuid(role.equals("a")?"b":"a"))){peer=true;o.addProperty("peer_x",p.posX);o.addProperty("peer_y",p.boundingBox.minY);o.addProperty("peer_z",p.posZ);}}
                o.addProperty("peer_visible",peer);
                liftId=Long.parseLong(string(control,"lift_id"));Lift actual=liftId==0?findOnlyClientLift():MinecraftClientData.getLift(liftId);
                boolean synced=false;try{synced=actual!=null&&configured(actual)&&floorGeometry(actual)&&labelsMatch(client.theWorld)&&linksMatch(client.theWorld);for(int y:FLOOR_Y)synced&=unlocked(client.theWorld,8,y,5)&&unlocked(client.theWorld,9,y,5);}catch(IllegalArgumentException|IllegalStateException waiting){}
                o.addProperty("states_ok",synced);
                if(rendered!=null&&renderPhase.equals(phase)&&clientTicks-num(rendered,"tick")<=3)o.add("lift",rendered);
                if(Plan.liftReadyBaseline(phase,clientMeasuring||bool(control,"measurement_active"),packetObserverReady,synced&&requestSent&&packetResponses.get()>0,client.thePlayer.onGround,zero,client.thePlayer.capabilities.isFlying,client.thePlayer.noClip,actual!=null&&VehicleRidingMovement.isRiding(actual.getId()),client.thePlayer.posX,client.thePlayer.boundingBox.minY,client.thePlayer.posZ)){baselineS08=(int)s08.get();}
                if(phase.equals("LIFT_APPROACH")&&role.equals("b")){
                    if(approachProduced&&client.thePlayer.movementInput.moveForward>.9)approachInputTicks++;
                    require(approachInputTicks<=Plan.Bootstrap.MAX_INPUT&&!VehicleRidingMovement.isRiding(liftId)&&!client.thePlayer.capabilities.isFlying,"Lift approach input budget/riding/flight differs");
                    approachProduced=bool(control,"approach_input")&&synced&&client.thePlayer.onGround&&client.thePlayer.posZ<4.55&&approachInputTicks<Plan.Bootstrap.MAX_INPUT;
                    if(approachProduced)key(client.gameSettings.keyBindForward,true);o.addProperty("approach_input_ticks",approachInputTicks);
                }
                if(phase.equals("LIFT_BOARD")&&actual!=null&&!VehicleRidingMovement.isRiding(actual.getId()))key(client.gameSettings.keyBindForward,client.thePlayer.posZ<6.55);
                if((phase.equals("LIFT_EXIT_A")&&role.equals("a")||phase.equals("LIFT_EXIT_B")&&role.equals("b"))&&client.thePlayer.posZ>4.35)key(client.gameSettings.keyBindBack,true);
                if((phase.equals("LIFT_GRAVITY_A")&&role.equals("a")||phase.equals("LIFT_GRAVITY_B")&&role.equals("b"))&&jumpTicks++<2)key(client.gameSettings.keyBindJump,true);
                if(!requestSent){InitClient.REGISTRY_CLIENT.sendPacketToServer(new org.mtr.mod.packet.PacketRequestData(new org.mtr.core.operation.DataRequest(client.thePlayer.getUniqueID(),Init.blockPosToPosition(new BlockPos((int)client.thePlayer.posX,72,(int)client.thePlayer.posZ)),64)));requestSent=true;}
                o.addProperty("packet_roundtrip",requestSent&&packetResponses.get()>0);
                if(!sentPhase.equals(phase)){
                    if(role.equals("a")&&phase.equals("LIFT_LABELS")&&clientStandReady(client)){for(int i=0;i<3;i++)InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateLiftTrackFloorConfig(floorPos(i),Integer.toString(i+1),DESCRIPTIONS[i],false));sentPhase=phase;}
                    else if(role.equals("a")&&phase.equals("LIFT_CONFIGURE")&&actual!=null){actual.setDimensions(3,2,2,0,0,-1.5);actual.setAngle(Angle.fromAngle(270));actual.setIsDoubleSided(false);InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance()).addLift(actual)));sentPhase=phase;}
                    else if(role.equals("a")&&(phase.equals("LIFT_CALL")||phase.equals("LIFT_REOPEN"))){require(client.thePlayer.getHeldItem()==null&&client.thePlayer.getDistanceSq(9.5,72.375,5.5)<=Math.pow(client.playerController.getBlockReachDistance(),2),"Landing call outside native reach/empty hand");require(client.theWorld.getBlock(9,72,5).onBlockActivated(client.theWorld,9,72,5,client.thePlayer,2,.5F,.375F,.5F),"Original lower call rejected");sentPhase=phase;}
                    else if(phase.equals("LIFT_UP_REQUEST")&&role.equals("a")||phase.equals("LIFT_DOWN_REQUEST")&&role.equals("b")){require(actual!=null&&VehicleRidingMovement.isRiding(actual.getId()),"Remote destination without passenger bind");org.mtr.core.operation.PressLift press=new org.mtr.core.operation.PressLift();press.add(new Position(8,phase.equals("LIFT_UP_REQUEST")?88:72,8),LiftDirection.NONE);InitClient.REGISTRY_CLIENT.sendPacketToServer(new org.mtr.mod.packet.PacketPressLiftButton(press));sentPhase=phase;}
                }
                o.addProperty("lift_command_sent",sentPhase);
            }else{o.addProperty("peer_visible",false);o.addProperty("states_ok",false);}
            o.addProperty("screenshot",shotPhase.equals(phase));write(role+".json",o);if(phase.equals("COMPLETE"))client.shutdown();
        }catch(Throwable problem){net.minecraft.client.settings.KeyBinding.unPressAllKeys();failed(problem);}}

        @SideOnly(Side.CLIENT) private void installS08Observer(net.minecraft.network.NetworkManager manager)throws Exception{
            io.netty.channel.Channel channel=null;for(java.lang.reflect.Field field:net.minecraft.network.NetworkManager.class.getDeclaredFields())if(io.netty.channel.Channel.class.isAssignableFrom(field.getType())){field.setAccessible(true);channel=(io.netty.channel.Channel)field.get(manager);}require(channel!=null,"Native lift TCP channel missing");final io.netty.channel.Channel actual=channel;
            actual.eventLoop().execute(()->{try{actual.pipeline().addBefore("packet_handler","mtr_mp_lift_observer",new io.netty.channel.ChannelInboundHandlerAdapter(){@Override public void channelRead(io.netty.channel.ChannelHandlerContext context,Object value)throws Exception{if(value instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook)s08.incrementAndGet();super.channelRead(context,value);}});packetObserverReady=true;}catch(Throwable problem){failed(problem);}});
        }
        @SideOnly(Side.CLIENT) void renderTick(TickEvent.RenderTickEvent event){if(!prepared||role.equals("server")||error!=null)return;try{
            net.minecraft.client.Minecraft client=net.minecraft.client.Minecraft.getMinecraft();Lift actual=client.theWorld==null?null:(liftId==0?findOnlyClientLift():MinecraftClientData.getLift(liftId));
            boolean eligible=actual!=null&&client.thePlayer!=null&&client.currentScreen==null&&!client.skipRenderWorld&&client.renderViewEntity==client.thePlayer&&MinecraftClientData.getInstance().liftWrapperList.get(actual.getId())!=null&&MinecraftClientData.getInstance().liftWrapperList.get(actual.getId()).shouldRender;
            java.lang.reflect.Field field=org.mtr.mod.render.MainRenderer.class.getDeclaredField("lastRenderedMillis");field.setAccessible(true);long millis=((Number)field.get(null)).longValue();
            if(event.phase==TickEvent.Phase.START){renderArmed=eligible;renderWorld=client.theWorld;renderPlayer=client.thePlayer;renderLift=actual;renderedBefore=millis;return;}
            if(!renderArmed||!eligible||renderWorld!=client.theWorld||renderPlayer!=client.thePlayer||renderLift!=actual||millis<=renderedBefore)return;renderArmed=false;
            final org.mtr.mapping.holder.World mappedWorld=new org.mtr.mapping.holder.World(client.theWorld);Vector pos=actual.getPosition((a,b)->ItemLiftRefresher.findPath(mappedWorld,a,b));JsonObject value=new JsonObject();value.addProperty("rendered",true);value.addProperty("frame",++renderFrame);value.addProperty("tick",clientTicks);value.addProperty("lift_id",Long.toString(actual.getId()));value.addProperty("cabin_y",pos.y+actual.getOffsetY());value.addProperty("door",actual.getDoorValue());int floorY=pos.y>87?88:pos.y<73?72:80;TileEntity doorTile=client.theWorld.getTileEntity(8,floorY,5);value.addProperty("landing_door",doorTile instanceof BlockPSDAPGDoorBase.BlockEntityBase?((BlockPSDAPGDoorBase.BlockEntityBase)doorTile).getDoorValue():0);value.addProperty("riding",VehicleRidingMovement.isRiding(actual.getId()));value.addProperty("no_clip",client.thePlayer.noClip);value.addProperty("flying",client.thePlayer.capabilities.isFlying);value.addProperty("s08_after_setup",baselineS08<0?-1:s08.get()-baselineS08);rendered=value;renderPhase=phase;
            if(measured()&&clientMeasuring&&inputLedger.ticks>0&&!shotPhase.equals(phase)){String filename="mtr-mp-"+epoch+"-"+role+"-"+phase.toLowerCase(Locale.ROOT)+".png";net.minecraft.util.ScreenShotHelper.saveScreenshot(client.mcDataDir,filename,client.displayWidth,client.displayHeight,client.getFramebuffer());require(Files.isRegularFile(node.resolve("screenshots").resolve(filename)),"Native lift screenshot missing");shotPhase=phase;}
        }catch(Throwable problem){failed(problem);}}

        private JsonArray liftBlocks(WorldServer world)throws IOException{
            JsonArray blocks=new JsonArray();for(int x=7;x<=9;x++)for(int y=72;y<=89;y++)for(int z:new int[]{5,8})if(z==8&&x==8&&y<=88||z==5&&(x==9&&(y==72||y==80||y==88)||x<=8&&(y==72||y==73||y==80||y==81||y==88||y==89))){
                JsonObject b=new JsonObject();b.addProperty("x",x);b.addProperty("y",y);b.addProperty("z",z);b.addProperty("id",net.minecraft.block.Block.getIdFromBlock(world.getBlock(x,y,z)));b.addProperty("block_registry",net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(x,y,z)));b.addProperty("metadata",world.getBlockMetadata(x,y,z));JsonObject props=new JsonObject();for(Map.Entry<String,String> e:state(world,x,y,z).data.getSerializedProperties().entrySet())props.addProperty(e.getKey(),e.getValue());b.add("properties",props);
                net.minecraft.tileentity.TileEntity tile=world.getTileEntity(x,y,z);if(tile!=null){NBTTagCompound tag=new NBTTagCompound();tile.writeToNBT(tag);ByteArrayOutputStream raw=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(raw)){CompressedStreamTools.write(tag,output);}b.addProperty("tile_nbt_base64",Base64.getEncoder().encodeToString(raw.toByteArray()));}blocks.add(b);
            }require(blocks.size()==32,"Lift snapshot is not32 states");return blocks;
        }
        private String liftStateHash(WorldServer world)throws Exception{StringBuilder text=new StringBuilder();for(JsonElement value:liftBlocks(world)){JsonObject b=value.getAsJsonObject();TreeMap<String,String> props=new TreeMap<>();for(Map.Entry<String,JsonElement> e:b.getAsJsonObject("properties").entrySet())props.put(e.getKey(),e.getValue().getAsString());text.append(b.get("x").getAsInt()).append(',').append(b.get("y").getAsInt()).append(',').append(b.get("z").getAsInt()).append(':').append(props).append('\n');}return hex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));}
        private void writeLiftExpected(WorldServer world)throws Exception{assertStand(world);CoreSample core=coreSample;require(core!=null&&stopped(core,0)&&core.emptyQueue&&!core.cooldown&&coreRiders(core).isEmpty(),"Final lift not idle/empty");JsonObject o=envelope();o.addProperty("world",worldName);o.addProperty("lift_id",Long.toString(liftId));o.addProperty("state_hash",liftStateHash(world));o.add("blocks",liftBlocks(world));o.add("final_lift",core.json);o.add("actions",actions);o.add("trajectory",trajectory);o.add("final_players",finalPlayersForExpected());o.addProperty("setup_teleports",setupTeleports);o.addProperty("ride_teleports",0);o.addProperty("probe_motion_writes",0);o.addProperty("ready_for_normal_stop",true);write("expected.json",o);}

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
        for(int x=7;x<=9;x++)for(int z=-6;z<=5;z++){world.setBlock(x,71,z,net.minecraft.init.Blocks.stone,0,3);for(int y=72;y<=75;y++)if(z<5)world.setBlockToAir(x,y,z);}
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
        boolean accepted = stack.getItem().onItemUse(stack, player, world, x, y, z, side, .5F, hitY, .5F);
        require(accepted,"Original lift item use rejected");
    }

    private void brush(WorldServer world, EntityPlayerMP player, int x, int y, int z) {
        boolean accepted = world.getBlock(x, y, z).onBlockActivated(world, x, y, z, player, 2, .5F, .7F, .5F);
        require(accepted && unlocked(world, x, y, z), "Original brush failed to unlock landing block");
    }

    private Item originalItem(String path, Item expected) {
        Item item = (Item) Item.itemRegistry.getObject("mtr:" + path);
        require(item != null && item == expected, "Registry mtr:" + path + " is not the original item identity");
        return item;
    }

    private Simulator actualSimulator() throws Exception {
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

    @SideOnly(Side.CLIENT) private Lift findOnlyClientLift() {
        require(MinecraftClientData.getInstance().lifts.size() <= 1, "New owned save has multiple unexpected client lifts");
        for (Lift value : MinecraftClientData.getInstance().lifts) return value;
        return null;
    }

    private BlockPos floorPos(int floor) { return new BlockPos(8, FLOOR_Y[floor], 8); }

    @SideOnly(Side.CLIENT) private void key(KeyBinding binding, boolean down) { if(down||binding.getIsKeyPressed())KeyBinding.setKeyBindState(binding.getKeyCode(), down); }

    private boolean labelsMatch(net.minecraft.world.World world) {
        for (int floor = 0; floor < 3; floor++) {
            TileEntity value = world.getTileEntity(8, FLOOR_Y[floor], 8);
            if (!(value instanceof BlockLiftTrackFloor.BlockEntity)) return false;
            BlockLiftTrackFloor.BlockEntity tile = (BlockLiftTrackFloor.BlockEntity) value;
            if (!Integer.toString(floor + 1).equals(tile.getFloorNumber()) || !DESCRIPTIONS[floor].equals(tile.getFloorDescription()) || tile.getShouldDing()) return false;
        }
        return true;
    }

    private boolean linksMatch(net.minecraft.world.World world) {
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

    @SideOnly(Side.CLIENT) private boolean clientStandReady(Minecraft client) {
        try {
            for (int y : FLOOR_Y) if (!(client.theWorld.getTileEntity(8, y, 8) instanceof BlockLiftTrackFloor.BlockEntity)) return false;
            return true;
        } catch (IllegalArgumentException | IllegalStateException waitingForNativeTileSync) { return false; }
    }

    private BlockState state(net.minecraft.world.World world, int x, int y, int z) {
        return new org.mtr.mapping.holder.World(world).getBlockState(new BlockPos(x, y, z));
    }

    private boolean unlocked(net.minecraft.world.World world, int x, int y, int z) {
        return "true".equals(state(world, x, y, z).data.getSerializedProperties().get("unlocked"));
    }

    private void assertTrack(WorldServer world) {
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

    private void assertStand(WorldServer world) {
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

    private boolean floorGeometry(Lift lift) {
        List<LiftFloor> floors = new ArrayList<>(); lift.iterateFloors(floors::add);
        if (floors.size() != 3) return false;
        for (int floor = 0; floor < 3; floor++) {
            LiftFloor value = floors.get(floor);
            if (!value.getPosition().equals(new Position(8, FLOOR_Y[floor], 8)) || !Integer.toString(floor + 1).equals(value.getNumber())
                || !DESCRIPTIONS[floor].equals(value.getDescription())) return false;
        }
        return true;
    }

    private boolean configured(Lift lift) {
        return lift.getHeight() == 3 && lift.getWidth() == 2 && lift.getDepth() == 2 && lift.getOffsetX() == 0
            && lift.getOffsetY() == 0 && lift.getOffsetZ() == -1.5 && !lift.getIsDoubleSided()
            && Math.abs(lift.getAngle().angleRadians - Angle.fromAngle(270).angleRadians) < 1E-8;
    }

    private JsonObject chunkStatus(WorldServer world, int x, int z) {
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

    private final class CoreSample {
        final long at = System.currentTimeMillis(), atNanos=System.nanoTime(), id;
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
    }

}
