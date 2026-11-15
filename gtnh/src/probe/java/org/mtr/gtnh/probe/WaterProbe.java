package org.mtr.gtnh.probe;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.tool.BlockStateDefinition;
import org.mtr.mapping.tool.BlockStateTileEntity;
import org.mtr.mod.block.BlockPlatformSlab;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Test-only fresh-save water scene. Root separately admits the build and accepts exit/visual evidence. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_water_probe",name="MTR contained-water world probe",version="0",
    dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class WaterProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final Path COPIED=Paths.get(requiredProbePath("mtr.probeClient"));
    private static final int Y=70,Z=4;
    private static final int[] HOST_X={4,5,9,10};
    private enum Stage { MENU,WORLD,SETUP,CHUNKS,DRY,DRY_TOP_SETUP,DRY_TOP,BUCKET_SETUP,BUCKET,WET,VIEW_SETUP,READY_WET,WET_TOP_SETUP,WET_TOP,SAVE,REOPEN,RELOAD_WORLD,RELOAD,CLOSE,FINISHED }
    private volatile Stage stage=Stage.MENU,shotAcknowledged;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private volatile ClientObservation clientSample;
    private volatile Object activeClientWorld;
    private volatile Angelica dryBuild;
    private volatile boolean captureReady;
    private volatile long phaseAt;
    private long startedAt;
    private int menuTicks,clientTicks,phaseTicks,stableTicks,setupTeleports,renderFrames,normalCloses;
    private Stage renderStage;
    private String runId,saveName;
    private Path save;
    private Object firstWorld;
    private boolean copyVerified,firstSaved,secondSaved,reloaded;
    private final JsonObject report=new JsonObject();
    // Server and client arrays have separate writers and are joined only after the server stops.
    private final JsonArray serverEvidence=new JsonArray(),screenshots=new JsonArray(),renderEvidence=new JsonArray();
    private final List<Host> expected=new ArrayList<>();

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=System.currentTimeMillis();phaseAt=startedAt;FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<10*60*1000L,"Water world probe deadline");
            if(stage==Stage.MENU) {
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();
                if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                reserve(client);client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=0;
                client.gameSettings.renderDistanceChunks=4;client.gameSettings.fovSetting=70;
                advance(Stage.WORLD);
                WorldSettings settings=new WorldSettings(0x57415445524d5452L,WorldSettings.GameType.SURVIVAL,false,false,WorldType.FLAT)
                    .enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName,"MTR water acceptance "+runId,settings);return;
            }
            require(System.currentTimeMillis()-phaseAt<120000,"Water phase deadline: "+stage);
            if(stage==Stage.WORLD||stage==Stage.RELOAD_WORLD) {
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                owned(client.getIntegratedServer());
                if(stage==Stage.WORLD) {
                    firstWorld=client.theWorld;activeClientWorld=client.theWorld;playerId=client.thePlayer.getUniqueID();advance(Stage.SETUP);
                } else {
                    require(client.theWorld!=firstWorld,"Reload retained the previous client world");
                    require(playerId.equals(client.thePlayer.getUniqueID()),"Reload changed the native player UUID");
                    activeClientWorld=client.theWorld;reloaded=true;advance(Stage.RELOAD);
                }
            }
            if(stage==Stage.SAVE) {
                closeOwned(client);verifySaved();firstSaved=true;report.addProperty("first_save_readback",true);
                advance(Stage.REOPEN);return;
            }
            if(stage==Stage.REOPEN) {
                require(client.theWorld==null&&client.getIntegratedServer()==null,"First world did not stop before reload");
                Gates.owned(COPIED,save,saveName,runId);
                require(client.getSaveLoader().canLoadWorld(saveName),"Native save loader refused the newly owned save");
                advance(Stage.RELOAD_WORLD);
                FMLClientHandler.instance().tryLoadExistingWorld(new GuiSelectWorld(new GuiMainMenu()),saveName,"MTR water acceptance "+runId);return;
            }
            if(stage==Stage.CLOSE){finish(client);return;}
            require(client.theWorld!=null&&client.thePlayer!=null,"Owned world/player disappeared");owned(client.getIntegratedServer());
            require(client.thePlayer.dimension==0&&playerId.equals(client.thePlayer.getUniqueID()),"Wrong client player/world");
            require(!client.gameSettings.pauseOnLostFocus,"Background pause was re-enabled");
            if(client.currentScreen!=null)client.displayGuiScreen(null);
            KeyBinding.unPressAllKeys();
            if(++clientTicks%4==0)clientSample=new ClientObservation(client,clientTicks);
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null)return;
        Stage current=stage;
        if(current==Stage.MENU||current==Stage.WORLD||current==Stage.SAVE||current==Stage.REOPEN||current==Stage.RELOAD_WORLD||current==Stage.CLOSE||current==Stage.FINISHED)return;
        try {
            MinecraftServer server=MinecraftServer.getServer();owned(server);EntityPlayerMP player=player(server);
            if(player==null){require(++phaseTicks<200,"Native server player missing");return;}
            WorldServer world=server.worldServerForDimension(0);require(player.worldObj==world&&player.dimension==0,"Wrong server overworld");
            require(System.currentTimeMillis()-phaseAt<120000,"Server phase deadline: "+current);++phaseTicks;
            if(current==Stage.SETUP) {
                player.setGameType(WorldSettings.GameType.CREATIVE);player.capabilities.isFlying=true;player.sendPlayerAbilities();
                teleport(player,7.5,70,.5,0,12);advance(Stage.CHUNKS);return;
            }
            if(current==Stage.CHUNKS) {
                stableTicks=chunksReady(world)&&at(player,7.5,70,.5)&&clientAt(7.5,70,.5)?stableTicks+1:0;
                if(stableTicks<2)return;
                build(world,player);player.setGameType(WorldSettings.GameType.SURVIVAL);player.capabilities.isFlying=false;player.sendPlayerAbilities();
                advance(Stage.DRY);return;
            }
            if(current==Stage.BUCKET_SETUP) {
                double pitch=Math.toDegrees(Math.atan2(player.getEyeHeight()+.5,1));
                teleport(player,9.5,71,3.5,0,(float)pitch);advance(Stage.BUCKET);return;
            }
            if(current==Stage.BUCKET) {
                if(phaseTicks<4||!at(player,9.5,71,3.5)||!clientAt(9.5,71,3.5))return;
                require(!player.capabilities.isCreativeMode&&player.onGround,"Bucket requires grounded native survival player");
                require(scene(world,false,false),"Dry native scene changed before bucket action");
                MovingObjectPosition hit=bucketRay(world,player);
                require(hit!=null&&hit.typeOfHit==MovingObjectPosition.MovingObjectType.BLOCK&&hit.blockX==9&&hit.blockY==Y&&hit.blockZ==Z,"Native bucket ray missed the first wet-basin slab");
                ItemStack full=new ItemStack(Items.water_bucket);player.inventory.currentItem=0;player.inventory.mainInventory[0]=full;
                ItemStack result=Items.water_bucket.onItemRightClick(full,world,player);
                require(result!=null&&result.getItem()==Items.bucket&&result.stackSize==1,"Native water bucket did not return one empty bucket");
                player.inventory.mainInventory[0]=result;
                Host filled=new Host(world,9);require(filled.matches(true),"Native bucket did not wet the original first slab");
                JsonObject action=new JsonObject();action.addProperty("action","native_water_bucket");action.addProperty("ray_x",hit.blockX);action.addProperty("ray_y",hit.blockY);action.addProperty("ray_z",hit.blockZ);action.addProperty("ray_side",hit.sideHit);action.addProperty("returned_item","minecraft:bucket");action.add("result",filled.json());serverEvidence.add(action);
                advance(Stage.WET);return;
            }
            if(current==Stage.VIEW_SETUP) {
                teleport(player,7.5,70,.5,0,12);advance(Stage.READY_WET);return;
            }
            if(current==Stage.DRY_TOP_SETUP||current==Stage.WET_TOP_SETUP) {
                teleport(player,7.5,73,-.5,0,35);advance(current==Stage.DRY_TOP_SETUP?Stage.DRY_TOP:Stage.WET_TOP);return;
            }
            boolean wet=current!=Stage.DRY&&current!=Stage.DRY_TOP;
            boolean correct=scene(world,wet,current==Stage.RELOAD)&&clientScene(wet,current==Stage.RELOAD);
            stableTicks=correct?stableTicks+1:0;
            if(stableTicks<20){captureReady=false;return;}
            if(current==Stage.WET){recordScene(world,"natural_flow_wet_pair");advance(Stage.VIEW_SETUP);return;}
            boolean top=current==Stage.DRY_TOP||current==Stage.WET_TOP||current==Stage.RELOAD;
            require(at(player,7.5,top?73:70,top?-.5:.5)&&clientAt(7.5,top?73:70,top?-.5:.5),"Native observation camera left its saved setup stand");
            require(!player.capabilities.isFlying&&!player.capabilities.isCreativeMode,"Observation changed native player flight/game mode");
            captureReady=true;
            if(shotAcknowledged!=current)return;
            if(current==Stage.DRY){recordScene(world,"dry");advance(Stage.DRY_TOP_SETUP);}
            else if(current==Stage.DRY_TOP)advance(Stage.BUCKET_SETUP);
            else if(current==Stage.READY_WET)advance(Stage.WET_TOP_SETUP);
            else if(current==Stage.WET_TOP) {
                recordScene(world,"wet");expected.clear();for(int x:HOST_X)expected.add(new Host(world,x));advance(Stage.SAVE);
            } else if(current==Stage.RELOAD){recordScene(world,"reloaded");advance(Stage.CLOSE);}
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        Stage current=stage;
        if(event.phase!=TickEvent.Phase.END||failure!=null||!captureReady||shotAcknowledged==current)return;
        if(current!=Stage.DRY&&current!=Stage.DRY_TOP&&current!=Stage.READY_WET&&current!=Stage.WET_TOP&&current!=Stage.RELOAD)return;
        try {
            Minecraft client=Minecraft.getMinecraft();
            if(current!=renderStage){renderStage=current;renderFrames=0;}
            if(client.currentScreen!=null||!clientScene(current!=Stage.DRY&&current!=Stage.DRY_TOP,current==Stage.RELOAD))return;
            Angelica actual=Angelica.read(client);
            Angelica baseline=current==Stage.READY_WET||current==Stage.WET_TOP?dryBuild:null;
            if(!actual.ready(baseline))return;
            if(++renderFrames<4)return;
            capture(client,current==Stage.DRY?"dry-oblique":current==Stage.DRY_TOP?"dry-top":current==Stage.READY_WET?"wet-oblique":current==Stage.WET_TOP?"wet-top":"reloaded-top");
            renderEvidence.add(actual.json(current.name()));
            if(current==Stage.DRY_TOP)dryBuild=actual;
            shotAcknowledged=current;
        } catch(Throwable problem){fail(problem);}
    }

    private void reserve(Minecraft client) throws Exception {
        require(client.theWorld==null&&client.getIntegratedServer()==null,"Refusing to replace an open world");
        require(Gates.checked(client.mcDataDir.toPath()).equals(Gates.checked(COPIED)),"Wrong copied client");
        require(Loader.isModLoaded("angelica"),"Actual Angelica mod is required");
        for(ModContainer mod:Loader.instance().getActiveModList())require(!mod.getModId().startsWith("mtr_gtnh_")||!mod.getModId().endsWith("_probe")||mod.getModId().equals("mtr_gtnh_water_probe"),"Another MTR runtime probe is active");
        runId=UUID.randomUUID().toString();saveName="MTR-water-acceptance-"+runId;
        Path saves=COPIED.resolve("saves");Files.createDirectories(saves);
        require(Gates.checked(saves).getParent().equals(Gates.checked(COPIED)),"Copied saves path escaped");
        save=Files.createDirectory(saves.resolve(saveName));Files.write(save.resolve("mtr-water-probe-owner.txt"),runId.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        Gates.owned(COPIED,save,saveName,runId);copyVerified=true;
        report.addProperty("run_id",runId);report.addProperty("save_directory",save.toString());report.addProperty("client_directory",COPIED.toString());
        report.addProperty("fresh_save_reserved",true);report.addProperty("existing_user_save_loaded",false);
        report.addProperty("scenario","four original platform slabs; native bucket and flow; actual Angelica; owned save/reload");
    }
    private void owned(MinecraftServer server) throws IOException {
        require(copyVerified&&save!=null&&server!=null&&saveName.equals(server.getFolderName()),"Refusing a foreign integrated server");
        require(server.worldServerForDimension(0)!=null,"Native owned overworld missing");
        Gates.nativeSave(save,server.worldServerForDimension(0).getSaveHandler().getWorldDirectory().toPath());
    }
    private EntityPlayerMP player(MinecraftServer server) {
        for(Object candidate:server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP value=(EntityPlayerMP)candidate;if(playerId.equals(value.getUniqueID()))return value;
        }
        return null;
    }
    private void teleport(EntityPlayerMP player,double x,double y,double z,float yaw,float pitch) {
        require(Gates.setupAllowed(stage.name(),setupTeleports),"Teleport outside explicitly bounded setup");
        player.playerNetServerHandler.setPlayerLocation(x,y,z,yaw,pitch);++setupTeleports;
        JsonObject value=new JsonObject();value.addProperty("action","setup_teleport");value.addProperty("stage",stage.name());value.addProperty("x",x);value.addProperty("feet_y",y);value.addProperty("z",z);serverEvidence.add(value);
    }
    private static boolean at(EntityPlayerMP player,double x,double y,double z) { return Math.abs(player.posX-x)<.15&&Math.abs(player.posZ-z)<.15&&Math.abs(player.boundingBox.minY-y)<.15; }
    private boolean clientAt(double x,double y,double z) { ClientObservation sample=clientSample;return sample!=null&&sample.world==activeClientWorld&&System.currentTimeMillis()-sample.at<2000&&Math.abs(sample.x-x)<.15&&Math.abs(sample.z-z)<.15&&Math.abs(sample.y-y)<.15; }
    private static boolean chunksReady(WorldServer world) {
        for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++) {
            if(!world.getChunkProvider().chunkExists(x,z))return false;
            Chunk chunk=(Chunk)world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x,z));
            if(chunk==null||chunk instanceof EmptyChunk||!chunk.isChunkLoaded||chunk.xPosition!=x||chunk.zPosition!=z)return false;
        }return true;
    }
    private void build(WorldServer world,EntityPlayerMP player) {
        require(stage==Stage.CHUNKS&&chunksReady(world),"Build before natural chunk readiness");
        world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.setWorldTime(6000);world.difficultySetting=EnumDifficulty.PEACEFUL;
        for(int x=2;x<=12;x++)for(int z=0;z<=6;z++) {
            world.setBlock(x,Y-1,z,net.minecraft.init.Blocks.stone);
            for(int y=Y;y<=Y+6;y++)world.setBlockToAir(x,y,z);
        }
        for(int y=Y-1;y<=Y+2;y++)world.setBlock(7,y,-1,net.minecraft.init.Blocks.stone);
        for(int base:new int[]{4,9})for(int x=base-1;x<=base+2;x++)for(int z=Z-1;z<=Z+1;z++)
            if(x==base-1||x==base+2||z!=Z)world.setBlock(x,Y,z,z==Z+1?net.minecraft.init.Blocks.stone:net.minecraft.init.Blocks.glass);
        Block block=(Block)Block.blockRegistry.getObject("mtr:platform_slab");require(block instanceof BlockPlatformSlab&&block==org.mtr.mod.Blocks.PLATFORM_SLAB.get().data,"Original platform slab registry identity missing");
        Item item=Item.getItemFromBlock(block);require(item!=null&&Item.itemRegistry.getObject("mtr:platform_slab")==item,"Original platform slab ItemBlock missing");
        player.rotationYaw=0;
        for(int x:HOST_X) {
            ItemStack stack=new ItemStack(item);boolean creative=player.capabilities.isCreativeMode;player.capabilities.isCreativeMode=false;
            boolean accepted;
            try {accepted=item.onItemUse(stack,player,world,x,Y-1,Z,1,.5F,1,.5F);}finally{player.capabilities.isCreativeMode=creative;}
            require(accepted&&stack.stackSize==0,"Original slab ItemBlock did not consume its one-shot placement");
            Host actual=new Host(world,x);require(actual.matches(false),"Original slab placement/state/TE mismatch");
            JsonObject action=new JsonObject();action.addProperty("action","original_platform_slab_item");action.addProperty("accepted",accepted);action.addProperty("stack_after",stack.stackSize);action.add("result",actual.json());serverEvidence.add(action);
        }
    }
    private static MovingObjectPosition bucketRay(World world,EntityPlayerMP player) throws Exception {
        for(Method method:Item.class.getDeclaredMethods())if(method.getReturnType()==MovingObjectPosition.class&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{World.class,EntityPlayer.class,boolean.class})) {
            method.setAccessible(true);return (MovingObjectPosition)method.invoke(Items.water_bucket,world,player,false);
        }throw new AssertionError("Native Item bucket ray method missing");
    }
    private boolean scene(World world,boolean wet,boolean exact) {
        for(int i=0;i<HOST_X.length;i++) {
            Host host=new Host(world,HOST_X[i]);if(!host.matches(wet&&i>=2)||exact&&!host.same(expected.get(i)))return false;
        }
        for(int x=2;x<=12;x++)for(int z=0;z<=6;z++)for(int y=Y;y<=Y+1;y++)if(world.getBlock(x,y,z).getMaterial()==Material.water)return false;
        return true;
    }
    private boolean clientScene(boolean wet,boolean exact) {
        ClientObservation sample=clientSample;if(sample==null||sample.world!=activeClientWorld||System.currentTimeMillis()-sample.at>2000||sample.hosts==null)return false;
        for(int i=0;i<4;i++)if(!sample.hosts[i].matches(wet&&i>=2)||exact&&!sample.hosts[i].same(expected.get(i)))return false;
        return true;
    }
    private void recordScene(World world,String label) {
        JsonObject value=new JsonObject();value.addProperty("phase",label);JsonArray hosts=new JsonArray();for(int x:HOST_X)hosts.add(new Host(world,x).json());value.add("hosts",hosts);serverEvidence.add(value);
    }
    private void capture(Minecraft client,String label) throws Exception {
        require(client.theWorld!=null&&client.thePlayer!=null&&client.currentScreen==null,"No actual world framebuffer");
        String name="mtr-water-probe-"+runId+"-"+label+".png";
        ScreenShotHelper.saveScreenshot(client.mcDataDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());
        File file=new File(new File(client.mcDataDir,"screenshots"),name);require(file.isFile()&&file.length()>100,"World screenshot was not written");
        JsonObject value=new JsonObject();value.addProperty("phase",label);value.addProperty("path",file.getCanonicalPath());value.addProperty("client_tick",clientTicks);value.addProperty("complete_render_frames",renderFrames);value.addProperty("camera_x",client.thePlayer.posX);value.addProperty("camera_feet_y",client.thePlayer.boundingBox.minY);value.addProperty("camera_z",client.thePlayer.posZ);value.addProperty("yaw",client.thePlayer.rotationYaw);value.addProperty("pitch",client.thePlayer.rotationPitch);value.addProperty("fov",client.gameSettings.fovSetting);screenshots.add(value);
    }
    private void advance(Stage next) { captureReady=false;phaseTicks=stableTicks=0;phaseAt=System.currentTimeMillis();stage=next; }
    private synchronized void fail(Throwable problem) { if(failure==null)failure=problem; }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }

    private static final class Host {
        final int x,id,metadata;final String registry,tileId;final Map<String,String> properties;final NBTTagCompound state;final boolean stateTile;
        Host(World world,int x) {
            this.x=x;Block block=world.getBlock(x,Y,Z);id=Block.getIdFromBlock(block);metadata=world.getBlockMetadata(x,Y,Z);registry=String.valueOf(Block.blockRegistry.getNameForObject(block));
            TileEntity tile=world.getTileEntity(x,Y,Z);stateTile=tile instanceof BlockStateTileEntity;NBTTagCompound tag=new NBTTagCompound();if(tile!=null)tile.writeToNBT(tag);
            tileId=tag.getString("id");state=tag.getCompoundTag("mtr_block_state");
            properties=new TreeMap<>(new BlockView(world).getBlockState(new BlockPos(x,Y,Z)).data.getSerializedProperties());
        }
        boolean matches(boolean wet) { return Gates.host(registry,properties,stateTile,wet)&&state.hasKey("waterlogged",8)&&!tileId.isEmpty(); }
        boolean same(Host other) { return id==other.id&&metadata==other.metadata&&registry.equals(other.registry)&&tileId.equals(other.tileId)&&state.equals(other.state)&&properties.equals(other.properties); }
        JsonObject json() {
            JsonObject value=new JsonObject(),props=new JsonObject();value.addProperty("x",x);value.addProperty("y",Y);value.addProperty("z",Z);value.addProperty("block",registry);value.addProperty("block_id",id);value.addProperty("metadata",metadata);value.addProperty("state_tile",stateTile);value.addProperty("tile_id",tileId);value.addProperty("native_mtr_state",state.toString());
            for(Map.Entry<String,String> row:properties.entrySet())props.addProperty(row.getKey(),row.getValue());value.add("properties",props);return value;
        }
    }
    private static final class ClientObservation {
        final long at=System.currentTimeMillis();final double x,y,z;final Host[] hosts;final Object world;
        ClientObservation(Minecraft client,int tick) {
            world=client.theWorld;x=client.thePlayer.posX;y=client.thePlayer.boundingBox.minY;z=client.thePlayer.posZ;
            Host[] values=new Host[4];try{for(int i=0;i<4;i++)values[i]=new Host(client.theWorld,HOST_X[i]);}catch(IllegalStateException missingTile){values=null;}hosts=values;
        }
    }
    private static final class Angelica {
        final Object context,pending,buildToken;final int frame,submitted;final boolean built,visible;
        Angelica(Object context,Object pending,Object buildToken,int frame,int submitted,boolean built,boolean visible) { this.context=context;this.pending=pending;this.buildToken=buildToken;this.frame=frame;this.submitted=submitted;this.built=built;this.visible=visible; }
        static Object invoke(Object target,String method,Class<?>[] types,Object... args) throws Exception {return target.getClass().getMethod(method,types).invoke(target,args);}
        static Angelica read(Minecraft client) throws Exception {
            Class<?> type=Class.forName("com.gtnewhorizons.angelica.rendering.celeritas.CeleritasWorldRenderer");Object renderer=type.getMethod("getInstanceOrNull").invoke(null);
            require(renderer!=null,"Angelica Celeritas renderer missing");Object actualWorld=invoke(renderer,"getWorld",new Class<?>[0]);Object manager=invoke(renderer,"getRenderSectionManager",new Class<?>[0]);
            require(Gates.active(client.theWorld,actualWorld,manager,(Boolean)invoke(renderer,"isActive",new Class<?>[0])),"Angelica renderer is inactive or bound to the wrong native world");
            boolean built=(Boolean)invoke(manager,"isSectionBuilt",new Class<?>[]{int.class,int.class,int.class},0,4,0),visible=(Boolean)invoke(manager,"isSectionVisible",new Class<?>[]{int.class,int.class,int.class},0,4,0);
            for(Object section:(Collection<?>)invoke(manager,"getAllRenderSections",new Class<?>[0])) {
                if(((Number)invoke(section,"getChunkX",new Class<?>[0])).intValue()!=0||((Number)invoke(section,"getChunkY",new Class<?>[0])).intValue()!=4||((Number)invoke(section,"getChunkZ",new Class<?>[0])).intValue()!=0)continue;
                return new Angelica(invoke(section,"getBuiltContext",new Class<?>[0]),invoke(section,"getPendingUpdate",new Class<?>[0]),invoke(section,"getBuildCancellationToken",new Class<?>[0]),((Number)invoke(section,"getLastBuiltFrame",new Class<?>[0])).intValue(),((Number)invoke(section,"getLastSubmittedFrame",new Class<?>[0])).intValue(),built,visible);
            }return new Angelica(null,null,null,-1,-1,false,false);
        }
        boolean ready(Angelica baseline) { return Gates.rebuilt(baseline==null?-1:baseline.frame,frame,submitted,built,visible,pending,buildToken); }
        JsonObject json(String phase) { JsonObject value=new JsonObject();value.addProperty("phase",phase);value.addProperty("active_world_identity_verified",true);value.addProperty("section_x",0);value.addProperty("section_y",4);value.addProperty("section_z",0);value.addProperty("last_built_frame",frame);value.addProperty("last_submitted_frame",submitted);value.addProperty("built_context_identity",System.identityHashCode(context));value.addProperty("built",built);value.addProperty("visible",visible);value.addProperty("pending_update",String.valueOf(pending));value.addProperty("build_in_flight",buildToken!=null);value.addProperty("build_token_identity",System.identityHashCode(buildToken));return value; }
    }

    private void closeOwned(Minecraft client) throws Exception {
        Gates.owned(COPIED,save,saveName,runId);owned(client.getIntegratedServer());
        if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());
        require(client.theWorld==null&&client.getIntegratedServer()==null,"Native close did not stop owned world");
        normalCloses++;
    }
    private void verifySaved() throws Exception {
        Gates.owned(COPIED,save,saveName,runId);require(expected.size()==4,"Missing complete four-host save expectation");require(Files.isRegularFile(save.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS),"Native level.dat missing");
        byte[] payload=Gates.payload(save,save.resolve("region/r.0.0.mca"),0,0);NBTTagCompound level;
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(payload))){level=CompressedStreamTools.read(input).getCompoundTag("Level");}
        require(level.hasKey("xPos",3)&&level.hasKey("zPos",3)&&level.getInteger("xPos")==0&&level.getInteger("zPos")==0,"Saved native chunk coordinates differ from its header slot");
        NBTTagCompound section=null;NBTTagList sections=level.getTagList("Sections",10);
        for(int i=0;i<sections.tagCount();i++)if((sections.getCompoundTagAt(i).getByte("Y")&255)==Y>>4)section=sections.getCompoundTagAt(i);
        require(section!=null,"Saved native scene section missing");
        byte[] low=array(section,"Blocks",4096,false),high=array(section,"Add",2048,true),higher=array(section,"BlocksB2Hi",2048,true),highest=array(section,"BlocksB3",4096,true),data=array(section,"Data",2048,false),dataHigh=array(section,"Data1High",2048,true),dataHighest=array(section,"Data2",4096,true);
        NBTTagList tiles=level.getTagList("TileEntities",10);
        for(Host host:expected) {
            int slot=((Y&15)<<8)|(Z<<4)|host.x;
            int id=Gates.nativeId(low,high,higher,highest,slot),metadata=Gates.nativeMetadata(data,dataHigh,dataHighest,slot);
            require(id==host.id&&metadata==host.metadata,"Saved native block ID/EndlessIDs metadata differs");NBTTagCompound tile=null;
            for(int i=0;i<tiles.tagCount();i++){NBTTagCompound value=tiles.getCompoundTagAt(i);if(value.getInteger("x")==host.x&&value.getInteger("y")==Y&&value.getInteger("z")==Z){require(tile==null,"Duplicate native saved tile");tile=value;}}
            require(tile!=null&&host.tileId.equals(tile.getString("id"))&&host.state.equals(tile.getCompoundTag("mtr_block_state")),"Saved original MTR TileEntity/state differs");
            Map<String,String> decoded=BlockStateDefinition.forBlock(Block.getBlockById(id)).read(metadata,tile).getSerializedProperties();
            require(host.properties.equals(decoded)&&Gates.host(host.registry,decoded,true,host.x>=9),"Saved state decodes to wrong wet/dry host");
        }
    }
    private static byte[] array(NBTTagCompound section,String name,int length,boolean optional) {if(!section.hasKey(name)){require(optional,"Missing native Anvil array "+name);return new byte[0];}require(section.hasKey(name,7),"Wrong native Anvil array type "+name);byte[] bytes=section.getByteArray(name);require(bytes.length==length,"Wrong native Anvil array length "+name);return bytes;}
    private void finish(Minecraft client) {
        if(stage==Stage.FINISHED)return;boolean completed=stage==Stage.CLOSE&&failure==null;
        try {
            if(client.getIntegratedServer()!=null)closeOwned(client);
            if(completed){verifySaved();secondSaved=true;}
        }catch(Throwable problem){fail(problem);}
        stage=Stage.FINISHED;
        String outcome=Gates.outcome(completed,firstSaved,secondSaved,reloaded,screenshots.size(),failure!=null);
        report.addProperty("scenario_readback_success",outcome.equals("PROVISIONAL"));report.addProperty("outcome",outcome);
        report.addProperty("passed",false);report.addProperty("runtime_acceptance","pending_parent_natural_exit_0_stop_logs_fresh_save_and_visual_inspection");
        report.addProperty("save_reopened",reloaded);report.addProperty("saved_and_closed_normally",normalCloses==2);report.addProperty("normal_close_count",normalCloses);report.addProperty("setup_teleport_count",setupTeleports);report.addProperty("observation_teleport_count",0);report.addProperty("probe_motion_writes",0);
        report.add("original_actions_and_states",serverEvidence);report.add("screenshots",screenshots);report.add("actual_angelica_builds",renderEvidence);
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try {
            if(copyVerified){Gates.owned(COPIED,save,saveName,runId);Files.write(COPIED.resolve("mtr-water-probe-"+runId+".json"),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
            System.out.println("MTR_WATER_PROBE_"+outcome+" "+runId);
            if(copyVerified&&client.getIntegratedServer()==null&&client.theWorld==null)client.shutdown();
        }catch(Throwable problem){throw new IllegalStateException("Could not write WaterProbe evidence",problem);}
    }

    // Pure admission/evidence gates are exercised without loading Minecraft by the isolated selfcheck.
    static final class Gates {
        static boolean host(String registry,Map<String,String> state,boolean tile,boolean wet) {
            Map<String,String> expected=new TreeMap<>();expected.put("door_type","none");expected.put("facing","south");expected.put("side","0");expected.put("type","bottom");expected.put("waterlogged",wet?"true":"false");
            return tile&&"mtr:platform_slab".equals(registry)&&expected.equals(state);
        }
        static boolean active(Object expectedWorld,Object actualWorld,Object manager,boolean active) {return active&&expectedWorld!=null&&expectedWorld==actualWorld&&manager!=null;}
        static boolean rebuilt(int before,int frame,int submitted,boolean built,boolean visible,Object pending,Object buildToken) {return built&&visible&&pending==null&&frame>before&&frame>=submitted&&buildToken==null;}
        static boolean setupAllowed(String stage,int count) {return count>=0&&count<5&&(stage.equals("SETUP")||stage.equals("DRY_TOP_SETUP")||stage.equals("BUCKET_SETUP")||stage.equals("VIEW_SETUP")||stage.equals("WET_TOP_SETUP"));}
        static String outcome(boolean completed,boolean firstSaved,boolean secondSaved,boolean reloaded,int shots,boolean failed) {return completed&&firstSaved&&secondSaved&&reloaded&&shots==5&&!failed?"PROVISIONAL":"FAIL";}
        static int nibble(byte[] bytes,int index) {return bytes.length==0?0:(bytes[index>>1]>>((index&1)*4))&15;}
        static int nativeId(byte[] low,byte[] high,byte[] higher,byte[] highest,int slot) {return (low[slot]&255)|(nibble(high,slot)<<8)|(nibble(higher,slot)<<12)|(highest.length==0?0:(highest[slot]&255)<<16);}
        static int nativeMetadata(byte[] low,byte[] high,byte[] highest,int slot) {return nibble(low,slot)|(nibble(high,slot)<<4)|(highest.length==0?0:(highest[slot]&255)<<8);}
        static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
        static Path checked(Path input) throws IOException {
            Path path=input.toAbsolutePath().normalize();for(Path part=path;part!=null;part=part.getParent())check(!Files.isSymbolicLink(part),"Symlink in owned path");return path.toRealPath();
        }
        static void owned(Path copy,Path save,String name,String id) throws IOException {
            check(UUID.fromString(id).toString().equals(id)&&name.equals("MTR-water-acceptance-"+id),"Invalid fresh save identity");
            Path root=checked(copy),actual=checked(save);check(actual.getFileName().toString().equals(name)&&actual.getParent().equals(checked(root.resolve("saves"))),"Save escaped exact copied saves root");
            Path marker=actual.resolve("mtr-water-probe-owner.txt");check(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&checked(marker).getParent().equals(actual)&&id.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Wrong owned-save marker");
        }
        static void nativeSave(Path expected,Path actual) throws IOException {check(checked(expected).equals(checked(actual)),"Native server save root differs from exact owned save");}
        static byte[] payload(Path save,Path region,int chunkX,int chunkZ) throws IOException {
            Path root=checked(save),file=checked(region);String name="r."+(chunkX>>5)+"."+(chunkZ>>5)+".mca";
            check(file.equals(root.resolve("region").resolve(name)),"Anvil outside exact owned region path");
            try(RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")) {
                check(input.length()>=8192&&input.length()%4096==0,"Malformed native Anvil length");
                input.seek(((chunkX&31)+(chunkZ&31)*32)*4L);int location=input.readInt(),sector=location>>>8,sectors=location&255;
                check(sector>=2&&sectors>0&&((long)sector+sectors)*4096<=input.length(),"Invalid native Anvil sectors");
                input.seek(sector*4096L);int length=input.readInt(),compression=input.readUnsignedByte();check(length>1&&length<=sectors*4096-4&&(compression==1||compression==2),"Malformed native chunk payload");
                byte[] compressed=new byte[length-1];input.readFully(compressed);
                try(InputStream bytes=new ByteArrayInputStream(compressed);InputStream expanded=compression==1?new GZIPInputStream(bytes):new InflaterInputStream(bytes);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];int size;while((size=expanded.read(buffer))!=-1){check(output.size()+size<=32*1024*1024,"Owned fixture chunk exceeds native read bound");output.write(buffer,0,size);}return output.toByteArray();
                }
            }
        }
    }
}
