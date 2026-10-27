package org.mtr.gtnh.probe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Property;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.Init;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only owned localhost server and one client; production callbacks are never replaced. */
@Mod(modid="mtr_gtnh_multiplayer_probe", name="MTR targeted dedicated interaction probe", version="0",
    dependencies="required-after:mtr", acceptableRemoteVersions="*")
public final class DedicatedLiftProbe {
    private static final String[] PANEL_NAMES={"lift_panel_even_1","lift_panel_even_2","lift_panel_odd_1","lift_panel_odd_2"};
    private Path root,node,coord;
    private String runId,role,worldName,artifactHash,phase="JOIN",error;
    private int ticks,phaseTicks,clientTicks,clientPhaseTicks,clickSent=-1,refreshIndex,menuTicks;
    private long deadline;
    private boolean prepared,connecting,finished;
    private volatile boolean observerReady;
    private String clientPhase="",screenshotPhase="";
    private Object observedManager;
    private final AtomicInteger effects=new AtomicInteger(),liftCount=new AtomicInteger(-1);
    private final AtomicBoolean corePending=new AtomicBoolean();
    private final JsonArray interactions=new JsonArray(),refreshResults=new JsonArray(),panelResults=new JsonArray();
    private final JsonArray chats=new JsonArray(),receivedEffects=new JsonArray();
    private final JsonObject clientEvidence=new JsonObject();
    private Simulator simulator;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        try {
            runId=required("mtr.mp.runId");role=required("mtr.mp.role");
            require(UUID.fromString(runId).toString().equals(runId),"Noncanonical run UUID");
            require("first".equals(required("mtr.mp.epoch")),"Targeted probe only permits first epoch");
            require(role.equals("server")||role.equals("a"),"Only server and one client A are supported");
            Path base=Paths.get(required("mtr.probeMultiplayerRoot")).toAbsolutePath().normalize();
            root=Paths.get(required("mtr.mp.root")).toAbsolutePath().normalize();
            require(root.equals(base.resolve(runId))&&root.toRealPath().equals(root),"Unexpected isolated root");
            Properties owner=properties(root.resolve("mtr-mp-owner.properties"));
            require(runId.equals(owner.getProperty("run_id")),"Root owner mismatch");
            worldName=owner.getProperty("world","");
            require(worldName.matches("mtr-(dedicated|mp)-[0-9a-f-]{36}"),"Unexpected owned world name");
            node=root.resolve(role.equals("server")?"server":"prism-data/instances/MTR-MP-A/.minecraft");
            require(Paths.get("").toRealPath().equals(node)&&node.toRealPath().equals(node),"Unexpected cwd");
            require((runId+":"+role).equals(text(node.resolve("mtr-mp-node-owner.txt")).trim()),"Node owner mismatch");
            Path server=root.resolve("server");Properties config=properties(server.resolve("server.properties"));
            require("127.0.0.1".equals(config.getProperty("server-ip"))&&"25579".equals(config.getProperty("server-port"))
                &&"false".equals(config.getProperty("online-mode"))&&"false".equals(config.getProperty("enable-rcon"))
                &&"false".equals(config.getProperty("enable-query"))&&worldName.equals(config.getProperty("level-name")),"Foreign server configuration");
            require(runId.equals(text(server.resolve(worldName).resolve("mtr-mp-world-owner.txt")).trim()),"World owner mismatch");
            require(json(server.resolve("config/mtr.json")).getAsJsonObject("server").get("webserverPort").getAsInt()==0,"Unexpected HTTP listener");
            String artifact=owner.getProperty("mtr_artifact","");artifactHash=owner.getProperty("mtr_sha256","");
            require(artifact.matches("[A-Za-z0-9._+-]+\\.jar")&&artifactHash.matches("[0-9a-f]{64}"),"Missing artifact identity");
            require(artifactHash.equals(hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(node.resolve("mods").resolve(artifact))))),"Artifact hash mismatch");
            require(event.getSide().isServer()==role.equals("server"),"Wrong native side");
            coord=root.resolve("coord/first");require(Files.isDirectory(coord,LinkOption.NOFOLLOW_LINKS)&&coord.toRealPath().equals(coord),"Missing owned coord directory");
            require(!Files.exists(coord.resolve("targeted-"+(role.equals("server")?"control":"a-report")+".json")),"Fresh run required");
            deadline=System.currentTimeMillis()+600000;
            FMLCommonHandler.instance().bus().register(this);MinecraftForge.EVENT_BUS.register(this);prepared=true;
            if(role.equals("server"))publish();
        }catch(Exception failure){throw new IllegalStateException("Targeted multiplayer preflight rejected",failure);}
    }

    private static String required(String name){String value=System.getProperty(name);if(value==null||value.isEmpty())throw new IllegalArgumentException("Missing "+name);return value;}
    private static void require(boolean good,String message){if(!good)throw new AssertionError(message);}
    private static String text(Path file)throws IOException{return new String(Files.readAllBytes(file),StandardCharsets.UTF_8);}
    private static Properties properties(Path file)throws IOException{Properties values=new Properties();try(Reader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){values.load(in);}return values;}
    private static JsonObject json(Path file)throws IOException{return JsonParser.parseString(text(file)).getAsJsonObject();}
    private static String string(JsonObject value,String key){return value!=null&&value.has(key)?value.get(key).getAsString():"";}
    private static boolean bool(JsonObject value,String key){return value!=null&&value.has(key)&&value.get(key).getAsBoolean();}
    private static int number(JsonObject value,String key){return value!=null&&value.has(key)?value.get(key).getAsInt():-1;}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format("%02x",b&255));return value.toString();}
    private JsonObject envelope(){JsonObject value=new JsonObject();value.addProperty("run_id",runId);value.addProperty("epoch","first");value.addProperty("role",role);value.addProperty("phase",phase);value.addProperty("mtr_sha256",artifactHash);return value;}
    private void write(String name,JsonObject value)throws IOException{
        Path file=coord.resolve(name),temporary=coord.resolve(name+".tmp");
        require(!Files.isSymbolicLink(file)&&!Files.isSymbolicLink(temporary)&&coord.toRealPath().equals(coord),"Coord escaped ownership");
        Files.write(temporary,value.toString().getBytes(StandardCharsets.UTF_8));Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void publish()throws IOException{JsonObject value=envelope();value.addProperty("server_tick",ticks);value.addProperty("phase_ticks",phaseTicks);value.addProperty("refresh_index",refreshIndex);value.addProperty("lift_count",liftCount.get());write("targeted-control.json",value);}
    private void advance(String next)throws IOException{phase=next;phaseTicks=0;publish();}
    private void failure(Throwable problem){if(error!=null)return;error=problem.toString();String failedPhase=phase;phase="FAILED";problem.printStackTrace();try{JsonObject value=report();value.addProperty("error",error);value.addProperty("failed_phase",failedPhase);write("targeted-"+(role.equals("server")?"server":"a")+"-report.json",value);if(role.equals("server"))publish();}catch(Exception output){output.printStackTrace();}}
    private JsonObject report(){JsonObject value=envelope();value.add("interactions",interactions);value.add("refresh_results",refreshResults);value.add("panel_results",panelResults);value.add("client_evidence",clientEvidence);synchronized(chats){value.add("chats",chats);}synchronized(receivedEffects){value.add("received_effects",receivedEffects);}value.addProperty("effects_received",effects.get());value.addProperty("lift_count",liftCount.get());return value;}
    private JsonObject clientAck()throws IOException{Path path=coord.resolve("targeted-a-report.json");if(!Files.isRegularFile(path))return null;JsonObject value=json(path);require(runId.equals(string(value,"run_id"))&&artifactHash.equals(string(value,"mtr_sha256")),"Foreign ACK");return value;}
    private static BlockState state(net.minecraft.world.World world,int x,int y,int z){return new org.mtr.mapping.holder.World(world).getBlockState(new BlockPos(x,y,z));}
    private static JsonObject nativeBlock(net.minecraft.world.World world,int x,int y,int z){
        Block block=world.getBlock(x,y,z);JsonObject value=new JsonObject();value.addProperty("x",x);value.addProperty("y",y);value.addProperty("z",z);value.addProperty("registry",Block.blockRegistry.getNameForObject(block));value.addProperty("class",block.getClass().getName());value.addProperty("id",Block.getIdFromBlock(block));value.addProperty("metadata",world.getBlockMetadata(x,y,z));
        try{JsonObject props=new JsonObject();for(java.util.Map.Entry<String,String> entry:state(world,x,y,z).data.getSerializedProperties().entrySet())props.addProperty(entry.getKey(),entry.getValue());value.add("properties",props);}catch(Throwable problem){value.addProperty("state_error",problem.toString());}return value;
    }
    private static Item refresher(){Item item=(Item)Item.itemRegistry.getObject("mtr:lift_refresher");require(item!=null&&item==org.mtr.mod.Items.LIFT_REFRESHER.get().data,"Noncanonical refresher item");return item;}
    private void put(net.minecraft.world.World world,int x,int y,int z,Block block){BlockState placed=new org.mtr.mapping.holder.Block(block).getDefaultState().with(new Property<>(DirectionHelper.FACING.data),Direction.NORTH.data);require(new org.mtr.mapping.holder.World(world).setBlockState(new BlockPos(x,y,z),placed,2),"Fixture placement failed");require(world.getBlock(x,y,z)==block,"Fixture registry identity changed");}
    private static Block panel(int index){return index==0?org.mtr.mod.Blocks.LIFT_PANEL_EVEN_1.get().data:index==1?org.mtr.mod.Blocks.LIFT_PANEL_EVEN_2.get().data:index==2?org.mtr.mod.Blocks.LIFT_PANEL_ODD_1.get().data:org.mtr.mod.Blocks.LIFT_PANEL_ODD_2.get().data;}
    private Simulator actualSimulator()throws Exception{
        Field mainField=Init.class.getDeclaredField("main");mainField.setAccessible(true);Main main=(Main)mainField.get(null);require(main!=null,"Original server Main missing");Field field=Main.class.getDeclaredField("simulators");field.setAccessible(true);
        for(Object candidate:(Iterable<?>)field.get(main)){Simulator actual=(Simulator)candidate;if("minecraft/overworld".equals(actual.dimension))return actual;}throw new AssertionError("Original overworld simulator missing");
    }
    private void sampleCore(){if(simulator!=null&&corePending.compareAndSet(false,true))simulator.run(()->{try{liftCount.set(simulator.lifts.size());}finally{corePending.set(false);}});}

    @SubscribeEvent(priority=cpw.mods.fml.common.eventhandler.EventPriority.LOWEST,receiveCanceled=true) public void interaction(PlayerInteractEvent event){
        if(!prepared||!"MtrProbeA".equals(event.entityPlayer.getCommandSenderName()))return;
        if(event.world.isRemote){
            if(!role.equals("a")||!phase.startsWith("REFRESH_")||(event.action!=PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK&&event.action!=PlayerInteractEvent.Action.RIGHT_CLICK_AIR))return;
            JsonObject value=new JsonObject();value.addProperty("action",event.action.name());value.addProperty("x",event.x);value.addProperty("y",event.y);value.addProperty("z",event.z);value.addProperty("face",event.face);value.addProperty("canceled",event.isCanceled());
            clientEvidence.add("mouse_event_"+phase+"_"+event.action.name(),value);return;
        }
        if(!role.equals("server")||event.action!=PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)return;
        try{JsonObject value=nativeBlock(event.world,event.x,event.y,event.z);value.addProperty("phase",phase);value.addProperty("tick",ticks);value.addProperty("face",event.face);ItemStack held=event.entityPlayer.getHeldItem();value.addProperty("held_registry",held==null?"":Item.itemRegistry.getNameForObject(held.getItem()));value.addProperty("held_class",held==null?"":held.getItem().getClass().getName());value.addProperty("native_right_click_event",true);interactions.add(value);}catch(Throwable problem){failure(problem);}
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||!prepared||!role.equals("server")||error!=null||finished)return;
        try{
            require(System.currentTimeMillis()<deadline,"Targeted server deadline exceeded");ticks++;phaseTicks++;
            MinecraftServer server=MinecraftServer.getServer();require(server!=null&&server.isDedicatedServer()&&worldName.equals(server.getFolderName()),"Foreign dedicated server");
            WorldServer world=null;for(WorldServer candidate:server.worldServers)if(candidate!=null&&candidate.provider.dimensionId==0)world=candidate;require(world!=null,"Owned overworld missing");
            require(runId.equals(text(root.resolve("server").resolve(worldName).resolve("mtr-mp-world-owner.txt")).trim()),"World owner changed");
            EntityPlayerMP player=null;for(Object candidate:world.playerEntities)if("MtrProbeA".equals(((EntityPlayerMP)candidate).getCommandSenderName()))player=(EntityPlayerMP)candidate;
            JsonObject ack=clientAck();if("FAILED".equals(string(ack,"phase")))throw new AssertionError("Client reported failure");
            if(phase.equals("JOIN")){
                if(player==null||!bool(ack,"connected")){publish();return;}
                world.getChunkFromChunkCoords(0,0);
                require(world.getChunkProvider().chunkExists(0,0),"Owned fixture setup could not load its chunk");
                world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.difficultySetting=net.minecraft.world.EnumDifficulty.PEACEFUL;
                for(int x=0;x<16;x++)for(int z=4;z<16;z++){world.setBlock(x,71,z,net.minecraft.init.Blocks.stone,0,2);for(int y=72;y<=83;y++)world.setBlock(x,y,z,net.minecraft.init.Blocks.air,0,2);}
                for(int y=72;y<=80;y++)put(world,8,y,8,y==72||y==80?org.mtr.mod.Blocks.LIFT_TRACK_FLOOR_1.get().data:org.mtr.mod.Blocks.LIFT_TRACK_VERTICAL_1.get().data);
                player.setGameType(WorldSettings.GameType.CREATIVE);player.playerNetServerHandler.setPlayerLocation(8.5,72,6.5,0,0);player.inventory.currentItem=0;player.inventory.mainInventory[0]=new ItemStack(refresher());player.inventoryContainer.detectAndSendChanges();
                simulator=actualSimulator();sampleCore();advance("REFRESH_0");return;
            }
            if(phase.equals("COMPLETE")){
                if(bool(ack,"complete")){JsonObject value=report();value.addProperty("complete",true);value.addProperty("refresher_pass",refreshResults.size()==2&&bool(refreshResults.get(0).getAsJsonObject(),"pass")&&bool(refreshResults.get(1).getAsJsonObject(),"pass"));value.addProperty("panel_effects_pass",panelResults.size()==4);write("targeted-server-report.json",value);finished=true;}return;
            }
            require(player!=null,"Client vanished before completion");if(ticks%10==0)sampleCore();
            if(phase.startsWith("REFRESH_")){
                if(phaseTicks<100||!phase.equals(string(ack,"phase"))||number(ack,"click_sent")!=refreshIndex){publish();return;}
                boolean ingress=false;for(JsonElement entry:interactions)if(phase.equals(string(entry.getAsJsonObject(),"phase"))&&"mtr:lift_refresher".equals(string(entry.getAsJsonObject(),"held_registry")))ingress=true;
                JsonObject result=new JsonObject();result.addProperty("index",refreshIndex);result.addProperty("lift_count",liftCount.get());result.add("server_target",nativeBlock(world,8,refreshIndex==0?72:74,8));result.add("client",ack);result.addProperty("server_native_click_seen",ingress);result.addProperty("pass",ingress&&liftCount.get()>0&&bool(ack.getAsJsonObject("evidence"),"lift_screen_"+phase));refreshResults.add(result);
                require(bool(result,"pass"),"A single native refresher click did not create the lift and open its original customization screen");
                if(refreshIndex++==0){advance("REFRESH_1");return;}
                for(int i=0;i<4;i++)put(world,2+4*i,72,12,panel(i));advance("PANELS_READY");return;
            }
            if(phase.equals("PANELS_READY")){
                if(!phase.equals(string(ack,"phase"))||!bool(ack,"panels_ready")||phaseTicks<30){publish();return;}
                advance("DESTROY_0");return;
            }
            if(phase.startsWith("DESTROY_")){
                int index=Integer.parseInt(phase.substring(8)),x=2+4*index;
                if(phaseTicks==1){
                    BlockState old=state(world,x,72,12);require(old.getBlock().data==panel(index),"Wrong original panel target");JsonObject result=nativeBlock(world,x,72,12);result.addProperty("variant",PANEL_NAMES[index]);result.addProperty("event",2001);
                    org.mtr.mapping.holder.World mapped=new org.mtr.mapping.holder.World(world);require(mapped.setBlockState(new BlockPos(x,72,12),org.mtr.mapping.holder.Blocks.getAirMapped().getDefaultState(),2),"Panel not removed");
                    mapped.syncWorldEvent(null,2001,new BlockPos(x,72,12),org.mtr.mapping.holder.Block.getRawIdFromState(old));result.addProperty("event_sent_after_air",world.isAirBlock(x,72,12));panelResults.add(result);publish();return;
                }
                if(!phase.equals(string(ack,"phase"))||!bool(ack,"effect_survived")||phaseTicks<50){publish();return;}
                panelResults.get(index).getAsJsonObject().add("client",ack);advance(index==3?"COMPLETE":"DESTROY_"+(index+1));return;
            }
            publish();
        }catch(Throwable problem){failure(problem);}
    }

    @SubscribeEvent @SideOnly(Side.CLIENT) public void chat(net.minecraftforge.client.event.ClientChatReceivedEvent event){if(!prepared||!role.equals("a"))return;synchronized(chats){JsonObject value=new JsonObject();value.addProperty("phase",phase);value.addProperty("text",event.message.getUnformattedText());chats.add(value);}}

    @SideOnly(Side.CLIENT) private void installObserver(net.minecraft.network.NetworkManager manager)throws Exception{
        io.netty.channel.Channel channel=null;for(Field field:net.minecraft.network.NetworkManager.class.getDeclaredFields())if(io.netty.channel.Channel.class.isAssignableFrom(field.getType())){field.setAccessible(true);channel=(io.netty.channel.Channel)field.get(manager);}require(channel!=null,"Native TCP channel missing");final io.netty.channel.Channel actual=channel;
        actual.eventLoop().execute(()->{try{actual.pipeline().addBefore("packet_handler","mtr_targeted_effect_observer",new io.netty.channel.ChannelInboundHandlerAdapter(){@Override @SideOnly(Side.CLIENT) public void channelRead(io.netty.channel.ChannelHandlerContext context,Object value)throws Exception{
            if(value instanceof net.minecraft.network.play.server.S28PacketEffect){JsonObject packet=new JsonObject();packet.addProperty("phase",phase);packet.addProperty("type",value.getClass().getName());for(Field field:value.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&(field.getType()==int.class||field.getType()==boolean.class)){field.setAccessible(true);packet.addProperty(field.getName(),String.valueOf(field.get(value)));}synchronized(receivedEffects){receivedEffects.add(packet);}effects.incrementAndGet();}super.channelRead(context,value);
        }});observerReady=true;}catch(Throwable problem){failure(problem);}});
    }

    @SideOnly(Side.CLIENT) private void screenshot(net.minecraft.client.Minecraft client)throws Exception{
        if(screenshotPhase.equals(phase))return;String name="mtr-targeted-"+phase.toLowerCase(java.util.Locale.ROOT)+".png";net.minecraft.util.ScreenShotHelper.saveScreenshot(client.mcDataDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());require(Files.isRegularFile(node.resolve("screenshots").resolve(name)),"Screenshot missing");screenshotPhase=phase;
    }

    @SideOnly(Side.CLIENT) private void nativeMouseClick(net.minecraft.client.Minecraft client,int y,int index)throws Exception{
        require(client.renderViewEntity==client.thePlayer,"Native mouse ray must originate from the real player");
        // Native getPosition(1) is the camera ray origin, including the client's legacy eye offset.
        net.minecraft.util.Vec3 eye=client.thePlayer.getPosition(1);
        double dx=8.5-eye.xCoord,dy=y+.5-eye.yCoord,dz=8+1.0/32-eye.zCoord;
        client.thePlayer.rotationYaw=client.thePlayer.prevRotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);
        client.thePlayer.rotationPitch=client.thePlayer.prevRotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
        client.entityRenderer.getMouseOver(1);
        net.minecraft.util.MovingObjectPosition hit=client.objectMouseOver;
        require(hit!=null&&hit.typeOfHit==net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==8&&hit.blockY==y&&hit.blockZ==8,"Native mouse ray did not select the intended thin lift track");
        JsonObject target=nativeBlock(client.theWorld,hit.blockX,hit.blockY,hit.blockZ);target.addProperty("side",hit.sideHit);target.addProperty("hit_x",hit.hitVec.xCoord);target.addProperty("hit_y",hit.hitVec.yCoord);target.addProperty("hit_z",hit.hitVec.zCoord);target.addProperty("eye_x",eye.xCoord);target.addProperty("eye_y",eye.yCoord);target.addProperty("eye_z",eye.zCoord);clientEvidence.add("native_mouse_target_"+index,target);
        java.lang.reflect.Method click;
        try{click=net.minecraft.client.Minecraft.class.getDeclaredMethod("func_147121_ag");}
        catch(NoSuchMethodException absentSrg){click=net.minecraft.client.Minecraft.class.getDeclaredMethod("rightClickMouse");}
        click.setAccessible(true);click.invoke(client);clientEvidence.addProperty("native_mouse_method_"+index,click.getName());
        String blockEvent="mouse_event_"+phase+"_RIGHT_CLICK_BLOCK",airEvent="mouse_event_"+phase+"_RIGHT_CLICK_AIR";
        require(clientEvidence.has(blockEvent)&&!bool(clientEvidence.getAsJsonObject(blockEvent),"canceled"),"Native client block interaction was missing or canceled");
        require(clientEvidence.has(airEvent)&&!bool(clientEvidence.getAsJsonObject(airEvent),"canceled"),"Native refresher PASS did not execute its normal air fallback");
        clientEvidence.addProperty("native_air_fallback_"+index,true);
    }

    @SubscribeEvent @SideOnly(Side.CLIENT) public void clientTick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||!prepared||!role.equals("a")||finished||error!=null)return;
        net.minecraft.client.Minecraft client=net.minecraft.client.Minecraft.getMinecraft();
        try{
            require(System.currentTimeMillis()<deadline,"Targeted client deadline exceeded");require(client.mcDataDir.getCanonicalFile().toPath().equals(node)&&client.getIntegratedServer()==null,"Foreign or integrated client");require("MtrProbeA".equals(client.getSession().getUsername()),"Wrong offline probe name");
            if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||!Files.exists(coord.resolve("targeted-control.json")))return;
            JsonObject control=json(coord.resolve("targeted-control.json"));require(runId.equals(string(control,"run_id"))&&artifactHash.equals(string(control,"mtr_sha256")),"Foreign control");phase=string(control,"phase");require(!phase.equals("FAILED"),"Dedicated server reported failure");
            clientTicks++;if(!phase.equals(clientPhase)){clientPhase=phase;clientPhaseTicks=0;}clientPhaseTicks++;
            client.gameSettings.pauseOnLostFocus=false;client.gameSettings.renderDistanceChunks=4;net.minecraft.client.settings.KeyBinding.unPressAllKeys();
            if(phase.equals("JOIN")&&client.theWorld==null&&!connecting){
                String menu=client.currentScreen==null?"":client.currentScreen.getClass().getName();
                if(!menu.equals("net.minecraft.client.gui.GuiMainMenu")&&!menu.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<40)return;
                cpw.mods.fml.client.FMLClientHandler.instance().connectToServerAtStartup("127.0.0.1",25579);connecting=true;
            }
            JsonObject ack=envelope();ack.addProperty("client_tick",clientTicks);boolean online=client.theWorld!=null&&client.thePlayer!=null&&client.getNetHandler()!=null&&client.getNetHandler().getNetworkManager().isChannelOpen();ack.addProperty("connected",online);
            if(online){
                net.minecraft.network.NetworkManager manager=client.getNetHandler().getNetworkManager();require(manager.getSocketAddress() instanceof java.net.InetSocketAddress,"Expected TCP connection");java.net.InetSocketAddress address=(java.net.InetSocketAddress)manager.getSocketAddress();require(address.getAddress().isLoopbackAddress()&&address.getPort()==25579,"Foreign server endpoint");
                if(observedManager!=manager){require(observedManager==null,"Unexpected reconnect");observedManager=manager;installObserver(manager);}ack.addProperty("observer_ready",observerReady);
                if(client.currentScreen!=null){clientEvidence.addProperty("last_screen",client.currentScreen.getClass().getName());if(client.currentScreen.getClass().getName().contains("Lift")){clientEvidence.addProperty("lift_screen_received",true);clientEvidence.addProperty("lift_screen_"+phase,true);}client.displayGuiScreen(null);}
                if(phase.startsWith("REFRESH_")){
                    int index=Integer.parseInt(phase.substring(8)),y=index==0?72:74;Block expected=index==0?org.mtr.mod.Blocks.LIFT_TRACK_FLOOR_1.get().data:org.mtr.mod.Blocks.LIFT_TRACK_VERTICAL_1.get().data;
                    ack.add("target",nativeBlock(client.theWorld,8,y,8));ItemStack held=client.thePlayer.getHeldItem();ack.addProperty("held_registry",held==null?"":Item.itemRegistry.getNameForObject(held.getItem()));
                    if(clickSent<index&&clientPhaseTicks>=25&&observerReady&&client.theWorld.getBlock(8,y,8)==expected&&held!=null&&held.getItem()==refresher()){
                        require(client.thePlayer.getDistanceSq(8.5,y+.5,8.5)<=Math.pow(client.playerController.getBlockReachDistance(),2),"Target outside native reach");
                        nativeMouseClick(client,y,index);clickSent=index;screenshot(client);
                    }
                    ack.addProperty("click_sent",clickSent);
                }
                if(phase.equals("PANELS_READY")){boolean ready=true;JsonArray values=new JsonArray();for(int i=0;i<4;i++){values.add(nativeBlock(client.theWorld,2+4*i,72,12));ready&=client.theWorld.getBlock(2+4*i,72,12)==panel(i);}ack.add("panels",values);ack.addProperty("panels_ready",ready&&observerReady);if(ready)screenshot(client);}
                if(phase.startsWith("DESTROY_")){int index=Integer.parseInt(phase.substring(8));boolean survived=clientPhaseTicks>=40&&effects.get()>=index+1&&client.theWorld.isAirBlock(2+4*index,72,12);ack.addProperty("effect_survived",survived);ack.addProperty("surviving_ticks",clientPhaseTicks);if(survived)screenshot(client);}
            }
            synchronized(chats){ack.add("chats",chats);}synchronized(receivedEffects){ack.add("received_effects",receivedEffects);}ack.add("evidence",clientEvidence);ack.addProperty("effects_received",effects.get());ack.addProperty("screenshot",screenshotPhase.equals(phase));
            if(phase.equals("COMPLETE")){require(online&&effects.get()>=4,"Completion without real effect packets");screenshot(client);ack.addProperty("complete",true);write("targeted-a-report.json",ack);client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());finished=true;client.shutdown();return;}
            write("targeted-a-report.json",ack);
        }catch(Throwable problem){failure(problem);try{if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());client.shutdown();}catch(Throwable ignored){}}
    }
}
