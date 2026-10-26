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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.storage.RegionFile;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.tool.BlockStateDefinition;
import org.mtr.mapping.tool.BlockStateTileEntity;
import org.mtr.mod.block.BlockEscalatorSide;
import org.mtr.mod.block.BlockEscalatorStep;

import java.io.DataInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fixed NORTH reload of the exact root-prepared isolated clone. No placement or ride physics writes. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_world_probe",name="MTR GTNH fixed NORTH reload acceptance",version="0",
    dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class WorldProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final Path TASK = java.nio.file.Paths.get(requiredProbePath("mtr.probeTaskRoot"));
    private static final File COPIED_CLIENT = new File(requiredProbePath("mtr.probeClient"));
    private static final String ACCEPTED = "9b27b691-c527-4a7e-9a96-df8287425cdf";
    private static final String SAVE_NAME = "MTR-acceptance-" + ACCEPTED;
    private static final Path SAVE = COPIED_CLIENT.toPath().resolve("saves").resolve(SAVE_NAME);
    private static final Path WORK = TASK.resolve("work/sp-escalator-reload-run");
    private static final Path SOURCE = TASK.resolve("work/mtr-port/mtr/gtnh/src/probe/java/org/mtr/gtnh/probe/WorldProbe.java");
    private static final String PREPARATION_SHA = "647cd130d700cbaf49b7d31d0da3cbdc430face836d3a40fafb0cd4e66c2a22d";
    private static final UUID SOURCE_PLAYER = UUID.fromString("6901a56c-c6c4-4350-ab76-1a2f4579791f");
    private static final double SOURCE_X=64.6,SOURCE_Y=71.9375,SOURCE_Z=61.44355526995026;
    private static final int SAMPLE_TICKS = 24;
    private static final String[] MODES = {"forward","backward","stopped"};
    private static final SlopeLayout SLOPE = new SlopeLayout("north");
    private enum Stage { MENU, WORLD, VERIFY, CHANGE_MODE, SETTLE, SAMPLE, SCREENSHOT, CLOSE, FINISHED }
    private volatile Stage stage=Stage.MENU;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private volatile ClientSample clientSample;
    private volatile int phaseIndex,clientModeAcknowledged=-1,screenshotAcknowledged=-1;
    private volatile boolean clientBaselineReady;
    private int menuTicks,clientTicks,phaseTicks,renderFrames,brushUses,readyChunkTicks;
    private int inputTicks,inputViolations,baselineSeenTick=-1;
    private long startedAt;
    private boolean copyVerified,completed,loadedExisting,closed;
    private String saveName,runId;
    private File saveDirectory;
    private Path resultDirectory;
    private NBTTagList sourceInventory;
    private volatile JsonObject finalPlayer;
    private final JsonObject report=new JsonObject();
    private final JsonArray phases=new JsonArray(),screenshots=new JsonArray(),slopeTrajectory=new JsonArray();
    private final List<StoredBlock> expectedSavedBlocks=new ArrayList<>();
    private JsonArray samples;
    private ClientSample firstClient;
    private double firstServerX,firstServerY,firstServerZ,slopeLastProgress;
    private int slopeStallTicks;
    private Set<Integer> sampledClientTicks;

    @Mod.EventHandler public void init(FMLInitializationEvent event){startedAt=System.currentTimeMillis();onlyProbe();FMLCommonHandler.instance().bus().register(this);}

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try{
            if(failure!=null){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<15*60*1000L,"NORTH reload deadline: "+stage);
            if(stage==Stage.MENU){
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.getIntegratedServer()!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();
                if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                preflight(client);report.addProperty("menu_class",screen);capture(client,"menu");
                client.gameSettings.thirdPersonView=1;client.gameSettings.renderDistanceChunks=4;
                stage=Stage.WORLD;
                cpw.mods.fml.client.FMLClientHandler.instance().tryLoadExistingWorld(new net.minecraft.client.gui.GuiSelectWorld(new GuiMainMenu()),SAVE_NAME,"MTR acceptance "+ACCEPTED);
                return;
            }
            if(stage==Stage.WORLD){
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                assertOwnedServer(client.getIntegratedServer());require(SOURCE_PLAYER.equals(client.thePlayer.getUniqueID()),"Wrong native restored player UUID");
                require(client.theWorld.isRemote&&client.getIntegratedServer().isSinglePlayer(),"Expected native singleplayer client/integrated server");
                playerId=SOURCE_PLAYER;loadedExisting=true;report.addProperty("world_opened",true);report.addProperty("existing_save_loaded",true);report.addProperty("player_uuid",playerId.toString());stage=Stage.VERIFY;
            }
            if(stage==Stage.CLOSE){finish(client);return;}
            if(stage==Stage.VERIFY||stage==Stage.SETTLE||stage==Stage.SAMPLE||stage==Stage.SCREENSHOT){
                assertOwnedServer(client.getIntegratedServer());
                require(client.theWorld!=null&&client.thePlayer!=null&&SOURCE_PLAYER.equals(client.thePlayer.getUniqueID())&&client.thePlayer.dimension==0,"Restored NORTH client disappeared/changed identity");
                require(!client.gameSettings.pauseOnLostFocus,"Isolated copied client pauseOnLostFocus must already be disabled");
                if(client.currentScreen!=null)client.displayGuiScreen(null);
                boolean zero=client.thePlayer.movementInput.moveForward==0&&client.thePlayer.movementInput.moveStrafe==0&&!client.thePlayer.movementInput.jump&&!client.thePlayer.movementInput.sneak;
                inputTicks++;if(!zero)inputViolations++;require(inputViolations==0,"Consumed native movement input during reload/carry");
                KeyBinding.unPressAllKeys();
                if(stage!=Stage.VERIFY){require(!client.thePlayer.capabilities.isFlying&&!client.thePlayer.noClip&&Math.abs(client.thePlayer.stepHeight-.6)<.02,"Reload rider used flight/noclip/modified stepHeight");assertInventory(client.thePlayer);}
                clientSample=new ClientSample(++clientTicks,phaseIndex,client.thePlayer.posX,client.thePlayer.boundingBox.minY,client.thePlayer.posZ,client.thePlayer.onGround,client.thePlayer.stepHeight,client.thePlayer.width,client.thePlayer.isCollidedHorizontally,zero,inputTicks,inputViolations);
                if(stage==Stage.VERIFY){try{assertRestoredPlayer(client.thePlayer);clientBaselineReady=slopeClientHasState(client,2);}catch(AssertionError pending){clientBaselineReady=false;report.addProperty("client_restore_last_wait",pending.toString());}}
                if(stage==Stage.SETTLE&&slopeClientHasState(client,SLOPE.mode(phaseIndex)))clientModeAcknowledged=phaseIndex;
            }
        }catch(Throwable problem){fail(problem);}
    }

    private void preflight(Minecraft client)throws Exception{
        require(client.theWorld==null&&client.getIntegratedServer()==null,"Refusing to replace an open world");onlyProbe();checkPaths();
        require(client.mcDataDir.getCanonicalFile().toPath().equals(checked(COPIED_CLIENT.toPath())),"Foreign copied client");
        Path artifact=artifact(WorldProbe.class.getProtectionDomain().getCodeSource().getLocation());
        JsonObject preparation=json(WORK.resolve("preparation.json"),PREPARATION_SHA,2*1024*1024);
        require("COW_COPIED_ACCEPTED_NORTH_WORLD".equals(text(preparation,"status"))&&ACCEPTED.equals(text(preparation,"accepted_run_id"))&&COPIED_CLIENT.toString().equals(text(preparation,"client_directory"))&&SAVE.toString().equals(text(preparation,"save_directory")),"Wrong fixed NORTH preparation");
        require(preparation.get("backup_verified").getAsBoolean()&&preparation.get("source_world_unchanged").getAsBoolean(),"Verified recoverable backup/source receipt missing");
        Path backup=checked(java.nio.file.Paths.get(text(preparation,"backup_archive")));
        require(backup.equals(checked(WORK.resolve("accepted-world-before-reload.tar.gz")))&&sha(backup).equals(text(preparation,"backup_sha256")),"Backup archive hash/path differs");
        JsonObject files=preparation.getAsJsonObject("source_world_files");verifyTree(files);
        Path admissionPath=WORK.resolve("parent-admission.json");String admissionSha=sha(checked(admissionPath));
        JsonObject admission=json(admissionPath,null,65536);
        require("mtr-escalator-sp-reload-admission-v1".equals(text(admission,"schema"))&&admission.has("approved")&&admission.get("approved").isJsonPrimitive()&&admission.get("approved").getAsJsonPrimitive().isBoolean()&&admission.get("approved").getAsBoolean()&&PREPARATION_SHA.equals(text(admission,"preparation_sha256"))&&ACCEPTED.equals(text(admission,"accepted_run_id"))&&COPIED_CLIENT.toString().equals(text(admission,"client_directory"))&&SAVE.toString().equals(text(admission,"save_directory")),"Wrong NORTH reload admission");
        runId=text(admission,"run_id");require(UUID.fromString(runId).toString().equals(runId)&&UUID.fromString(runId).version()==4&&!runId.equals(ACCEPTED),"Fresh canonical reload UUID4 required");
        require(sha(checked(SOURCE)).equals(text(admission,"probe_source_sha256"))&&sha(artifact).equals(text(admission,"probe_jar_sha256")),"Parent source/JAR CodeSource pins differ");
        require(!Files.exists(WORK.resolve("parent-admission.used-"+runId+".json"),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(WORK.resolve("run-"+runId),LinkOption.NOFOLLOW_LINKS),"Reload admission already consumed");
        saveName=SAVE_NAME;saveDirectory=SAVE.toFile();
        NBTTagCompound level=readNbt(SAVE.resolve("level.dat"));require(!level.getCompoundTag("FML").hasKey("ModItemData"),"Legacy conversion is outside fixed reload");
        require(("MTR acceptance "+ACCEPTED).equals(level.getCompoundTag("Data").getString("LevelName")),"Wrong accepted native level name");
        NBTTagCompound player=readNbt(SAVE.resolve("playerdata/"+SOURCE_PLAYER+".dat"));assertSavedSourcePlayer(player);assertSavedSourcePlayer(level.getCompoundTag("Data").getCompoundTag("Player"));
        sourceInventory=(NBTTagList)player.getTagList("Inventory",10).copy();
        expectedSavedBlocks.addAll(readSavedBaseline());require(expectedSavedBlocks.size()==128,"Missing complete source NORTH snapshot");
        require(client.getSaveLoader().canLoadWorld(SAVE_NAME),"Native existing-save loader rejected NORTH clone");
        verifyTree(files);
        require(admissionSha.equals(sha(checked(admissionPath))),"Admission changed before claim");
        Files.move(admissionPath,WORK.resolve("parent-admission.used-"+runId+".json"));resultDirectory=Files.createDirectory(WORK.resolve("run-"+runId));copyVerified=true;
        report.addProperty("run_id",runId);report.addProperty("accepted_run_id",ACCEPTED);report.addProperty("client_directory",COPIED_CLIENT.toString());report.addProperty("save_directory",SAVE.toString());report.addProperty("fresh_save_reserved",false);report.addProperty("escalator_case","slope_north_reload");report.addProperty("preparation_sha256",PREPARATION_SHA);report.add("parent_admission",admission);
        report.addProperty("setup_teleports",0);report.addProperty("ride_teleports",0);report.addProperty("probe_motion_writes",0);report.addProperty("probe_step_height_mutations",false);report.addProperty("original_item_placements",0);report.addProperty("saved_source_blocks_before_load",expectedSavedBlocks.size());report.add("slope_trajectory",slopeTrajectory);
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event){
        Stage current=stage;if(event.phase!=TickEvent.Phase.END||failure!=null||current==Stage.MENU||current==Stage.WORLD||current==Stage.CLOSE||current==Stage.FINISHED||current==Stage.CHANGE_MODE)return;
        try{
            MinecraftServer server=MinecraftServer.getServer();assertOwnedServer(server);WorldServer world=server.worldServerForDimension(0);EntityPlayerMP player=null;
            for(Object candidate:server.getConfigurationManager().playerEntityList){EntityPlayerMP value=(EntityPlayerMP)candidate;require(value.getUniqueID().equals(SOURCE_PLAYER),"Foreign integrated-server player");player=value;}
            require(player!=null&&player.worldObj==world&&player.dimension==0,"Missing actual restored server player");
            if(current==Stage.VERIFY){
                require(++phaseTicks<1200,"NORTH native chunks/player/state did not load");
                JsonArray chunks=standChunkStatus(world);report.add("stand_chunks_last_wait",chunks);if(!allChunksReady(chunks)||clientSample==null||!clientBaselineReady||!clientSample.onGround||!player.onGround)return;
                assertRestoredPlayer(player);require(Math.abs(player.stepHeight-.6)<.02,"Restored server stepHeight differs");assertBaseline(world);
                if(clientSample.tick<=baselineSeenTick)return;baselineSeenTick=clientSample.tick;if(++readyChunkTicks<12)return;
                report.addProperty("native_source_player_restored",true);report.addProperty("loaded_native_blocks_verified",128);phaseIndex=0;beginSlopePhase(world,player);return;
            }
            require(!player.capabilities.isFlying&&!player.noClip&&Math.abs(player.stepHeight-.6)<.02,"Server reload rider used altered physics");assertInventory(player);
            require(clientSample!=null&&clientSample.inputZero&&clientSample.inputViolations==0,"Missing zero consumed client input");
            try{slopeTick(current,world,player);}catch(Throwable problem){try{report.add("slope_failure_geometry",slopeGeometry(world,player));}catch(Throwable diagnostic){report.addProperty("slope_geometry_error",diagnostic.toString());}if(samples!=null)report.add("incomplete_slope_samples",samples);throw problem;}
        }catch(Throwable problem){fail(problem);}
    }

    private void assertRestoredPlayer(net.minecraft.entity.player.EntityPlayer player){
        require(SOURCE_PLAYER.equals(player.getUniqueID())&&player.dimension==0&&Math.abs(player.posX-SOURCE_X)<.08&&Math.abs(player.boundingBox.minY-SOURCE_Y)<.08&&Math.abs(player.posZ-SOURCE_Z)<.08&&!player.capabilities.isFlying&&!player.noClip,"Native source player/position did not restore");assertInventory(player);
    }
    private void assertInventory(net.minecraft.entity.player.EntityPlayer player){
        require(sourceInventory!=null&&sourceInventory.equals(player.inventory.writeToNBT(new NBTTagList()))&&player.inventory.currentItem==0&&player.getHeldItem()!=null&&player.getHeldItem().getItem()==org.mtr.mod.Items.BRUSH.get().data,"Native saved brush/inventory identity changed");
    }
    private void assertSavedSourcePlayer(NBTTagCompound player){
        NBTTagList p=player.getTagList("Pos",6),items=player.getTagList("Inventory",10);
        require(p.tagCount()==3&&Math.abs(p.func_150309_d(0)-SOURCE_X)<1E-6&&Math.abs(p.func_150309_d(1)-SOURCE_Y)<1E-6&&Math.abs(p.func_150309_d(2)-SOURCE_Z)<1E-6&&player.getInteger("Dimension")==0&&player.getBoolean("OnGround")&&!player.getCompoundTag("abilities").getBoolean("flying")&&new UUID(player.getLong("UUIDMost"),player.getLong("UUIDLeast")).equals(SOURCE_PLAYER)&&player.getInteger("SelectedItemSlot")==0&&items.tagCount()==1,"Saved source NORTH player baseline differs");
        ItemStack item=ItemStack.loadItemStackFromNBT(items.getCompoundTagAt(0));require(item!=null&&item.stackSize==1&&item.getItem()==org.mtr.mod.Items.BRUSH.get().data&&items.getCompoundTagAt(0).getByte("Slot")==0,"Saved source original brush differs");
    }
    private void assertBaseline(WorldServer world){
        require(expectedSavedBlocks.size()==128,"Source baseline absent");assertSlope(world,2);
        for(StoredBlock expected:expectedSavedBlocks){StoredBlock actual=snapshot(world,expected.x,expected.y,expected.z);require(expected.id==actual.id&&expected.metadata==actual.metadata&&expected.state.equals(actual.state)&&expected.properties.equals(actual.properties),"Loaded/final NORTH native block differs from source baseline");}
    }
    private void assertOwnedServer(MinecraftServer server)throws Exception{
        checkPaths();require(copyVerified&&server!=null&&server.isSinglePlayer()&&SAVE_NAME.equals(server.getFolderName())&&checked(server.worldServerForDimension(0).getSaveHandler().getWorldDirectory().toPath()).equals(checked(SAVE)),"Foreign integrated server/save");
    }
    private List<StoredBlock> readSavedBaseline()throws Exception{
        Path file=SAVE.resolve("region/r.0.0.mca");String before=sha(checked(file));List<StoredBlock> blocks=new ArrayList<>();java.util.Map<Integer,NBTTagCompound> chunks=new java.util.HashMap<>();
        RegionFile region=new RegionFile(file.toFile());
        try{
            for(int row=0;row<32;row++)for(int half=0;half<2;half++)for(int above=0;above<2;above++){
                int x=SLOPE.x(row,half),y=SLOPE.y(row)+above,z=SLOPE.z(row,half),cz=z>>4;
                NBTTagCompound level=chunks.get(cz);
                if(level==null){try(DataInputStream input=region.getChunkDataInputStream(x>>4,cz)){require(input!=null,"Missing source NORTH saved chunk");level=CompressedStreamTools.read(input).getCompoundTag("Level");}require(level.getInteger("xPos")==4&&level.getInteger("zPos")==cz,"Wrong source native chunk");chunks.put(cz,level);}
                StoredBlock block=savedBlock(level,x,y,z);Block nativeBlock=Block.getBlockById(block.id);
                BlockState decoded=new BlockState(BlockStateDefinition.forBlock(nativeBlock).read(block.metadata,blockTag(block.state)));
                require(slopeStateMatches(decoded,row,half,above,2),"Source baseline is not complete original NORTH STOP");blocks.add(block);
            }
        }finally{region.close();}
        require(chunks.size()==3&&before.equals(sha(file)),"Read-only source NORTH Anvil changed bytes/chunks");return blocks;
    }
    private static NBTTagCompound blockTag(NBTTagCompound state){NBTTagCompound tag=new NBTTagCompound();tag.setTag("mtr_block_state",state.copy());return tag;}
    private static StoredBlock savedBlock(NBTTagCompound level,int x,int y,int z){
        int slot=((y&15)<<8)|((z&15)<<4)|(x&15);NBTTagCompound section=null;NBTTagList sections=level.getTagList("Sections",10);
        for(int i=0;i<sections.tagCount();i++){NBTTagCompound candidate=sections.getCompoundTagAt(i);if((candidate.getByte("Y")&255)==y>>4){require(section==null,"Duplicate source section");section=candidate;}}
        require(section!=null,"Missing saved source section");
        byte[] low=savedArray(section,"Blocks",4096,false),high=savedArray(section,"Add",2048,true),higher=savedArray(section,"BlocksB2Hi",2048,true),highest=savedArray(section,"BlocksB3",4096,true);
        int id=(low[slot]&255)|(high.length==0?0:nibble(high,slot)<<8)|(higher.length==0?0:nibble(higher,slot)<<12)|(highest.length==0?0:(highest[slot]&255)<<16);
        byte[] data=savedArray(section,"Data",2048,false),dataHigh=savedArray(section,"Data1High",2048,true),dataHighest=savedArray(section,"Data2",4096,true);
        int metadata=nibble(data,slot)|(dataHigh.length==0?0:nibble(dataHigh,slot)<<4)|(dataHighest.length==0?0:(dataHighest[slot]&255)<<8);
        NBTTagCompound tile=null;NBTTagList tiles=level.getTagList("TileEntities",10);
        for(int i=0;i<tiles.tagCount();i++){NBTTagCompound candidate=tiles.getCompoundTagAt(i);if(candidate.getInteger("x")==x&&candidate.getInteger("y")==y&&candidate.getInteger("z")==z){require(tile==null,"Duplicate source tile");tile=candidate;}}
        Block block=Block.getBlockById(id);require(block!=null&&tile!=null&&tile.hasKey("mtr_block_state",10),"Missing native source block/tile state");
        TileEntity restored=TileEntity.createAndLoadEntity(tile);require(restored instanceof BlockStateTileEntity,"Source MTR tile registry cannot restore");
        Map<String,String> properties=((BlockStateTileEntity)restored).readMtrState(BlockStateDefinition.forBlock(block),metadata).data.getSerializedProperties();
        return new StoredBlock(x,y,z,id,metadata,tile.getCompoundTag("mtr_block_state"),properties);
    }
    private static NBTTagCompound readNbt(Path path)throws Exception{String before=sha(checked(path));NBTTagCompound value;try(java.io.InputStream input=Files.newInputStream(path)){value=CompressedStreamTools.readCompressed(input);}require(before.equals(sha(path)),"Read-only native NBT changed bytes");return value;}
    private static void onlyProbe(){for(cpw.mods.fml.common.ModContainer mod:Loader.instance().getModList()){String id=mod.getModId();require(!id.startsWith("mtr_gtnh_")||!id.endsWith("_probe")||id.equals("mtr_gtnh_world_probe"),"Coexisting acceptance probe "+id);}}
    private static Path checked(Path path)throws Exception{Path p=path.toAbsolutePath().normalize();for(Path member=p;member!=null;member=member.getParent())require(!Files.isSymbolicLink(member),"Symlink path "+member);return p.toRealPath();}
    private static void checkPaths()throws Exception{
        Path client=checked(COPIED_CLIENT.toPath()),save=checked(SAVE);require(client.equals(COPIED_CLIENT.toPath())&&save.equals(SAVE)&&save.getParent().equals(client.resolve("saves")),"NORTH save escaped exact isolated copy");
        Path marker=SAVE.resolve("mtr-world-probe-owner.txt");require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&checked(marker).startsWith(save)&&ACCEPTED.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Source NORTH owner marker differs");
        require(checked(WORK).equals(WORK),"Foreign reload work directory");
    }
    private static Path artifact(java.net.URL location)throws Exception{
        if("jar".equals(location.getProtocol())){java.net.JarURLConnection connection=(java.net.JarURLConnection)location.openConnection();String entry=connection.getEntryName();require(entry==null||entry.isEmpty()||entry.equals("org/mtr/gtnh/probe/WorldProbe.class"),"Unexpected nested CodeSource");location=connection.getJarFileURL();}
        java.net.URI uri=location.toURI();require("file".equals(uri.getScheme())&&uri.getAuthority()==null&&uri.getQuery()==null&&uri.getFragment()==null,"CodeSource must be local file JAR");Path jar=checked(java.nio.file.Paths.get(uri));require(jar.equals(checked(COPIED_CLIENT.toPath().resolve("mods/MTR-GTNH-4.0.5-gtnh.0.jar")))&&Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS),"Foreign WorldProbe installed CodeSource");return jar;
    }
    private static String sha(Path file)throws Exception{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(java.io.InputStream input=Files.newInputStream(file)){byte[] b=new byte[65536];for(int n;(n=input.read(b))!=-1;)digest.update(b,0,n);}StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format("%02x",b&255));return text.toString();}
    private static String text(JsonObject o,String key){return o.get(key).getAsString();}
    private static JsonObject json(Path file,String pin,int cap)throws Exception{Path p=checked(file);require(p.getParent().equals(checked(WORK))&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)>0&&Files.size(p)<cap,"Invalid local reload manifest");byte[] bytes=Files.readAllBytes(p);if(pin!=null){java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");StringBuilder actual=new StringBuilder();for(byte b:digest.digest(bytes))actual.append(String.format("%02x",b&255));require(pin.equals(actual.toString()),"Preparation manifest pin differs");}return org.mtr.libraries.com.google.gson.JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void verifyTree(JsonObject files)throws Exception{
        require(files.entrySet().size()==582,"Expected exact582source-world files");final Set<String> seen=new HashSet<>();
        Files.walkFileTree(SAVE,new java.nio.file.SimpleFileVisitor<Path>(){
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path,java.nio.file.attribute.BasicFileAttributes attrs)throws java.io.IOException{require(!attrs.isSymbolicLink(),"Symlink save directory");return java.nio.file.FileVisitResult.CONTINUE;}
            @Override public java.nio.file.FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attrs)throws java.io.IOException{
                try{String relative=SAVE.relativize(path).toString().replace(File.separatorChar,'/');require(attrs.isRegularFile()&&!attrs.isSymbolicLink()&&files.has(relative)&&seen.add(relative)&&checked(path).startsWith(checked(SAVE)),"Foreign/missing native save member");JsonObject expected=files.getAsJsonObject(relative);require(attrs.size()==expected.get("bytes").getAsLong()&&sha(path).equals(text(expected,"sha256")),"Source tree clone differs: "+relative);return java.nio.file.FileVisitResult.CONTINUE;}catch(java.io.IOException e){throw e;}catch(Exception e){throw new java.io.IOException(e);}
            }
        });require(seen.size()==files.entrySet().size(),"Incomplete exact source world clone");
    }

    private void finish(Minecraft client){
        if(stage==Stage.FINISHED)return;stage=Stage.FINISHED;
        try{
            if(client.getIntegratedServer()!=null){assertOwnedServer(client.getIntegratedServer());if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());require(client.getIntegratedServer()==null&&client.theWorld==null,"Owned native save did not close");closed=true;report.addProperty("native_close_returned",true);}
            if(copyVerified&&closed&&expectedSavedBlocks.size()==128){verifySavedAnvil();if(completed){assertSavedFinalPlayer(readNbt(SAVE.resolve("playerdata/"+SOURCE_PLAYER+".dat")));assertSavedFinalPlayer(readNbt(SAVE.resolve("level.dat")).getCompoundTag("Data").getCompoundTag("Player"));report.addProperty("saved_native_player_verified",true);}}
        }catch(Throwable problem){fail(problem);}
        boolean localSuccess=failure==null&&completed&&loadedExisting&&closed;report.addProperty("scenario_readback_success",localSuccess);report.addProperty("passed",false);report.addProperty("runtime_acceptance_pending",true);report.addProperty("saved_and_closed_normally",false);report.addProperty("requires_parent_natural_exit_and_save_logs",true);report.addProperty("original_native_brush_uses",brushUses);report.addProperty("input_ticks",inputTicks);report.addProperty("input_violations",inputViolations);report.add("phases",phases);report.add("screenshots",screenshots);
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try{if(copyVerified){byte[] output=report.toString().getBytes(StandardCharsets.UTF_8);Files.write(resultDirectory.resolve("world-probe.json"),output,StandardOpenOption.CREATE_NEW);Files.write(COPIED_CLIENT.toPath().resolve("mtr-world-probe-"+runId+".json"),output,StandardOpenOption.CREATE_NEW);}System.out.println((localSuccess?"MTR_WORLD_PROBE_PROVISIONAL ":"MTR_WORLD_PROBE_FAIL ")+report);if(copyVerified)client.shutdown();}catch(Throwable problem){throw new IllegalStateException("Could not write NORTH reload result",problem);}
    }
    private void assertSavedFinalPlayer(NBTTagCompound player){
        require(finalPlayer!=null,"Final actual player baseline missing");NBTTagList p=player.getTagList("Pos",6);
        require(p.tagCount()==3&&Math.abs(p.func_150309_d(0)-finalPlayer.get("x").getAsDouble())<.05&&Math.abs(p.func_150309_d(1)-finalPlayer.get("feet_y").getAsDouble())<.1&&Math.abs(p.func_150309_d(2)-finalPlayer.get("z").getAsDouble())<.05&&player.getInteger("Dimension")==0&&!player.getCompoundTag("abilities").getBoolean("flying")&&SOURCE_PLAYER.equals(new UUID(player.getLong("UUIDMost"),player.getLong("UUIDLeast")))&&player.getInteger("SelectedItemSlot")==0&&sourceInventory.equals(player.getTagList("Inventory",10)),"Final native saved player/inventory differs from actual STOP rider");
    }

    static final class SlopeLayout {
        final String facing;
        final int fx, fz, rx, rz, yaw;
        SlopeLayout(String facing) {
            this.facing = facing;
            switch (facing) {
                case "south": fx=0; fz=1; yaw=0; break;
                case "west": fx=-1; fz=0; yaw=90; break;
                case "north": fx=0; fz=-1; yaw=180; break;
                case "east": fx=1; fz=0; yaw=270; break;
                default: throw new IllegalArgumentException("Expected north/east/south/west, got " + facing);
            }
            rx=-fz; rz=fx;
        }
        int x(int row, int half) { return 64+row*fx+half*rx; }
        int z(int row, int half) { return 64+row*fz+half*rz; }
        int y(int row) { return 71+(row<=11?0:row<=19?row-11:8); }
        int clickX(int row) { return x(row,0)-rx; }
        int clickZ(int row) { return z(row,0)-rz; }
        int clickSide() { return rx<0?4:rx>0?5:rz<0?2:3; }
        float hitX() { return rx<0?0:rx>0?1:.5F; }
        float hitZ() { return rz<0?0:rz>0?1:.5F; }
        double riderX() { return x(4,0)+.5+.1*rx; }
        double riderZ() { return z(4,0)+.5+.1*rz; }
        double along(double x,double z) { return (x-64.5)*fx+(z-64.5)*fz; }
        double across(double x,double z) { return (x-64.5)*rx+(z-64.5)*rz; }
        String orientation(int row) {
            return row==0?"landing_bottom":row==31?"landing_top":row==11?"transition_bottom"
                :row==19?"transition_top":row>=12 && row<=18?"slope":"flat";
        }
        int mode(int phase) { return phase==0?0:phase==1 || phase==3?1:2; }
        String label(int phase) {
            return new String[]{"uphill","downhill_to_slope","stopped_on_slope","downhill_to_landing","stopped_lower"}[phase];
        }
        boolean reached(int phase,double client,double server) {
            return phase==0?client>=25 && server>=25:phase==1?client<=15 && server<=15:phase==3 && client<=5 && server<=5;
        }
        boolean onSlope(double u) { return u>=12 && u<19; }
        boolean corridorClear(double across,double width) { return across-width/2>-.25 && across+width/2<.5; }
        int minX() { return Math.min(x(0,0),Math.min(x(31,1),clickX(31)))-2; }
        int maxX() { return Math.max(x(0,0),Math.max(x(31,1),clickX(31)))+2; }
        int minZ() { return Math.min(z(0,0),Math.min(z(31,1),clickZ(31)))-2; }
        int maxZ() { return Math.max(z(0,0),Math.max(z(31,1),clickZ(31)))+2; }
    }

    private void slopeTick(Stage current, WorldServer world, EntityPlayerMP player) {
        ClientSample client = clientSample;
        require(client != null, "Actual slope client sample is missing");
        JsonObject observed = slopeSample(world, player, client);
        observed.addProperty("stage", current.name());
        slopeTrajectory.add(observed);
        double clientU=SLOPE.along(client.x,client.z), serverU=SLOPE.along(player.posX,player.posZ);
        require(clientU>1 && clientU<30 && serverU>1 && serverU<30, "Slope rider left the interior fixture");
        require(Math.abs(SLOPE.across(client.x,client.z)-.1)<.15
            && Math.abs(SLOPE.across(player.posX,player.posZ)-.1)<.15, "Slope rider drifted toward handrail or seam");
        require(SLOPE.corridorClear(SLOPE.across(client.x,client.z),client.width)
            && SLOPE.corridorClear(SLOPE.across(player.posX,player.posZ),player.width), "Native rider bbox touched the handrail or crossed the half seam");
        require(client.y>70.8 && player.boundingBox.minY>70.8 && client.y<81 && player.boundingBox.minY<81,
            "Slope rider fell below the fixture or left its vertical envelope");
        require(!player.capabilities.isFlying, "Slope ride used flight");
        if (current == Stage.SETTLE) {
            require(++phaseTicks<240, "Slope mode/state or real rider never settled");
            if (phaseTicks<12 || clientModeAcknowledged!=phaseIndex || client.phase!=phaseIndex || !client.onGround || !player.onGround) return;
            if (phaseIndex==0) require(clientU<10 && serverU<10 && Math.abs(client.y-71.9375)<.1,
                "Initial slope rider missed the lower flat approach");
            if (phaseIndex==1) require(clientU>19.5 && serverU>19.5, "Reverse ride missed the upper flat approach");
            if (phaseIndex==2) require(SLOPE.onSlope(clientU) && SLOPE.onSlope(serverU), "Stopped rider coasted off the incline");
            assertSlope(world,SLOPE.mode(phaseIndex));
            firstClient=client; firstServerX=player.posX; firstServerZ=player.posZ; firstServerY=player.boundingBox.minY;
            samples=new JsonArray(); sampledClientTicks=new HashSet<>();
            phaseTicks=slopeStallTicks=0; slopeLastProgress=serverU;
            stage=Stage.SAMPLE;
        } else if (current == Stage.SAMPLE) {
            require(client.phase==phaseIndex, "Slope sample belongs to another phase");
            samples.add(observed); sampledClientTicks.add(client.tick);
            phaseTicks++;
            int mode=SLOPE.mode(phaseIndex);
            boolean reached;
            if (mode==2) {
                require(Math.abs(clientU-SLOPE.along(firstClient.x,firstClient.z))<.12
                    && Math.abs(serverU-SLOPE.along(firstServerX,firstServerZ))<.12
                    && Math.abs(client.y-firstClient.y)<.1 && Math.abs(player.boundingBox.minY-firstServerY)<.1,
                    "Stopped original slope continued to transport its rider");
                if (phaseIndex==2) require(SLOPE.onSlope(clientU) && SLOPE.onSlope(serverU), "Stop was not measured on the incline");
                reached=phaseTicks>=SAMPLE_TICKS;
            } else {
                if (Math.abs(serverU-slopeLastProgress)>.05) { slopeLastProgress=serverU; slopeStallTicks=0; }
                else slopeStallTicks++;
                require(slopeStallTicks<80, "Native slope rider stalled; see slope_failure_geometry for actual stepHeight/collision evidence");
                require(phaseTicks<900, "Native slope rider did not reach the phase checkpoint");
                reached=SLOPE.reached(phaseIndex,clientU,serverU);
            }
            if (!reached) return;
            require(sampledClientTicks.size()>=10, "Too few distinct actual slope client ticks");
            require(Math.abs(clientU-serverU)<1.5, "Slope client/server longitudinal positions disagree");
            if (phaseIndex==0) require(Math.abs(client.y-79.9375)<.12 && Math.abs(player.boundingBox.minY-79.9375)<.12,
                "Forward original escalator did not raise the real rider eight blocks");
            if (phaseIndex==3) require(Math.abs(client.y-71.9375)<.12 && Math.abs(player.boundingBox.minY-71.9375)<.12,
                "Reverse original escalator did not return the rider to the lower flat");
            JsonObject result=new JsonObject();
            result.addProperty("phase",SLOPE.label(phaseIndex)); result.addProperty("mode",MODES[mode]);
            result.addProperty("distinct_client_ticks",sampledClientTicks.size());
            result.addProperty("client_delta_u",clientU-SLOPE.along(firstClient.x,firstClient.z));
            result.addProperty("server_delta_u",serverU-SLOPE.along(firstServerX,firstServerZ));
            result.addProperty("client_delta_y",client.y-firstClient.y); result.addProperty("server_delta_y",player.boundingBox.minY-firstServerY);
            result.add("samples",samples); phases.add(result);
            phaseTicks=renderFrames=0; stage=Stage.SCREENSHOT;
        } else if (current == Stage.SCREENSHOT) {
            require(++phaseTicks<240, "Slope framebuffer was not captured");
            if (screenshotAcknowledged!=phaseIndex) return;
            if (phaseIndex<4) {
                stage=Stage.CHANGE_MODE; phaseIndex++; beginSlopePhase(world,player);
            } else {
                assertSlope(world,2); assertBaseline(world); finalPlayer=playerPosition(player); report.add("final_actual_server_player",finalPlayer); completed=true; stage=Stage.CLOSE;
            }
        }
    }

    private void beginSlopePhase(WorldServer world, EntityPlayerMP player) {
        int x=SLOPE.x(4,0),y=SLOPE.y(4),z=SLOPE.z(4,0),mode=SLOPE.mode(phaseIndex);
        for (int attempts=0;!matchesMode(state(world,x,y,z),mode);attempts++) {
            require(attempts<3,"Original slope brush could not select "+MODES[mode]);
            require(world.getBlock(x,y,z).onBlockActivated(world,x,y,z,player,1,.5F,.9F,.5F),"Original slope brush activation rejected");
            brushUses++;
        }
        assertSlope(world,mode);
        phaseTicks=0; clientModeAcknowledged=-1; screenshotAcknowledged=-1; samples=null;
        stage=Stage.SETTLE;
    }

    private static void assertSlope(WorldServer world,int mode) {
        for (int row=0;row<32;row++) for (int half=0;half<2;half++) for (int above=0;above<2;above++) {
            BlockState actual=state(world,SLOPE.x(row,half),SLOPE.y(row)+above,SLOPE.z(row,half));
            require(slopeStateMatches(actual,row,half,above,mode),"Original slope state mismatch at row "+row+",half "+half+",above "+above+": "+actual.data.getSerializedProperties());
        }
    }

    private static boolean slopeStateMatches(BlockState actual,int row,int half,int above,int mode) {
        if (above==0 ? !(actual.getBlock().data instanceof BlockEscalatorStep) : !(actual.getBlock().data instanceof BlockEscalatorSide)) return false;
        Map<String,String> properties=actual.data.getSerializedProperties();
        return SLOPE.facing.equals(properties.get("facing")) && (half==0?"left":"right").equals(properties.get("side"))
            && SLOPE.orientation(row).equals(properties.get("orientation")) && (above!=0 || matchesMode(actual,mode));
    }

    private static boolean slopeClientHasState(Minecraft client,int mode) {
        try {
            org.mtr.mapping.holder.World mapped=new org.mtr.mapping.holder.World(client.theWorld);
            for (int row=0;row<32;row++) for (int half=0;half<2;half++) for (int above=0;above<2;above++)
                if (!slopeStateMatches(mapped.getBlockState(new BlockPos(SLOPE.x(row,half),SLOPE.y(row)+above,SLOPE.z(row,half))),row,half,above,mode)) return false;
            return true;
        } catch (IllegalArgumentException | IllegalStateException notYetSynchronized) { return false; }
    }

    private JsonObject slopeSample(WorldServer world,EntityPlayerMP player,ClientSample client) {
        JsonObject value=client.json();
        value.addProperty("phase",SLOPE.label(phaseIndex)); value.addProperty("server_tick",world.getTotalWorldTime());
        value.addProperty("server_x",player.posX); value.addProperty("server_y",player.boundingBox.minY); value.addProperty("server_z",player.posZ);
        value.addProperty("client_u",SLOPE.along(client.x,client.z)); value.addProperty("server_u",SLOPE.along(player.posX,player.posZ));
        value.addProperty("server_step_height",player.stepHeight); value.addProperty("server_width",player.width);
        value.addProperty("server_horizontal_collision",player.isCollidedHorizontally); value.addProperty("server_on_ground",player.onGround);
        value.addProperty("server_motion_x",player.motionX); value.addProperty("server_motion_y",player.motionY); value.addProperty("server_motion_z",player.motionZ);
        return value;
    }

    private JsonObject slopeGeometry(WorldServer world,EntityPlayerMP player) {
        JsonObject evidence=playerPosition(player);
        evidence.addProperty("phase",SLOPE.label(phaseIndex)); evidence.addProperty("u",SLOPE.along(player.posX,player.posZ));
        evidence.addProperty("forward_box_offset",.125); evidence.addProperty("incline_riser",9.0/16);
        evidence.addProperty("step_height",player.stepHeight); evidence.addProperty("horizontal_collision",player.isCollidedHorizontally);
        evidence.addProperty("on_ground",player.onGround); evidence.add("rider_box",boxJson(player.boundingBox));
        // Read-only translated boxes test the actual native obstacle; the entity itself never moves here.
        net.minecraft.util.AxisAlignedBB nativeStep=player.boundingBox.getOffsetBoundingBox(.125*SLOPE.fx,player.stepHeight+1e-4,.125*SLOPE.fz);
        net.minecraft.util.AxisAlignedBB requiredStep=player.boundingBox.getOffsetBoundingBox(.125*SLOPE.fx,9.0/16+1e-4,.125*SLOPE.fz);
        boolean nativeBlocked=!world.getCollidingBoundingBoxes(player,nativeStep).isEmpty();
        boolean requiredBlocked=!world.getCollidingBoundingBoxes(player,requiredStep).isEmpty();
        evidence.addProperty("native_height_forward_box_blocked",nativeBlocked);
        evidence.addProperty("nine_sixteenths_forward_box_blocked",requiredBlocked);
        evidence.addProperty("stalled_server_ticks",slopeStallTicks);
        evidence.addProperty("step_height_obstacle_observed",phaseIndex==0 && slopeStallTicks>=80 && player.stepHeight<9.0/16
            && player.onGround && nativeBlocked && !requiredBlocked);
        JsonArray collisions=new JsonArray();
        for (Object box:world.getCollidingBoundingBoxes(player,player.boundingBox.expand(.8,.7,.8))) collisions.add(boxJson((net.minecraft.util.AxisAlignedBB)box));
        evidence.add("nearby_native_collision_boxes",collisions);
        JsonArray blocks=new JsonArray();
        int row=Math.max(0,Math.min(30,(int)Math.floor(SLOPE.along(player.posX,player.posZ)+.5)));
        for (int i=Math.max(0,row-1);i<=Math.min(31,row+2);i++) for (int half=0;half<2;half++) {
            blocks.add(placedBlockStatus(world,SLOPE.x(i,half),SLOPE.y(i),SLOPE.z(i,half)));
            blocks.add(placedBlockStatus(world,SLOPE.x(i,half),SLOPE.y(i)+1,SLOPE.z(i,half)));
        }
        evidence.add("nearby_original_blocks",blocks);
        return evidence;
    }

    private static JsonObject boxJson(net.minecraft.util.AxisAlignedBB box) {
        JsonObject value=new JsonObject();
        value.addProperty("min_x",box.minX); value.addProperty("min_y",box.minY); value.addProperty("min_z",box.minZ);
        value.addProperty("max_x",box.maxX); value.addProperty("max_y",box.maxY); value.addProperty("max_z",box.maxZ);
        return value;
    }

    private static JsonObject playerPosition(EntityPlayerMP player) {
        JsonObject value = new JsonObject();
        value.addProperty("x", player.posX); value.addProperty("feet_y", player.boundingBox.minY); value.addProperty("z", player.posZ);
        value.addProperty("managed_x", player.managedPosX); value.addProperty("managed_z", player.managedPosZ);
        return value;
    }

    private static JsonObject chunkStatus(WorldServer world, int x, int z) {
        boolean exists = world.getChunkProvider().chunkExists(x, z);
        // Loaded-only lookup: diagnostic/readiness reads cannot themselves generate a chunk.
        Chunk chunk = (Chunk) world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x, z));
        JsonObject value = new JsonObject();
        value.addProperty("chunk_x", x); value.addProperty("chunk_z", z);
        value.addProperty("chunk_exists", exists);
        value.addProperty("load_on_provide", world.theChunkProviderServer.loadChunkOnProvideRequest);
        value.addProperty("ready", exists && chunk != null && !(chunk instanceof EmptyChunk)
            && chunk.xPosition == x && chunk.zPosition == z && chunk.isChunkLoaded);
        if (chunk != null) {
            value.addProperty("class", chunk.getClass().getName());
            value.addProperty("actual_x", chunk.xPosition); value.addProperty("actual_z", chunk.zPosition);
            value.addProperty("is_chunk_loaded", chunk.isChunkLoaded);
            value.addProperty("terrain_populated", chunk.isTerrainPopulated);
        }
        return value;
    }

    private static boolean allChunksReady(JsonArray chunks) {
        for (int i = 0; i < chunks.size(); i++) if (!chunks.get(i).getAsJsonObject().get("ready").getAsBoolean()) return false;
        return true;
    }

    private static JsonObject placedBlockStatus(WorldServer world, int x, int y, int z) {
        JsonObject value = new JsonObject();
        Block block = world.getBlock(x, y, z);
        value.addProperty("x", x); value.addProperty("y", y); value.addProperty("z", z);
        value.addProperty("block_id", Block.getIdFromBlock(block));
        value.addProperty("block_registry", Block.blockRegistry.getNameForObject(block));
        value.addProperty("block_class", block.getClass().getName());
        value.addProperty("metadata", world.getBlockMetadata(x, y, z));
        value.add("chunk", chunkStatus(world, x >> 4, z >> 4));
        TileEntity tile = world.getTileEntity(x, y, z);
        value.addProperty("tile_present", tile != null);
        if (tile != null) {
            value.addProperty("tile_class", tile.getClass().getName());
            try {
                NBTTagCompound tag = new NBTTagCompound();
                tile.writeToNBT(tag);
                value.addProperty("tile_id", tag.getString("id"));
                value.addProperty("mtr_block_state", tag.getCompoundTag("mtr_block_state").toString());
            } catch (Exception diagnosticFailure) { value.addProperty("tile_diagnostic_error", diagnosticFailure.toString()); }
        }
        return value;
    }

    private static boolean matchesMode(BlockState state, int mode) {
        Map<String, String> values = state.data.getSerializedProperties();
        return Boolean.toString(mode == 0).equals(values.get("direction")) && Boolean.toString(mode != 2).equals(values.get("status"));
    }

    private static BlockState state(WorldServer world, int x, int y, int z) {
        return new org.mtr.mapping.holder.World(world).getBlockState(new BlockPos(x, y, z));
    }

    private static StoredBlock snapshot(WorldServer world, int x, int y, int z) {
        NBTTagCompound tag = new NBTTagCompound();
        world.getTileEntity(x, y, z).writeToNBT(tag);
        require(tag.hasKey("mtr_block_state", 10), "Native escalator has no persistent extended state");
        return new StoredBlock(x, y, z, Block.getIdFromBlock(world.getBlock(x, y, z)), world.getBlockMetadata(x, y, z),
            tag.getCompoundTag("mtr_block_state"), state(world, x, y, z).data.getSerializedProperties());
    }

    private void capture(Minecraft client, String label) throws Exception {
        String name = "mtr-world-probe-" + runId + "-" + label + ".png";
        ScreenShotHelper.saveScreenshot(client.mcDataDir, name, client.displayWidth, client.displayHeight, client.getFramebuffer());
        File image = new File(new File(client.mcDataDir, "screenshots"), name);
        require(image.isFile() && image.length() > 100, "Actual framebuffer screenshot was not written");
        JsonObject shot = new JsonObject();
        shot.addProperty("stage", label);
        shot.addProperty("file", image.getAbsolutePath());
        shot.addProperty("world_rendered", client.theWorld != null);
        screenshots.add(shot);
    }

    private static byte[] savedArray(NBTTagCompound section, String name, int length, boolean optional) {
        if (!section.hasKey(name)) {
            require(optional, "Saved Anvil section is missing required array " + name);
            return new byte[0];
        }
        require(section.hasKey(name, 7), "Saved Anvil field " + name + " is not a byte array");
        byte[] value = section.getByteArray(name);
        require(value.length == length, "Saved Anvil array " + name + " has length " + value.length + "; expected " + length);
        return value;
    }

    private static int nibble(byte[] array, int index) { return (array[index >> 1] >> ((index & 1) * 4)) & 15; }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private void fail(Throwable problem) { if (failure == null) failure = problem; }

    private static final class StoredBlock {
        final int x, y, z, id, metadata;
        final NBTTagCompound state;
        final Map<String, String> properties;
        StoredBlock(int x, int y, int z, int id, int metadata, NBTTagCompound state, Map<String, String> properties) {
            this.x = x; this.y = y; this.z = z; this.id = id; this.metadata = metadata;
            this.state = (NBTTagCompound) state.copy(); this.properties = properties;
        }
    }

    private static JsonArray standChunkStatus(WorldServer world) {
        JsonArray chunks=new JsonArray();
        for(int x=SLOPE.minX()>>4;x<=SLOPE.maxX()>>4;x++)for(int z=SLOPE.minZ()>>4;z<=SLOPE.maxZ()>>4;z++)chunks.add(chunkStatus(world,x,z));
        return chunks;
    }
    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage != Stage.SCREENSHOT || failure != null) return;
        try {
            Minecraft client = Minecraft.getMinecraft();
            require(client.theWorld != null && client.thePlayer != null && client.currentScreen == null, "World is not rendered");
            if (++renderFrames < 4 || screenshotAcknowledged == phaseIndex) return;
            capture(client, SLOPE.label(phaseIndex));
            screenshotAcknowledged = phaseIndex;
        } catch (Throwable problem) { fail(problem); }
    }

    private void verifySavedAnvil() throws Exception {
        require(saveDirectory != null && new File(saveDirectory, "level.dat").isFile(), "Exact NORTH clone was not saved");
        Path marker = new File(saveDirectory, "mtr-world-probe-owner.txt").toPath();
        require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            && ACCEPTED.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)), "Probe save ownership marker changed");
        require(expectedSavedBlocks.size() == 128, "No complete native saved-state expectation");
        File regionPath = new File(saveDirectory, "region/r.0.0.mca");
        require(regionPath.isFile(), "Actual Anvil region file is missing");
        RegionFile region = new RegionFile(regionPath);
        JsonArray persisted = new JsonArray();
        try {
            for (StoredBlock expected : expectedSavedBlocks) {
                NBTTagCompound level;
                try (DataInputStream input = region.getChunkDataInputStream(expected.x >> 4, expected.z >> 4)) {
                    require(input != null, "Actual saved stand chunk is missing");
                    level = CompressedStreamTools.read(input).getCompoundTag("Level");
                }
                int slot = ((expected.y & 15) << 8) | ((expected.z & 15) << 4) | (expected.x & 15);
                NBTTagCompound section = null;
                NBTTagList sections = level.getTagList("Sections", 10);
                for (int index = 0; index < sections.tagCount(); index++) {
                    NBTTagCompound candidate = sections.getCompoundTagAt(index);
                    if ((candidate.getByte("Y") & 255) == expected.y >> 4) section = candidate;
                }
                require(section != null, "Actual saved section is missing");
                // Installed EndlessIDs 1.7.4 BlockIDManager writes B1, B2Low, B2High, B3.
                // Match its native storage's eid$getID, including optional bits above vanilla's 12.
                byte[] low = savedArray(section, "Blocks", 4096, false), high = savedArray(section, "Add", 2048, true);
                byte[] higher = savedArray(section, "BlocksB2Hi", 2048, true), highest = savedArray(section, "BlocksB3", 4096, true);
                int blockId = (low[slot] & 255) | (high.length == 0 ? 0 : nibble(high, slot) << 8)
                    | (higher.length == 0 ? 0 : nibble(higher, slot) << 12)
                    | (highest.length == 0 ? 0 : (highest[slot] & 255) << 16);
                byte[] data = savedArray(section, "Data", 2048, false), dataHigh = savedArray(section, "Data1High", 2048, true);
                byte[] dataHighest = savedArray(section, "Data2", 4096, true);
                int metadata = nibble(data, slot) | (dataHigh.length == 0 ? 0 : nibble(dataHigh, slot) << 4)
                    | (dataHighest.length == 0 ? 0 : (dataHighest[slot] & 255) << 8);
                require(blockId == expected.id && metadata == expected.metadata, "Saved native block ID/metadata changed");
                NBTTagCompound tile = null;
                NBTTagList tiles = level.getTagList("TileEntities", 10);
                for (int index = 0; index < tiles.tagCount(); index++) {
                    NBTTagCompound candidate = tiles.getCompoundTagAt(index);
                    if (candidate.getInteger("x") == expected.x && candidate.getInteger("y") == expected.y && candidate.getInteger("z") == expected.z) tile = candidate;
                }
                require(tile != null && expected.state.equals(tile.getCompoundTag("mtr_block_state")), "Saved extended escalator NBT differs from the real world");
                TileEntity restored = TileEntity.createAndLoadEntity(tile);
                require(restored instanceof BlockStateTileEntity, "Saved native TileEntity registry ID cannot restore MTR state");
                Map<String, String> decoded = ((BlockStateTileEntity) restored)
                    .readMtrState(BlockStateDefinition.forBlock(Block.getBlockById(blockId)), metadata).data.getSerializedProperties();
                require(expected.properties.equals(decoded), "Actual Anvil state does not decode to original escalator properties");
                JsonObject value = new JsonObject(), properties = new JsonObject();
                value.addProperty("x", expected.x); value.addProperty("y", expected.y); value.addProperty("z", expected.z);
                value.addProperty("block", Block.blockRegistry.getNameForObject(Block.getBlockById(blockId)));
                value.addProperty("metadata", metadata);
                for (Map.Entry<String, String> property : decoded.entrySet()) properties.addProperty(property.getKey(), property.getValue());
                value.add("properties", properties);
                persisted.add(value);
            }
        } finally { region.close(); }
        report.addProperty("saved_native_blocks_verified", persisted.size());
        report.addProperty("save_reopened", true);
        report.add("persisted_blocks", persisted);
    }

    private static final class ClientSample {
        final int tick, phase;
        final double x, y, z;
        final boolean onGround;
        final float stepHeight, width;
        final boolean horizontalCollision,inputZero;
        final int inputTicks,inputViolations;
        ClientSample(int tick, int phase, double x, double y, double z, boolean onGround, float stepHeight, float width, boolean horizontalCollision, boolean inputZero, int inputTicks, int inputViolations) {
            this.tick = tick; this.phase = phase; this.x = x; this.y = y; this.z = z; this.onGround = onGround;
            this.stepHeight = stepHeight; this.width = width; this.horizontalCollision = horizontalCollision;this.inputZero=inputZero;this.inputTicks=inputTicks;this.inputViolations=inputViolations;
        }
        JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("client_tick", tick); value.addProperty("client_x", x); value.addProperty("client_y", y);
            value.addProperty("client_z", z); value.addProperty("client_on_ground", onGround);
            value.addProperty("client_step_height", stepHeight); value.addProperty("client_width", width);
            value.addProperty("client_horizontal_collision", horizontalCollision);value.addProperty("input_zero",inputZero);value.addProperty("input_ticks",inputTicks);value.addProperty("input_violations",inputViolations);
            return value;
        }
    }
}
