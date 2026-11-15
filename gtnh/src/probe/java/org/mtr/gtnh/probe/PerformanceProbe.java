package org.mtr.gtnh.probe;

import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.*;
import com.google.gson.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.opengl.Display;
import java.io.File;
import java.lang.management.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Same standalone test mod in both runs. MTR is optional and accessed only after its presence is checked. */
@SideOnly(Side.CLIENT)
@Mod(modid="gtnh_performance_probe",name="GTNH isolated performance probe",version="0",acceptableRemoteVersions="*")
public final class PerformanceProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT=new File(requiredProbePath("mtr.probeClient"));
    private static final long WARM_NS=20_000_000_000L,MEASURE_NS=60_000_000_000L,SOAK_NS=180_000_000_000L;
    private static final int[] LIFT_X={8,20,32},FLOORS={4,12,20};
    private enum Stage {MENU,WORLD,SETUP,WARM,MEASURE,BUILD,WAIT_LAYOUT,WAIT_SERVER_EXIT,REOPEN,REOPEN_CHECK,CLOSE,FINISHED}
    private volatile Stage stage=Stage.MENU;
    private volatile Throwable failure;
    private volatile Window window;
    private volatile boolean sceneReady,layoutBuilt,setupTeleported;
    private boolean owned,mtr,layout,completed,oldVsync,oldPause,benchmarkControlsApplied,neiReloadSeen,soak;
    private Field neiPluginLoaded,neiPendingReload;
    private int startupReadyTicks,epoch;
    private final Set<Long> savedLiftIds=new HashSet<>();
    private final Map<Long,Double> reopenedLiftProgress=new HashMap<>();
    private int menuTicks,oldCap,oldDistance,oldThird,clientTicks,placements,buttonCalls,visiblePeak;
    private long initNs,launchedNs,worldReadyNs,phaseNs,lastMemoryNs,lastCallNs,lastLogNs;
    private long serverStartNs,lastServerEndNs,lastRenderNs;
    private volatile Thread observedServerThread;
    private volatile MinecraftServer observedServer;
    private Thread closingServerThread;
    private MinecraftServer closingServer;
    private int callFloor=2;
    private UUID playerId;
    private String runId,saveName;
    private File saveDirectory;
    private final JsonObject report=new JsonObject();
    private final JsonArray windows=new JsonArray(),memory=new JsonArray(),landingSetup=new JsonArray();
    private final Map<Long,double[]> liftProgress=new LinkedHashMap<>();

    @Mod.EventHandler public void init(FMLInitializationEvent event){
        initNs=phaseNs=System.nanoTime();mtr=Loader.isModLoaded("mtr");
        require(!Loader.isModLoaded("mtr_gtnh_train_probe")&&!Loader.isModLoaded("mtr_gtnh_world_probe")&&!Loader.isModLoaded("mtr_gtnh_lift_probe")&&!Loader.isModLoaded("mtr_gtnh_cable_probe")&&!Loader.isModLoaded("mtr_gtnh_boat_probe"),"Select performance probe alone");
        report.addProperty("schema","gtnh-native-performance-v1");report.addProperty("mtr_loaded",mtr);
        report.addProperty("jvm_uptime_at_probe_init_ms",ManagementFactory.getRuntimeMXBean().getUptime());
        report.addProperty("java_version",System.getProperty("java.version"));report.addProperty("os_arch",System.getProperty("os.arch"));
        report.addProperty("processors",Runtime.getRuntime().availableProcessors());
        JsonArray mods=new JsonArray();List<String> list=new ArrayList<>();for(ModContainer mod:Loader.instance().getModList())list.add(mod.getModId()+"="+mod.getVersion());Collections.sort(list);for(String mod:list)mods.add(new JsonPrimitive(mod));report.add("loaded_mods",mods);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();long now=System.nanoTime();
        try{
            if(failure!=null||stage==Stage.CLOSE){finish(client);return;}
            require(now-initNs<12*60_000_000_000L,"Performance total deadline: "+stage);
            if(stage==Stage.MENU){
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.getIntegratedServer()!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;reserve(client);report.addProperty("jvm_uptime_at_ready_menu_ms",ManagementFactory.getRuntimeMXBean().getUptime());
                oldCap=client.gameSettings.limitFramerate;oldVsync=client.gameSettings.enableVsync;oldPause=client.gameSettings.pauseOnLostFocus;oldDistance=client.gameSettings.renderDistanceChunks;oldThird=client.gameSettings.thirdPersonView;
                require(!oldPause,"Background measurement requires the copied client pause-on-lost-focus disabled");report.addProperty("measurement_mode","controlled_background");report.addProperty("foreground_fps_claim",false);report.addProperty("original_max_fps",oldCap);report.addProperty("requested_max_fps",260);report.addProperty("requested_vsync",false);report.addProperty("settings_saved_by_probe",false);
                launchedNs=now;stage=Stage.WORLD;
                WorldSettings settings=new WorldSettings(0x504552464C41544CL,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT).enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName,"GTNH performance "+runId,settings);return;
            }
            if(stage==Stage.WORLD){
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                assertOwned(client.getIntegratedServer());playerId=client.thePlayer.getUniqueID();worldReadyNs=now;report.addProperty("launch_to_world_player_ms",(now-launchedNs)/1E6);report.addProperty("player_uuid",playerId.toString());stage=Stage.SETUP;phaseNs=now;return;
            }
            if(stage==Stage.WAIT_SERVER_EXIT){
                require(now-phaseNs<30_000_000_000L,"Old owned server thread did not exit before reload");
                require(client.theWorld==null&&client.getIntegratedServer()==null,"Old native client world remained attached");
                if(closingServerThread.isAlive())return;
                closingServerThread.join(1);
                require(closingServerThread.getState()==Thread.State.TERMINATED&&closingServer.isServerStopped(),"Old server teardown has not completed");
                require(MinecraftServer.getServer()==null||MinecraftServer.getServer()==closingServer,"Foreign server appeared during owned reload");
                report.addProperty("old_server_thread_terminated_before_reopen",true);report.addProperty("old_server_thread_state",closingServerThread.getState().name());
                report.addProperty("old_server_close_to_thread_exit_ms",(now-phaseNs)/1E6);
                closingServerThread=null;closingServer=null;stage=Stage.REOPEN;phaseNs=now;
                client.launchIntegratedServer(saveName,"GTNH owned performance reload "+runId,null);return;
            }
            if(stage==Stage.REOPEN||stage==Stage.REOPEN_CHECK){
                require(now-phaseNs<90_000_000_000L,"Owned-world reopen deadline");
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                assertOwned(client.getIntegratedServer());require(playerId.equals(client.thePlayer.getUniqueID()),"Reloaded player UUID changed");
                if(client.theWorld.getBlock(2,stepY(0),0)!=net.minecraft.init.Blocks.stone)return;
                if(mtr){
                    Set<Long> currentIds=new HashSet<>();for(Object lift:(Iterable<?>)field(clientData(),"lifts"))currentIds.add(((Number)lift.getClass().getMethod("getId").invoke(lift)).longValue());
                    if(!savedLiftIds.equals(currentIds))return;
                    if(!landingReady(client,"reopen_landing_readiness"))return;
                    if(stage==Stage.REOPEN){
                        for(Object lift:(Iterable<?>)field(clientData(),"lifts"))reopenedLiftProgress.put(((Number)lift.getClass().getMethod("getId").invoke(lift)).longValue(),((Number)field(lift,"railProgress")).doubleValue());
                        for(int x:LIFT_X)for(int y:new int[]{FLOORS[0],FLOORS[2]})pressLanding(client,x+1,y,"Reloaded original lift call rejected");
                        stage=Stage.REOPEN_CHECK;return;
                    }
                    boolean allMoved=true;for(Object lift:(Iterable<?>)field(clientData(),"lifts")){long id=((Number)lift.getClass().getMethod("getId").invoke(lift)).longValue();allMoved&=Math.abs(((Number)field(lift,"railProgress")).doubleValue()-reopenedLiftProgress.get(id))>.25;}
                    if(!allMoved)return;report.addProperty("reloaded_all_three_lifts_moved_from_original_calls",true);
                }
                epoch=1;observeMemory(now);report.addProperty("owned_world_reopened_and_verified",true);report.addProperty("reload_elapsed_ms",(now-phaseNs)/1E6);completed=true;stage=Stage.CLOSE;return;
            }
            assertOwned(client.getIntegratedServer());require(client.theWorld!=null&&client.thePlayer!=null,"Owned performance client disappeared");
            if(client.currentScreen!=null)client.displayGuiScreen(null);KeyBinding.unPressAllKeys();++clientTicks;
            if(now-lastLogNs>10_000_000_000L){lastLogNs=now;System.out.println("GTNH_PERFORMANCE_PROBE_PROGRESS "+stage+" layout="+layout+" active="+Display.isActive());}
            if(stage!=Stage.SETUP){require(benchmarkControlsApplied,"Benchmark controls not applied");assertBenchmarkSettings(client,now);}
            if(stage==Stage.SETUP){
                boolean optionsReady=neiOptionsReady();
                if(sceneReady&&cameraReady(client)&&!report.has("launch_to_common_scene_ready_ms"))report.addProperty("launch_to_common_scene_ready_ms",(now-launchedNs)/1E6);
                if(!sceneReady||!cameraReady(client)||!optionsReady||Display.isActive()||!Display.isVisible()||client.isGamePaused()){startupReadyTicks=0;return;}
                if(++startupReadyTicks<2)return;
                require(!benchmarkControlsApplied,"Benchmark controls applied twice");report.add("benchmark_settings_before_one_time_apply",benchmarkSettings(client,now));
                client.gameSettings.limitFramerate=260;client.gameSettings.enableVsync=false;client.gameSettings.pauseOnLostFocus=false;client.gameSettings.renderDistanceChunks=6;client.gameSettings.thirdPersonView=0;Display.setVSyncEnabled(false);
                benchmarkControlsApplied=true;report.addProperty("benchmark_controls_apply_count",1);report.addProperty("benchmark_startup_ready_client_ticks",startupReadyTicks);report.addProperty("launch_to_benchmark_controls_applied_ms",(now-launchedNs)/1E6);
                assertBenchmarkSettings(client,now);stage=Stage.WARM;phaseNs=now;
            }
            if(stage==Stage.WAIT_LAYOUT){
                require(now-phaseNs<90_000_000_000L,"Original lifts did not reach client");
                if(!layoutBuilt||liftCount()<3||!cameraReady(client)||client.thePlayer.getHeldItem()!=null||!landingReady(client,"layout_landing_readiness"))return;
                observeLifts();stage=Stage.WARM;phaseNs=now;lastCallNs=0;
            }
            if((stage==Stage.WARM||stage==Stage.MEASURE)&&layout&&now-lastCallNs>=10_000_000_000L){
                lastCallNs=now;for(int x:LIFT_X){int y=FLOORS[callFloor];pressLanding(client,x+1,y,"Original landing call rejected");buttonCalls++;}callFloor=callFloor==2?0:2;
            }
            if(stage==Stage.WARM&&now-phaseNs>=WARM_NS){
                observeMemory(now);window=new Window(layout?(soak?"mtr_layout_soak":"mtr_layout"):"common_flat",now,now+(soak?SOAK_NS:MEASURE_NS));lastRenderNs=lastServerEndNs=0;stage=Stage.MEASURE;
            }
            if(stage==Stage.MEASURE){
                require(cameraReady(client),"Benchmark camera moved");
                if(now-lastMemoryNs>=1_000_000_000L){observeMemory(now);if(layout)observeLifts();}
                Window current=window;if(now>=current.end){window=null;windows.add(current.json());
                    if(mtr&&!layout){layout=true;stage=Stage.BUILD;phaseNs=now;}
                    else if(mtr&&!soak){soak=true;stage=Stage.WARM;phaseNs=now;}
                    else {
                        savedLiftIds.addAll(liftProgress.keySet());closingServer=client.getIntegratedServer();assertOwned(closingServer);closingServerThread=observedServerThread;
                        require(observedServer==closingServer&&closingServerThread!=null&&closingServerThread!=Thread.currentThread()&&closingServerThread.isAlive(),"Original owned server thread receipt missing");
                        // isServerStopped becomes true before FML/GTNH stop callbacks finish; only thread exit orders the next launch.
                        stage=Stage.WAIT_SERVER_EXIT;phaseNs=System.nanoTime();
                        client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);require(client.theWorld==null&&client.getIntegratedServer()==null,"First native world close incomplete");
                        report.addProperty("first_native_save_close_complete",true);return;
                    }
                }
            }
        }catch(Throwable problem){fail(problem);}
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event){
        long now=System.nanoTime();if(event.phase==TickEvent.Phase.START){serverStartNs=now;return;}
        if(stage==Stage.FINISHED||stage==Stage.MENU||stage==Stage.WORLD||stage==Stage.WAIT_SERVER_EXIT||stage==Stage.CLOSE||failure!=null)return;
        try{
            MinecraftServer server=MinecraftServer.getServer();assertOwned(server);observedServer=server;observedServerThread=Thread.currentThread();WorldServer world=server.worldServerForDimension(0);EntityPlayerMP player=player(server);if(player==null)return;
            if(stage==Stage.SETUP){
                require(now-phaseNs<90_000_000_000L,"Performance scene chunks did not load");
                if(!setupTeleported){player.setGameType(WorldSettings.GameType.CREATIVE);player.capabilities.isFlying=true;player.sendPlayerAbilities();player.playerNetServerHandler.setPlayerLocation(16.5,18,-12.5,0,0);setupTeleported=true;return;}
                if(!sceneReady&&chunksReady(world)){long begin=System.nanoTime();buildCommon(world);report.addProperty("common_scene_build_ms",(System.nanoTime()-begin)/1E6);sceneReady=true;}return;
            }
            if(stage==Stage.BUILD){long begin=System.nanoTime();buildLayout(world,player);report.addProperty("mtr_layout_build_ms",(System.nanoTime()-begin)/1E6);layoutBuilt=true;stage=Stage.WAIT_LAYOUT;phaseNs=System.nanoTime();return;}
            Window current=window;
            if(current!=null&&now>=current.start&&now<current.end){if(lastServerEndNs>=current.start)current.serverIntervals.add(now-lastServerEndNs);if(serverStartNs>=current.start)current.serverEventSpans.add(now-serverStartNs);lastServerEndNs=now;}
        }catch(Throwable problem){fail(problem);}
    }

    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event){
        if(event.phase!=TickEvent.Phase.END)return;long now=System.nanoTime();Window current=window;
        if(current==null||now<current.start||now>=current.end)return;
        Minecraft client=Minecraft.getMinecraft();if(client.theWorld==null||client.thePlayer==null)return;
        if(lastRenderNs>=current.start){long elapsed=now-lastRenderNs;current.renderIntervals.add(elapsed);if(Display.isActive()&&Display.isVisible()&&!client.isGamePaused())current.focusedIntervals.add(elapsed);else current.backgroundFrames++;if(!Display.isVisible()||client.isGamePaused())current.ineligibleFrames++;}
        lastRenderNs=now;if(current.width!=0&&(current.width!=client.displayWidth||current.height!=client.displayHeight))current.resized=true;current.width=client.displayWidth;current.height=client.displayHeight;
    }

    private boolean neiOptionsReady() throws ReflectiveOperationException {
        if(neiPluginLoaded==null){
            ModContainer nei=Loader.instance().getIndexedModList().get("NotEnoughItems");require(nei!=null&&"2.8.155-GTNH".equals(nei.getVersion()),"Unexpected NEI readiness contract");
            neiPluginLoaded=Class.forName("codechicken.nei.NEIClientConfig").getDeclaredField("pluginNEIConfigLoaded");neiPluginLoaded.setAccessible(true);
            neiPendingReload=Class.forName("codechicken.nei.KeyManager").getDeclaredField("pendingOptionsReload");neiPendingReload.setAccessible(true);
            report.addProperty("nei_options_gate_version",nei.getVersion());report.addProperty("nei_options_gate_contract","pluginNEIConfigLoaded && !pendingOptionsReload, two consecutive visible background client END ticks before one-time controls");
        }
        boolean loaded=neiPluginLoaded.getBoolean(null),pending=neiPendingReload.getBoolean(null);neiReloadSeen|=pending;
        report.addProperty("nei_plugin_config_loaded",loaded);report.addProperty("nei_pending_options_reload",pending);report.addProperty("nei_pending_options_reload_observed",neiReloadSeen);report.addProperty("nei_observed_pending_reload_cleared",neiReloadSeen&&!pending);
        return loaded&&!pending;
    }
    private JsonObject benchmarkSettings(Minecraft client,long now){
        JsonObject actual=new JsonObject();actual.addProperty("phase",stage.name());actual.addProperty("at_ms",(now-initNs)/1E6);actual.addProperty("phase_ms",(now-phaseNs)/1E6);
        actual.addProperty("max_fps",client.gameSettings.limitFramerate);actual.addProperty("vsync",client.gameSettings.enableVsync);actual.addProperty("pause_on_lost_focus",client.gameSettings.pauseOnLostFocus);actual.addProperty("render_distance_chunks",client.gameSettings.renderDistanceChunks);actual.addProperty("third_person_view",client.gameSettings.thirdPersonView);
        actual.addProperty("display_active",Display.isActive());actual.addProperty("display_visible",Display.isVisible());actual.addProperty("native_focus",client.inGameHasFocus);actual.addProperty("game_paused",client.isGamePaused());actual.addProperty("screen",client.currentScreen==null?"none":client.currentScreen.getClass().getName());return actual;
    }
    private void assertBenchmarkSettings(Minecraft client,long now){
        if(!(client.gameSettings.limitFramerate==260&&!client.gameSettings.enableVsync&&!client.gameSettings.pauseOnLostFocus&&client.gameSettings.renderDistanceChunks==6)){
            JsonObject actual=benchmarkSettings(client,now);report.add("benchmark_settings_mismatch",actual);throw new AssertionError("Benchmark settings changed: "+actual);
        }
    }

    private static boolean cameraReady(Minecraft client){return Math.abs(client.thePlayer.posX-16.5)<.1&&Math.abs(client.thePlayer.boundingBox.minY-18)<.1&&Math.abs(client.thePlayer.posZ+12.5)<.1&&Math.abs(client.thePlayer.rotationYaw%360)<.1&&Math.abs(client.thePlayer.rotationPitch)<.1&&client.thePlayer.capabilities.isFlying;}
    private static boolean chunksReady(WorldServer world){for(int x=-1;x<=3;x++)for(int z=-1;z<=4;z++){Chunk chunk=(Chunk)world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x,z));if(chunk==null||!chunk.isChunkLoaded||chunk.xPosition!=x||chunk.zPosition!=z)return false;}return true;}
    private static int stepY(int row){return row<=11?4:row<=51?row-7:44;}
    private void buildCommon(WorldServer world){
        world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.getGameRules().setOrCreateGameRule("doMobSpawning","false");world.setWorldTime(6000);world.difficultySetting=EnumDifficulty.PEACEFUL;
        // These identical vanilla supports are present in both common-flat runs before measurement.
        for(int row=0;row<64;row++)world.setBlock(2,stepY(row),row,net.minecraft.init.Blocks.stone,0,3);
        for(int x:LIFT_X){world.setBlock(x,3,24,net.minecraft.init.Blocks.stone,0,3);for(int y:FLOORS)for(int dx=-2;dx<=2;dx++)for(int z=18;z<=21;z++)world.setBlock(x+dx,y-1,z,net.minecraft.init.Blocks.stone,0,3);}
        report.addProperty("scene_seed_hex","504552464c41544c");report.addProperty("render_distance_chunks",6);report.addProperty("camera","16.5,18,-12.5 yaw=0 pitch=0 creative_flying");report.addProperty("common_supports_identical",true);
    }
    private void buildLayout(WorldServer world,EntityPlayerMP player)throws Exception{
        require(mtr&&sceneReady&&chunksReady(world),"MTR layout outside ready owned scene");player.inventory.currentItem=0;player.rotationYaw=0;
        for(int row=0;row<64;row++){ItemStack stack=new ItemStack(item("escalator"));use(world,player,stack,2,stepY(row),row,4,0,.5F,.5F);require(stack.stackSize==0,"Original escalator item was not consumed");}
        for(int x:LIFT_X){
            player.rotationYaw=180;
            for(int y=4;y<=20;y++){boolean floor=y==4||y==12||y==20;use(world,player,new ItemStack(item(floor?"lift_track_floor_1":"lift_track_1")),x,y-1,24,1,.5F,1,.5F);}
            player.rotationYaw=0;
            for(int y:FLOORS){use(world,player,new ItemStack(item("lift_door_1")),x,y-1,21,1,.5F,1,.5F);use(world,player,new ItemStack(item("lift_buttons_1")),x+1,y-1,21,1,.5F,1,.5F);}
            use(world,player,new ItemStack(item("lift_refresher")),x,4,24,1,.5F,.5F,.5F);
            ItemStack connector=new ItemStack(item("lift_buttons_link_connector"));
            for(int y:FLOORS){
                use(world,player,connector,x,y,24,1,.5F,.5F,.5F);use(world,player,connector,x+1,y,21,2,.5F,.5F,.5F);
                JsonObject trace=new JsonObject();trace.add("after_connector",landingStatus(world,x+1,y));landingSetup.add(trace);report.add("original_landing_setup",landingSetup);
                require(linkedFloor(world,x+1,y),"Original connector did not create the exact floor link: "+trace);
                player.inventory.mainInventory[0]=new ItemStack(item("brush"));
                for(int dx=0;dx<=1;dx++){
                    JsonObject brush=new JsonObject();trace.add("unlock_"+dx,brush);brush.add("before",landingStatus(world,x+dx,y));
                    boolean toggle=!"true".equals(originalState(world,x+dx,y,21).get("unlocked"));brush.addProperty("original_brush_needed",toggle);
                    if(toggle)require(world.getBlock(x+dx,y,21).onBlockActivated(world,x+dx,y,21,player,2,.5F,.7F,.5F),"Original lift unlock brush rejected: "+trace);
                    brush.add("after",landingStatus(world,x+dx,y));
                    require("true".equals(originalState(world,x+dx,y,21).get("unlocked")),"Original landing block remains locked: "+trace);
                    require(linkedFloor(world,x+1,y),"Original floor link was lost during landing state change: "+trace);
                }
                if(x==LIFT_X[0]&&y==FLOORS[0]){
                    Object linkedTile=world.getTileEntity(x+1,y,21);JsonArray cycle=new JsonArray();trace.add("representative_original_brush_lock_unlock",cycle);
                    for(boolean unlocked:new boolean[]{false,true}){
                        JsonObject step=new JsonObject();step.addProperty("expected_unlocked",unlocked);step.add("before",landingStatus(world,x+1,y));cycle.add(step);
                        boolean accepted=world.getBlock(x+1,y,21).onBlockActivated(world,x+1,y,21,player,2,.5F,.7F,.5F);
                        step.addProperty("original_brush_accepted",accepted);step.add("after",landingStatus(world,x+1,y));step.addProperty("same_actual_tile",linkedTile==world.getTileEntity(x+1,y,21));
                        require(accepted&&linkedTile==world.getTileEntity(x+1,y,21)&&linkedFloor(world,x+1,y)
                            &&Boolean.toString(unlocked).equals(originalState(world,x+1,y,21).get("unlocked")),"Original brush cycle lost linked tile/state: "+trace);
                    }
                    report.addProperty("original_brush_lock_unlock_preserved_actual_tile_and_links",true);
                }
            }
        }
        for(int row=0;row<64;row++)for(int x=0;x<=1;x++)for(int dy=0;dy<=1;dy++)require(String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(x,stepY(row)+dy,row))).startsWith("mtr:escalator"),"Original long escalator block missing");
        player.rotationYaw=0;player.inventory.mainInventory[0]=null;player.inventoryContainer.detectAndSendChanges();
        report.addProperty("original_escalator_rows",64);report.addProperty("original_lifts",3);report.addProperty("floors_per_lift",3);report.addProperty("original_item_use_count",placements);
    }
    private static Item item(String path){Item result=(Item)Item.itemRegistry.getObject("mtr:"+path);require(result!=null,"Original registry item missing: "+path);return result;}
    private void use(WorldServer world,EntityPlayerMP player,ItemStack stack,int x,int y,int z,int side,float hitX,float hitY,float hitZ){player.inventory.mainInventory[0]=stack;require(stack.getItem().onItemUse(stack,player,world,x,y,z,side,hitX,hitY,hitZ),"Original one-shot item rejected at "+x+","+y+","+z);placements++;}

    private static Map<?,?> originalState(net.minecraft.world.World world,int x,int y,int z)throws Exception{
        Class<?> posType=Class.forName("org.mtr.mapping.holder.BlockPos"),worldType=Class.forName("org.mtr.mapping.holder.World");
        Object pos=posType.getConstructor(int.class,int.class,int.class).newInstance(x,y,z),mapped=worldType.getConstructor(net.minecraft.world.World.class).newInstance(world);
        Object state=worldType.getMethod("getBlockState",posType).invoke(mapped,pos),data=field(state,"data");
        return (Map<?,?>)data.getClass().getMethod("getSerializedProperties").invoke(data);
    }
    private static boolean linkedFloor(net.minecraft.world.World world,int buttonX,int y)throws Exception{
        Object tile=world.getTileEntity(buttonX,y,21);
        if(tile==null||!tile.getClass().getName().equals("org.mtr.mod.block.BlockLiftButtons$BlockEntity"))return false;
        List<Object> positions=new ArrayList<>();java.util.function.Consumer<Object> collect=positions::add;
        tile.getClass().getMethod("forEachTrackPosition",java.util.function.Consumer.class).invoke(tile,collect);
        Object expected=Class.forName("org.mtr.mapping.holder.BlockPos").getConstructor(int.class,int.class,int.class).newInstance(buttonX-1,y,24);
        return positions.size()==1&&expected.equals(positions.get(0));
    }
    private static JsonObject landingStatus(net.minecraft.world.World world,int x,int y){
        JsonObject value=new JsonObject();value.addProperty("x",x);value.addProperty("y",y);value.addProperty("z",21);
        Block block=world.getBlock(x,y,21);value.addProperty("block_registry",String.valueOf(Block.blockRegistry.getNameForObject(block)));value.addProperty("block_class",block.getClass().getName());value.addProperty("metadata",world.getBlockMetadata(x,y,21));
        net.minecraft.tileentity.TileEntity tile=world.getTileEntity(x,y,21);
        if(tile!=null){value.addProperty("tile_class",tile.getClass().getName());value.addProperty("tile_identity",System.identityHashCode(tile));try{net.minecraft.nbt.NBTTagCompound tag=new net.minecraft.nbt.NBTTagCompound();tile.writeToNBT(tag);value.addProperty("native_nbt",tag.toString());}catch(RuntimeException waiting){value.addProperty("native_nbt_error",waiting.toString());}}
        try{JsonObject state=new JsonObject();for(Map.Entry<?,?> entry:originalState(world,x,y,21).entrySet())state.addProperty(String.valueOf(entry.getKey()),String.valueOf(entry.getValue()));value.add("original_state",state);value.addProperty("unlocked","true".equals(originalState(world,x,y,21).get("unlocked")));value.addProperty("exact_floor_link",linkedFloor(world,x,y));}
        catch(Exception waiting){value.addProperty("state_error",waiting.toString());}
        return value;
    }
    private boolean landingReady(Minecraft client,String key){
        JsonArray pending=new JsonArray();
        for(int x:LIFT_X)for(int y:FLOORS)for(int dx=0;dx<=1;dx++){
            JsonObject value=landingStatus(client.theWorld,x+dx,y);String expected=dx==0?"mtr:lift_door_1":"mtr:lift_buttons_1";
            if(!expected.equals(value.get("block_registry").getAsString())||value.has("native_nbt_error")||!value.has("unlocked")||!value.get("unlocked").getAsBoolean()||dx==1&&(!value.has("exact_floor_link")||!value.get("exact_floor_link").getAsBoolean()))pending.add(value);
        }
        JsonObject receipt=new JsonObject();receipt.addProperty("ready",pending.size()==0);receipt.add("pending",pending);report.add(key,receipt);return pending.size()==0;
    }
    private void pressLanding(Minecraft client,int x,int y,String message){
        JsonObject value=landingStatus(client.theWorld,x,y);value.addProperty("native_side",2);value.addProperty("native_hit_y",.375);value.addProperty("empty_hand",client.thePlayer.getHeldItem()==null);report.add("last_original_landing_call",value);
        require(client.thePlayer.getHeldItem()==null,"Original landing call requires empty hand: "+value);
        boolean accepted=client.theWorld.getBlock(x,y,21).onBlockActivated(client.theWorld,x,y,21,client.thePlayer,2,.5F,.375F,.5F);value.addProperty("accepted",accepted);require(accepted,message+": "+value);
    }

    // No MTR type, annotation dependency, DTO or shaded library is linked by the baseline class.
    private static Object field(Object value,String name)throws Exception{for(Class<?> type=value.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(value);}catch(NoSuchFieldException next){}throw new NoSuchFieldException(name);}
    private static Object clientData()throws Exception{return Class.forName("org.mtr.mod.client.MinecraftClientData").getMethod("getInstance").invoke(null);}
    private static int liftCount()throws Exception{int count=0;for(Object lift:(Iterable<?>)field(clientData(),"lifts"))count++;return count;}
    private void observeLifts()throws Exception{
        Object data=clientData();int visible=0;Object wrappers=field(data,"liftWrapperList");if(wrappers instanceof Map)for(Object wrapper:((Map<?,?>)wrappers).values())if(Boolean.TRUE.equals(field(wrapper,"shouldRender")))visible++;
        visiblePeak=Math.max(visiblePeak,visible);
        for(Object lift:(Iterable<?>)field(data,"lifts")){long id=((Number)lift.getClass().getMethod("getId").invoke(lift)).longValue();double progress=((Number)field(lift,"railProgress")).doubleValue();double[] range=liftProgress.get(id);if(range==null)liftProgress.put(id,new double[]{progress,progress});else{range[0]=Math.min(range[0],progress);range[1]=Math.max(range[1],progress);}}
    }
    private void observeMemory(long now){lastMemoryNs=now;Runtime runtime=Runtime.getRuntime();JsonObject value=new JsonObject();value.addProperty("at_ms",(now-initNs)/1E6);value.addProperty("layout",layout);value.addProperty("epoch",epoch);value.addProperty("used_bytes",runtime.totalMemory()-runtime.freeMemory());value.addProperty("committed_bytes",runtime.totalMemory());value.addProperty("max_bytes",runtime.maxMemory());value.addProperty("gc_collections",gc(false));value.addProperty("gc_time_ms",gc(true));memory.add(value);}
    private static long gc(boolean time){long total=0;for(GarbageCollectorMXBean bean:ManagementFactory.getGarbageCollectorMXBeans()){long value=time?bean.getCollectionTime():bean.getCollectionCount();if(value>=0)total+=value;}return total;}

    private void reserve(Minecraft client)throws Exception{
        require(client.mcDataDir.getCanonicalFile().equals(COPIED_CLIENT.getCanonicalFile()),"Refusing foreign copied client");require(client.theWorld==null&&client.getIntegratedServer()==null,"Refusing existing world");
        Path saves=client.mcDataDir.toPath().resolve("saves");Files.createDirectories(saves);require(!Files.isSymbolicLink(saves)&&saves.toRealPath().getParent().equals(client.mcDataDir.toPath().toRealPath()),"Save path escaped client");
        runId=UUID.randomUUID().toString();saveName="GTNH-performance-"+runId;Path reserved=Files.createDirectory(saves.resolve(saveName));saveDirectory=reserved.toFile();Files.write(reserved.resolve("performance-probe-owner.txt"),runId.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);owned=true;
        report.addProperty("run_id",runId);report.addProperty("save_directory",saveDirectory.getCanonicalPath());report.addProperty("client_directory",client.mcDataDir.getCanonicalPath());report.addProperty("fresh_uuid_save",true);
    }
    private void assertOwned(MinecraftServer server)throws Exception{require(owned&&server!=null&&saveName.equals(server.getFolderName())&&!Files.isSymbolicLink(saveDirectory.toPath()),"Foreign integrated server");Path marker=saveDirectory.toPath().resolve("performance-probe-owner.txt");require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&runId.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Owned marker changed");}
    private EntityPlayerMP player(MinecraftServer server){for(Object value:server.getConfigurationManager().playerEntityList){EntityPlayerMP player=(EntityPlayerMP)value;if(player.getUniqueID().equals(playerId))return player;}return null;}
    private synchronized void fail(Throwable problem){if(failure==null)failure=problem;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void finish(Minecraft client){
        if(stage==Stage.FINISHED)return;stage=Stage.FINISHED;window=null;
        try{
            if(client.getIntegratedServer()!=null){assertOwned(client.getIntegratedServer());client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());report.addProperty("saved_and_closed_normally",true);}
            if(owned){KeyBinding.unPressAllKeys();client.gameSettings.limitFramerate=oldCap;client.gameSettings.enableVsync=oldVsync;client.gameSettings.pauseOnLostFocus=oldPause;client.gameSettings.renderDistanceChunks=oldDistance;client.gameSettings.thirdPersonView=oldThird;Display.setVSyncEnabled(oldVsync);}
            if(failure==null){require(completed&&windows.size()==(mtr?3:1),"Performance windows incomplete");require(new File(saveDirectory,"level.dat").isFile(),"Native normal save missing level.dat");}
        }catch(Throwable problem){fail(problem);}
        report.add("windows",windows);report.add("heap_samples",memory);report.addProperty("gc_forced_by_probe",false);report.addProperty("original_landing_call_count",buttonCalls);report.addProperty("visible_lift_peak",visiblePeak);
        JsonArray progress=new JsonArray();boolean moving=liftProgress.size()==3;for(Map.Entry<Long,double[]> entry:liftProgress.entrySet()){JsonObject value=new JsonObject();value.addProperty("id",Long.toString(entry.getKey()));value.addProperty("min_progress",entry.getValue()[0]);value.addProperty("max_progress",entry.getValue()[1]);progress.add(value);moving&=entry.getValue()[1]-entry.getValue()[0]>1;}report.add("original_lift_progress_ranges",progress);report.addProperty("layout_motion_and_render_observed",mtr&&moving&&visiblePeak>0);
        if(failure==null&&mtr)try{require(moving&&visiblePeak>0,"Representative layout did not show all three moving lifts and native rendering");}catch(Throwable problem){fail(problem);}
        report.addProperty("passed",failure==null);report.addProperty("measurement_note","Render END-to-END wall intervals and native server START-to-END event spans / END-to-END intervals; Controlled background run; foreground eligibility remains recorded separately. Background render intervals are observations under the actual inactive-window policy, not foreground FPS or GPU-only time; server event spans are not exact profiler MSPT.");
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try{if(owned)Files.write(new File(client.mcDataDir,"gtnh-performance-probe-"+runId+".json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);System.out.println((failure==null?"GTNH_PERFORMANCE_PROBE_PASS ":"GTNH_PERFORMANCE_PROBE_FAIL ")+report);if(owned)client.shutdown();}catch(Exception problem){throw new IllegalStateException(problem);}
    }

    private static final class Window {
        final String scene;final long start,end,gcCount=gc(false),gcMs=gc(true);final Samples renderIntervals=new Samples(),focusedIntervals=new Samples(),serverIntervals=new Samples(),serverEventSpans=new Samples();volatile int backgroundFrames,ineligibleFrames,width,height;volatile boolean resized;
        Window(String scene,long start,long end){this.scene=scene;this.start=start;this.end=end;}
        JsonObject json(){JsonObject value=new JsonObject();value.addProperty("scene",scene);value.addProperty("duration_ms",(end-start)/1E6);value.addProperty("display_width",width);value.addProperty("display_height",height);value.addProperty("display_resized",resized);value.addProperty("background_frame_intervals",backgroundFrames);value.addProperty("background_comparison_eligible",!resized&&ineligibleFrames==0&&backgroundFrames==renderIntervals.count&&renderIntervals.count>100&&width>0&&height>0);value.addProperty("ineligible_frame_intervals",ineligibleFrames);value.addProperty("foreground_fps_claim",false);value.addProperty("throughput_eligible",!resized&&backgroundFrames==0&&focusedIntervals.count>100&&width>0&&height>0);value.addProperty("gc_collections_delta",gc(false)-gcCount);value.addProperty("gc_time_ms_delta",gc(true)-gcMs);value.add("render_intervals",renderIntervals.json());value.add("focused_render_intervals",focusedIntervals.json());value.add("server_tick_intervals",serverIntervals.json());value.add("server_tick_event_spans",serverEventSpans.json());return value;}
    }
    private static final class Samples {
        long[] values=new long[8192];int count;
        synchronized void add(long value){if(value<=0)return;if(count==values.length)values=Arrays.copyOf(values,values.length*2);values[count++]=value;}
        synchronized JsonObject json(){JsonObject result=new JsonObject();result.addProperty("count",count);if(count==0)return result;long[] sorted=Arrays.copyOf(values,count);Arrays.sort(sorted);double sum=0;int over50=0;for(long value:sorted){sum+=value;if(value>50_000_000L)over50++;}result.addProperty("mean_ms",sum/count/1E6);result.addProperty("observed_rate_hz",count*1E9/sum);result.addProperty("min_ms",sorted[0]/1E6);result.addProperty("p50_ms",sorted[(count-1)/2]/1E6);result.addProperty("p95_ms",sorted[(int)Math.ceil(count*.95)-1]/1E6);result.addProperty("p99_ms",sorted[(int)Math.ceil(count*.99)-1]/1E6);result.addProperty("max_ms",sorted[count-1]/1E6);result.addProperty("intervals_over_50ms",over50);return result;}
    }
}
