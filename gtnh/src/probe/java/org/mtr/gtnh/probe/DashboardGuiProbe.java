package org.mtr.gtnh.probe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.mtr.core.Main;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.data.TransportMode;
import net.minecraft.client.gui.ScaledResolution;
import org.mtr.mapping.mapper.CheckboxWidgetExtension;
import org.mtr.mod.screen.PIDSConfigScreen;
import org.mtr.mod.screen.DashboardListSelectorScreen;
import org.mtr.mod.block.BlockPIDSBase;
import org.mtr.core.data.Station;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.serializer.JsonWriter;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mapping.mapper.ClickableWidgetExtension;
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.screen.DashboardList;
import org.mtr.mod.screen.DashboardListItem;
import org.mtr.mod.screen.DashboardScreen;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only original Dashboard flow. Raw evidence is always provisional. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_dashboard_gui_probe",name="MTR GTNH native Dashboard queue probe",version="0",
    dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class DashboardGuiProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT=new File(requiredProbePath("mtr.probeClient"));
    private static final String WRAPPER_SHA="3c5af555d62eb9b7f4196adea94cb4cbd4c8efe3e1d512f83537aa88d9d5211e";
    private static final String FINAL_NAME="Порт GTNH";
    private static final String ROUTE_NAME="GUI route GTNH";
    private static final String PIDS_MESSAGE="Тест PIDS GTNH";
    private static final String OWNER="mtr-dashboard-gui-probe-owner.txt";
    private enum Stage {MENU,WORLD,SETUP,SEED,OPEN_FIRST,ROW_FIRST,DRAW_FIRST,CLEAR,TYPE,DONE,ACK,CLOSE_FIRST,
        OPEN_SECOND,ROW_SECOND,DRAW_SECOND,CHECK_SECOND,CLOSE_SECOND,
        TAB_ROUTES,ADD_ROUTE,ROUTE_CLEAR,ROUTE_TYPE,ROUTE_DONE,ROUTE_ACK,TAB_DEPOTS,TAB_STATIONS,
        SEARCH_CLEAR,SEARCH_TYPE,SEARCH_CHECK,SEARCH_RESET,ZOOM_IN,ZOOM_OUT,MAP_CURRENT,MAP_TOP,
        OPEN_THIRD,TAB_ROUTE_REOPEN,ROW_ROUTE_REOPEN,DRAW_ROUTE_REOPEN,CHECK_ROUTE_REOPEN,CLOSE_THIRD,
        PIDS_SETUP,PIDS_OPEN_FIRST,PIDS_MESSAGE_CLEAR,PIDS_MESSAGE_TYPE,PIDS_HIDE,PIDS_PAGE_CLEAR,PIDS_PAGE_TYPE,
        PIDS_AUTO_OFF,PIDS_AUTO_ON,PIDS_FILTER_OPEN,PIDS_FILTER_REVEAL,PIDS_FILTER_ADD,PIDS_FILTER_DONE,PIDS_FILTER_RETURN,PIDS_CLOSE_FIRST,
        PIDS_ACK,PIDS_OPEN_SECOND,PIDS_CHECK_SECOND,PIDS_AUTOMATIC_CLEAR,PIDS_RESIZE,PIDS_RESIZED,PIDS_CLOSE_SECOND,PIDS_CLEAR_ACK,PIDS_OPEN_THIRD,PIDS_CHECK_THIRD,PIDS_CLOSE_THIRD,PIDS_FINAL_ACK,SAVE,FINISHED}
    private volatile Stage stage=Stage.MENU;
    private volatile Throwable failure;
    private String runId,saveName,initialName,artifactSha,probeSha;
    private Path saveDirectory;
    private UUID playerId;
    private Object clientWorld;
    private volatile NetworkManager networkManager;
    private volatile NetworkManager serverNetworkManager;
    private volatile Simulator simulator;
    private volatile ServerView serverView;
    private volatile CoreView coreView;
    private ClientView clientView;
    private volatile long stationId;
    private String stationHex;
    private boolean copyVerified,setupServerRequested,setupServerDone,setupClientDone,complete;
    private volatile boolean channelObserverReady;
    private int tick,menuTicks,stillTicks,setupDtoPackets,optionWrites,orientationWrites,focusWrites;
    private long frame,startedAt,stageAt,doneAt,openAt;
    private volatile long actionSerial;
    private int s08Baseline,openBaseline,useBaseline,updateBaseline,charIndex;
    private GuiScreen actualScreen,firstScreen;
    private PIDSConfigScreen firstPids,secondPids;
    private long lastNativeInputObservedAt;
    private volatile int pidsX,pidsY,pidsZ;
    private volatile boolean pidsFixtureReady,brushEquipped;
    private volatile JsonObject pidsServer;
    private volatile long pidsServerAt;
    private volatile int pidsOpenPackets,pidsUpdatePackets;
    private volatile long lastPidsOpenAt,lastPidsUpdateAt;
    private volatile long routeId,platformId;
    private JsonObject baselinePlatform,baselineRail;
    private volatile boolean railFixtureRequested,railFixtureReady;
    private int seedWaitSamples,inventorySlotWrites,serverSetupOrientationWrites,worldOpenOrientationWrites;
    private boolean pidsAutomaticExpected=true,pidsSelected;
    private int priorGuiScale;
    private long resizeAtNanos;
    private int resizeTick;
    private long resizeFrame;
    private JsonObject routeBaseline,routeAck,pidsExpected;
    private double initialMapScale;
    private int routeExpectedColor;
    private boolean settingsCaptured,priorPauseOnLostFocus;
    private int priorThirdPerson,priorRenderDistance;
    private final JsonArray pidsSamples=new JsonArray();
    private JsonObject baselineCore,ackCore,ackClient,reopenClient;
    private Action action;
    private Class<?> mouseClass,keyboardClass;
    private final AtomicBoolean corePending=new AtomicBoolean();
    private final AtomicInteger c08=new AtomicInteger(),s08=new AtomicInteger(),openPackets=new AtomicInteger();
    private final AtomicInteger clientUpdates=new AtomicInteger(),serverUpdates=new AtomicInteger();
    private volatile long lastOpenAt,lastClientUpdateAt,lastServerUpdateAt;
    private FMLEventChannel originalChannel;
    private final ConcurrentLinkedQueue<JsonObject> packetEvents=new ConcurrentLinkedQueue<>();
    private JsonObject lastSetupSnapshot;
    private int setupWaitSnapshots;
    private final JsonObject report=new JsonObject(),nativeEvidence=new JsonObject();
    private final JsonArray transitions=new JsonArray(),inputs=new JsonArray(),screenshots=new JsonArray(),samples=new JsonArray(),packets=new JsonArray();

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=stageAt=System.currentTimeMillis();
        try {
            for(String mod:Loader.instance().getIndexedModList().keySet())
                require(!mod.startsWith("mtr_gtnh_")||!mod.endsWith("_probe")||mod.equals("mtr_gtnh_dashboard_gui_probe"),"Coexisting probe "+mod);
            Path artifact=codeSource(getClass(),"artifact");
            artifactSha=sha(artifact);probeSha=System.getProperty("mtr.dashboard.probeSha256","");
            Path probeSource=checked(Paths.get(requiredProbePath("mtr.dashboard.probeSource")));
            require(probeSha.matches("[0-9a-f]{64}")&&probeSha.equals(sha(probeSource))&&artifactSha.equals(System.getProperty("mtr.dashboard.artifactSha256")),"Parent source/artifact pins required");
            report.addProperty("probe_source_path",probeSource.toString());
            report.addProperty("artifact_path",artifact.toString());report.addProperty("artifact_sha256",artifactSha);report.addProperty("probe_sha256",probeSha);
            // Observe the existing original event channel without replacing callbacks.
            Field network=org.mtr.mapping.registry.Registry.class.getDeclaredField("network");network.setAccessible(true);
            Object original=network.get(Init.REGISTRY);require(original!=null,"Original MTR network missing");
            originalChannel=(FMLEventChannel)field(original,"channel");originalChannel.register(this);
        }catch(Throwable problem){fail(problem);}
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<15*60*1000L,"Dashboard total deadline: "+stage);
            // Match existing LiftMenuProbe: MENU startup is covered by the total deadline,
            // not by the short GUI-action deadline. WORLD retains a separate bounded load wait.
            if(stage!=Stage.MENU)require(System.currentTimeMillis()-stageAt<(stage==Stage.WORLD?180000L:60000L),"Dashboard phase deadline: "+stage);
            ++tick;JsonObject packet;while((packet=packetEvents.poll())!=null)packets.add(packet);
            if(stage==Stage.MENU){
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.currentScreen==null){menuTicks=0;return;}
                String name=client.currentScreen.getClass().getName();
                if(!name.equals("net.minecraft.client.gui.GuiMainMenu")&&!name.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                reserve(client);prepareInput();selfCheckInputGeometry();
                if(Boolean.getBoolean("mtr.dashboard.lifecycleOnly")){
                    report.addProperty("lifecycle_regression_only",true);runDraftLifecycleRegression(client);report.addProperty("pids_draft_lifecycle_regression_completed",true);advance(Stage.SAVE);return;
                }
                priorPauseOnLostFocus=client.gameSettings.pauseOnLostFocus;priorThirdPerson=client.gameSettings.thirdPersonView;priorRenderDistance=client.gameSettings.renderDistanceChunks;priorGuiScale=client.gameSettings.guiScale;settingsCaptured=true;
                client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=0;client.gameSettings.renderDistanceChunks=5;optionWrites=3;
                report.addProperty("background_gui_probe",true);report.addProperty("foreground_fps_claimed",false);report.addProperty("display_active_modified",false);
                WorldSettings settings=new WorldSettings(0x4D545244415348L,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT)
                    .enableCommands().func_82750_a("2;7,2x3,2;1;");
                advance(Stage.WORLD);client.launchIntegratedServer(saveName,"MTR Dashboard GUI acceptance "+runId,settings);return;
            }
            if(stage==Stage.WORLD){
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null||client.getNetHandler()==null)return;
                assertServer(client.getIntegratedServer());playerId=client.thePlayer.getUniqueID();clientWorld=client.theWorld;
                networkManager=client.getNetHandler().getNetworkManager();require(networkManager!=null&&networkManager.isChannelOpen(),"Native client connection missing");
                report.addProperty("player_uuid",playerId.toString());report.addProperty("network_manager_generation",1);
                report.addProperty("network_manager_identity",System.identityHashCode(networkManager));advance(Stage.SETUP);
            }
            if(stage==Stage.SAVE){finish(client);return;}
            assertClient(client);readCoreSoon();
            if(stage==Stage.SETUP){
                if(!setupClientDone){
                    require(client.currentScreen==null,"Unexpected setup screen");
                    KeyBinding.unPressAllKeys();client.thePlayer.rotationYaw=0;client.thePlayer.rotationPitch=-80;orientationWrites=2;
                    setupClientDone=true;
                }
                ServerView server=serverView;CoreView core=coreView;
                boolean ready=setupServerDone&&channelObserverReady&&server!=null&&core!=null&&core.phase==stage&&core.serial==actionSerial&&
                    core.count==0&&core.otherCount==0&&server.held&&server.creative&&server.world==0&&server.manager==serverNetworkManager&&
                    client.thePlayer.getHeldItem()!=null&&client.thePlayer.getHeldItem().getItem()==dashboardItem()&&client.thePlayer.inventory.currentItem==0&&
                    client.currentScreen==null&&air(client)&&zeroMovement(client)&&client.thePlayer.onGround&&server.onGround;
                if(!ready){recordSetupWait(client,server,core);stillTicks=0;return;}
                require(MinecraftClientData.getInstance().stations.isEmpty()&&MinecraftClientData.getDashboardInstance().stations.isEmpty(),"Fresh client station stores were not empty");
                if(++stillTicks<20)return;
                report.add("setup_pre_seed_core",core.json());advance(Stage.SEED);return;
            }
            if(stage==Stage.SEED){
                if(!railFixtureReady)return;
                if(setupDtoPackets==0){setupSeed();return;}
                CoreView core=coreView;ClientView view=clientView;
                if(core==null||view==null||!fresh(core,view))return;
                if(++seedWaitSamples<=40||seedWaitSamples%20==0){JsonObject sample=envelope();sample.add("core",core.json());sample.add("client",view.json());sample.addProperty("server_updates",serverUpdates.get());sample.addProperty("client_updates",clientUpdates.get());sample.addProperty("last_server_update_at_nanos",lastServerUpdateAt);sample.addProperty("last_client_update_at_nanos",lastClientUpdateAt);report.add("last_seed_wait_snapshot",sample);}
                if(setupDtoPackets==1){
                    if(core.count!=1||core.otherCount!=2||core.platform==null||core.rail==null||!initialName.equals(name(core.station))||!initialName.equals(name(view.normal))||!initialName.equals(name(view.dashboard))||serverUpdates.get()!=1)return;
                    require(core.station.equals(view.normal)&&core.station.equals(view.dashboard)&&core.platformId!=0,"Station/rail setup first ACK differs");
                    platformId=core.platformId;Platform platform=new Platform(new JsonReader(copy(core.platform)),MinecraftClientData.getDashboardInstance());platform.setName("GUI platform 1");
                    require(platform.getId()==platformId,"Generated platform DTO changed ID");++setupDtoPackets;
                    InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getDashboardInstance()).addPlatform(platform)));input("setup_original_generated_platform_config_dto");return;
                }
                require(setupDtoPackets==2,"Unexpected setup DTO count");
                if(view.atNanos<=lastClientUpdateAt||core.atNanos<=lastServerUpdateAt)return;
                if(core.count!=1||core.otherCount!=2||core.platform==null||core.rail==null||!initialName.equals(name(core.station))||!"GUI platform 1".equals(name(core.platform))||!"GUI platform 1".equals(name(view.platformNormal))||!"GUI platform 1".equals(name(view.platformDashboard))||view.platformNormal==null||view.platformDashboard==null||view.railNormal==null||view.railDashboard==null||serverUpdates.get()!=2||clientUpdates.get()!=3)return;
                require(core.platform.equals(view.platformNormal)&&core.platform.equals(view.platformDashboard)&&core.rail.equals(view.railNormal)&&core.rail.equals(view.railDashboard)&&core.station.equals(view.normal)&&core.station.equals(view.dashboard),"Full original generated platform/rail setup ACK differs");
                baselineCore=copy(core.station);baselinePlatform=copy(core.platform);baselineRail=copy(core.rail);stationHex=core.hex;report.add("baseline_platform",baselinePlatform);report.add("baseline_rail",baselineRail);report.addProperty("platform_id",Long.toString(platformId));report.addProperty("rail_hex",core.railHex);
                require(stationHex.matches("[0-9A-F]{16}"),"Unexpected original station hex ID");
                report.add("baseline_core",baselineCore);report.addProperty("station_id",Long.toString(stationId));report.addProperty("station_hex",stationHex);
                report.addProperty("initial_name",initialName);report.addProperty("final_name",FINAL_NAME);
                s08Baseline=s08.get();require(c08.get()==0&&openPackets.get()==0,"Dashboard input before measurement");
                require(client.gameSettings.keyBindUseItem.getKeyCode()==-99,"Ordinary RMB use binding differs");
                report.addProperty("setup_closed_at_nanos",System.nanoTime());advance(Stage.OPEN_FIRST);return;
            }
            if(stage.ordinal()>=Stage.PIDS_SETUP.ordinal()){pidsStep(client);return;}
            if(stage==Stage.CLOSE_SECOND||(stage.ordinal()>=Stage.TAB_ROUTES.ordinal()&&stage.ordinal()<=Stage.CLOSE_THIRD.ordinal())){broaderDashboard(client);return;}
            measuredStep(client);
        }catch(Throwable problem){fail(problem);}
    }

    // BEGIN_SETUP_SEED
    private void setupSeed() {
        require(stage==Stage.SEED&&setupDtoPackets==0&&stationId==0,"Seed outside one-shot setup");
        MinecraftClientData data=MinecraftClientData.getDashboardInstance();
        Station station=new Station(data);station.setName(initialName);station.setColor(0x3366CC);
        station.setCorners(new Position(pidsX-8,Long.MIN_VALUE,pidsZ-8),new Position(pidsX+8,Long.MAX_VALUE,pidsZ+8));
        stationId=station.getId();require(stationId!=0,"Zero seeded station ID");++setupDtoPackets;
        InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(data).addStation(station)));
        input("setup_original_station_dto_after_original_rail_connector");
    }
    // END_SETUP_SEED

    // BEGIN_MEASURED_FLOW
    private void measuredStep(Minecraft client)throws Exception {
        require(setupDtoPackets==2&&zeroMovement(client)&&!client.gameSettings.pauseOnLostFocus,"Measured setup/input state changed");
        require(s08.get()==s08Baseline,"Unexpected native S08 correction during measurement");
        ServerView server=serverView;
        require(server!=null&&server.held&&server.creative&&server.manager==serverNetworkManager&&server.world==0&&
            System.nanoTime()-server.atNanos<5000000000L,"Server item/player/connection changed or stale");
        if(stage==Stage.OPEN_FIRST||stage==Stage.OPEN_SECOND){
            if(action==null){
                require(client.currentScreen==null&&air(client),"Original Dashboard item use must be in air");
                openDashboard(client);return;
            }
            observeAction();
            if(!action.release||client.currentScreen==null)return;
            require(client.currentScreen instanceof DashboardScreen,"Original item opened another screen");
            require(openPackets.get()<=openBaseline+1&&c08.get()<=useBaseline+1,"Repeated original item/open packet");
            if(openPackets.get()!=openBaseline+1||c08.get()!=useBaseline+1||lastOpenAt<=openAt)return;
            actualScreen=(DashboardScreen)client.currentScreen;
            if(stage==Stage.OPEN_FIRST){firstScreen=actualScreen;nativeEvidence.addProperty("original_controller_dashboard_open",true);}
            else {require(actualScreen!=firstScreen&&openPackets.get()==2,"Reopen reused screen/missing new LIST_DATA response");nativeEvidence.addProperty("new_screen_list_data",true);}
            if(!laterFrame()||clientView.screenIdentity!=System.identityHashCode(actualScreen)||clientView.atNanos<=lastOpenAt||!listReady(actualScreen,stage==Stage.OPEN_FIRST?initialName:FINAL_NAME))return;
            capture(client,stage==Stage.OPEN_FIRST?"baseline":"reopened-list");
            report.addProperty(stage==Stage.OPEN_FIRST?"first_screen_identity":"reopened_screen_identity",System.identityHashCode(actualScreen));
            recordViews();advance(stage==Stage.OPEN_FIRST?Stage.ROW_FIRST:Stage.ROW_SECOND);return;
        }
        if(stage==Stage.CLOSE_FIRST||stage==Stage.CLOSE_SECOND){
            if(action==null){require(client.currentScreen==actualScreen,"Close lost actual Dashboard");queueEscape();return;}
            observeAction();if(!action.release||client.currentScreen!=null||!laterFrame())return;
            if(stage==Stage.CLOSE_FIRST){advance(Stage.OPEN_SECOND);return;}
            nativeEvidence.addProperty("normal_close",true);advance(Stage.PIDS_SETUP);return;
        }
        require(client.currentScreen==actualScreen&&actualScreen!=null,"Measured native screen replaced/disconnected");
        require("STATIONS".equals(String.valueOf(field(actualScreen,"selectedTab"))),"Dashboard selected tab changed");
        if(stage==Stage.ROW_FIRST||stage==Stage.ROW_SECOND){
            DashboardList list=(DashboardList)field(actualScreen,"dashboardList");
            require(listReady(actualScreen,stage==Stage.ROW_FIRST?initialName:FINAL_NAME),"Initial station list differs");
            if(action==null){
                // Original DashboardList hides and leaves icons unpositioned until real mouseMoved.
                // Reveal the only real row first; read the actual icon bounds only afterwards.
                action=new Action("native_station_row_reveal",0,null);
                action.pointer=new Pointer(actualScreen.width,actualScreen.height,client.displayWidth,client.displayHeight,pixelScale(),list.x,list.y+24,list.width,20);
                action.moveBefore=wrapperNanos();queueMove(action.pointer);action.moveAfter=wrapperNanos();input("queued_native_station_row_reveal");return;
            }
            observeAction();if(!action.move||!laterFrame())return;
            require(action.pointer.same(client,actualScreen,pixelScale())&&list.getHoverItemIndex()==0,"Row reveal dimensions/hover changed");
            ClickableWidgetExtension draw=(ClickableWidgetExtension)field(list,"buttonDrawArea");
            require(draw.visible&&draw.getActiveMapped()&&containsIdentity((List<?>)field(actualScreen,"buttonList"),draw),"Native row reveal did not expose original icon");
            require(draw.getX2()==list.x+list.width-60&&draw.getY2()==list.y+24&&draw.getWidth2()==20&&draw.getHeight2()==20,"Actual revealed station icon bounds differ");
            recordViews();advance(stage==Stage.ROW_FIRST?Stage.DRAW_FIRST:Stage.DRAW_SECOND);return;
        }
        if(stage==Stage.DRAW_FIRST||stage==Stage.DRAW_SECOND){
            ClickableWidgetExtension draw=(ClickableWidgetExtension)field(field(actualScreen,"dashboardList"),"buttonDrawArea");
            if(!click(client,draw,0,"station_draw_area",true))return;
            Object editing=field(actualScreen,"editingArea");
            require(editing instanceof Station&&((Station)editing).getId()==stationId,"Row icon opened wrong station");
            String expected=stage==Stage.DRAW_FIRST?initialName:FINAL_NAME;
            require(expected.equals(nameField().getText2())&&nameField().visible,"Actual inline name differs");
            capture(client,stage==Stage.DRAW_FIRST?"inline-initial":"inline-reopened");
            if(stage==Stage.DRAW_FIRST){nativeEvidence.addProperty("draw_clicked",true);advance(Stage.CLEAR);}
            else advance(Stage.CHECK_SECOND);
            return;
        }
        if(stage==Stage.CLEAR){
            TextFieldWidgetExtension name=nameField();if(!click(client,name,1,"native_name_RMB_clear",false))return;
            require(name.getText2().isEmpty()&&name.isFocused2(),"Native field clear/focus missing");
            nativeEvidence.addProperty("native_clear",true);capture(client,"inline-cleared");advance(Stage.TYPE);return;
        }
        if(stage==Stage.TYPE){
            TextFieldWidgetExtension name=nameField();require(name.visible&&name.getActiveMapped()&&name.isFocused2(),"Native text focus lost");
            if(action==null){
                if(charIndex==FINAL_NAME.length()){
                    require(FINAL_NAME.equals(name.getText2()),"Typed Unicode final name differs");
                    nativeEvidence.addProperty("unicode_delivery",true);capture(client,"inline-typed");advance(Stage.DONE);return;
                }
                int codepoint=FINAL_NAME.codePointAt(charIndex);require(Character.charCount(codepoint)==1,"Probe fixture expects BMP name");
                queueCharacter(codepoint);return;
            }
            observeAction();if(!action.character||!laterFrame())return;
            recordCharacterPrefix(name,FINAL_NAME);
            require(FINAL_NAME.substring(0,charIndex+1).equals(name.getText2()),"Native character prefix differs");
            ++charIndex;action=null;return;
        }
        if(stage==Stage.DONE){
            ClickableWidgetExtension done=(ClickableWidgetExtension)field(actualScreen,"buttonDoneEditingStation");
            if(action==null){require(FINAL_NAME.equals(nameField().getText2()),"Done without actual final widget text");updateBaseline=clientUpdates.get();}
            if(!click(client,done,0,"native_station_Done",false))return;
            require(field(actualScreen,"editingArea")==null,"Original Done did not exit inline editor");
            doneAt=action.atNanos;nativeEvidence.addProperty("done_release",true);recordViews();advance(Stage.ACK);return;
        }
        if(stage==Stage.ACK){
            CoreView core=coreView;ClientView view=clientView;
            if(core==null||view==null||!fresh(core,view)||!(core.atNanos>doneAt&&view.atNanos>doneAt)||
                clientUpdates.get()<=updateBaseline||lastClientUpdateAt<=doneAt||lastServerUpdateAt<=doneAt)return;
            if(!FINAL_NAME.equals(name(core.station))||!FINAL_NAME.equals(name(view.normal))||!FINAL_NAME.equals(name(view.dashboard)))return;
            require(serverUpdates.get()==3&&core.count==1&&core.otherCount==2,"Original one GUI update/count differs");
            unchanged(core.station);unchanged(view.normal);unchanged(view.dashboard);
            ackCore=copy(core.station);ackClient=copy(view.normal);report.add("ack_core",ackCore);report.add("ack_client",ackClient);
            report.add("post_click_core_sample",core.json());report.add("post_click_client_sample",view.json());
            nativeEvidence.addProperty("fresh_core_client_ack",true);capture(client,"done-acknowledged");advance(Stage.CLOSE_FIRST);return;
        }
        if(stage==Stage.CHECK_SECOND){
            ClientView view=clientView;CoreView core=coreView;
            if(view==null||core==null||!fresh(core,view))return;
            require(FINAL_NAME.equals(nameField().getText2())&&openPackets.get()==2&&actualScreen!=firstScreen,"Fresh reopened actual name/LIST_DATA missing");
            unchanged(core.station);unchanged(view.normal);unchanged(view.dashboard);
            reopenClient=copy(view.dashboard);report.add("reopen_client",reopenClient);report.add("reopened_client_sample",view.json());
            nativeEvidence.addProperty("reopen_widget_name",true);capture(client,"reopened-final-name");advance(Stage.TAB_ROUTES);
        }
    }
    // END_MEASURED_FLOW

    private void openDashboard(Minecraft client) {
        require(action==null&&client.currentScreen==null&&air(client),"Dashboard world-open precondition");
        openBaseline=openPackets.get();useBaseline=c08.get();openAt=System.nanoTime();
        action=new Action("original_controller_dashboard_item_use",-4,null);action.release=true;
        ItemStack held=client.thePlayer.getHeldItem();int count=held.stackSize;
        boolean stackChanged=client.playerController.sendUseItem(client.thePlayer,client.theWorld,held);
        require(!stackChanged&&client.thePlayer.getHeldItem()==held&&held.stackSize==count,"Dashboard controller unexpectedly replaced/consumed the held stack");
        nativeEvidence.addProperty("dashboard_controller_stack_unchanged",true);
        input("original_controller_world_open_not_queued_RMB");
    }

    private ClickableWidgetExtension widget(String name)throws Exception{return (ClickableWidgetExtension)field(actualScreen,name);}
    private TextFieldWidgetExtension searchField()throws Exception{return (TextFieldWidgetExtension)field(field(actualScreen,"dashboardList"),"textFieldSearch");}
    private boolean typeText(TextFieldWidgetExtension widget,String text)throws Exception {
        require(widget.visible&&widget.getActiveMapped()&&widget.isFocused2(),"Typed GUI field lost focus");
        if(action==null){
            if(charIndex==text.length()){require(text.equals(widget.getText2()),"Final native field text differs");charIndex=0;return true;}
            int codepoint=text.codePointAt(charIndex);require(Character.charCount(codepoint)==1,"Fixture requires BMP text");queueCharacter(codepoint);return false;
        }
        observeAction();if(!action.character||!laterFrame())return false;
        recordCharacterPrefix(widget,text);
        require(text.substring(0,charIndex+1).equals(widget.getText2()),"Typed native prefix differs for "+stage);
        ++charIndex;action=null;return false;
    }
    private boolean routeListReady()throws Exception {
        if(!"ROUTES".equals(String.valueOf(field(actualScreen,"selectedTab")))||field(actualScreen,"editingRoute")!=null)return false;
        DashboardList list=(DashboardList)field(actualScreen,"dashboardList");List<?> sorted=(List<?>)field(list,"dataSorted");Map<?,?> filtered=(Map<?,?>)field(list,"dataFiltered");
        if(sorted.size()!=1||filtered.size()!=1||!searchField().getText2().isEmpty())return false;
        DashboardListItem item=(DashboardListItem)sorted.get(0);return item.id==routeId&&ROUTE_NAME.equals(item.getName(false));
    }
    private void broaderDashboard(Minecraft client)throws Exception {
        require(zeroMovement(client)&&s08.get()==s08Baseline,"Dashboard broader flow changed movement/correction state");
        if(stage==Stage.CLOSE_SECOND||stage==Stage.CLOSE_THIRD){
            if(action==null){require(client.currentScreen==actualScreen,"Dashboard close screen lost");queueEscape();return;}
            observeAction();if(!action.release||client.currentScreen!=null||!laterFrame())return;
            if(stage==Stage.CLOSE_THIRD)nativeEvidence.addProperty("dashboard_normal_close",true);advance(stage==Stage.CLOSE_SECOND?Stage.OPEN_THIRD:Stage.PIDS_SETUP);return;
        }
        if(stage==Stage.OPEN_THIRD){
            if(action==null){openDashboard(client);return;}
            if(client.currentScreen==null||!laterFrame())return;
            require(client.currentScreen instanceof DashboardScreen&&client.currentScreen!=firstScreen&&client.currentScreen!=actualScreen,"Third Dashboard did not create a new screen");
            if(openPackets.get()!=3||lastOpenAt<=openAt||c08.get()!=useBaseline+1)return;
            if(clientView==null||clientView.screenIdentity!=System.identityHashCode(client.currentScreen)||clientView.atNanos<=lastOpenAt)return;actualScreen=client.currentScreen;capture(client,"dashboard-third-open");advance(Stage.TAB_ROUTE_REOPEN);return;
        }
        require(client.currentScreen==actualScreen&&actualScreen instanceof DashboardScreen,"Broader Dashboard screen replaced");
        if(stage==Stage.TAB_ROUTES){
            if(!click(client,widget("buttonTabRoutes"),0,"dashboard_routes_tab",false))return;
            require("ROUTES".equals(String.valueOf(field(actualScreen,"selectedTab")))&&field(actualScreen,"editingArea")==null,"Routes tab callback differs");capture(client,"dashboard-routes-empty");advance(Stage.ADD_ROUTE);return;
        }
        if(stage==Stage.ADD_ROUTE){
            if(!click(client,widget("buttonAddRoute"),0,"dashboard_add_route",false))return;
            Object route=field(actualScreen,"editingRoute");require(route instanceof Route,"Add Route did not open original inline editor");
            routeId=((Route)route).getId();routeBaseline=full((Route)route);routeExpectedColor=((org.mtr.mod.screen.WidgetColorSelector)field(actualScreen,"colorSelector")).getColor();require(routeExpectedColor>=0&&routeExpectedColor<=0xFFFFFF,"Original route color widget is outside RGB");report.addProperty("route_original_widget_color",routeExpectedColor);require(routeId!=0&&nameField().visible,"New original route missing");advance(Stage.ROUTE_CLEAR);return;
        }
        if(stage==Stage.ROUTE_CLEAR){
            if(!click(client,nameField(),1,"route_name_clear",false))return;
            require(nameField().getText2().isEmpty(),"Route field not cleared");advance(Stage.ROUTE_TYPE);return;
        }
        if(stage==Stage.ROUTE_TYPE){if(!typeText(nameField(),ROUTE_NAME))return;capture(client,"dashboard-route-typed");advance(Stage.ROUTE_DONE);return;}
        if(stage==Stage.ROUTE_DONE){
            if(action==null){require(ROUTE_NAME.equals(nameField().getText2())&&((org.mtr.mod.screen.WidgetColorSelector)field(actualScreen,"colorSelector")).getColor()==routeExpectedColor,"Original route widgets changed before Done");updateBaseline=clientUpdates.get();}
            if(!click(client,widget("buttonDoneEditingRoute"),0,"dashboard_route_done",false))return;
            require(field(actualScreen,"editingRoute")==null,"Original Route Done did not leave editor");doneAt=action.atNanos;advance(Stage.ROUTE_ACK);return;
        }
        if(stage==Stage.ROUTE_ACK){
            CoreView core=coreView;ClientView view=clientView;
            if(core==null||view==null||!fresh(core,view)||core.atNanos<=doneAt||view.atNanos<=doneAt||clientUpdates.get()<=updateBaseline||lastClientUpdateAt<=doneAt||lastServerUpdateAt<=doneAt)return;
            JsonObject normal=route(MinecraftClientData.getInstance()),dashboard=route(MinecraftClientData.getDashboardInstance());
            if(!ROUTE_NAME.equals(name(core.route))||!ROUTE_NAME.equals(name(normal))||!ROUTE_NAME.equals(name(dashboard)))return;
            JsonObject expected=copy(routeBaseline);expected.addProperty("name",ROUTE_NAME);expected.addProperty("color",routeExpectedColor);
            require(expected.equals(core.route)&&expected.equals(normal)&&expected.equals(dashboard)&&core.count==1&&core.otherCount==3&&serverUpdates.get()==4,"Full original route ACK differs");
            unchanged(core.station);routeAck=copy(core.route);report.add("route_ack_core_sample",core.json());nativeEvidence.addProperty("dashboard_route_created_native_gui",true);capture(client,"dashboard-route-ack");advance(Stage.TAB_DEPOTS);return;
        }
        if(stage==Stage.TAB_DEPOTS){
            if(!click(client,widget("buttonTabDepots"),0,"dashboard_depots_tab",false))return;
            require("DEPOTS".equals(String.valueOf(field(actualScreen,"selectedTab"))),"Depots tab differs");capture(client,"dashboard-depots-empty");advance(Stage.TAB_STATIONS);return;
        }
        if(stage==Stage.TAB_STATIONS){
            if(!click(client,widget("buttonTabStations"),0,"dashboard_stations_tab",false))return;
            if(!listReady(actualScreen,FINAL_NAME))return;
            advance(Stage.SEARCH_CLEAR);return;
        }
        if(stage==Stage.SEARCH_CLEAR){if(!click(client,searchField(),1,"dashboard_search_focus_clear",false))return;advance(Stage.SEARCH_TYPE);return;}
        if(stage==Stage.SEARCH_TYPE){if(!typeText(searchField(),"~"))return;advance(Stage.SEARCH_CHECK);return;}
        if(stage==Stage.SEARCH_CHECK){
            if(((Map<?,?>)field(field(actualScreen,"dashboardList"),"dataFiltered")).size()!=0)return;
            require("~".equals(searchField().getText2())&&"~".equals(MinecraftClientData.DASHBOARD_SEARCH),"Original search listener/filter differs");capture(client,"dashboard-search-no-match");advance(Stage.SEARCH_RESET);return;
        }
        if(stage==Stage.SEARCH_RESET){
            if(!click(client,searchField(),1,"dashboard_search_reset",false))return;
            if(!listReady(actualScreen,FINAL_NAME))return;
            nativeEvidence.addProperty("dashboard_native_search_tabs",true);initialMapScale=((Number)field(field(actualScreen,"widgetMap"),"scale")).doubleValue();advance(Stage.ZOOM_IN);return;
        }
        if(stage==Stage.ZOOM_IN||stage==Stage.ZOOM_OUT){
            boolean zoomIn=stage==Stage.ZOOM_IN;if(!click(client,widget(zoomIn?"buttonZoomIn":"buttonZoomOut"),0,zoomIn?"dashboard_zoom_in":"dashboard_zoom_out",false))return;
            double scale=((Number)field(field(actualScreen,"widgetMap"),"scale")).doubleValue();require(scale==(zoomIn?initialMapScale*2:initialMapScale),"Original map zoom callback differs");capture(client,zoomIn?"dashboard-zoom-in":"dashboard-zoom-restored");advance(zoomIn?Stage.ZOOM_OUT:Stage.MAP_CURRENT);return;
        }
        if(stage==Stage.MAP_CURRENT||stage==Stage.MAP_TOP){
            boolean current=stage==Stage.MAP_CURRENT;if(!click(client,widget(current?"buttonMapCurrentY":"buttonMapTopView"),0,current?"dashboard_map_current_y":"dashboard_map_top",false))return;
            require(!widget(current?"buttonMapCurrentY":"buttonMapTopView").getActiveMapped()&&widget(current?"buttonMapTopView":"buttonMapCurrentY").getActiveMapped(),"Original overlay mode buttons differ");
            capture(client,current?"dashboard-map-current":"dashboard-map-top");if(current)advance(Stage.MAP_TOP);else{nativeEvidence.addProperty("dashboard_native_map_controls",true);advance(Stage.CLOSE_SECOND);}return;
        }
        if(stage==Stage.TAB_ROUTE_REOPEN){
            if(!click(client,widget("buttonTabRoutes"),0,"dashboard_reopened_routes_tab",false))return;
            if(!routeListReady())return;advance(Stage.ROW_ROUTE_REOPEN);return;
        }
        if(stage==Stage.ROW_ROUTE_REOPEN){
            if(!routeListReady())return;DashboardList list=(DashboardList)field(actualScreen,"dashboardList");
            if(action==null){action=new Action("route_row_reveal",0,null);action.pointer=new Pointer(actualScreen.width,actualScreen.height,client.displayWidth,client.displayHeight,pixelScale(),list.x,list.y+24,list.width,20);action.moveBefore=wrapperNanos();queueMove(action.pointer);action.moveAfter=wrapperNanos();input("queued_route_row_reveal");return;}
            observeAction();if(!action.move||!laterFrame())return;
            require(list.getHoverItemIndex()==0,"Reopened native route row hover differs");advance(Stage.DRAW_ROUTE_REOPEN);return;
        }
        if(stage==Stage.DRAW_ROUTE_REOPEN){
            if(!click(client,(ClickableWidgetExtension)field(field(actualScreen,"dashboardList"),"buttonDrawArea"),0,"dashboard_reopened_route_draw",false))return;
            require(field(actualScreen,"editingRoute") instanceof Route&&((Route)field(actualScreen,"editingRoute")).getId()==routeId,"Reopened route inline editor differs");advance(Stage.CHECK_ROUTE_REOPEN);return;
        }
        if(stage==Stage.CHECK_ROUTE_REOPEN){
            require(ROUTE_NAME.equals(nameField().getText2())&&routeAck.equals(full((Route)field(actualScreen,"editingRoute"))),"Reopened route widget/full data differs");
            nativeEvidence.addProperty("dashboard_route_reopened_native_gui",true);capture(client,"dashboard-reopened-route-name");advance(Stage.CLOSE_THIRD);return;
        }
        throw new AssertionError("Unhandled broader Dashboard stage "+stage);
    }

    private Position railStart(){return new Position(pidsX-3,pidsY-2,pidsZ+2);}
    private Position railEnd(){return new Position(pidsX+3,pidsY-2,pidsZ+2);}
    private JsonObject rail(MinecraftClientData data){Rail found=null;for(Rail value:data.rails)if(value.getHexId().equals(TwoPositionsBase.getHexId(railStart(),railEnd()))){require(found==null,"Duplicate client platform rail");found=value;}return found==null?null:full(found);}
    private void setupPlatformRailFixture(EntityPlayerMP player){
        require(stage==Stage.SEED&&!railFixtureReady&&player.getHeldItem()!=null&&player.getHeldItem().getItem()==dashboardItem(),"Original rail setup phase/item differs");
        ItemStack previous=player.inventory.mainInventory[0];float previousYaw=player.rotationYaw;
        try{
            player.rotationYaw=-90;
            Item node=(Item)Item.itemRegistry.getObject("mtr:rail");require(node!=null&&node==Item.getItemFromBlock(org.mtr.mod.Blocks.RAIL_NODE.get().data),"Original rail node item identity differs");
            for(int x:new int[]{pidsX-3,pidsX+3}){
                int y=pidsY-2,z=pidsZ+2;require(player.worldObj.isAirBlock(x,y,z)&&player.worldObj.getBlock(x,y-1,z)==net.minecraft.init.Blocks.grass,"Fresh rail fixture would replace non-air/unsupported ground");
                ItemStack stack=new ItemStack(node);player.inventory.mainInventory[0]=stack;++inventorySlotWrites;
                require(node.onItemUse(stack,player,player.worldObj,x,y-1,z,1,.5F,.5F,.5F)&&stack.stackSize==0&&player.worldObj.getBlock(x,y,z)==org.mtr.mod.Blocks.RAIL_NODE.get().data,"Original rail node placement rejected");
            }
            Item connectorItem=(Item)Item.itemRegistry.getObject("mtr:rail_connector_platform");require(connectorItem instanceof org.mtr.mod.item.ItemRailModifier,"Original platform connector missing");
            ItemStack connector=new ItemStack(connectorItem);player.inventory.mainInventory[0]=connector;++inventorySlotWrites;
            require(connectorItem.onItemUse(connector,player,player.worldObj,pidsX-3,pidsY-2,pidsZ+2,1,.5F,.5F,.5F),"Original platform connector first use rejected");
            require(connector.hasTagCompound()&&connector.getTagCompound().getLong(org.mtr.mod.item.ItemBlockClickingBase.TAG_POS)==new org.mtr.mapping.holder.BlockPos(pidsX-3,pidsY-2,pidsZ+2).asLong(),"Original connector first-click state missing");
            require(connectorItem.onItemUse(connector,player,player.worldObj,pidsX+3,pidsY-2,pidsZ+2,1,.5F,.5F,.5F)&&!connector.getTagCompound().hasKey(org.mtr.mod.item.ItemBlockClickingBase.TAG_POS)&&connector.stackSize==1,"Original reusable platform connector click-pair rejected");
            railFixtureReady=true;
        }finally{player.rotationYaw=previousYaw;player.inventory.mainInventory[0]=previous;++inventorySlotWrites;serverSetupOrientationWrites+=2;player.inventoryContainer.detectAndSendChanges();}
    }

    private JsonObject platform(MinecraftClientData data){Platform found=null;for(Platform value:data.platforms)if(value.getId()==platformId){require(found==null,"Duplicate client platform");found=value;}return found==null?null:full(found);}
    private JsonObject route(MinecraftClientData data){Route found=null;for(Route value:data.routes)if(value.getId()==routeId){require(found==null,"Duplicate client route");found=value;}return found==null?null:full(found);}
    private void setupPidsFixture(EntityPlayerMP player) {
        require(stage==Stage.SETUP&&!pidsFixtureReady,"PIDS fixture setup repeated");
        pidsX=(int)Math.floor(player.posX)+2;pidsY=(int)Math.floor(player.boundingBox.minY)+2;pidsZ=(int)Math.floor(player.posZ)+1;
        require(player.worldObj.isAirBlock(pidsX,pidsY,pidsZ)&&player.worldObj.isAirBlock(pidsX,pidsY,pidsZ-1)&&player.worldObj.isAirBlock(pidsX,pidsY-1,pidsZ),"PIDS fresh fixture would replace non-air");
        require(player.worldObj.setBlock(pidsX,pidsY-1,pidsZ,net.minecraft.init.Blocks.stone),"PIDS support placement rejected");
        float priorYaw=player.rotationYaw;player.rotationYaw=0;
        ItemStack stack=new ItemStack(Item.getItemFromBlock(org.mtr.mod.Blocks.PIDS_1.get().data));
        try{require(stack.getItem().onItemUse(stack,player,player.worldObj,pidsX,pidsY-1,pidsZ,1,.5F,.5F,.5F),"Original PIDS ItemBlock placement rejected");}
        finally{player.rotationYaw=priorYaw;serverSetupOrientationWrites+=2;}
        require(player.worldObj.getBlock(pidsX,pidsY,pidsZ)==org.mtr.mod.Blocks.PIDS_1.get().data&&player.worldObj.getBlock(pidsX,pidsY,pidsZ-1)==org.mtr.mod.Blocks.PIDS_1.get().data,"Original PIDS pair missing");pidsFixtureReady=true;
    }
    private JsonObject pidsState(net.minecraft.world.World world) {
        net.minecraft.tileentity.TileEntity tile=world.getTileEntity(pidsX,pidsY,pidsZ);if(!(tile instanceof BlockPIDSBase.BlockEntityBase))return null;
        BlockPIDSBase.BlockEntityBase pids=(BlockPIDSBase.BlockEntityBase)tile;JsonObject value=new JsonObject();
        value.addProperty("message",pids.getMessage(0));value.addProperty("hide_arrival",pids.getHideArrival(0));value.addProperty("display_page",pids.getDisplayPage());
        JsonArray ids=new JsonArray();for(long id:pids.getPlatformIds())ids.add(Long.toString(id));value.add("platform_ids",ids);return value;
    }
    private TextFieldWidgetExtension pidsMessage()throws Exception{return ((TextFieldWidgetExtension[])field(actualScreen,"textFieldMessages"))[0];}
    private CheckboxWidgetExtension pidsHide()throws Exception{return ((CheckboxWidgetExtension[])field(actualScreen,"buttonsHideArrival"))[0];}
    private TextFieldWidgetExtension pidsPage()throws Exception{return (TextFieldWidgetExtension)field(actualScreen,"displayPageInput");}
    private void pidsControls()throws Exception {
        require(actualScreen instanceof PIDSConfigScreen&&new org.mtr.mapping.holder.BlockPos(pidsX,pidsY,pidsZ).equals(field(actualScreen,"blockPos"))&&((Number)field(actualScreen,"maxArrivals")).intValue()==1,"PIDS screen owner/max arrivals differ");
        require(PIDS_MESSAGE.equals(pidsMessage().getText2())&&pidsHide().isChecked2()&&"2".equals(pidsPage().getText2())&&((CheckboxWidgetExtension)field(actualScreen,"selectAllCheckbox")).isChecked2()==pidsAutomaticExpected,"PIDS native draft lost across selector/reopen");
        org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet ids=(org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet)field(actualScreen,"filterPlatformIds");
        require(ids.size()==(pidsSelected?1:0)&&(!pidsSelected||ids.contains(platformId)),"PIDS actual filter IDs differ");
    }
    private void recordPids(Minecraft client,String label)throws Exception {
        JsonObject sample=envelope();sample.addProperty("label",label);sample.add("server",pidsServer==null?null:copy(pidsServer));sample.addProperty("server_sample_at_nanos",pidsServerAt);sample.add("client",pidsState(client.theWorld));
        if(actualScreen instanceof PIDSConfigScreen){sample.addProperty("widget_message",pidsMessage().getText2());sample.addProperty("widget_hide",pidsHide().isChecked2());sample.addProperty("widget_page",pidsPage().getText2());}
        pidsSamples.add(sample);
    }
    private void recordPidsOpenWait(Minecraft client,net.minecraft.util.Vec3 eye,MovingObjectPosition ray) {
        JsonObject sample=new JsonObject();report.add("last_pids_open_wait_snapshot",sample);
        try{
            sample.add("stamp",envelope());sample.addProperty("diagnostic_only",true);sample.addProperty("controller_action_created",action!=null);
            sample.addProperty("ray_origin_x",eye.xCoord);sample.addProperty("ray_origin_y",eye.yCoord);sample.addProperty("ray_origin_z",eye.zCoord);
            sample.addProperty("native_pos_y",client.thePlayer.posY);sample.addProperty("feet_y",client.thePlayer.boundingBox.minY);sample.addProperty("native_eye_height",client.thePlayer.getEyeHeight());sample.addProperty("native_default_eye_height",client.thePlayer.getDefaultEyeHeight());sample.addProperty("native_y_offset",client.thePlayer.yOffset);
            sample.addProperty("yaw",client.thePlayer.rotationYaw);sample.addProperty("pitch",client.thePlayer.rotationPitch);sample.addProperty("render_view_entity_is_player",client.renderViewEntity==client.thePlayer);
            ItemStack held=client.thePlayer.getHeldItem();sample.addProperty("original_brush_held",held!=null&&held.getItem()==org.mtr.mod.Items.BRUSH.get().data);sample.addProperty("held_item_id",held==null?-1:Item.getIdFromItem(held.getItem()));
            sample.addProperty("ray_type",ray==null?"null":String.valueOf(ray.typeOfHit));if(ray!=null){sample.addProperty("ray_block_x",ray.blockX);sample.addProperty("ray_block_y",ray.blockY);sample.addProperty("ray_block_z",ray.blockZ);sample.addProperty("ray_side",ray.sideHit);if(ray.hitVec!=null){sample.addProperty("ray_hit_x",ray.hitVec.xCoord);sample.addProperty("ray_hit_y",ray.hitVec.yCoord);sample.addProperty("ray_hit_z",ray.hitVec.zCoord);}}
            sample.addProperty("native_C08_count",c08.get());sample.addProperty("pids_open_packets",pidsOpenPackets);sample.addProperty("last_pids_open_at_nanos",lastPidsOpenAt);sample.addProperty("open_action_at_nanos",openAt);
            sample.addProperty("render_sample_at_nanos",clientView==null?0:clientView.atNanos);sample.addProperty("server_sample_at_nanos",serverView==null?0:serverView.atNanos);sample.addProperty("pids_server_sample_at_nanos",pidsServerAt);sample.add("pids_server",pidsServer==null?null:copy(pidsServer));
        }catch(Throwable diagnostic){sample.addProperty("diagnostic_error",diagnostic.toString());}
    }
    private void pidsStep(Minecraft client)throws Exception {
        require(zeroMovement(client),"PIDS GUI movement changed");
        if(stage==Stage.PIDS_SETUP){
            if(!brushEquipped||client.thePlayer.getHeldItem()==null||client.thePlayer.getHeldItem().getItem()!=org.mtr.mod.Items.BRUSH.get().data||pidsState(client.theWorld)==null)return;
            require(client.currentScreen==null&&pidsFixtureReady,"PIDS original fixture missing");advance(Stage.PIDS_OPEN_FIRST);return;
        }
        if(stage==Stage.PIDS_OPEN_FIRST||stage==Stage.PIDS_OPEN_SECOND||stage==Stage.PIDS_OPEN_THIRD){
            net.minecraft.util.Vec3 eye=client.thePlayer.getPosition(1F);recordPidsOpenWait(client,eye,client.objectMouseOver);
            if(action==null){
                require(client.currentScreen==null,"PIDS open needs ordinary world view");
                double dx=pidsX+.5-eye.xCoord,dz=pidsZ+.5-eye.zCoord,dy=pidsY+.35-eye.yCoord;
                client.thePlayer.rotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);client.thePlayer.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));worldOpenOrientationWrites+=2;
                MovingObjectPosition ray=client.objectMouseOver;if(ray==null||ray.typeOfHit!=MovingObjectPosition.MovingObjectType.BLOCK||ray.blockX!=pidsX||ray.blockY!=pidsY||ray.blockZ!=pidsZ)return;
                require(client.thePlayer.getDistanceSq(pidsX+.5,pidsY+.5,pidsZ+.5)<20.25&&ray.hitVec!=null,"Original PIDS hit/reach differs");
                action=new Action("original_controller_brush_PIDS_open",-4,null);action.release=true;openAt=System.nanoTime();
                require(client.playerController.onPlayerRightClick(client.thePlayer,client.theWorld,client.thePlayer.getHeldItem(),ray.blockX,ray.blockY,ray.blockZ,ray.sideHit,ray.hitVec),"Original PIDS brush open rejected");input("original_controller_PIDS_world_open_not_queued_RMB");return;
            }
            if(client.currentScreen==null||!laterFrame()||lastPidsOpenAt<=openAt||clientView.screenIdentity!=System.identityHashCode(client.currentScreen)||clientView.atNanos<=lastPidsOpenAt)return;
            require(client.currentScreen instanceof PIDSConfigScreen&&pidsOpenPackets==(stage==Stage.PIDS_OPEN_FIRST?1:stage==Stage.PIDS_OPEN_SECOND?2:3),"Original PIDS open response/screen differs");
            actualScreen=client.currentScreen;
            if(stage==Stage.PIDS_OPEN_FIRST){firstPids=(PIDSConfigScreen)actualScreen;require(pidsMessage().getText2().isEmpty()&&!pidsHide().isChecked2()&&"1".equals(pidsPage().getText2()),"Fresh PIDS controls differ");capture(client,"pids-initial");advance(Stage.PIDS_MESSAGE_CLEAR);}
            else{require(actualScreen!=firstPids,"PIDS reopen reused previous screen");if(stage==Stage.PIDS_OPEN_THIRD){require(actualScreen!=secondPids,"Third PIDS open reused second screen");pidsSelected=false;pidsAutomaticExpected=true;advance(Stage.PIDS_CHECK_THIRD);}else{secondPids=(PIDSConfigScreen)actualScreen;advance(Stage.PIDS_CHECK_SECOND);}}return;
        }
        if(stage==Stage.PIDS_CLOSE_FIRST||stage==Stage.PIDS_CLOSE_SECOND||stage==Stage.PIDS_CLOSE_THIRD){
            if(action==null){pidsControls();recordPids(client,"before-native-ESC");queueEscape();return;}
            observeAction();if(!action.release||client.currentScreen!=null||!laterFrame())return;
            doneAt=action.atNanos;advance(stage==Stage.PIDS_CLOSE_FIRST?Stage.PIDS_ACK:stage==Stage.PIDS_CLOSE_SECOND?Stage.PIDS_CLEAR_ACK:Stage.PIDS_FINAL_ACK);return;
        }
        if(stage==Stage.PIDS_ACK||stage==Stage.PIDS_CLEAR_ACK||stage==Stage.PIDS_FINAL_ACK){
            JsonObject server=pidsServer,local=pidsState(client.theWorld);int expectedPackets=stage==Stage.PIDS_ACK?1:stage==Stage.PIDS_CLEAR_ACK?2:3;
            if(server==null||local==null||pidsServerAt<=doneAt||lastPidsUpdateAt<=doneAt||pidsUpdatePackets!=expectedPackets||!pidsExpected.equals(server)||!pidsExpected.equals(local))return;
            recordPids(client,stage==Stage.PIDS_ACK?"nonempty-original-close-ack":stage==Stage.PIDS_CLEAR_ACK?"automatic-cleared-original-close-ack":"third-original-close-ack");
            if(stage==Stage.PIDS_ACK){nativeEvidence.addProperty("pids_original_packet_server_client_ack",true);advance(Stage.PIDS_OPEN_SECOND);}
            else if(stage==Stage.PIDS_CLEAR_ACK){nativeEvidence.addProperty("pids_automatic_cleared_original_server_client_ack",true);advance(Stage.PIDS_OPEN_THIRD);}
            else{require(routeAck.equals(route(MinecraftClientData.getInstance())),"Route changed during PIDS GUI");require(baselinePlatform.equals(platform(MinecraftClientData.getInstance()))&&baselinePlatform.equals(platform(MinecraftClientData.getDashboardInstance()))&&coreView!=null&&baselinePlatform.equals(coreView.platform)&&baselineRail.equals(coreView.rail)&&baselineRail.equals(rail(MinecraftClientData.getInstance()))&&baselineRail.equals(rail(MinecraftClientData.getDashboardInstance())),"Seeded platform/rail changed during native GUI");nativeEvidence.addProperty("pids_mouse_layout_accepted",true);nativeEvidence.addProperty("normal_close",true);complete=true;advance(Stage.SAVE);}return;
        }
        if(stage==Stage.PIDS_FILTER_REVEAL){
            if(action==null){
                require(client.currentScreen instanceof DashboardListSelectorScreen,"PIDS Edit did not open original selector");actualScreen=client.currentScreen;
                java.util.List<?> all=(java.util.List<?>)field(actualScreen,"allData");require(all.size()==1&&((DashboardListItem)all.get(0)).id==platformId,"Original selector platform fixture differs");
                DashboardList available=(DashboardList)field(actualScreen,"availableList");require(((java.util.List<?>)field(available,"dataSorted")).size()==1,"Original available platform row missing");capture(client,"pids-platform-selector-before-add");
                action=new Action("pids_available_platform_row_reveal",0,null);action.pointer=new Pointer(actualScreen.width,actualScreen.height,client.displayWidth,client.displayHeight,pixelScale(),available.x,available.y+24,available.width,20);
                action.moveBefore=wrapperNanos();queueMove(action.pointer);action.moveAfter=wrapperNanos();input("queued_pids_platform_row_reveal");return;
            }
            observeAction();if(!action.move||!laterFrame())return;
            DashboardList available=(DashboardList)field(actualScreen,"availableList");require(action.pointer.same(client,actualScreen,pixelScale())&&available.getHoverItemIndex()==0,"Native PIDS available row reveal differs");
            ClickableWidgetExtension add=(ClickableWidgetExtension)field(available,"buttonAdd");require(add.visible&&add.getActiveMapped(),"Original PIDS row Add icon hidden");advance(Stage.PIDS_FILTER_ADD);return;
        }
        if(stage==Stage.PIDS_FILTER_ADD){
            require(client.currentScreen==actualScreen&&actualScreen instanceof DashboardListSelectorScreen,"PIDS selector replaced before Add");
            DashboardList available=(DashboardList)field(actualScreen,"availableList");
            if(!click(client,(ClickableWidgetExtension)field(available,"buttonAdd"),0,"pids_original_available_platform_add",false))return;
            org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection selected=(org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection)field(actualScreen,"selectedIds");
            DashboardList chosen=(DashboardList)field(actualScreen,"selectedList");java.util.List<?> chosenData=(java.util.List<?>)field(chosen,"dataSorted");
            require(selected.size()==1&&selected.contains(platformId)&&chosenData.size()==1&&((DashboardListItem)chosenData.get(0)).id==platformId&&((java.util.List<?>)field(available,"dataSorted")).isEmpty(),"Original native Add did not move exact platform into selected list");
            capture(client,"pids-platform-selector-selected");nativeEvidence.addProperty("pids_native_platform_row_add",true);advance(Stage.PIDS_FILTER_DONE);return;
        }
        if(stage==Stage.PIDS_FILTER_DONE){
            if(!click(client,widget("buttonDone"),0,"pids_selector_done",false))return;
            pidsSelected=true;pidsAutomaticExpected=false;JsonArray ids=new JsonArray();ids.add(Long.toString(platformId));pidsExpected.add("platform_ids",ids);
            advance(Stage.PIDS_FILTER_RETURN);return;
        }
        if(stage==Stage.PIDS_FILTER_RETURN){
            require(client.currentScreen==firstPids,"PIDS selector did not return original screen");actualScreen=client.currentScreen;
            pidsControls();capture(client,"pids-draft-after-selector");nativeEvidence.addProperty("pids_draft_preserved_across_selector",true);advance(Stage.PIDS_CLOSE_FIRST);return;
        }
        if(stage==Stage.PIDS_FILTER_OPEN){if(!click(client,widget("filterButton"),0,"pids_filter_edit",false))return;advance(Stage.PIDS_FILTER_REVEAL);return;}
        require(client.currentScreen==actualScreen&&actualScreen instanceof PIDSConfigScreen,"PIDS native screen replaced");
        if(stage==Stage.PIDS_MESSAGE_CLEAR){if(!click(client,pidsMessage(),1,"pids_message_focus_clear",false))return;advance(Stage.PIDS_MESSAGE_TYPE);return;}
        if(stage==Stage.PIDS_MESSAGE_TYPE){if(!typeText(pidsMessage(),PIDS_MESSAGE))return;advance(Stage.PIDS_HIDE);return;}
        if(stage==Stage.PIDS_HIDE){if(!click(client,pidsHide(),0,"pids_hide_arrival",false))return;require(pidsHide().isChecked2(),"PIDS hide checkbox did not toggle");advance(Stage.PIDS_PAGE_CLEAR);return;}
        if(stage==Stage.PIDS_PAGE_CLEAR){if(!click(client,pidsPage(),1,"pids_page_clear",false))return;advance(Stage.PIDS_PAGE_TYPE);return;}
        if(stage==Stage.PIDS_PAGE_TYPE){if(!typeText(pidsPage(),"2"))return;advance(Stage.PIDS_AUTO_OFF);return;}
        if(stage==Stage.PIDS_AUTO_OFF||stage==Stage.PIDS_AUTO_ON){
            boolean on=stage==Stage.PIDS_AUTO_ON;if(!click(client,widget("selectAllCheckbox"),0,on?"pids_auto_on":"pids_auto_off",false))return;
            require(((CheckboxWidgetExtension)widget("selectAllCheckbox")).isChecked2()==on,"PIDS automatic checkbox did not toggle");
            if(!on)advance(Stage.PIDS_AUTO_ON);else{
                pidsControls();pidsExpected=new JsonObject();pidsExpected.addProperty("message",PIDS_MESSAGE);pidsExpected.addProperty("hide_arrival",true);pidsExpected.addProperty("display_page",1);pidsExpected.add("platform_ids",new JsonArray());
                capture(client,"pids-native-draft-before-selector");advance(Stage.PIDS_FILTER_OPEN);
            }return;
        }
        if(stage==Stage.PIDS_CHECK_SECOND){pidsControls();recordPids(client,"reopened-nonempty-original-widget-values");capture(client,"pids-reopened-nonempty-filter");nativeEvidence.addProperty("pids_nonempty_filter_original_reopened_widget_values",true);advance(Stage.PIDS_AUTOMATIC_CLEAR);return;}
        if(stage==Stage.PIDS_AUTOMATIC_CLEAR){
            if(!click(client,widget("selectAllCheckbox"),0,"pids_native_automatic_on_clear_filter",false))return;
            require(((CheckboxWidgetExtension)widget("selectAllCheckbox")).isChecked2(),"Original automatic checkbox did not turn on");pidsAutomaticExpected=true;pidsControls();capture(client,"pids-automatic-on-before-resize");advance(Stage.PIDS_RESIZE);return;
        }
        if(stage==Stage.PIDS_RESIZE){
            int oldWidth=actualScreen.width,oldHeight=actualScreen.height,oldScale=client.gameSettings.guiScale;
            client.gameSettings.guiScale=oldScale==1?2:1;++optionWrites;
            ScaledResolution resized=new ScaledResolution(client,client.displayWidth,client.displayHeight);
            require(resized.getScaledWidth()!=oldWidth||resized.getScaledHeight()!=oldHeight,"RAM GUI scale did not resize actual GUI");
            JsonObject sizes=envelope();sizes.addProperty("old_width",oldWidth);sizes.addProperty("old_height",oldHeight);sizes.addProperty("new_width",resized.getScaledWidth());sizes.addProperty("new_height",resized.getScaledHeight());sizes.addProperty("old_gui_scale",oldScale);sizes.addProperty("new_gui_scale",client.gameSettings.guiScale);report.add("pids_original_resize",sizes);
            actualScreen.setWorldAndResolution(client,resized.getScaledWidth(),resized.getScaledHeight());pidsControls();resizeAtNanos=System.nanoTime();resizeTick=tick;resizeFrame=frame;advance(Stage.PIDS_RESIZED);return;
        }
        if(stage==Stage.PIDS_RESIZED){
            if(tick<=resizeTick||frame<=resizeFrame||clientView==null||clientView.phase!=stage||clientView.atNanos<=resizeAtNanos)return;
            pidsControls();recordPids(client,"automatic-on-preserved-after-actual-gui-resize");capture(client,"pids-automatic-on-after-resize");nativeEvidence.addProperty("pids_native_automatic_on_preserved_after_resize",true);pidsExpected.add("platform_ids",new JsonArray());advance(Stage.PIDS_CLOSE_SECOND);return;
        }
        if(stage==Stage.PIDS_CHECK_THIRD){pidsControls();recordPids(client,"reopened-cleared-original-widget-values");capture(client,"pids-reopened-cleared-filter");nativeEvidence.addProperty("pids_original_reopened_widget_values",true);advance(Stage.PIDS_CLOSE_THIRD);return;}
        throw new AssertionError("Unhandled PIDS stage "+stage);
    }

    private void runDraftLifecycleRegression(Minecraft client)throws Exception {
        require(client.theWorld==null&&client.getIntegratedServer()==null&&MinecraftClientData.getInstance().stations.isEmpty()&&MinecraftClientData.getInstance().platforms.isEmpty(),"Lifecycle regression requires empty copied menu context");
        GuiScreen previous=client.currentScreen;
        PIDSConfigScreen pids=new PIDSConfigScreen(new org.mtr.mapping.holder.BlockPos(0,0,0),1);
        try{
            client.displayGuiScreen(pids);
            TextFieldWidgetExtension message=((TextFieldWidgetExtension[])field(pids,"textFieldMessages"))[0];
            CheckboxWidgetExtension hide=((CheckboxWidgetExtension[])field(pids,"buttonsHideArrival"))[0];
            TextFieldWidgetExtension page=(TextFieldWidgetExtension)field(pids,"displayPageInput");
            require(pids.mouseClicked2(message.getX2()+8,message.getY2()+8,0),"Lifecycle draft focus");
            for(char c:"Draft PIDS".toCharArray())require(pids.charTyped2(c,0),"Lifecycle draft character callback");
            require(pids.mouseClicked2(hide.getX2()+8,hide.getY2()+8,0)&&hide.isChecked2(),"Lifecycle hide toggle");
            require(pids.mouseClicked2(page.getX2()+8,page.getY2()+8,1)&&pids.charTyped2('2',0),"Lifecycle page draft");
            ((ClickableWidgetExtension)field(pids,"filterButton")).onClick2(0,0);
            require(client.currentScreen instanceof DashboardListSelectorScreen,"Original filter callback did not open selector");
            ((ClickableWidgetExtension)field(client.currentScreen,"buttonDone")).onClick2(0,0);
            require(client.currentScreen==pids,"Original selector Done did not restore PIDS");
            require("Draft PIDS".equals(message.getText2())&&hide.isChecked2()&&"2".equals(page.getText2()),"PIDS draft lost on selector return/reinit");
            CheckboxWidgetExtension automatic=(CheckboxWidgetExtension)field(pids,"selectAllCheckbox");
            require(automatic.isChecked2()&&pids.mouseClicked2(automatic.getX2()+8,automatic.getY2()+8,0)&&!automatic.isChecked2(),"Lifecycle manual automatic-off toggle");
            int oldWidth=message.getWidth2(),oldHideX=hide.getX2();
            pids.setWorldAndResolution(client,pids.width+214,pids.height+120);
            require("Draft PIDS".equals(message.getText2())&&hide.isChecked2()&&"2".equals(page.getText2()),"PIDS draft lost on resize reinit");
            require(!automatic.isChecked2(),"Empty PIDS filter manual automatic-off lost on resize");
            require(message.getWidth2()==oldWidth+214&&hide.getX2()==oldHideX+214,"PIDS reflow bounds did not update");
            // Callback regression fixture only: represent an existing nonempty saved filter.
            org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet ids=(org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet)field(pids,"filterPlatformIds");
            ids.add(42L);automatic.setChecked(false);
            require(pids.mouseClicked2(automatic.getX2()+8,automatic.getY2()+8,0)&&automatic.isChecked2(),"Lifecycle nonempty-filter automatic-on toggle");
            pids.setWorldAndResolution(client,pids.width+107,pids.height+60);
            require(automatic.isChecked2(),"Nonempty PIDS filter automatic-on lost on resize");
        }finally{client.displayGuiScreen(previous);}
    }

    static void selfCheckInputGeometry(){
        for(int[] size:new int[][]{{640,360,1280,720},{854,480,1708,960},{1280,720,2560,1440}}){
            for(float scale:new float[]{1F,2F})for(int[] bounds:new int[][]{{22,162,300,20},{420,164,180,20},{22,102,96,20},{100,680,200,20}}){
                if(bounds[0]+bounds[2]>size[0]||bounds[1]+bounds[3]>size[1])continue;
                Pointer point=new Pointer(size[0],size[1],size[2],size[3],scale,bounds[0],bounds[1],bounds[2],bounds[3]);
                require(point.x>=bounds[0]&&point.x<bounds[0]+bounds[2]&&point.y>=bounds[1]&&point.y<bounds[1]+bounds[3],"Geometry roundtrip self-check");
            }
        }
        boolean rejected=false;try{new Pointer(640,360,1280,720,2F,-1,10,100,20);}catch(AssertionError expected){rejected=true;}require(rejected,"Offscreen bounds must fail");
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        Stage phase=stage;
        if(event.phase!=TickEvent.Phase.END||failure!=null||phase==Stage.MENU||phase==Stage.WORLD||phase==Stage.SAVE||phase==Stage.FINISHED)return;
        try {
            MinecraftServer server=MinecraftServer.getServer();assertServer(server);EntityPlayerMP player=null;
            for(Object candidate:server.getConfigurationManager().playerEntityList){EntityPlayerMP value=(EntityPlayerMP)candidate;if(value.getUniqueID().equals(playerId))player=value;}
            if(player==null){require(phase==Stage.SETUP,"Owned server player missing");return;}
            require(player.worldObj==server.worldServerForDimension(0)&&player.dimension==0,"Foreign server world");
            if(!setupServerRequested){
                require(phase==Stage.SETUP,"Server setup outside setup phase");setupServerRequested=true;
                require(player.inventory.currentItem==0&&player.capabilities.isCreativeMode,"Fresh creative selected-slot setup differs");
                player.inventory.mainInventory[0]=new ItemStack(dashboardItem());++inventorySlotWrites;player.inventoryContainer.detectAndSendChanges();
                serverNetworkManager=player.playerNetServerHandler.netManager;installChannelObserver(serverNetworkManager);
                setupPidsFixture(player);
            }
            require(player.playerNetServerHandler.netManager==serverNetworkManager&&serverNetworkManager.isChannelOpen(),"Server manager replaced");
            if(simulator==null)simulator=findSimulator();
            if(phase==Stage.SEED&&!railFixtureRequested){railFixtureRequested=true;setupPlatformRailFixture(player);}
            if(phase.ordinal()>=Stage.PIDS_SETUP.ordinal()&&!brushEquipped){
                player.inventory.mainInventory[0]=new ItemStack(org.mtr.mod.Items.BRUSH.get().data);++inventorySlotWrites;player.inventoryContainer.detectAndSendChanges();brushEquipped=true;
            }
            if(pidsFixtureReady){pidsServer=pidsState(player.worldObj);pidsServerAt=System.nanoTime();}
            serverView=new ServerView(player);setupServerDone=true;
        }catch(Throwable problem){fail(problem);}
    }

    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null||stage==Stage.MENU||stage==Stage.WORLD||stage==Stage.SAVE||stage==Stage.FINISHED)return;
        try {
            Minecraft client=Minecraft.getMinecraft();assertClient(client);++frame;
            clientView=new ClientView(client);
        }catch(Throwable problem){fail(problem);}
    }

    private boolean click(Minecraft client,ClickableWidgetExtension widget,int button,String label,boolean row)throws Exception {
        if(action==null){
            require(containsIdentity((List<?>)field(actualScreen,"buttonList"),widget)&&widget.visible&&widget.getActiveMapped(),"Actual widget missing/hidden/disabled");
            action=new Action(label,button,widget);action.pointer=pointer(client,widget);action.moveBefore=wrapperNanos();queueMove(action.pointer);action.moveAfter=wrapperNanos();input("queued_move_"+label);return false;
        }
        observeAction();
        if(action.buttonsQueued)return action.release&&laterFrame();
        require(action.widget==widget&&action.pointer.same(client,actualScreen,pixelScale()),"Widget/dimensions changed after hover");
        require(widget.visible&&widget.getActiveMapped()&&containsIdentity((List<?>)field(actualScreen,"buttonList"),widget),"Hovered widget lost native membership");
        if(!action.move||!laterFrame())return false;
        require(action.pointer.hits(widget)&&widget.isHovered(),"Native rendered hover misses actual widget");
        if(row)require(listReady(actualScreen,stage==Stage.DRAW_FIRST?initialName:FINAL_NAME)&&((DashboardList)field(actualScreen,"dashboardList")).getHoverItemIndex()==0,"Wrong station row hover");
        capture(client,"hover-"+label+"-"+stage.name());
        action.atNanos=System.nanoTime();action.tick=tick;action.frame=frame;action.buttonsQueued=true;
        action.before=wrapperNanos();
        try{mouseClass.getMethod("addButtonEvent",int.class,boolean.class).invoke(null,button,true);input("queued_button_down_"+label);}
        finally{mouseClass.getMethod("addButtonEvent",int.class,boolean.class).invoke(null,button,false);action.after=wrapperNanos();input("queued_button_up_"+label);}
        return false;
    }

    private void queueButton(int button,String label)throws Exception {
        require(action==null,"Repeated native button action");action=new Action(label,button,null);action.buttonsQueued=true;
        action.before=wrapperNanos();
        try{mouseClass.getMethod("addButtonEvent",int.class,boolean.class).invoke(null,button,true);input("queued_button_down_"+label);}
        finally{mouseClass.getMethod("addButtonEvent",int.class,boolean.class).invoke(null,button,false);action.after=wrapperNanos();input("queued_button_up_"+label);}
    }

    private void recordCharacterPrefix(TextFieldWidgetExtension name,String text) {
        JsonObject value=new JsonObject();report.add("last_native_character_prefix",value);
        try{
            value.add("stamp",envelope());String expected=text.substring(0,charIndex+1),actual=name.getText2();
            value.addProperty("expected",expected);value.addProperty("actual",actual);value.addProperty("char_index",charIndex);
            JsonArray expectedPoints=new JsonArray(),actualPoints=new JsonArray();
            for(int codepoint:expected.codePoints().toArray())expectedPoints.add(codepoint);
            for(int codepoint:actual.codePoints().toArray())actualPoints.add(codepoint);
            value.add("expected_codepoints",expectedPoints);value.add("actual_codepoints",actualPoints);
            value.addProperty("queued_codepoint",action.codepoint);value.addProperty("observed_character",action.character);
            value.addProperty("wrapper_before",action.before);value.addProperty("wrapper_after",action.after);
            value.addProperty("action_at_nanos",action.atNanos);value.addProperty("action_tick",action.tick);value.addProperty("action_render_frame",action.frame);
            value.addProperty("field_identity",System.identityHashCode(name));value.addProperty("field_focused",name.isFocused2());
            value.addProperty("field_visible",name.visible);value.addProperty("field_active",name.getActiveMapped());
            value.addProperty("field_x",name.getX2());value.addProperty("field_y",name.getY2());value.addProperty("field_width",name.getWidth2());value.addProperty("field_height",name.getHeight2());
            value.addProperty("event_key",number(keyboardClass,"getEventKey"));
            value.addProperty("event_character_codepoint",(int)(Character)keyboardClass.getMethod("getEventCharacter").invoke(null));
            value.addProperty("event_pressed",(Boolean)keyboardClass.getMethod("getEventKeyState").invoke(null));
            value.addProperty("event_nanoseconds",number(keyboardClass,"getEventNanoseconds"));
        }catch(Throwable diagnostic){value.addProperty("diagnostic_error",diagnostic.toString());}
    }

    private void queueCharacter(int codepoint)throws Exception {
        require(action==null&&(stage==Stage.TYPE||stage==Stage.ROUTE_TYPE||stage==Stage.SEARCH_TYPE||stage==Stage.PIDS_MESSAGE_TYPE||stage==Stage.PIDS_PAGE_TYPE),"Character outside owned input stage");action=new Action("native_character",-2,null);action.codepoint=codepoint;
        Class<?> textEvent=Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent");
        action.before=wrapperNanos();
        Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents").getMethod("injectTextEvent",textEvent)
            .invoke(null,textEvent.getConstructor(String.class).newInstance(new String(Character.toChars(codepoint))));
        keyboardClass.getMethod("addCharEvent",int.class,int.class).invoke(null,0,codepoint);action.after=wrapperNanos();input("queued_character_"+codepoint);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void queueEscape()throws Exception {
        require(action==null,"Repeated ESC action");action=new Action("native_ESCAPE",-3,null);
        Class<?> keys=Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$KeyAction");
        action.before=wrapperNanos();
        try{keyboardClass.getMethod("addSdlKeyEvent",int.class,int.class,keys,int.class,int.class,long.class)
            .invoke(null,27,41,Enum.valueOf((Class)keys,"PRESSED"),0,0,wrapperNanos());input("queued_ESCAPE_down");}
        finally{keyboardClass.getMethod("addSdlKeyEvent",int.class,int.class,keys,int.class,int.class,long.class)
            .invoke(null,27,41,Enum.valueOf((Class)keys,"RELEASED"),0,0,wrapperNanos());action.after=wrapperNanos();input("queued_ESCAPE_up");}
    }

    private void observeAction()throws Exception {
        if(action==null)return;
        if(action.button>=0){
            long nanos=number(mouseClass,"getEventNanoseconds"),x=number(mouseClass,"getEventX"),y=number(mouseClass,"getEventY"),button=number(mouseClass,"getEventButton");
            boolean pressed=(Boolean)mouseClass.getMethod("getEventButtonState").invoke(null);
            if(action.pointer!=null&&!action.buttonsQueued&&button==-1&&!pressed&&x==action.pointer.rawX&&y==action.pointer.rawY&&between(nanos,action.moveBefore,action.moveAfter)){
                if(!action.move){action.move=true;action.observedAt=System.nanoTime();lastNativeInputObservedAt=action.observedAt;input("consumed_native_move");}
            }
            if(action.buttonsQueued&&!action.release&&button==action.button&&!pressed&&between(nanos,action.before,action.after)&&
                (action.pointer==null||x==action.pointer.rawX&&y==action.pointer.rawY)){
                action.release=true;action.observedAt=System.nanoTime();lastNativeInputObservedAt=action.observedAt;input("consumed_native_button_release");
            }
        }else {
            long nanos=number(keyboardClass,"getEventNanoseconds"),key=number(keyboardClass,"getEventKey");
            boolean pressed=(Boolean)keyboardClass.getMethod("getEventKeyState").invoke(null);
            if(!between(nanos,action.before,action.after))return;
            if(action.button==-2&&pressed&&key==0&&((Character)keyboardClass.getMethod("getEventCharacter").invoke(null))==action.codepoint){
                if(!action.character){action.character=true;action.observedAt=System.nanoTime();lastNativeInputObservedAt=action.observedAt;input("consumed_native_character");}
            }else if(action.button==-3&&key==1&&!pressed&&!action.release){action.release=true;action.observedAt=System.nanoTime();lastNativeInputObservedAt=action.observedAt;input("consumed_native_ESCAPE_release");}
        }
    }

    private boolean laterFrame(){return action!=null&&tick>action.tick&&frame>action.frame&&clientView!=null&&clientView.phase==stage&&clientView.serial==actionSerial&&clientView.atNanos>Math.max(action.atNanos,action.observedAt);}
    private boolean fresh(CoreView core,ClientView view){return core.phase==stage&&view.phase==stage&&core.serial==actionSerial&&view.serial==actionSerial&&core.id==stationId&&view.id==stationId&&
        core.run.equals(runId)&&view.run.equals(runId)&&view.world==clientWorld&&view.manager==networkManager&&view.player.equals(playerId)&&core.atNanos>0&&view.atNanos>0;}
    private TextFieldWidgetExtension nameField()throws Exception{return (TextFieldWidgetExtension)field(actualScreen,"textFieldName");}
    private boolean listReady(GuiScreen screen,String expected)throws Exception {
        if(!"STATIONS".equals(String.valueOf(field(screen,"selectedTab")))||field(screen,"editingArea")!=null)return false;
        DashboardList list=(DashboardList)field(screen,"dashboardList");List<?> sorted=(List<?>)field(list,"dataSorted");Map<?,?> filtered=(Map<?,?>)field(list,"dataFiltered");
        if(sorted.size()!=1||filtered.size()!=1||((Number)field(list,"page")).intValue()!=0)return false;
        TextFieldWidgetExtension search=(TextFieldWidgetExtension)field(list,"textFieldSearch");if(!search.getText2().isEmpty())return false;
        DashboardListItem item=(DashboardListItem)sorted.get(0),other=(DashboardListItem)filtered.get(0);
        return other!=null&&item.id==stationId&&other.id==stationId&&expected.equals(item.getName(false))&&expected.equals(other.getName(false));
    }

    private Path codeSource(Class<?> owner,String label)throws Exception {
        require(owner.getProtectionDomain()!=null&&owner.getProtectionDomain().getCodeSource()!=null,"Missing CodeSource for "+label);
        java.net.URL location=owner.getProtectionDomain().getCodeSource().getLocation();
        require(location!=null,"Missing CodeSource location for "+label);
        report.addProperty(label+"_codesource_scheme",location.getProtocol());
        require(("file".equals(location.getProtocol())||"jar".equals(location.getProtocol()))&&location.getQuery()==null&&location.getRef()==null,"Unexpected CodeSource protocol/query/fragment");
        if("jar".equals(location.getProtocol())){
            // Same standard-library resolution as the existing LiftReloadProbe Guard.
            java.net.JarURLConnection connection=(java.net.JarURLConnection)location.openConnection();
            String entry=connection.getEntryName();
            require(entry==null||entry.isEmpty()||entry.equals(owner.getName().replace('.','/')+".class"),"Unexpected nested/class CodeSource");
            report.addProperty(label+"_codesource_entry",entry==null?"":entry);
            location=connection.getJarFileURL(); // Parse only; never open a ZIP filesystem or network connection.
        }
        java.net.URI uri=location.toURI();
        require("file".equals(uri.getScheme())&&uri.getAuthority()==null&&uri.getQuery()==null&&uri.getFragment()==null,"CodeSource must be a local file JAR");
        Path file=checked(Paths.get(uri));
        require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&file.getFileName().toString().endsWith(".jar")&&file.getParent().equals(checked(COPIED_CLIENT.toPath().resolve("mods"))),"CodeSource escaped copied mods");
        report.addProperty(label+"_codesource_resolved_scheme",uri.getScheme());
        return file;
    }
    private void recordSetupWait(Minecraft client,ServerView server,CoreView core) {
        JsonObject snapshot=new JsonObject();
        try{
            snapshot.add("stamp",envelope());snapshot.addProperty("diagnostic_only",true);snapshot.addProperty("ready_at_wait",false);
            snapshot.addProperty("wait_snapshot_count",++setupWaitSnapshots);snapshot.addProperty("still_ticks_before_reset",stillTicks);
            ItemStack held=client.thePlayer.getHeldItem();Item original=(Item)Item.itemRegistry.getObject("mtr:dashboard");
            JsonObject predicates=new JsonObject();snapshot.add("predicates",predicates);
            predicates.addProperty("setup_server_done",setupServerDone);
            predicates.addProperty("channel_observer_ready",channelObserverReady);
            predicates.addProperty("server_view_present",server!=null);
            predicates.addProperty("core_view_present",core!=null);
            predicates.addProperty("core_phase_matches",core!=null&&core.phase==stage);
            predicates.addProperty("core_serial_matches",core!=null&&core.serial==actionSerial);
            predicates.addProperty("core_empty",core!=null&&core.count==0);
            predicates.addProperty("core_other_empty",core!=null&&core.otherCount==0);
            predicates.addProperty("server_held",server!=null&&server.held);
            predicates.addProperty("server_creative",server!=null&&server.creative);
            predicates.addProperty("server_dimension_zero",server!=null&&server.world==0);
            predicates.addProperty("server_manager_same",server!=null&&server.manager==serverNetworkManager);
            predicates.addProperty("client_held_nonnull",held!=null);
            predicates.addProperty("client_held_original",held!=null&&held.getItem()==original);
            predicates.addProperty("client_selected_slot_zero",client.thePlayer.inventory.currentItem==0);
            predicates.addProperty("no_screen",client.currentScreen==null);
            predicates.addProperty("in_game_has_focus",client.inGameHasFocus);
            predicates.addProperty("air",air(client));predicates.addProperty("zero_movement",zeroMovement(client));
            predicates.addProperty("client_on_ground",client.thePlayer.onGround);
            predicates.addProperty("server_on_ground",server!=null&&server.onGround);
            snapshot.addProperty("core_pending",corePending.get());snapshot.addProperty("simulator_present",simulator!=null);
            snapshot.addProperty("simulator_identity",simulator==null?0:System.identityHashCode(simulator));
            if(core!=null){snapshot.add("core_view",core.json());snapshot.addProperty("core_age_nanos",System.nanoTime()-core.atNanos);}
            if(server!=null){snapshot.add("server_view",server.json());snapshot.addProperty("server_age_nanos",System.nanoTime()-server.atNanos);}
            snapshot.addProperty("client_normal_station_count",MinecraftClientData.getInstance().stations.size());
            snapshot.addProperty("client_dashboard_station_count",MinecraftClientData.getDashboardInstance().stations.size());
            JsonObject nativeClient=new JsonObject();snapshot.add("native_client",nativeClient);
            nativeClient.addProperty("current_slot",client.thePlayer.inventory.currentItem);
            nativeClient.addProperty("held_item_id",held==null?-1:Item.getIdFromItem(held.getItem()));
            nativeClient.addProperty("held_item_name",held==null?"":String.valueOf(Item.itemRegistry.getNameForObject(held.getItem())));
            nativeClient.addProperty("player_uuid",client.thePlayer.getUniqueID().toString());nativeClient.addProperty("dimension",client.thePlayer.dimension);
            nativeClient.addProperty("world_identity",System.identityHashCode(client.theWorld));nativeClient.addProperty("player_identity",System.identityHashCode(client.thePlayer));
            NetworkManager actualManager=client.getNetHandler().getNetworkManager();nativeClient.addProperty("native_manager_identity",System.identityHashCode(actualManager));
            nativeClient.addProperty("native_manager_is_retained",actualManager==networkManager);nativeClient.addProperty("native_channel_open",actualManager.isChannelOpen());
            nativeClient.addProperty("client_world_tick",client.theWorld.getTotalWorldTime());nativeClient.addProperty("world_is_retained",client.theWorld==clientWorld);
            snapshot.addProperty("server_retained_manager_identity",serverNetworkManager==null?0:System.identityHashCode(serverNetworkManager));
            nativeClient.addProperty("x",client.thePlayer.posX);nativeClient.addProperty("feet_y",client.thePlayer.boundingBox.minY);nativeClient.addProperty("z",client.thePlayer.posZ);
            nativeClient.addProperty("rotation_yaw",client.thePlayer.rotationYaw);nativeClient.addProperty("rotation_pitch",client.thePlayer.rotationPitch);
            nativeClient.addProperty("on_ground",client.thePlayer.onGround);nativeClient.addProperty("creative",client.thePlayer.capabilities.isCreativeMode);
            nativeClient.addProperty("flying",client.thePlayer.capabilities.isFlying);nativeClient.addProperty("sneaking",client.thePlayer.isSneaking());
            nativeClient.addProperty("move_forward",client.thePlayer.movementInput.moveForward);nativeClient.addProperty("move_strafe",client.thePlayer.movementInput.moveStrafe);
            nativeClient.addProperty("jump",client.thePlayer.movementInput.jump);nativeClient.addProperty("sneak",client.thePlayer.movementInput.sneak);
            nativeClient.addProperty("key_forward",client.gameSettings.keyBindForward.getIsKeyPressed());
            nativeClient.addProperty("key_back",client.gameSettings.keyBindBack.getIsKeyPressed());
            nativeClient.addProperty("key_left",client.gameSettings.keyBindLeft.getIsKeyPressed());
            nativeClient.addProperty("key_right",client.gameSettings.keyBindRight.getIsKeyPressed());
            nativeClient.addProperty("key_jump",client.gameSettings.keyBindJump.getIsKeyPressed());
            nativeClient.addProperty("key_sneak",client.gameSettings.keyBindSneak.getIsKeyPressed());
            nativeClient.addProperty("screen_class",client.currentScreen==null?"null":client.currentScreen.getClass().getName());
            nativeClient.addProperty("screen_identity",client.currentScreen==null?0:System.identityHashCode(client.currentScreen));
            nativeClient.addProperty("in_game_has_focus",client.inGameHasFocus);nativeClient.addProperty("native_game_paused",client.isGamePaused());
            nativeClient.addProperty("pause_on_lost_focus",client.gameSettings.pauseOnLostFocus);
            nativeClient.addProperty("render_view_entity_present",client.renderViewEntity!=null);
            nativeClient.addProperty("render_view_entity_is_player",client.renderViewEntity==client.thePlayer);
            nativeClient.addProperty("render_view_entity_identity",client.renderViewEntity==null?0:System.identityHashCode(client.renderViewEntity));
            MovingObjectPosition ray=client.objectMouseOver;
            nativeClient.addProperty("ray_is_null",ray==null);nativeClient.addProperty("ray_type",ray==null?"null":String.valueOf(ray.typeOfHit));
            if(ray!=null){nativeClient.addProperty("ray_block_x",ray.blockX);nativeClient.addProperty("ray_block_y",ray.blockY);nativeClient.addProperty("ray_block_z",ray.blockZ);
                nativeClient.addProperty("ray_side",ray.sideHit);nativeClient.addProperty("ray_entity_present",ray.entityHit!=null);
                if(ray.hitVec!=null){nativeClient.addProperty("ray_hit_x",ray.hitVec.xCoord);nativeClient.addProperty("ray_hit_y",ray.hitVec.yCoord);nativeClient.addProperty("ray_hit_z",ray.hitVec.zCoord);}}
            try{nativeClient.addProperty("native_display_active",(Boolean)Class.forName("org.lwjglx.opengl.Display").getMethod("isActive").invoke(null));}
            catch(Throwable diagnostic){nativeClient.addProperty("native_display_query_error",diagnostic.toString());}
            try{nativeClient.addProperty("native_mouse_grabbed",(Boolean)mouseClass.getMethod("isGrabbed").invoke(null));}
            catch(Throwable diagnostic){nativeClient.addProperty("native_mouse_query_error",diagnostic.toString());}
        }catch(Throwable diagnostic){snapshot.addProperty("diagnostic_error",diagnostic.toString());}
        lastSetupSnapshot=snapshot;
    }
    private void prepareInput()throws Exception {
        mouseClass=Class.forName("org.lwjglx.input.Mouse");keyboardClass=Class.forName("org.lwjglx.input.Keyboard");
        Path wrapper=codeSource(mouseClass,"mouse_wrapper");
        require(WRAPPER_SHA.equals(sha(wrapper))&&wrapper.equals(codeSource(keyboardClass,"keyboard_wrapper")),"Pinned native input wrapper differs");
        mouseClass.getMethod("addMoveEvent",Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent"));mouseClass.getMethod("addButtonEvent",int.class,boolean.class);
        keyboardClass.getMethod("addCharEvent",int.class,int.class);
        Class<?> textEvent=Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent"),textInput=Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents");
        require(wrapper.equals(codeSource(textEvent,"text_event"))&&wrapper.equals(codeSource(textInput,"text_input_dispatcher")),"Pinned native text input wrapper differs");
        textEvent.getConstructor(String.class);textInput.getMethod("injectTextEvent",textEvent);
        report.addProperty("input_wrapper_sha256",WRAPPER_SHA);report.addProperty("input_wrapper_path",wrapper.toString());
    }
    private long wrapperNanos()throws Exception{return number(Class.forName("org.lwjglx.Sys"),"getNanoTime");}
    private float pixelScale()throws Exception{return ((Number)Class.forName("org.lwjglx.opengl.Display").getMethod("getPixelScaleFactor").invoke(null)).floatValue();}
    private static long number(Class<?> type,String method)throws Exception{return ((Number)type.getMethod(method).invoke(null)).longValue();}
    private static boolean between(long value,long before,long after){return before>0&&after>=before&&value>=before&&value<=after;}
    private Pointer pointer(Minecraft client,ClickableWidgetExtension widget)throws Exception {
        require(actualScreen.width>=288&&actualScreen.height>=124,"GUI too small");
        return new Pointer(actualScreen.width,actualScreen.height,client.displayWidth,client.displayHeight,pixelScale(),widget);
    }
    private void queueMove(Pointer point)throws Exception {
        Class<?> type=Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent");Object event=type.getMethod("calloc").invoke(null);
        try{type.getMethod("x",float.class).invoke(event,point.sdlX);type.getMethod("y",float.class).invoke(event,point.sdlY);
            type.getMethod("xrel",float.class).invoke(event,0F);type.getMethod("yrel",float.class).invoke(event,0F);mouseClass.getMethod("addMoveEvent",type).invoke(null,event);
        }finally{type.getMethod("free").invoke(event);}
    }
    private static final class Pointer {
        final int w,h,dw,dh,rawX,rawY,x,y,bx,by,bw,bh;final float scale,sdlX,sdlY;final double hoverX,hoverY;
        Pointer(int w,int h,int dw,int dh,float scale,ClickableWidgetExtension widget){
            this(w,h,dw,dh,scale,widget.getX2(),widget.getY2(),widget.getWidth2(),widget.getHeight2());
            require(hits(widget),"Mapped native point misses actual widget");
        }
        Pointer(int w,int h,int dw,int dh,float scale,int bx,int by,int bw,int bh){
            require(dw>0&&dh>0&&Float.isFinite(scale)&&scale>0,"Invalid display dimensions/scale");this.w=w;this.h=h;this.dw=dw;this.dh=dh;this.scale=scale;
            this.bx=bx;this.by=by;this.bw=bw;this.bh=bh;
            require(bw>4&&bh>4&&bx>=0&&by>=0&&bx+bw<=w&&by+bh<=h,"Widget bounds outside actual GUI");
            int targetX=bx+bw/2,targetY=by+bh/2;
            rawX=(int)Math.floor((targetX+.5)*dw/w);rawY=(int)Math.floor((h-targetY-.5)*dh/h);
            require((long)rawX*w<=Integer.MAX_VALUE&&(long)rawY*h<=Integer.MAX_VALUE,"Native mouse integer overflow");
            sdlX=(rawX+.25F)/scale;sdlY=(dh-rawY+.25F)/scale;
            require((int)(sdlX*scale)==rawX&&dh-(int)(sdlY*scale)==rawY,"SDL float truncation lost pixel");
            x=rawX*w/dw;y=h-rawY*h/dh-1;hoverX=rawX*w/(double)dw;hoverY=h-rawY*h/(double)dh-1;
            require(x>=bx&&x<bx+bw&&y>=by&&y<by+bh&&hoverX>=bx&&hoverX<bx+bw&&hoverY>=by&&hoverY<by+bh,"Mapped native point misses target");
        }
        boolean hits(ClickableWidgetExtension widget){return bx==widget.getX2()&&by==widget.getY2()&&bw==widget.getWidth2()&&bh==widget.getHeight2()&&
            x>=bx&&x<bx+bw&&y>=by&&y<by+bh&&hoverX>=bx&&hoverX<bx+bw&&hoverY>=by&&hoverY<by+bh;}
        boolean same(Minecraft client,GuiScreen screen,float scale){return w==screen.width&&h==screen.height&&dw==client.displayWidth&&dh==client.displayHeight&&this.scale==scale;}
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("gui_width",w);result.addProperty("gui_height",h);result.addProperty("display_width",dw);result.addProperty("display_height",dh);result.addProperty("pixel_scale",scale);
            result.addProperty("gui_x",x);result.addProperty("gui_y",y);result.addProperty("raw_x",rawX);result.addProperty("raw_y",rawY);result.addProperty("sdl_x",sdlX);result.addProperty("sdl_y",sdlY);
            result.addProperty("widget_x",bx);result.addProperty("widget_y",by);result.addProperty("widget_width",bw);result.addProperty("widget_height",bh);return result;}
    }

    private final class Action {
        final long serial=++actionSerial;final String label;final int button;final Object widget;
        int tick=DashboardGuiProbe.this.tick,codepoint;long frame=DashboardGuiProbe.this.frame,atNanos=System.nanoTime(),observedAt,before,after,moveBefore,moveAfter;
        boolean move,buttonsQueued,release,character;Pointer pointer;
        Action(String label,int button,Object widget){this.label=label;this.button=button;this.widget=widget;}
    }
    private void readCoreSoon(){
        final Simulator value=simulator;if(value==null||!corePending.compareAndSet(false,true))return;
        final Stage phase=stage;final long serial=actionSerial,id=stationId;final String run=runId;
        value.run(()->{try{coreView=new CoreView(value,phase,serial,id,run);}catch(Throwable problem){fail(problem);}finally{corePending.set(false);}});
    }
    private static Simulator findSimulator()throws Exception {
        Field main=Init.class.getDeclaredField("main");main.setAccessible(true);Object actual=main.get(null);require(actual!=null,"Original server Core Main missing");
        for(Object candidate:(Iterable<?>)field(actual,"simulators")){Simulator value=(Simulator)candidate;if("minecraft/overworld".equals(value.dimension))return value;}
        throw new AssertionError("Original overworld simulator missing");
    }
    private static final class ServerView {
        final long atNanos=System.nanoTime(),worldTick;final NetworkManager manager;final int world;final boolean held,creative,onGround;
        final int selectedSlot,heldItemId;final String heldItemName;final UUID nativePlayer;final Object nativeWorld;
        final double x,y,z;final float yaw,pitch;final boolean flying;
        ServerView(EntityPlayerMP player){worldTick=player.worldObj.getTotalWorldTime();manager=player.playerNetServerHandler.netManager;world=player.dimension;
            held=player.getHeldItem()!=null&&player.getHeldItem().getItem()==dashboardItem()&&player.inventory.currentItem==0;creative=player.capabilities.isCreativeMode;onGround=player.onGround;
            selectedSlot=player.inventory.currentItem;ItemStack stack=player.getHeldItem();heldItemId=stack==null?-1:Item.getIdFromItem(stack.getItem());
            heldItemName=stack==null?"":String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));nativePlayer=player.getUniqueID();nativeWorld=player.worldObj;
            x=player.posX;y=player.boundingBox.minY;z=player.posZ;yaw=player.rotationYaw;pitch=player.rotationPitch;flying=player.capabilities.isFlying;}
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("at_nanos",atNanos);result.addProperty("world_tick",worldTick);result.addProperty("dimension",world);
            result.addProperty("manager_identity",System.identityHashCode(manager));result.addProperty("native_player_uuid",nativePlayer.toString());result.addProperty("native_world_identity",System.identityHashCode(nativeWorld));
            result.addProperty("selected_slot",selectedSlot);result.addProperty("native_held_item_id",heldItemId);result.addProperty("native_held_item_name",heldItemName);
            result.addProperty("original_held_item",held);result.addProperty("creative",creative);result.addProperty("server_on_ground",onGround);result.addProperty("flying",flying);
            result.addProperty("x",x);result.addProperty("feet_y",y);result.addProperty("z",z);result.addProperty("yaw",yaw);result.addProperty("pitch",pitch);return result;}
    }
    private final class CoreView {
        final Stage phase;final long serial,id,atNanos;final String run,hex,railHex;final long platformId;final int count,otherCount;final JsonObject station,route,platform,rail;
        CoreView(Simulator data,Stage phase,long serial,long id,String run){
            this.phase=phase;this.serial=serial;this.id=id;this.run=run;count=data.stations.size();
            otherCount=data.platforms.size()+data.sidings.size()+data.routes.size()+data.depots.size()+data.rails.size()+data.lifts.size();
            Station found=null;for(Station value:data.stations)if(value.getId()==id){require(found==null,"Duplicate Core station");found=value;}
            station=found==null?null:full(found);hex=found==null?"":found.getHexId();
            Route routeFound=null;for(Route value:data.routes)if(value.getId()==routeId){require(routeFound==null,"Duplicate Core route");routeFound=value;}
            route=routeFound==null?null:full(routeFound);
            Platform platformFound=null;for(Platform value:data.platforms)if(value.containsPos(railStart())&&value.containsPos(railEnd())){require(platformFound==null,"Duplicate Core platform");platformFound=value;}
            platform=platformFound==null?null:full(platformFound);platformId=platformFound==null?0:platformFound.getId();
            Rail railFound=null;for(Rail value:data.rails)if(value.getHexId().equals(TwoPositionsBase.getHexId(railStart(),railEnd()))){require(railFound==null&&value.isValid()&&value.isPlatform()&&value.getTransportMode()==TransportMode.TRAIN,"Original platform rail identity/flags differ");railFound=value;}
            rail=railFound==null?null:full(railFound);railHex=railFound==null?"":railFound.getHexId();atNanos=System.nanoTime();
        }
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("run_id",run);result.addProperty("phase",phase.name());result.addProperty("action_serial",serial);result.addProperty("station_id",Long.toString(id));
            result.addProperty("at_nanos",atNanos);result.addProperty("station_count",count);result.addProperty("other_core_count",otherCount);result.add("station",station);result.add("route",route);result.add("platform",platform);result.add("rail",rail);result.addProperty("generated_platform_id",Long.toString(platformId));result.addProperty("rail_hex",railHex);return result;}
    }
    private final class ClientView {
        final Stage phase=stage;final long serial=actionSerial,id=stationId,atNanos,renderFrame=frame;final int clientTick=tick,screenIdentity;
        final String run=runId;final UUID player;final Object world;final NetworkManager manager;final JsonObject normal,dashboard,platformNormal,platformDashboard,railNormal,railDashboard;
        ClientView(Minecraft client){player=client.thePlayer.getUniqueID();world=client.theWorld;manager=client.getNetHandler().getNetworkManager();screenIdentity=client.currentScreen==null?0:System.identityHashCode(client.currentScreen);
            normal=station(MinecraftClientData.getInstance(),id);dashboard=station(MinecraftClientData.getDashboardInstance(),id);platformNormal=platform(MinecraftClientData.getInstance());platformDashboard=platform(MinecraftClientData.getDashboardInstance());railNormal=rail(MinecraftClientData.getInstance());railDashboard=rail(MinecraftClientData.getDashboardInstance());atNanos=System.nanoTime();}
        JsonObject json(){JsonObject result=new JsonObject();result.addProperty("run_id",run);result.addProperty("phase",phase.name());result.addProperty("action_serial",serial);result.addProperty("station_id",Long.toString(id));
            result.addProperty("at_nanos",atNanos);result.addProperty("client_tick",clientTick);result.addProperty("render_frame",renderFrame);result.addProperty("screen_identity",screenIdentity);
            result.addProperty("player_uuid",player.toString());result.addProperty("network_manager_identity",System.identityHashCode(manager));result.add("normal_station",normal);result.add("dashboard_station",dashboard);result.add("normal_platform",platformNormal);result.add("dashboard_platform",platformDashboard);result.add("normal_rail",railNormal);result.add("dashboard_rail",railDashboard);return result;}
    }

    @SubscribeEvent public void clientPacket(FMLNetworkEvent.ClientCustomPacketEvent event){
        if(event.manager!=networkManager||!"mtr:packet".equals(event.packet.channel()))return;
        String type=packetType(event.packet.payload());long at=System.nanoTime();
        if("org.mtr.mod.packet.PacketOpenDashboardScreen".equals(type)){openPackets.incrementAndGet();lastOpenAt=at;}
        else if("org.mtr.mod.packet.PacketUpdateData".equals(type)){clientUpdates.incrementAndGet();lastClientUpdateAt=at;}
        else if("org.mtr.mod.packet.PacketOpenPIDSConfigScreen".equals(type)){++pidsOpenPackets;lastPidsOpenAt=at;}
        else return;
        packetEvent(type,"client_inbound_original_FML_channel",at);
    }
    @SubscribeEvent public void serverPacket(FMLNetworkEvent.ServerCustomPacketEvent event){
        if(event.manager!=serverNetworkManager||!"mtr:packet".equals(event.packet.channel()))return;
        String type=packetType(event.packet.payload());
        if("org.mtr.mod.packet.PacketUpdateData".equals(type)){long at=System.nanoTime();serverUpdates.incrementAndGet();lastServerUpdateAt=at;packetEvent(type,"server_inbound_original_FML_channel",at);}
        else if("org.mtr.mod.packet.PacketUpdatePIDSConfig".equals(type)){long at=System.nanoTime();++pidsUpdatePackets;lastPidsUpdateAt=at;packetEvent(type,"server_inbound_original_FML_channel",at);}
    }
    private static String packetType(ByteBuf frame){
        int start=frame.readerIndex(),size=frame.readableBytes();if(size<20||frame.getInt(start+8)!=0||frame.getInt(start+12)!=1)return null;
        int length=frame.getInt(start+16);if(length<1||length>256||length>(size-20)/2)return null;
        StringBuilder type=new StringBuilder();for(int index=0;index<length;index++)type.append(frame.getChar(start+20+index*2));return type.toString();
    }
    private void packetEvent(String type,String boundary,long at){JsonObject value=new JsonObject();value.addProperty("run_id",runId);value.addProperty("packet",type);value.addProperty("boundary",boundary);value.addProperty("phase",stage.name());value.addProperty("at_nanos",at);packetEvents.add(value);}
    private void installChannelObserver(NetworkManager manager)throws Exception {
        Channel channel=null;for(Field value:NetworkManager.class.getDeclaredFields())if(Channel.class.isAssignableFrom(value.getType())){value.setAccessible(true);channel=(Channel)value.get(manager);}
        require(channel!=null,"Original server native channel missing");final Channel actual=channel;
        actual.eventLoop().execute(()->{try{actual.pipeline().addBefore("packet_handler","mtr_dashboard_gui_observer",new ChannelDuplexHandler(){
            @Override public void channelRead(ChannelHandlerContext context,Object message)throws Exception {
                if(message instanceof C08PacketPlayerBlockPlacement){c08.incrementAndGet();packetEvent(message.getClass().getName(),"server_native_C08",System.nanoTime());}
                super.channelRead(context,message);
            }
            @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise)throws Exception {
                if(message instanceof S08PacketPlayerPosLook){s08.incrementAndGet();packetEvent(message.getClass().getName(),"server_native_S08",System.nanoTime());}
                super.write(context,message,promise);
            }
        });channelObserverReady=true;}catch(Throwable problem){fail(problem);}});
    }

    private void reserve(Minecraft client)throws Exception {
        require(checked(client.mcDataDir.toPath()).equals(checked(COPIED_CLIENT.toPath())),"Refusing to run outside disposable copied client");
        require(client.theWorld==null&&client.getIntegratedServer()==null,"Existing world must not be replaced");
        runId=UUID.randomUUID().toString();saveName="MTR-dashboard-gui-acceptance-"+runId;initialName="Dashboard "+runId;
        Path saves=client.mcDataDir.toPath().resolve("saves");Files.createDirectories(saves);require(checked(saves).getParent().equals(checked(client.mcDataDir.toPath())),"Copied saves escaped client");
        saveDirectory=Files.createDirectory(saves.resolve(saveName));Files.write(saveDirectory.resolve(OWNER),runId.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);copyVerified=true;
        report.addProperty("run_id",runId);report.addProperty("save_name",saveName);report.addProperty("save_directory",checked(saveDirectory).toString());report.addProperty("client_directory",checked(client.mcDataDir.toPath()).toString());
        report.addProperty("new_uuid_save_reserved",true);report.addProperty("existing_save_loaded",false);
    }
    private void assertServer(MinecraftServer server)throws Exception {
        require(copyVerified&&server!=null&&saveName.equals(server.getFolderName()),"Refusing foreign server mutation/closure");
        require(checked(server.worldServerForDimension(0).getSaveHandler().getWorldDirectory().toPath()).equals(checked(saveDirectory)),"Native server save root differs");
        require(checked(saveDirectory).equals(checked(COPIED_CLIENT.toPath()).resolve("saves").resolve(saveName))&&
            runId.equals(new String(Files.readAllBytes(checked(saveDirectory.resolve(OWNER))),StandardCharsets.UTF_8)),"Owned save marker changed");
    }
    private void assertClient(Minecraft client)throws Exception {
        assertServer(client.getIntegratedServer());require(clientWorld!=null&&client.theWorld==clientWorld&&client.thePlayer!=null&&client.thePlayer.dimension==0&&client.thePlayer.getUniqueID().equals(playerId),"Foreign client world/player");
        require(client.getNetHandler()!=null&&client.getNetHandler().getNetworkManager()==networkManager&&networkManager.isChannelOpen(),"Native client connection replaced");
        if(stage!=Stage.SETUP&&stage!=Stage.PIDS_SETUP)require(client.thePlayer.getHeldItem()!=null&&client.thePlayer.getHeldItem().getItem()==(stage.ordinal()>=Stage.PIDS_SETUP.ordinal()?org.mtr.mod.Items.BRUSH.get().data:dashboardItem())&&client.thePlayer.inventory.currentItem==0,"Native client held item changed");
    }
    private static Item dashboardItem(){Item item=(Item)Item.itemRegistry.getObject("mtr:dashboard");require(item!=null,"Original mtr:dashboard missing");return item;}
    private static boolean air(Minecraft client){return client.objectMouseOver!=null&&client.objectMouseOver.typeOfHit==MovingObjectPosition.MovingObjectType.MISS&&!client.thePlayer.isSneaking();}
    private static boolean zeroMovement(Minecraft client){return !client.gameSettings.keyBindForward.getIsKeyPressed()&&!client.gameSettings.keyBindBack.getIsKeyPressed()&&!client.gameSettings.keyBindLeft.getIsKeyPressed()&&
        !client.gameSettings.keyBindRight.getIsKeyPressed()&&!client.gameSettings.keyBindJump.getIsKeyPressed()&&!client.gameSettings.keyBindSneak.getIsKeyPressed()&&client.thePlayer.movementInput.moveForward==0&&client.thePlayer.movementInput.moveStrafe==0;}
    private static boolean containsIdentity(List<?> values,Object expected){for(Object value:values)if(value==expected)return true;return false;}
    private static Object field(Object value,String name)throws Exception {
        if("buttonList".equals(name))return ReflectionHelper.findField(GuiScreen.class,"buttonList","field_146292_n").get(value);
        for(Class<?> type=value.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(value);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(value.getClass().getName()+"."+name);
    }
    private static JsonObject full(SerializedDataBase value){JsonObject result=new JsonObject();value.serializeFullData(new JsonWriter(result));return result;}
    private static JsonObject copy(JsonObject value){return new JsonParser().parse(value.toString()).getAsJsonObject();}
    private static String name(JsonObject value){return value==null||!value.has("name")?"":value.get("name").getAsString();}
    private static JsonObject station(MinecraftClientData data,long id){Station found=null;for(Station value:data.stations)if(value.getId()==id){require(found==null,"Duplicate client station ID");found=value;}return found==null?null:full(found);}
    private void unchanged(JsonObject actual){require(actual!=null,"Station missing from immutable sample");JsonObject expected=copy(baselineCore);expected.addProperty("name",FINAL_NAME);require(expected.equals(actual),"Full station changed outside final name");}
    private static Path checked(Path value)throws Exception {
        Path absolute=value.toAbsolutePath().normalize();for(Path part=absolute;part!=null;part=part.getParent())require(!Files.isSymbolicLink(part),"Symlink path: "+part);
        return absolute.toRealPath();
    }
    private static String sha(Path value)throws Exception {
        Path file=checked(value);require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=128L*1024*1024,"Hash input size/type differs");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];
        try(InputStream input=Files.newInputStream(file)){int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format("%02x",b&255));return result.toString();
    }
    private JsonObject envelope(){JsonObject value=new JsonObject();value.addProperty("run_id",runId);value.addProperty("artifact_sha256",artifactSha);value.addProperty("phase",stage.name());value.addProperty("action_serial",actionSerial);
        value.addProperty("station_id",Long.toString(stationId));value.addProperty("at_nanos",System.nanoTime());value.addProperty("client_tick",tick);value.addProperty("render_frame",frame);
        value.addProperty("player_uuid",playerId==null?"":playerId.toString());value.addProperty("client_world_identity",clientWorld==null?0:System.identityHashCode(clientWorld));
        ServerView server=serverView;if(server!=null){value.addProperty("server_world_tick",server.worldTick);value.addProperty("server_sample_at_nanos",server.atNanos);value.addProperty("server_manager_identity",System.identityHashCode(server.manager));}
        value.addProperty("network_manager_generation",1);value.addProperty("network_manager_identity",networkManager==null?0:System.identityHashCode(networkManager));value.addProperty("screen_identity",actualScreen==null?0:System.identityHashCode(actualScreen));return value;}
    private void input(String event){JsonObject value=envelope();value.addProperty("event",event);if(action!=null){value.addProperty("label",action.label);value.addProperty("native_clock_before",action.before);value.addProperty("native_clock_after",action.after);value.addProperty("input_created_at_nanos",action.atNanos);value.addProperty("input_observed_at_nanos",action.observedAt);value.addProperty("queued_client_tick",action.tick);value.addProperty("queued_render_frame",action.frame);if(action.pointer!=null)value.add("pointer",action.pointer.json());}inputs.add(value);}
    private void recordViews(){JsonObject value=envelope();if(coreView!=null)value.add("core",coreView.json());if(clientView!=null)value.add("client",clientView.json());samples.add(value);}
    private void advance(Stage next){JsonObject value=envelope();value.addProperty("next",next.name());transitions.add(value);stage=next;stageAt=System.currentTimeMillis();charIndex=0;action=null;}
    private void capture(Minecraft client,String label)throws Exception {
        require(client.currentScreen==actualScreen&&actualScreen!=null,"GUI screenshot lost actual screen");
        require(clientView!=null&&clientView.screenIdentity==System.identityHashCode(actualScreen)&&clientView.atNanos>lastNativeInputObservedAt,"Actual screenshot lacks a current render sample after consumed input");
        String name="mtr-dashboard-gui-"+runId+"-"+label+".png";Path target=client.mcDataDir.toPath().resolve("screenshots").resolve(name);
        require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"Screenshot already exists");
        ScreenShotHelper.saveScreenshot(client.mcDataDir,name,client.displayWidth,client.displayHeight,client.getFramebuffer());
        require(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&Files.size(target)>100,"Actual GUI framebuffer missing");JsonObject value=envelope();value.addProperty("label",label);value.addProperty("gui_width",actualScreen.width);value.addProperty("gui_height",actualScreen.height);value.addProperty("display_width",client.displayWidth);value.addProperty("display_height",client.displayHeight);value.addProperty("render_sample_at_nanos",clientView.atNanos);value.addProperty("last_native_input_observed_at_nanos",lastNativeInputObservedAt);value.addProperty("mouse_raw_x",number(mouseClass,"getX"));value.addProperty("mouse_raw_y",number(mouseClass,"getY"));value.addProperty("path",checked(target).toString());value.addProperty("sha256",sha(target));screenshots.add(value);
    }
    private synchronized void fail(Throwable problem){if(failure==null){failure=problem;problem.printStackTrace();}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}

    private void finish(Minecraft client){
        if(stage==Stage.FINISHED)return;report.addProperty("terminal_phase",stage.name());if(lastSetupSnapshot!=null)report.add("last_setup_wait_snapshot",lastSetupSnapshot);stage=Stage.FINISHED;boolean safe=false;
        try{
            if(copyVerified&&client.getIntegratedServer()!=null){
                assertServer(client.getIntegratedServer());safe=true;
                if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();
                client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());
                report.addProperty("save_closed_normally",client.theWorld==null&&client.getIntegratedServer()==null);
            }else safe=copyVerified&&client.theWorld==null;
            if(failure==null&&!Boolean.getBoolean("mtr.dashboard.lifecycleOnly"))require(complete&&baselineCore!=null&&ackCore!=null&&ackClient!=null&&reopenClient!=null,"Incomplete native observations");
        }catch(Throwable problem){fail(problem);}
        if(settingsCaptured){client.gameSettings.pauseOnLostFocus=priorPauseOnLostFocus;client.gameSettings.thirdPersonView=priorThirdPerson;client.gameSettings.renderDistanceChunks=priorRenderDistance;client.gameSettings.guiScale=priorGuiScale;
            report.addProperty("ram_settings_restored",client.gameSettings.pauseOnLostFocus==priorPauseOnLostFocus&&client.gameSettings.thirdPersonView==priorThirdPerson&&client.gameSettings.renderDistanceChunks==priorRenderDistance&&client.gameSettings.guiScale==priorGuiScale);}
        if(originalChannel!=null)originalChannel.unregister(this);
        report.addProperty("schema",1);report.addProperty("probe_revision",7);report.addProperty("kind","final-sp-gui-native-raw");report.addProperty("passed",false);report.addProperty("provisional",true);
        report.addProperty("native_observations_complete",failure==null&&complete);report.addProperty("parent_exit_verified",false);report.addProperty("independent_saved_readback",false);
        report.addProperty("physical_os_input_claimed",false);report.addProperty("world_restart_accepted",false);
        JsonObject setup=new JsonObject();setup.addProperty("dto_packets",setupDtoPackets);setup.addProperty("stations",stationId==0?0:1);setup.addProperty("platforms",platformId==0?0:1);setup.addProperty("rails",railFixtureReady?1:0);setup.addProperty("rail_fixture_original_item_uses",railFixtureReady?4:0);setup.addProperty("original_server_rail_packets",railFixtureReady?1:0);setup.addProperty("inventory_slot_writes",inventorySlotWrites);
        setup.addProperty("selected_slot_writes",0);setup.addProperty("option_writes",optionWrites);setup.addProperty("orientation_writes",orientationWrites+serverSetupOrientationWrites);setup.addProperty("world_open_orientation_writes",worldOpenOrientationWrites);setup.addProperty("focus_writes",focusWrites);setup.addProperty("options_file_writes",0);setup.addProperty("pids_fixture_original_item_uses",pidsFixtureReady?1:0);setup.addProperty("movement_release_calls",setupClientDone?1:0);setup.addProperty("teleports",0);setup.addProperty("motion_writes",0);report.add("setup",setup);
        JsonObject measurement=new JsonObject();measurement.addProperty("dto_packets",0);measurement.addProperty("teleports",0);measurement.addProperty("motion_writes",0);measurement.addProperty("widget_writes",0);report.add("measurement",measurement);
        report.addProperty("world_open_via_original_controller",true);report.addProperty("queued_world_open_claimed",false);report.addProperty("physical_rmb_claimed",false);
        report.addProperty("pids_gui_resize_calls",resizeAtNanos==0?0:1);report.addProperty("pids_gui_scale_original",priorGuiScale);report.add("pids_samples",pidsSamples);if(pidsExpected!=null)report.add("pids_expected",pidsExpected);if(routeAck!=null)report.add("route_ack",routeAck);
        report.addProperty("route_id",Long.toString(routeId));report.addProperty("pids_open_packets",pidsOpenPackets);report.addProperty("pids_update_packets",pidsUpdatePackets);
        JsonObject pidsPosition=new JsonObject();pidsPosition.addProperty("x",pidsX);pidsPosition.addProperty("y",pidsY);pidsPosition.addProperty("z",pidsZ);report.add("pids_position",pidsPosition);
        report.add("native_evidence",nativeEvidence);report.add("transitions",transitions);report.add("input_ledger",inputs);report.add("screenshots",screenshots);report.add("samples",samples);report.add("packet_events",packets);
        report.addProperty("native_C08_count",c08.get());report.addProperty("native_S08_count",s08.get());report.addProperty("S08_measurement_baseline",s08Baseline);report.addProperty("original_open_packet_count",openPackets.get());
        report.addProperty("original_update_server_count",serverUpdates.get());report.addProperty("original_update_client_count",clientUpdates.get());
        if(failure!=null)report.addProperty("error",failure.toString());
        report.addProperty("acceptance_note","Always provisional: parent natural exit0/save markers, full-station independent readback and review are required.");
        try{
            if(copyVerified)Files.write(client.mcDataDir.toPath().resolve("mtr-dashboard-gui-probe-"+runId+".json"),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            System.out.println("MTR_DASHBOARD_GUI_PROVISIONAL "+report);if(safe)client.shutdown();
        }catch(Throwable problem){problem.printStackTrace();throw new IllegalStateException("Could not preserve provisional Dashboard evidence",problem);}
    }
}
