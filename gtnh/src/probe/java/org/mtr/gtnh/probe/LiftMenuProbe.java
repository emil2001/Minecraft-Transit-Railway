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
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import org.mtr.core.Main;
import org.mtr.core.data.Data;
import org.mtr.core.data.Lift;
import org.mtr.core.data.LiftDirection;
import org.mtr.core.data.LiftFloor;
import org.mtr.core.data.Position;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.serializer.MessagePackReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.org.msgpack.core.MessagePack;
import org.mtr.libraries.org.msgpack.core.MessageUnpacker;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mapping.tool.BlockStateDefinition;
import org.mtr.mapping.tool.BlockStateTileEntity;
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
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.mtr.mapping.mapper.ClickableWidgetExtension;
import org.mtr.mod.screen.LiftSelectionScreen;
import org.mtr.mod.screen.DashboardList;
import org.mtr.mod.screen.DashboardListItem;

/** Explicit test-only fresh-save passenger menu acceptance. No physical OS input claim. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_lift_menu_probe", name="MTR GTNH native game input queue lift menu probe", version="0",
    dependencies="required-after:mtr", acceptableRemoteVersions="*")
public final class LiftMenuProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final File COPIED_CLIENT = new File(requiredProbePath("mtr.probeClient"));
    private static final String WRAPPER_SHA = "3c5af555d62eb9b7f4196adea94cb4cbd4c8efe3e1d512f83537aa88d9d5211e";
    private static final int[] FLOOR_Y = {72,80,88};
    private static final String[] DESCRIPTIONS = {"Lower","Middle","Upper"};
    private enum Stage { MENU,WORLD,SETUP,CHUNKS,LABELS,GENERATE,CONFIGURE,READY,CALL,PICKUP,BOARD,
        OPEN_MENU,HOVER,CLICK,ACK,UP,TOP,EXIT,DISMOUNT,GRAVITY,IDLE,CLOSE,FINISHED }
    private volatile Stage stage=Stage.MENU, clientAcknowledged;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private volatile Object ownedClientWorld;
    private volatile ClientSample clientSample;
    private volatile CoreSample coreSample;
    private volatile MenuSample menuSample, hoverProof;
    private volatile LiftSelectionScreen actualMenu;
    private volatile long liftId, clickAtNanos, moveFrame, moveBefore, moveAfter, buttonBefore, buttonAfter;
    private volatile int clientTicks, moveTick, shotRequest, shotAcknowledged;
    private volatile String shotLabel;
    private volatile boolean menuClosedAfterClick, buttonReleaseObserved, clientBoardingAuthorized;
    private InputContract.Pointer pointer;
    private Class<?> mouseClass;
    private final InputContract.KeyLease menuKey=new InputContract.KeyLease();
    private final AtomicBoolean coreReadPending=new AtomicBoolean();
    private final RenderSampleGate renderSampleGate=new RenderSampleGate();
    private long consumedClientFrame;
    private volatile JsonObject clientDiagnostics, renderSampleDiagnostics;
    private int menuTicks,phaseTicks,readyChunkTicks,renderedFrames,setupTeleports,stillTicks,jumpClientTicks;
    private boolean jumped,gravityFalling;
    private double jumpPeak,serverJumpPeak,previousJumpY;
    private long startedAt;
    private volatile long stageStartedAt;
    private boolean copyVerified,standBuilt,queueConfirmed,movingCaptured;
    private String runId,saveName;
    private File saveDirectory;
    private Simulator simulator;
    private final JsonObject report=new JsonObject();
    private final JsonArray timeline=new JsonArray(),transitions=new JsonArray(),screenshots=new JsonArray();
    private final JsonArray placements=new JsonArray(),interactions=new JsonArray(),inputLedger=new JsonArray();
    private final JsonArray pauseOptionCorrections=new JsonArray();
    private final List<StoredBlock> expectedSavedBlocks=new ArrayList<>();
    private final DoorCycle[] doors={new DoorCycle(),new DoorCycle(),new DoorCycle()};
    private final DoorCycle[] landingDoors={new DoorCycle(),new DoorCycle(),new DoorCycle()};

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=stageStartedAt=System.currentTimeMillis();
        for(String mod:Loader.instance().getIndexedModList().keySet())
            require(!mod.startsWith("mtr_gtnh_") || !mod.endsWith("_probe") || mod.equals("mtr_gtnh_lift_menu_probe"),"Coexisting probe "+mod);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<15*60*1000L,"Total deadline in "+stage);
            if(stage==Stage.MENU){
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.currentScreen==null){menuTicks=0;return;}
                String name=client.currentScreen.getClass().getName();
                if(!name.equals("net.minecraft.client.gui.GuiMainMenu")&&!name.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                reserveNewSave(client);report.addProperty("menu_class",name);
                client.gameSettings.pauseOnLostFocus=false;client.gameSettings.thirdPersonView=1;client.gameSettings.renderDistanceChunks=5;
                client.gameSettings.saveOptions();advance(Stage.WORLD);
                WorldSettings settings=new WorldSettings(0x4D54524C494654L,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT)
                    .enableCommands().func_82750_a("2;7,2x3,2;1;");
                client.launchIntegratedServer(saveName,"MTR lift menu acceptance "+runId,settings);return;
            }
            if(stage==Stage.WORLD){
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                assertOwnedServer(client.getIntegratedServer());require(client.thePlayer.dimension==0,"Client entered foreign dimension");
                playerId=client.thePlayer.getUniqueID();ownedClientWorld=client.theWorld;
                report.addProperty("player_uuid",playerId.toString());advance(Stage.SETUP);
            }
            if(stage==Stage.CLOSE){finish(client);return;}
            assertClientOwned(client);
            if(client.gameSettings.pauseOnLostFocus){
                client.gameSettings.pauseOnLostFocus=false;client.gameSettings.saveOptions();
                JsonObject correction=new JsonObject();correction.addProperty("phase",stage.name());correction.addProperty("client_tick",clientTicks);pauseOptionCorrections.add(correction);
            }
            Stage current=stage;
            require(System.currentTimeMillis()-stageStartedAt<(current==Stage.CHUNKS?90000:60000),"Client watchdog in "+current);
            require(client.currentScreen==null || client.currentScreen instanceof LiftSelectionScreen &&
                (current==Stage.OPEN_MENU||current==Stage.HOVER||current==Stage.CLICK||current==Stage.ACK),"Unexpected native screen: "+client.currentScreen);
            if(actualMenu!=null&&client.currentScreen!=null)require(client.currentScreen==actualMenu,"Passenger menu instance replaced");
            if(current!=Stage.OPEN_MENU)menuKey.release();
            key(client.gameSettings.keyBindForward,false);key(client.gameSettings.keyBindBack,false);
            key(client.gameSettings.keyBindJump,false);key(client.gameSettings.keyBindSneak,false);
            if(current==Stage.BOARD){
                if(client.thePlayer.posZ<6.55)key(client.gameSettings.keyBindForward,true);
                else if(!clientBoardingAuthorized)key(client.gameSettings.keyBindBack,true);
            }else if(current==Stage.EXIT && client.thePlayer.posZ>4.35)key(client.gameSettings.keyBindBack,true);
            else if(current==Stage.GRAVITY&&jumpClientTicks++<2){
                require(InputContract.inputOwner(stage.name(),"jump"),"Jump outside owned gravity phase");key(client.gameSettings.keyBindJump,true);
            }
            JsonObject diagnostic=new JsonObject();diagnostic.addProperty("phase",current.name());diagnostic.addProperty("client_tick",clientTicks);
            diagnostic.addProperty("screen",client.currentScreen==null?"null":client.currentScreen.getClass().getName());
            diagnostic.addProperty("native_paused",client.isGamePaused());diagnostic.addProperty("native_world_tick",client.theWorld.getTotalWorldTime());
            clientDiagnostics=diagnostic;
            if(clientTicks%100==0)System.out.println("MTR_LIFT_MENU_PROBE_PROGRESS "+diagnostic);
            Lift lift=liftId==0?findOnlyClientLift():MinecraftClientData.getLift(liftId);
            if(current==Stage.LABELS&&clientAcknowledged!=current&&clientStandReady(client)){
                require(stage==current,"Stage changed before label setup");
                for(int floor=0;floor<3;floor++)InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateLiftTrackFloorConfig(floorPos(floor),Integer.toString(floor+1),DESCRIPTIONS[floor],false));
                clientAcknowledged=current;
            }else if(current==Stage.CONFIGURE&&clientAcknowledged!=current&&lift!=null){
                require(stage==current&&lift.getId()==liftId,"Wrong setup lift/stage");
                lift.setDimensions(3,2,2,0,0,-1.5);lift.setAngle(Angle.fromAngle(270));lift.setIsDoubleSided(false);
                InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateData(new UpdateDataRequest(MinecraftClientData.getInstance()).addLift(lift)));clientAcknowledged=current;
            }else if(current==Stage.CALL&&clientAcknowledged!=current){
                require(stage==current&&client.thePlayer.getHeldItem()==null,"Landing call not owned/empty hand");
                double reach=client.playerController.getBlockReachDistance();
                require(client.thePlayer.getDistanceSq(9.5,72.375,5.5)<=reach*reach,"Lower landing call outside native reach");
                require(client.theWorld.getBlock(9,72,5).onBlockActivated(client.theWorld,9,72,5,client.thePlayer,2,.5F,.375F,.5F),"Original reachable lower landing call rejected");
                clientAcknowledged=current;
            }else if(current==Stage.OPEN_MENU){
                require(InputContract.inputOwner(stage.name(),"key")&&VehicleRidingMovement.isRiding(liftId),"Menu key without original rider/stage");
                if(client.currentScreen==null){
                    if(!menuKey.held){
                        final KeyBinding binding=org.mtr.mod.KeyBindings.LIFT_MENU.data;final int code=binding.getKeyCode();
                        require(code!=0,"Passenger key is unbound");
                        require(java.util.Arrays.asList(client.gameSettings.keyBindings).contains(binding),"Passenger key object is not registered");
                        JsonArray sharedKeys=new JsonArray();
                        for(KeyBinding other:client.gameSettings.keyBindings)if(other!=binding&&other.getKeyCode()==code){
                            require(InputContract.sharedMenuKey(code,other.getKeyDescription(),client.thePlayer.getHeldItem()==null),"Conflicting registered passenger key: "+other.getKeyDescription());
                            sharedKeys.add(other.getKeyDescription());
                        }
                        report.add("native_menu_shared_keys",sharedKeys);
                        menuKey.hold(down->KeyBinding.setKeyBindState(code,down));
                        require(binding.getIsKeyPressed(),"Registered passenger key did not receive held state");
                        input("registered_key_down",null);report.addProperty("native_menu_keycode",code);
                        report.addProperty("menu_key_press_queue_used",false); // This adapter requires held state, so no unused onTick press remains.
                    }
                }else{
                    require(client.currentScreen instanceof LiftSelectionScreen,"Original key opened foreign screen");
                    if(actualMenu==null)actualMenu=(LiftSelectionScreen)client.currentScreen;
                    if(menuKey.held){menuKey.release();input("registered_key_up",null);}clientAcknowledged=current;
                }
            }else if(current==Stage.HOVER&&clientAcknowledged!=current){
                require(InputContract.inputOwner(stage.name(),"move")&&client.currentScreen==actualMenu,"Move without actual owned menu");
                prepareMouse();float scale=((Number)Class.forName("org.lwjglx.opengl.Display").getMethod("getPixelScaleFactor").invoke(null)).floatValue();
                pointer=InputContract.pointer(actualMenu.width,actualMenu.height,client.displayWidth,client.displayHeight,scale,0);
                moveTick=clientTicks;moveFrame=renderSampleGate.frame;
                moveBefore=wrapperNanos();queueMove(pointer);moveAfter=wrapperNanos();input("queue_move_upper_icon",pointer);
                clientAcknowledged=current;
            }else if(current==Stage.CLICK&&clientAcknowledged!=current){
                require(InputContract.inputOwner(stage.name(),"button")&&client.currentScreen==actualMenu,"Click without actual owned menu");
                MenuSample now=new MenuSample(client,actualMenu,renderSampleGate.frame,clientTicks);
                require(hoverProof!=null&&now.readyToClick()&&pointer.sameDimensions(actualMenu.width,actualMenu.height,client.displayWidth,client.displayHeight,
                    ((Number)Class.forName("org.lwjglx.opengl.Display").getMethod("getPixelScaleFactor").invoke(null)).floatValue()),"Stale/hidden/early/wrong-scale native click");
                clickAtNanos=System.nanoTime();buttonBefore=wrapperNanos();
                try{mouseClass.getMethod("addButtonEvent",int.class,boolean.class).invoke(null,0,true);input("queue_left_down",pointer);}
                finally{mouseClass.getMethod("addButtonEvent",int.class,boolean.class).invoke(null,0,false);buttonAfter=wrapperNanos();input("queue_left_up",pointer);}
                clientAcknowledged=current;
            }
            if(clickAtNanos!=0&&client.currentScreen==null)menuClosedAfterClick=true;
            observeRelease();++clientTicks;
        }catch(Throwable problem){
            try{if(copyVerified){assertClientOwned(client);menuKey.release();releaseMovement(client);}}catch(Throwable cleanup){problem.addSuppressed(cleanup);}
            fail(problem);
        }
    }

    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||failure!=null)return;
        Stage current=stage;if(current==Stage.MENU||current==Stage.WORLD||current==Stage.CLOSE||current==Stage.FINISHED)return;
        try{
            MinecraftServer server=MinecraftServer.getServer();assertOwnedServer(server);EntityPlayerMP player=actualPlayer(server);
            if(player==null){require(current==Stage.SETUP&&++phaseTicks<200,"Actual server player missing");return;}
            WorldServer world=server.worldServerForDimension(0);require(player.worldObj==world&&player.dimension==0,"Foreign server player world");
            require(System.currentTimeMillis()-stageStartedAt<(current==Stage.CHUNKS?90000:60000),"Server watchdog in "+current);++phaseTicks;
            if(current==Stage.SETUP){
                player.setGameType(WorldSettings.GameType.CREATIVE);player.capabilities.isFlying=false;player.sendPlayerAbilities();
                setupTeleport(player,8,5,4.5);advance(Stage.CHUNKS);return;
            }
            ClientSample client=nextRenderedClientSample();if(client==null)return;
            if(current==Stage.CHUNKS){
                JsonObject chunk=chunkStatus(world,0,0);report.add("stand_chunk_last_wait",chunk);
                boolean arrived=Math.abs(client.x-8)<.5&&Math.abs(client.z-4.5)<.5&&Math.abs(player.managedPosX-8)<.5&&Math.abs(player.managedPosZ-4.5)<.5;
                readyChunkTicks=arrived&&chunk.get("ready").getAsBoolean()?readyChunkTicks+1:0;if(readyChunkTicks<2)return;
                buildStand(world,player);simulator=actualSimulator();advance(Stage.LABELS);return;
            }
            readCoreSoon();CoreSample core=coreSample;
            if(core!=null)require(System.nanoTime()-core.atNanos<5000000000L,"Core samples stale");observe(world,player,client,core);
            switch(current){
                case LABELS:
                    if(clientAcknowledged!=current||!labelsMatch(world)||!client.labelsMatch)return;
                    use(world,player,new ItemStack(originalItem("lift_refresher",org.mtr.mod.Items.LIFT_REFRESHER.get().data)),8,72,8,1,.5F);advance(Stage.GENERATE);break;
                case GENERATE:
                    if(core==null||core.count==0||client.liftId==0)return;
                    require(core.count==1&&core.id==client.liftId&&core.floorCount==3&&client.floorCount==3&&core.floorGeometry&&client.floorGeometry,"Wrong generated original lift/floors");
                    liftId=core.id;require(liftId!=0,"Zero generated lift ID");report.addProperty("original_assigned_lift_id",Long.toString(liftId));advance(Stage.CONFIGURE);break;
                case CONFIGURE:
                    if(clientAcknowledged!=current||core==null||!core.configured||!client.configured)return;
                    require(core.id==liftId&&Math.abs(client.cabinX-8)<.01&&Math.abs(client.cabinY-72)<.01&&Math.abs(client.cabinZ-6.5)<.01,"Wrong cabin geometry");
                    connectAndUnlock(world,player);player.inventory.mainInventory[0]=null;player.inventoryContainer.detectAndSendChanges();
                    player.capabilities.isFlying=false;player.sendPlayerAbilities();setupTeleport(player,8,72,4.5);advance(Stage.READY);break;
                case READY:
                    if(!client.linksMatch||!client.unlocked||!client.buttonStates||!client.emptyHand||!client.onGround||Math.abs(client.y-72)>.08||Math.abs(player.boundingBox.minY-72)>.08)return;
                    require(Math.abs(client.x-8)<.1&&Math.abs(client.z-4.5)<.1,"Lower setup placement not acknowledged");
                    if(++stillTicks<10||!captureDone("shaft-links"))return;assertStand(world);advance(Stage.CALL);break;
                case CALL:if(clientAcknowledged==current)advance(Stage.PICKUP);break;
                case PICKUP:
                    if(!stoppedAt(client,core,0)||client.door<.95||client.landingDoors[0]<.70||!captureDone("pickup1"))return;
                    require(!client.topInstruction&&!core.topInstruction,"Destination3 already present before GUI");advance(Stage.BOARD);break;
                case BOARD:
                    require(stoppedAt(client,core,0)&&client.door>.2,"Missed natural lower boarding door window");
                    if(!client.riding||!PlayerPhysics.authorized(player)||client.z<6.2||client.z>7.1)return;
                    assertPassenger(player,client);report.addProperty("natural_render_boarding_authorized",true);advance(Stage.OPEN_MENU);break;
                case OPEN_MENU:
                    assertPassenger(player,client);
                    if(clientAcknowledged!=current||menuSample==null||!menuSample.rowsReady)return;
                    if(!captureDone("menu-populated-3-2-1"))return;
                    report.add("actual_populated_menu",menuSample.json);advance(Stage.HOVER);break;
                case HOVER:
                    assertPassenger(player,client);
                    if(clientAcknowledged!=current||menuSample==null||!menuSample.readyToClick())return;
                    if(!captureDone("menu-hover-upper-icon"))return;
                    hoverProof=menuSample;report.add("actual_native_hover",hoverProof.json);advance(Stage.CLICK);break;
                case CLICK:
                    assertPassenger(player,client);if(clientAcknowledged==current)advance(Stage.ACK);break;
                case ACK:
                    assertPassenger(player,client);
                    if(core==null||!InputContract.queueAck(clickAtNanos,core.atNanos,client.atNanos,liftId,core.id,client.liftId,core.topInstruction,client.topInstruction,menuClosedAfterClick&&buttonReleaseObserved))return;
                    require(!core.bottomInstruction&&!client.bottomInstruction,"Unexpected GUI destination1 instruction");
                    queueConfirmed=true;report.add("original_core_gui_destination3_none",core.json);report.add("original_client_gui_destination3_none",client.liftJson);
                    report.addProperty("natural_menu_close_and_native_button_release",true);advance(Stage.UP);break;
                case UP:
                    assertPassenger(player,client);
                    if(!movingCaptured&&client.cabinY>73&&client.cabinY<87&&core!=null&&Math.abs(core.speed)>.00001){
                        require(client.door<=.02,"Open cabin in GUI-selected original travel");movingCaptured=captureDone("moving-gui-passenger");
                    }
                    if(stoppedAt(client,core,2)){require(movingCaptured&&queueConfirmed&&!core.topInstruction&&!client.topInstruction,"Missing moving GUI trip/consumed upper instruction");advance(Stage.TOP);}break;
                case TOP:
                    assertPassenger(player,client);
                    if(client.door<.95||client.landingDoors[2]<.70||!captureDone("stop3"))return;
                    advance(Stage.EXIT);break;
                case EXIT:
                    require(stoppedAt(client,core,2)&&client.door>.05,"Missed natural upper exit door window");
                    if(client.z<=4.4)advance(Stage.DISMOUNT);break;
                case DISMOUNT:
                    if(client.riding||PlayerPhysics.authorized(player)||!client.onGround||!player.onGround)return;
                    require(!client.noClip&&!player.noClip&&Math.abs(client.y-88)<.1&&Math.abs(player.boundingBox.minY-88)<.1,"Upper natural dismount/clipping/feet mismatch");
                    require(Math.abs(PlayerPhysics.gravity(.08,player)-.08)<1E-10,"Server gravity still suppressed after natural dismount");
                    if(++stillTicks<5)return;report.addProperty("natural_dismount_server_physics_revoked",true);
                    previousJumpY=client.y;jumpPeak=client.y;serverJumpPeak=player.boundingBox.minY;advance(Stage.GRAVITY);break;
                case GRAVITY:
                    require(!client.riding&&!PlayerPhysics.authorized(player),"Riding permission returned after natural dismount");
                    require(Math.abs(PlayerPhysics.gravity(.08,player)-.08)<1E-10,"Normal server gravity still suppressed");
                    if(client.y>88.15)jumped=true;jumpPeak=Math.max(jumpPeak,client.y);serverJumpPeak=Math.max(serverJumpPeak,player.boundingBox.minY);
                    if(jumped&&client.y<previousJumpY-.01)gravityFalling=true;previousJumpY=client.y;
                    if(!InputContract.gravityComplete(jumped,gravityFalling,client.onGround,player.onGround,client.y,player.boundingBox.minY,serverJumpPeak))return;
                    report.addProperty("native_jump_peak_y_after_dismount",jumpPeak);report.addProperty("native_jump_server_peak_y_after_dismount",serverJumpPeak);
                    report.addProperty("native_gravity_jump_fall_landing_verified",true);advance(Stage.IDLE);break;
                case IDLE:
                    if(!stoppedAt(client,core,2)||client.door!=0||core.cooldown||!core.emptyQueue||client.riding)return;
                    require(!PlayerPhysics.authorized(player)&&!player.noClip&&!client.noClip,"Rider flags returned at final idle");
                    require(doors[0].complete()&&doors[2].complete()&&landingDoors[0].complete()&&landingDoors[2].complete(),"Lower/upper cabin and landing door cycles incomplete");
                    require(setupTeleports==2,"Unexpected setup teleport count");assertStand(world);snapshotStand(world);
                    report.add("final_original_core_state",core.json);report.add("final_native_server_player",playerPosition(player));advance(Stage.CLOSE);break;
                default:break;
            }
        }catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (failure != null || stage == Stage.FINISHED || !copyVerified) return;
        try {
            Minecraft client = Minecraft.getMinecraft();
            if (client.getIntegratedServer() != null) assertOwnedServer(client.getIntegratedServer());
            boolean owned = client.getIntegratedServer() != null && client.theWorld != null && client.thePlayer != null
                && playerId != null && playerId.equals(client.thePlayer.getUniqueID()) && client.thePlayer.dimension == 0 && client.theWorld==ownedClientWorld;
            Lift lift = owned ? (liftId == 0 ? findOnlyClientLift() : MinecraftClientData.getLift(liftId)) : null;
            MinecraftClientData.LiftWrapper wrapper = lift == null ? null : MinecraftClientData.getInstance().liftWrapperList.get(lift.getId());
            boolean visible = lift == null || wrapper != null && wrapper.getLift() == lift && wrapper.shouldRender;
            boolean eligible = owned && stage != Stage.MENU && stage != Stage.WORLD && stage != Stage.CLOSE
                && (client.currentScreen == null || client.currentScreen==actualMenu && actualMenu!=null) && !client.skipRenderWorld && !client.isGamePaused()
                && client.renderViewEntity == client.thePlayer && client.displayWidth > 0 && client.displayHeight > 0 && visible;
            int tick = clientTicks;
            // Original MainRenderer writes this only in its non-shadow world-render branch, before RenderLifts reconciles the rider.
            long mtrRenderedMillis = eligible ? ((Number) diagnosticField(org.mtr.mod.render.MainRenderer.class, "lastRenderedMillis")).longValue() : -1;
            ClientSample sampled = renderSampleGate.sample(event.phase, eligible, client.theWorld, client.thePlayer, lift, tick, mtrRenderedMillis,
                () -> new ClientSample(client, lift, tick, renderSampleGate.frame, mtrRenderedMillis));
            if (event.phase != TickEvent.Phase.END) return;
            JsonObject diagnostic = new JsonObject(); diagnostic.addProperty("sampling_boundary", "RenderTick.END after original MTR world render");
            diagnostic.addProperty("frame", renderSampleGate.frame); diagnostic.addProperty("native_client_tick", tick);
            diagnostic.addProperty("eligible", eligible); diagnostic.addProperty("owned_world_player", owned); diagnostic.addProperty("lift_visible", visible);
            diagnostic.addProperty("native_paused", client.isGamePaused()); diagnostic.addProperty("skip_render_world", client.skipRenderWorld);
            diagnostic.addProperty("mtr_render_millis_before", renderSampleGate.mtrBefore); diagnostic.addProperty("mtr_render_millis_after", mtrRenderedMillis);
            diagnostic.addProperty("sample_published", sampled != null); renderSampleDiagnostics = diagnostic;
            if (sampled == null) return;
            clientSample = sampled;
            observeRelease();
            if(client.currentScreen instanceof LiftSelectionScreen && client.currentScreen==actualMenu)menuSample=new MenuSample(client,actualMenu,sampled.frame,tick);
            if (shotRequest <= shotAcknowledged) return;
            if (++renderedFrames < 4) return;
            if(shotLabel.startsWith("menu-"))require(client.currentScreen==actualMenu&&actualMenu!=null,"Menu framebuffer lost actual screen");
            String name = "mtr-lift-menu-probe-" + runId + "-" + shotLabel + ".png";
            ScreenShotHelper.saveScreenshot(client.mcDataDir, name, client.displayWidth, client.displayHeight, client.getFramebuffer());
            File file = new File(new File(client.mcDataDir, "screenshots"), name);
            require(file.isFile() && file.length() > 100, "Actual lift framebuffer was not written");
            JsonObject value = new JsonObject();
            value.addProperty("phase", shotLabel); value.addProperty("path", file.getCanonicalPath());
            value.addProperty("render_frames_after_request", renderedFrames); value.addProperty("client_tick", tick); value.addProperty("render_frame", sampled.frame);
            screenshots.add(value);
            shotAcknowledged = shotRequest;
        } catch (Throwable problem) { fail(problem); }
    }

    private ClientSample nextRenderedClientSample() {
        ClientSample value = clientSample;
        if (value == null || !renderedSampleFresh(value.tick, clientTicks, value.frame, consumedClientFrame, value.atNanos, System.nanoTime())) return null;
        consumedClientFrame = value.frame;
        return value;
    }

    private static boolean renderedSampleFresh(int tick, int currentTick, long frame, long consumed, long atNanos, long nowNanos) {
        return tick == currentTick && frame > consumed && nowNanos >= atNanos && nowNanos - atNanos <= 250000000L;
    }

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
        report.addProperty("native_chunk_ready_before_all_one_shot_placements", true);
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

    private void brush(WorldServer world, EntityPlayerMP player, int x, int y, int z) {
        JsonObject value = blockStatus(world, x, y, z);
        interactions.add(value);
        boolean accepted = world.getBlock(x, y, z).onBlockActivated(world, x, y, z, player, 2, .5F, .7F, .5F);
        value.addProperty("original_brush_accepted", accepted);
        require(accepted && unlocked(world, x, y, z), "Original brush failed to unlock landing block");
    }

    private static Item originalItem(String path, Item expected) {
        Item item = (Item) Item.itemRegistry.getObject("mtr:" + path);
        require(item != null && item == expected, "Registry mtr:" + path + " is not the original item identity");
        return item;
    }

    private void assertPassenger(EntityPlayerMP player, ClientSample client) {
        require(client.riding && PlayerPhysics.authorized(player), "Original rider lost client bind or real server physics authorization");
        require(player.noClip && Math.abs(PlayerPhysics.gravity(.08, player)) < 1E-10,
            "Original authorized rider lacks the native server clipping/gravity flags");
        require(!player.capabilities.isFlying && !client.flying, "Lift ride was replaced by native flight");
        require(Math.abs(client.y - client.cabinY) < .35 && Math.abs(player.boundingBox.minY - client.cabinY) < 1.5,
            "Real client/server passenger did not follow original cabin Y");
        require(Math.abs(client.x - 8) < .8 && client.z >= 5.7 && client.z <= 7.3, "Passenger left actual original cabin bounds");
        require(Math.abs(player.posX - client.x) < 1 && Math.abs(player.posZ - client.z) < 1,
            "Native client/server rider coordinates diverged");
    }

    private static boolean stoppedAt(ClientSample client, CoreSample core, int floor) {
        return core != null && core.id == client.liftId && Math.abs(core.speed) < 1E-8
            && Math.abs(client.cabinY - FLOOR_Y[floor]) < .03 && Math.abs(core.progress - (FLOOR_Y[floor] - 72)) < .03;
    }

    private boolean captureDone(String label) {
        if (!label.equals(shotLabel)) {
            require(shotRequest == shotAcknowledged, "Previous actual screenshot has no render acknowledgment");
            renderedFrames = 0; shotLabel = label; shotRequest++;
            return false;
        }
        return shotAcknowledged == shotRequest;
    }

    private void advance(Stage next) {
        JsonObject value = new JsonObject();
        value.addProperty("from", stage.name()); value.addProperty("to", next.name());
        value.addProperty("elapsed_ms", System.currentTimeMillis() - startedAt);
        transitions.add(value);
        phaseTicks = 0; stillTicks = 0; stageStartedAt = System.currentTimeMillis();
        stage = next;
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

    private static Lift findOnlyClientLift() {
        require(MinecraftClientData.getInstance().lifts.size() <= 1, "New owned save has multiple unexpected client lifts");
        for (Lift value : MinecraftClientData.getInstance().lifts) return value;
        return null;
    }

    private static BlockPos floorPos(int floor) { return new BlockPos(8, FLOOR_Y[floor], 8); }

    private static void key(KeyBinding binding, boolean down) { if(down||binding.getIsKeyPressed())KeyBinding.setKeyBindState(binding.getKeyCode(), down); }

    private EntityPlayerMP actualPlayer(MinecraftServer server) {
        for (Object candidate : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP value = (EntityPlayerMP) candidate;
            if (value.getUniqueID().equals(playerId)) return value;
        }
        return null;
    }

    private static boolean labelsMatch(net.minecraft.world.World world) {
        for (int floor = 0; floor < 3; floor++) {
            TileEntity value = world.getTileEntity(8, FLOOR_Y[floor], 8);
            if (!(value instanceof BlockLiftTrackFloor.BlockEntity)) return false;
            BlockLiftTrackFloor.BlockEntity tile = (BlockLiftTrackFloor.BlockEntity) value;
            if (!Integer.toString(floor + 1).equals(tile.getFloorNumber()) || !DESCRIPTIONS[floor].equals(tile.getFloorDescription()) || tile.getShouldDing()) return false;
        }
        return true;
    }

    private static boolean linksMatch(net.minecraft.world.World world) {
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

    private static boolean clientStandReady(Minecraft client) {
        try {
            for (int y : FLOOR_Y) if (!(client.theWorld.getTileEntity(8, y, 8) instanceof BlockLiftTrackFloor.BlockEntity)) return false;
            return true;
        } catch (IllegalArgumentException | IllegalStateException waitingForNativeTileSync) { return false; }
    }

    private static BlockState state(net.minecraft.world.World world, int x, int y, int z) {
        return new org.mtr.mapping.holder.World(world).getBlockState(new BlockPos(x, y, z));
    }

    private static boolean unlocked(net.minecraft.world.World world, int x, int y, int z) {
        return "true".equals(state(world, x, y, z).data.getSerializedProperties().get("unlocked"));
    }

    private static void assertTrack(WorldServer world) {
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

    private static void assertStand(WorldServer world) {
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

    private static boolean floorGeometry(Lift lift) {
        List<LiftFloor> floors = new ArrayList<>(); lift.iterateFloors(floors::add);
        if (floors.size() != 3) return false;
        for (int floor = 0; floor < 3; floor++) {
            LiftFloor value = floors.get(floor);
            if (!value.getPosition().equals(new Position(8, FLOOR_Y[floor], 8)) || !Integer.toString(floor + 1).equals(value.getNumber())
                || !DESCRIPTIONS[floor].equals(value.getDescription())) return false;
        }
        return true;
    }

    private static boolean configured(Lift lift) {
        return lift.getHeight() == 3 && lift.getWidth() == 2 && lift.getDepth() == 2 && lift.getOffsetX() == 0
            && lift.getOffsetY() == 0 && lift.getOffsetZ() == -1.5 && !lift.getIsDoubleSided()
            && Math.abs(lift.getAngle().angleRadians - Angle.fromAngle(270).angleRadians) < 1E-8;
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

    private void snapshotStand(WorldServer world) {
        for (int y = 72; y <= 88; y++) expectedSavedBlocks.add(snapshot(world, 8, y, 8));
        for (int y : FLOOR_Y) {
            expectedSavedBlocks.add(snapshot(world, 9, y, 5));
            for (int x = 7; x <= 8; x++) for (int dy = 0; dy <= 1; dy++) expectedSavedBlocks.add(snapshot(world, x, y + dy, 5));
        }
        require(expectedSavedBlocks.size() == 32, "Incomplete native Anvil expectations");
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
        require((tag != null) == block.hasTileEntity(metadata), "Native lift TileEntity presence differs from original block contract");
        if (definition.requiresTileEntity()) require(tag != null && tag.hasKey("mtr_block_state", 10), "Extended lift state is missing");
        return definition.read(metadata, tag == null ? new NBTTagCompound() : tag).getSerializedProperties();
    }

    private void verifySavedAnvil() throws Exception {
        require(new File(saveDirectory, "level.dat").isFile() && expectedSavedBlocks.size() == 32, "Actual new save/expectations missing");
        File file = new File(saveDirectory, "region/r.0.0.mca"); require(file.isFile(), "Actual saved Anvil region missing");
        JsonArray persisted = new JsonArray();
        NBTTagCompound level = readSavedChunkReadOnly(saveDirectory, file);
        require(level.getInteger("xPos") == 0 && level.getInteger("zPos") == 0, "Saved chunk location differs from stand");
            for (StoredBlock expected : expectedSavedBlocks) {
                int slot = ((expected.y & 15) << 8) | ((expected.z & 15) << 4) | (expected.x & 15);
                NBTTagCompound section = null; NBTTagList sections = level.getTagList("Sections", 10);
                for (int index = 0; index < sections.tagCount(); index++) {
                    NBTTagCompound value = sections.getCompoundTagAt(index);
                    if ((value.getByte("Y") & 255) == expected.y >> 4) section = value;
                }
                require(section != null, "Saved lift section missing");
                byte[] low = savedArray(section, "Blocks", 4096, false), high = savedArray(section, "Add", 2048, true);
                byte[] higher = savedArray(section, "BlocksB2Hi", 2048, true), highest = savedArray(section, "BlocksB3", 4096, true);
                int id = (low[slot] & 255) | (high.length == 0 ? 0 : nibble(high, slot) << 8)
                    | (higher.length == 0 ? 0 : nibble(higher, slot) << 12) | (highest.length == 0 ? 0 : (highest[slot] & 255) << 16);
                byte[] data = savedArray(section, "Data", 2048, false), dataHigh = savedArray(section, "Data1High", 2048, true);
                byte[] dataHighest = savedArray(section, "Data2", 4096, true);
                int metadata = nibble(data, slot) | (dataHigh.length == 0 ? 0 : nibble(dataHigh, slot) << 4)
                    | (dataHighest.length == 0 ? 0 : (dataHighest[slot] & 255) << 8);
                require(id == expected.id && metadata == expected.metadata, "Saved native lift ID/metadata differs");
                NBTTagCompound tag = null; NBTTagList tiles = level.getTagList("TileEntities", 10);
                for (int index = 0; index < tiles.tagCount(); index++) {
                    NBTTagCompound value = tiles.getCompoundTagAt(index);
                    if (value.getInteger("x") == expected.x && value.getInteger("y") == expected.y && value.getInteger("z") == expected.z) tag = value;
                }
                Block block = Block.getBlockById(id);
                BlockStateDefinition definition = BlockStateDefinition.forBlock(block);
                require((tag != null) == (expected.tag != null), "Saved lift TileEntity presence changed");
                Map<String, String> properties = storedProperties(block, metadata, tag);
                if (definition.requiresTileEntity()) require(expected.tag.getCompoundTag("mtr_block_state").equals(tag.getCompoundTag("mtr_block_state")), "Saved extended lift state differs");
                TileEntity restored = null;
                if (tag != null) {
                    restored = TileEntity.createAndLoadEntity(tag);
                    require(restored instanceof BlockStateTileEntity, "Actual saved tile ID cannot decode original state");
                    require(properties.equals(((BlockStateTileEntity) restored).readMtrState(definition, metadata).data.getSerializedProperties()),
                        "Saved native TileEntity codec disagrees with original block definition");
                }
                require(expected.properties.equals(properties), "Saved lift state properties changed through original codec");
                if (expected.x == 8 && expected.z == 8 && (expected.y == 72 || expected.y == 80 || expected.y == 88)) {
                    int floor = (expected.y - 72) / 8;
                    require(restored instanceof BlockLiftTrackFloor.BlockEntity, "Saved floor tile lost original type");
                    BlockLiftTrackFloor.BlockEntity tile = (BlockLiftTrackFloor.BlockEntity) restored;
                    require(Integer.toString(floor + 1).equals(tile.getFloorNumber()) && DESCRIPTIONS[floor].equals(tile.getFloorDescription()) && !tile.getShouldDing(),
                        "Saved original floor labels/ding differ");
                } else if (expected.x == 9 && expected.z == 5) {
                    long[] links = new CompoundTag(tag).getLongArray("track_floor_pos");
                    require(restored instanceof BlockLiftButtons.BlockEntity && links.length == 1 && links[0] == floorPos((expected.y - 72) / 8).asLong(),
                        "Saved native long-list button link differs");
                }
                JsonObject value = new JsonObject(); value.addProperty("x", expected.x); value.addProperty("y", expected.y); value.addProperty("z", expected.z);
                value.addProperty("block", Block.blockRegistry.getNameForObject(block)); value.addProperty("native_nbt", tag == null ? "absent" : tag.toString());
                value.addProperty("has_native_tile", tag != null); value.addProperty("extended_state_required", definition.requiresTileEntity()); persisted.add(value);
            }
        report.add("persisted_anvil_blocks", persisted); report.addProperty("saved_native_blocks_verified", persisted.size());
        report.addProperty("anvil_open_mode", "r");
    }

    private static NBTTagCompound readSavedChunkReadOnly(File ownedSave, File file) throws Exception {
        Path root = InputContract.checked(ownedSave.toPath()), resolved = InputContract.checked(file.toPath());
        require(resolved.startsWith(root) && !resolved.equals(root), "Anvil path escaped owned save");
        require(!Files.isSymbolicLink(file.toPath()), "Owned Anvil region must not be a symlink");
        // RegionFile opens in rw mode and may pad damaged files; this reader never writes a header or sector.
        try (RandomAccessFile region = new RandomAccessFile(resolved.toFile(), "r")) {
            require(region.length() >= 8192 && region.length() % 4096 == 0, "Malformed saved Anvil region length");
            int location = region.readInt(); // Stand is wholly in chunk(0,0), location-table entry zero.
            int sector = location >>> 8, sectors = location & 255;
            require(sector >= 2 && sectors > 0 && ((long) sector + sectors) * 4096 <= region.length(), "Saved chunk sectors outside region");
            region.seek((long) sector * 4096);
            int length = region.readInt();
            require(length > 1 && length <= sectors * 4096 - 4, "Malformed saved chunk payload length");
            int compression = region.readUnsignedByte();
            require(compression == 1 || compression == 2, "Unsupported native Anvil compression " + compression);
            byte[] bytes = new byte[length - 1]; region.readFully(bytes);
            try (InputStream compressed = new ByteArrayInputStream(bytes);
                 InputStream decompressed = compression == 1 ? new GZIPInputStream(compressed) : new InflaterInputStream(compressed);
                 DataInputStream input = new DataInputStream(decompressed)) {
                return CompressedStreamTools.read(input).getCompoundTag("Level");
            }
        }
    }

    private void verifySavedCore() throws Exception {
        Path directory = saveDirectory.toPath().resolve("mtr/minecraft/overworld/lifts");
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Actual original core lifts save directory missing");
        Path real = InputContract.checked(directory); require(real.startsWith(InputContract.checked(saveDirectory.toPath())), "Core save escaped owned save");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.forEach(path -> { require(!Files.isSymbolicLink(path), "Symlink in owned core lift save"); if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) files.add(path); });
        }
        require(files.size() == 1, "Expected exactly one real core Lift payload, got " + files.size());
        Path file = files.get(0); InputContract.firstMap(saveDirectory.toPath(),file); byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        Lift decoded;
        // FileLoader.readFile is deliberately not called: its error handler may delete corrupt data.
        try (InputStream input = Files.newInputStream(file); MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(input)) {
            decoded = new Lift(new MessagePackReader(unpacker), new Data() {});
        }
        byte[] after = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        require(java.util.Arrays.equals(before, after), "Read-only core check changed actual saved bytes");
        require(decoded.getId() == liftId && floorGeometry(decoded) && configured(decoded), "Saved core original ID/floors/configuration differ");
        JsonObject json = Utilities.getJsonObjectFromData(decoded);
        require(Math.abs(json.get("railProgress").getAsDouble()-16) < .01 && Math.abs(json.get("speed").getAsDouble()) < 1E-8
            && !decoded.hasCoolDown() && json.getAsJsonArray("instructions").size() == 0 && json.getAsJsonArray("ridingEntities").size() == 0,
            "Saved original core Lift is not idle and empty at floor3");
        require(coreSample != null && decoded.getStyle().equals(coreSample.style), "Saved core style changed");
        report.addProperty("saved_core_payload_file", file.toString()); report.add("saved_core_lift", json);
        report.addProperty("saved_core_readonly_bytes_unchanged", true);
    }

    private static byte[] savedArray(NBTTagCompound section, String name, int length, boolean optional) {
        if (!section.hasKey(name)) { require(optional, "Missing saved Anvil array " + name); return new byte[0]; }
        require(section.hasKey(name, 7), "Saved Anvil field is not byte array: " + name);
        byte[] value = section.getByteArray(name); require(value.length == length, "Wrong Anvil array length " + name); return value;
    }

    private static int nibble(byte[] value, int index) { return (value[index >> 1] >> ((index & 1) * 4)) & 15; }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private synchronized void fail(Throwable problem) { if (failure == null) failure = problem; }


    private void assertClientOwned(Minecraft client) throws Exception {
        require(copyVerified&&client.thePlayer!=null&&InputContract.worldOwner(ownedClientWorld,client.theWorld,playerId,client.thePlayer.getUniqueID(),client.thePlayer.dimension),"Foreign/missing native client world/player");
        require(InputContract.checked(client.mcDataDir.toPath()).equals(InputContract.checked(COPIED_CLIENT.toPath())),"Foreign copied client");
        assertOwnedServer(client.getIntegratedServer());
    }
    private void reserveNewSave(Minecraft client) throws Exception {
        require(InputContract.checked(client.mcDataDir.toPath()).equals(InputContract.checked(COPIED_CLIENT.toPath())),"Refusing foreign client");
        require(client.theWorld==null&&client.getIntegratedServer()==null,"Refusing existing open world");
        runId=UUID.randomUUID().toString();saveName="MTR-lift-menu-acceptance-"+runId;
        Path clientRoot=InputContract.checked(client.mcDataDir.toPath()),saves=clientRoot.resolve("saves");
        if(!Files.exists(saves,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(saves);
        require(InputContract.checked(saves).getParent().equals(clientRoot),"Saves escaped copied client");
        Path reserved=Files.createDirectory(saves.resolve(saveName));saveDirectory=reserved.toFile();
        Files.write(reserved.resolve("mtr-lift-menu-probe-owner.txt"),runId.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        InputContract.owned(clientRoot,COPIED_CLIENT.toPath(),reserved,runId,saveName);copyVerified=true;
        report.addProperty("run_id",runId);report.addProperty("save_directory",saveDirectory.getCanonicalPath());
        report.addProperty("client_directory",clientRoot.toString());report.addProperty("new_uuid_save_reserved",true);
        report.addProperty("existing_save_loaded",false);report.addProperty("input_scope","native game input queue/hit-test");
        report.addProperty("physical_os_mouse_key_delivery_tested",false);report.addProperty("mapped_hit_test_fallback_used",false);
        report.addProperty("scenario","original items/refresher/labels/links; lower reachable call; natural boarding; actual menu row0/floor3; one 1->3 trip; natural exit/physics revoke");
    }
    private void assertOwnedServer(MinecraftServer server) throws Exception {
        require(copyVerified&&server!=null&&saveDirectory!=null,"No owned native server");
        InputContract.owned(Minecraft.getMinecraft().mcDataDir.toPath(),COPIED_CLIENT.toPath(),saveDirectory.toPath(),runId,server.getFolderName());
        InputContract.nativeRoot(saveDirectory.toPath(),server.worldServerForDimension(0).getSaveHandler().getWorldDirectory().toPath());
    }
    private void setupTeleport(EntityPlayerMP player,double x,double y,double z){
        require(InputContract.setupTeleport(stage.name(),setupTeleports,PlayerPhysics.authorized(player),x,y,z),"Foreign/repeated/measured-phase setup teleport");
        JsonObject value=new JsonObject();value.addProperty("phase",stage.name());value.addProperty("sequence",setupTeleports);
        value.addProperty("x",x);value.addProperty("y",y);value.addProperty("z",z);inputLedger.add(value);
        player.playerNetServerHandler.setPlayerLocation(x,y,z,0,10);setupTeleports++;
    }
    private static void releaseMovement(Minecraft client){
        key(client.gameSettings.keyBindForward,false);key(client.gameSettings.keyBindBack,false);
        key(client.gameSettings.keyBindJump,false);key(client.gameSettings.keyBindSneak,false);
    }
    private void prepareMouse() throws Exception {
        if(mouseClass!=null)return;
        Class<?> type=Class.forName("org.lwjglx.input.Mouse");
        require(type.getProtectionDomain().getCodeSource()!=null,"No wrapper CodeSource for pinned native queue");
        Path jar=java.nio.file.Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(WRAPPER_SHA.equals(InputContract.sha(jar)),"Pinned lwjgl3ify wrapper differs");
        Class<?> event=Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent");
        type.getMethod("addMoveEvent",event);type.getMethod("addButtonEvent",int.class,boolean.class);
        event.getMethod("calloc");event.getMethod("free");
        mouseClass=type;report.addProperty("input_wrapper_sha256",InputContract.sha(jar));report.addProperty("input_wrapper_path",jar.toString());
    }
    private long wrapperNanos() throws Exception {
        return ((Number)Class.forName("org.lwjglx.Sys").getMethod("getNanoTime").invoke(null)).longValue();
    }
    private void queueMove(InputContract.Pointer point) throws Exception {
        Class<?> type=Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent");Object event=type.getMethod("calloc").invoke(null);
        try{
            type.getMethod("x",float.class).invoke(event,point.sdlX);type.getMethod("y",float.class).invoke(event,point.sdlY);
            type.getMethod("xrel",float.class).invoke(event,0F);type.getMethod("yrel",float.class).invoke(event,0F);
            mouseClass.getMethod("addMoveEvent",type).invoke(null,event);
        }finally{type.getMethod("free").invoke(event);}
    }
    private long mouseValue(String name) throws Exception {return ((Number)mouseClass.getMethod(name).invoke(null)).longValue();}
    private boolean mouseState() throws Exception {return (Boolean)mouseClass.getMethod("getEventButtonState").invoke(null);}
    private void observeRelease() throws Exception {
        if(mouseClass==null||pointer==null||buttonAfter==0||buttonReleaseObserved)return;
        long nanos=mouseValue("getEventNanoseconds");
        if(mouseValue("getEventButton")==0&&!mouseState()&&mouseValue("getEventX")==pointer.rawX&&mouseValue("getEventY")==pointer.rawY
            &&nanos>=buttonBefore&&nanos<=buttonAfter){buttonReleaseObserved=true;input("native_queue_release_observed",pointer);}
    }
    private void input(String action,InputContract.Pointer point){
        JsonObject value=new JsonObject();value.addProperty("phase",stage.name());value.addProperty("action",action);
        value.addProperty("client_tick",clientTicks);value.addProperty("render_frame",renderSampleGate.frame);value.addProperty("at_nanos",System.nanoTime());
        if(point!=null){value.addProperty("gui_x",point.guiX);value.addProperty("gui_y",point.guiY);value.addProperty("raw_x",point.rawX);value.addProperty("raw_y",point.rawY);
            value.addProperty("sdl_x",point.sdlX);value.addProperty("sdl_y",point.sdlY);value.addProperty("pixel_scale",point.scale);
            value.addProperty("gui_width",point.w);value.addProperty("gui_height",point.h);value.addProperty("display_width",point.dw);value.addProperty("display_height",point.dh);}
        inputLedger.add(value);
    }
    private void observe(WorldServer world,EntityPlayerMP player,ClientSample client,CoreSample core){
        JsonObject value=client.json();value.addProperty("phase",stage.name());value.addProperty("phase_tick",phaseTicks);
        value.addProperty("server_world_tick",world.getTotalWorldTime());value.add("server_player",playerPosition(player));
        clientBoardingAuthorized=PlayerPhysics.authorized(player);value.addProperty("server_authorized",clientBoardingAuthorized);
        value.addProperty("server_gravity_for_0_08",PlayerPhysics.gravity(.08,player));
        value.addProperty("client_sample_age_ms",(System.nanoTime()-client.atNanos)/1000000D);
        if(core!=null){value.addProperty("core_sample_age_ms",(System.nanoTime()-core.atNanos)/1000000D);value.add("core_lift",core.json);}
        timeline.add(value);
        for(int floor=0;floor<3;floor++)if(client.liftId==liftId&&Math.abs(client.cabinY-FLOOR_Y[floor])<.05){
            doors[floor].observe(client.door);landingDoors[floor].observe(Math.min(1,client.landingDoors[floor]/.75));
        }
    }
    private final class MenuSample {
        final long frame;final int tick;final boolean rowsReady,visible,active,moveConsumed;
        final int hover,iconX,iconY,iconWidth,iconHeight;final long rowId;
        final JsonObject json=new JsonObject();
        MenuSample(Minecraft client,LiftSelectionScreen screen,long frame,int tick) throws Exception {
            this.frame=frame;this.tick=tick;
            require(client.currentScreen==screen&&screen==actualMenu,"Foreign menu sample");
            require(((Number)diagnosticField(screen,"liftId")).longValue()==liftId,"Menu targets another lift");
            DashboardList list=(DashboardList)diagnosticField(screen,"selectionList");
            List<?> levels=(List<?>)diagnosticField(screen,"floorLevels"),sorted=(List<?>)diagnosticField(list,"dataSorted");
            Map<?,?> filtered=(Map<?,?>)diagnosticField(list,"dataFiltered");
            boolean ready=levels.size()==3&&sorted.size()==3&&filtered.size()==3&&((Number)diagnosticField(list,"page")).intValue()==0;
            JsonArray rows=new JsonArray();
            if(ready)for(int row=0;row<3;row++){
                int floor=InputContract.floorIndex(3,row);DashboardListItem item=(DashboardListItem)sorted.get(row);
                DashboardListItem prior=(DashboardListItem)filtered.get(row);
                ready&=prior!=null&&InputContract.rowMatches(item.id,item.getName(false),prior.id,prior.getName(false),floorPos(floor).asLong(),Integer.toString(floor+1)+" "+DESCRIPTIONS[floor])
                    &&levels.get(floor).equals(floorPos(floor));
                JsonObject entry=new JsonObject();entry.addProperty("visual_row",row);entry.addProperty("floor_index",floor);entry.addProperty("id",Long.toString(item.id));entry.addProperty("name",item.getName(false));rows.add(entry);
            }
            rowsReady=ready;hover=list.getHoverItemIndex();rowId=hover>=0&&hover<sorted.size()?((DashboardListItem)sorted.get(hover)).id:0;
            ClickableWidgetExtension button=(ClickableWidgetExtension)diagnosticField(list,"buttonFind");
            visible=button.visible;active=button.getActiveMapped();iconX=button.getX2();iconY=button.getY2();iconWidth=button.getWidth2();iconHeight=button.getHeight2();
            long eventX=0,eventY=0,eventButton=-2,eventNanos=0;boolean pressed=false;
            if(mouseClass!=null){eventX=mouseValue("getEventX");eventY=mouseValue("getEventY");eventButton=mouseValue("getEventButton");eventNanos=mouseValue("getEventNanoseconds");pressed=mouseState();}
            moveConsumed=pointer!=null&&InputContract.deliveredMove(pointer,eventX,eventY,eventButton,pressed,eventNanos,moveBefore,moveAfter);
            json.addProperty("menu_class",screen.getClass().getName());json.addProperty("menu_identity",System.identityHashCode(screen));json.addProperty("lift_id",Long.toString(liftId));
            json.addProperty("frame",frame);json.addProperty("client_tick",tick);json.addProperty("gui_width",screen.width);json.addProperty("gui_height",screen.height);
            json.addProperty("rows_ready_3_2_1",rowsReady);json.add("rows",rows);json.addProperty("hover_index",hover);json.addProperty("button_visible",visible);json.addProperty("button_active",active);
            json.addProperty("icon_x",iconX);json.addProperty("icon_y",iconY);json.addProperty("icon_width",iconWidth);json.addProperty("icon_height",iconHeight);
            json.addProperty("native_current_event_x",eventX);json.addProperty("native_current_event_y",eventY);json.addProperty("native_current_event_button",eventButton);
            json.addProperty("native_current_event_nanos",eventNanos);json.addProperty("native_current_event_pressed",pressed);json.addProperty("own_queue_move_consumed",moveConsumed);
        }
        boolean readyToClick(){
            return rowsReady&&moveConsumed&&pointer!=null&&InputContract.clickReady(pointer,visible,active,hover,rowId,floorPos(2).asLong(),moveTick,tick,moveFrame,frame,iconX,iconY,iconWidth,iconHeight);
        }
    }
    private void verifySavedPlayer() throws Exception {
        Path file=saveDirectory.toPath().resolve("playerdata").resolve(playerId.toString()+".dat");
        require(InputContract.checked(file).startsWith(InputContract.checked(saveDirectory.toPath())),"Player file escaped owned save");
        String before=InputContract.sha(file);NBTTagCompound tag;
        try(InputStream input=Files.newInputStream(file)){tag=CompressedStreamTools.readCompressed(input);}
        require(before.equals(InputContract.sha(file)),"Player readback changed bytes");
        NBTTagList pos=tag.getTagList("Pos",6);require(pos.tagCount()==3&&tag.getInteger("Dimension")==0,"Saved player pos/dimension invalid");
        double x=pos.func_150309_d(0),y=pos.func_150309_d(1),z=pos.func_150309_d(2);
        require(Math.abs(x-8)<.8&&Math.abs(y-88)<.15&&z<4.5&&z>2&&tag.getBoolean("OnGround")&&!tag.getCompoundTag("abilities").getBoolean("flying"),"Saved player not naturally outside upper cabin");
        if(tag.hasKey("UUIDMost"))require(new UUID(tag.getLong("UUIDMost"),tag.getLong("UUIDLeast")).equals(playerId),"Saved player UUID differs");
        JsonObject value=new JsonObject();value.addProperty("path",file.toString());value.addProperty("sha256",before);value.addProperty("x",x);value.addProperty("feet_y",y);value.addProperty("z",z);
        value.addProperty("dimension",tag.getInteger("Dimension"));value.addProperty("on_ground",tag.getBoolean("OnGround"));value.addProperty("readonly_bytes_unchanged",true);report.add("local_saved_player_readback",value);
    }
    private void finish(Minecraft client){
        if(stage==Stage.FINISHED)return;Stage terminal=stage;stage=Stage.FINISHED;
        boolean observationsComplete=failure==null&&terminal==Stage.CLOSE,safeShutdown=false,nativeCloseReturned=false;
        try{
            if(copyVerified){
                if(client.getIntegratedServer()!=null){
                    assertOwnedServer(client.getIntegratedServer());
                    require(ownedClientWorld==null||client.theWorld==null||client.theWorld==ownedClientWorld,"Refusing foreign client world close");
                    menuKey.release();releaseMovement(client);safeShutdown=true;
                    if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());
                    require(client.getIntegratedServer()==null&&client.theWorld==null,"Native stop did not return");nativeCloseReturned=true;
                }else{
                    InputContract.owned(client.mcDataDir.toPath(),COPIED_CLIENT.toPath(),saveDirectory.toPath(),runId,saveName);
                    require(client.theWorld==null,"Refusing foreign client world cleanup");menuKey.release();releaseMovement(client);safeShutdown=true;
                }
                if(observationsComplete&&failure==null){verifySavedAnvil();verifySavedCore();verifySavedPlayer();report.addProperty("local_readonly_save_checks_completed",true);}
            }
        }catch(Throwable problem){fail(problem);}
        report.addProperty("terminal_phase",terminal.name());report.addProperty("scenario_observations_completed",observationsComplete&&failure==null);
        report.addProperty("passed",false);report.addProperty("native_full_save_verified",false);report.addProperty("saved_and_closed_normally",false);
        report.addProperty("runtime_acceptance_pending",true);report.addProperty("native_close_returned",nativeCloseReturned);
        report.addProperty("parent_proof_required","unique game PID; natural exit0; fresh ordered native/core full-save logs; no stop-save error; frame inspection; readonly32states/core/player comparison");
        report.addProperty("setup_teleport_count",setupTeleports);report.addProperty("measured_gui_ride_teleport_count",0);report.addProperty("probe_motion_writes",0);
        report.addProperty("physical_os_mouse_key_delivery_tested",false);report.addProperty("mapped_hit_test_fallback_used",false);
        report.addProperty("audio_accepted",false);report.addProperty("remote_multiplayer_accepted",false);
        report.add("timeline",timeline);report.add("transitions",transitions);report.add("screenshots",screenshots);report.add("input_ledger",inputLedger);
        report.add("original_one_shot_item_uses",placements);report.add("original_brush_interactions",interactions);report.add("owned_pause_on_lost_focus_corrections",pauseOptionCorrections);
        if(clientDiagnostics!=null)report.add("last_client_diagnostics",clientDiagnostics);if(renderSampleDiagnostics!=null)report.add("last_render_sample_diagnostics",renderSampleDiagnostics);
        JsonArray cycles=new JsonArray(),landingCycles=new JsonArray();for(int floor=0;floor<3;floor++){cycles.add(doors[floor].json());landingCycles.add(landingDoors[floor].json());}
        report.add("door_cycles_floor1_2_3",cycles);report.add("landing_door_cycles_floor1_2_3_normalized_to_original_0_75_max",landingCycles);
        if(failure!=null){report.addProperty("error",failure.toString());failure.printStackTrace();}
        try{
            if(copyVerified){InputContract.owned(client.mcDataDir.toPath(),COPIED_CLIENT.toPath(),saveDirectory.toPath(),runId,saveName);
                Files.write(new File(client.mcDataDir,"mtr-lift-menu-probe-"+runId+".json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
            System.out.println((failure==null?"MTR_LIFT_MENU_PROBE_PROVISIONAL ":"MTR_LIFT_MENU_PROBE_FAIL ")+report);
            if(safeShutdown)client.shutdown();
        }catch(Throwable problem){problem.printStackTrace();throw new IllegalStateException("Could not write provisional lift menu result",problem);}
    }

// BEGIN_ISOLATED_INPUT_CONTRACT
    static final class InputContract {
        static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
        static final class Pointer {
            final int w,h,dw,dh,rawX,rawY,guiX,guiY;final float scale,sdlX,sdlY;final double hoverX,hoverY;
            Pointer(int w,int h,int dw,int dh,float scale,int rawX,int rawY,float sdlX,float sdlY){
                this.w=w;this.h=h;this.dw=dw;this.dh=dh;this.scale=scale;this.rawX=rawX;this.rawY=rawY;this.sdlX=sdlX;this.sdlY=sdlY;
                guiX=rawX*w/dw;guiY=h-rawY*h/dh-1;hoverX=rawX*w/(double)dw;hoverY=h-rawY*h/(double)dh-1;
            }
            boolean sameDimensions(int w,int h,int dw,int dh,float scale){return this.w==w&&this.h==h&&this.dw==dw&&this.dh==dh&&this.scale==scale;}
        }
        static Pointer pointer(int w,int h,int dw,int dh,float scale,int row){
            check(w>=288&&h>=124&&dw>0&&dh>0&&Float.isFinite(scale)&&scale>0,"Invalid GUI/display/scale");
            floorIndex(3,row);int x=w/2+134,y=54+20*row;
            int rawX=(int)Math.floor((x+.5)*dw/w),rawY=(int)Math.floor((h-y-.5)*dh/h);
            check((long)rawX*w<=Integer.MAX_VALUE&&(long)rawY*h<=Integer.MAX_VALUE,"Native coordinate integer overflow");
            float sdlX=(rawX+.25F)/scale,sdlY=(dh-rawY+.25F)/scale;
            check((int)(sdlX*scale)==rawX&&dh-(int)(sdlY*scale)==rawY,"SDL float truncation lost selected raw pixel");
            Pointer p=new Pointer(w,h,dw,dh,scale,rawX,rawY,sdlX,sdlY);
            check(p.guiX>=w/2+126&&p.guiX<w/2+142&&p.guiY>=46+20*row&&p.guiY<62+20*row,"Mapped pixel misses icon interior");return p;
        }
        static boolean clickReady(Pointer p,boolean visible,boolean active,int hover,long row,long expected,int moveTick,int tick,long moveFrame,long frame,int x,int y,int width,int height){
            return visible&&active&&hover==0&&row==expected&&tick>moveTick&&frame>moveFrame&&width==20&&height==20
                &&p.guiX>=x&&p.guiX<x+width&&p.guiY>=y&&p.guiY<y+height&&p.hoverX>=x&&p.hoverX<x+width&&p.hoverY>=y&&p.hoverY<y+height;
        }
        static boolean deliveredMove(Pointer p,long x,long y,long button,boolean pressed,long nanos,long before,long after){
            return before>0&&after>=before&&nanos>=before&&nanos<=after&&x==p.rawX&&y==p.rawY&&button==-1&&!pressed;
        }
        static boolean rowMatches(long id,String name,long filteredId,String filteredName,long expected,String expectedName){
            return id==expected&&filteredId==expected&&expectedName.equals(name)&&expectedName.equals(filteredName);
        }
        static boolean worldOwner(Object expected,Object actual,java.util.UUID player,java.util.UUID actualPlayer,int dimension){
            return expected!=null&&expected==actual&&player!=null&&player.equals(actualPlayer)&&dimension==0;
        }
        static int floorIndex(int floors,int row){check(floors==3&&row>=0&&row<floors,"Invalid passenger menu row");return floors-row-1;}
        static boolean setupTeleport(String stage,int count,boolean authorized,double x,double y,double z){
            return !authorized&&x==8&&z==4.5&&((stage.equals("SETUP")&&count==0&&y==5)||(stage.equals("CONFIGURE")&&count==1&&y==72));
        }
        static boolean inputOwner(String stage,String action){
            return stage.equals("OPEN_MENU")&&action.equals("key")||stage.equals("HOVER")&&action.equals("move")||stage.equals("CLICK")&&action.equals("button")||stage.equals("GRAVITY")&&action.equals("jump");
        }
        static boolean sharedMenuKey(int code,String description,boolean emptyHand){
            // GTNH delivers shared Z to each binding; these consumers need a tooltip/container or a held manipulator.
            return code==44&&(description.equals("nei.options.keys.gui.next_tooltip")||description.equals("nei.options.keys.itemzoom.toggle")
                ||description.equals("key.chromatictooltips.next_page")||emptyHand&&description.equals("key.mm-reset"));
        }
        static final class KeyLease {
            boolean held;private java.util.function.Consumer<Boolean> write;
            void hold(java.util.function.Consumer<Boolean> write){check(!held,"Repeated key lease");this.write=write;held=true;write.accept(true);}
            void release(){if(!held)return;try{write.accept(false);}finally{held=false;write=null;}}
        }
        static boolean gravityComplete(boolean jumped,boolean falling,boolean clientGround,boolean serverGround,double clientY,double serverY,double serverPeak){
            return jumped&&falling&&clientGround&&serverGround&&Math.abs(clientY-88)<.1&&Math.abs(serverY-88)<.1&&serverPeak>88.15;
        }
        static boolean queueAck(long click,long core,long client,long expected,long coreId,long clientId,boolean coreTop,boolean clientTop,boolean closed){
            return click>0&&core>click&&client>click&&expected!=0&&coreId==expected&&clientId==expected&&coreTop&&clientTop&&closed;
        }
        static java.nio.file.Path checked(java.nio.file.Path value) throws Exception {
            java.nio.file.Path absolute=value.toAbsolutePath().normalize();
            for(java.nio.file.Path part=absolute;part!=null;part=part.getParent())check(!java.nio.file.Files.isSymbolicLink(part),"Symlink path: "+part);
            return absolute.toRealPath();
        }
        static void nativeRoot(java.nio.file.Path expected,java.nio.file.Path actual) throws Exception {
            check(checked(expected).equals(checked(actual)),"Native server save directory differs");
        }
        static void owned(java.nio.file.Path client,java.nio.file.Path allowed,java.nio.file.Path save,String run,String folder) throws Exception {
            check(java.util.UUID.fromString(run).toString().equals(run),"Noncanonical fresh UUID");String name="MTR-lift-menu-acceptance-"+run;
            java.nio.file.Path root=checked(client),realSave=checked(save);
            check(root.equals(checked(allowed))&&folder.equals(name)&&realSave.equals(root.resolve("saves").resolve(name)),"Foreign client/save/folder");
            java.nio.file.Path marker=realSave.resolve("mtr-lift-menu-probe-owner.txt");
            check(checked(marker).getParent().equals(realSave)&&java.nio.file.Files.isRegularFile(marker,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                &&java.nio.file.Files.size(marker)==36&&run.equals(new String(java.nio.file.Files.readAllBytes(marker),java.nio.charset.StandardCharsets.UTF_8)),"Foreign fresh-save marker");
        }
        static String sha(java.nio.file.Path file) throws Exception {
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(checked(file)));
            StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b&255));return result.toString();
        }
        static void firstMap(java.nio.file.Path root,java.nio.file.Path file) throws Exception {
            java.nio.file.Path real=checked(file),owner=checked(root);
            check(real.startsWith(owner)&&!real.equals(owner)&&java.nio.file.Files.isRegularFile(real,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                &&java.nio.file.Files.size(real)>0&&java.nio.file.Files.size(real)<=16*1024*1024,"Invalid owned core payload");
            String before=sha(real);
            try(org.mtr.libraries.org.msgpack.core.MessageUnpacker unpacker=org.mtr.libraries.org.msgpack.core.MessagePack.newDefaultUnpacker(java.nio.file.Files.newInputStream(real))){
                check(unpacker.unpackValue().isMapValue(),"Original Lift must begin with one complete map");
                // Native FileLoader consumes only the first map; CREATE-only shorter writes can retain arbitrary suffix bytes.
            }finally{check(before.equals(sha(real)),"Read-only complete-map validation changed bytes");}
        }
    }
// END_ISOLATED_INPUT_CONTRACT

    private static final class RenderSampleGate {
        private long frame, mtrBefore;
        private int tick;
        private boolean armed;
        private Object world, player, lift;
        private <T> T sample(TickEvent.Phase phase, boolean eligible, Object world, Object player, Object lift, int tick, long mtrRenderedMillis,
                java.util.function.Supplier<T> read) {
            if (phase == TickEvent.Phase.START) {
                frame++; armed = eligible && world != null && player != null;
                this.world = world; this.player = player; this.lift = lift; this.tick = tick; mtrBefore = mtrRenderedMillis;
                return null;
            }
            boolean completed = armed && eligible && this.world == world && this.player == player && this.lift == lift
                && this.tick == tick && mtrRenderedMillis > mtrBefore;
            armed = false;
            return completed ? read.get() : null;
        }
    }
    private static final class DoorCycle {
        double previous;
        boolean opening, open, closing, closed;
        void observe(double value) {
            if (value > previous + .005) opening = true;
            if (opening && value >= .95) open = true;
            if (open && value < previous - .005) closing = true;
            if (closing && value <= .02) closed = true;
            previous = value;
        }
        boolean complete() { return opening && open && closing && closed; }
        JsonObject json() { JsonObject value = new JsonObject(); value.addProperty("opening", opening); value.addProperty("open", open);
            value.addProperty("closing", closing); value.addProperty("closed", closed); return value; }
    }
    private static final class CoreSample {
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
    private static final class ClientSample {
        final Object screen;
        final int tick, floorCount, renderDistanceChunks, cameraX, cameraY, cameraZ;
        final long requestRadius, frame, atNanos, mtrRenderedMillis;
        final long liftId;
        final double x, y, z, motionY, cabinX, cabinY, cabinZ, yaw, door;
        final boolean riding, onGround, noClip, flying, labelsMatch, linksMatch, unlocked, buttonStates, emptyHand;
        final boolean floorGeometry, configured, topInstruction, bottomInstruction;
        final boolean shouldRender;
        final double[] landingDoors = new double[3];
        final JsonObject liftJson, nativeInput, originalRiding;
        ClientSample(Minecraft client, Lift lift, int tick, long frame, long mtrRenderedMillis) {
            screen=client.currentScreen;
            this.frame = frame; this.mtrRenderedMillis = mtrRenderedMillis; atNanos = System.nanoTime();
            this.tick = tick; x = client.thePlayer.posX; y = client.thePlayer.boundingBox.minY; z = client.thePlayer.posZ;
            renderDistanceChunks = MinecraftClientHelper.getRenderDistance(); requestRadius = renderDistanceChunks * 16L;
            BlockPos camera = org.mtr.mapping.holder.MinecraftClient.getInstance().getGameRendererMapped().getCamera().getBlockPos();
            cameraX = camera.getX(); cameraY = camera.getY(); cameraZ = camera.getZ();
            motionY = client.thePlayer.motionY;
            nativeInput = new JsonObject();
            nativeInput.addProperty("entity_move_forward", client.thePlayer.moveForward); nativeInput.addProperty("entity_move_strafing", client.thePlayer.moveStrafing);
            nativeInput.addProperty("movement_input_present", client.thePlayer.movementInput != null);
            if (client.thePlayer.movementInput != null) {
                nativeInput.addProperty("movement_input_move_forward", client.thePlayer.movementInput.moveForward);
                nativeInput.addProperty("movement_input_move_strafe", client.thePlayer.movementInput.moveStrafe);
            }
            nativeInput.addProperty("key_forward", client.gameSettings.keyBindForward.getIsKeyPressed()); nativeInput.addProperty("key_back", client.gameSettings.keyBindBack.getIsKeyPressed());
            nativeInput.addProperty("rotation_yaw", client.thePlayer.rotationYaw); nativeInput.addProperty("motion_x", client.thePlayer.motionX); nativeInput.addProperty("motion_z", client.thePlayer.motionZ);
            originalRiding = originalRidingDiagnostics();
            onGround = client.thePlayer.onGround; noClip = client.thePlayer.noClip; flying = client.thePlayer.capabilities.isFlying;
            emptyHand = client.thePlayer.getHeldItem() == null;
            labelsMatch = labelsMatch(client.theWorld); linksMatch = linksMatch(client.theWorld);
            boolean allUnlocked = true, allButtons = true;
            for (int floor = 0; floor < 3; floor++) {
                int fy = FLOOR_Y[floor];
                if (!(client.theWorld.getBlock(9, fy, 5) instanceof BlockLiftButtons)) { allUnlocked = allButtons = false; continue; }
                try { allUnlocked &= unlocked(client.theWorld, 9, fy, 5); }
                catch (IllegalArgumentException | IllegalStateException waitingForNativeStateSync) { allUnlocked = false; }
                for (int dx = 7; dx <= 8; dx++) for (int dy = 0; dy <= 1; dy++) {
                    if (!(client.theWorld.getBlock(dx, fy + dy, 5) instanceof BlockLiftDoor)) allUnlocked = false;
                    else {
                        try { allUnlocked &= unlocked(client.theWorld, dx, fy + dy, 5); }
                        catch (IllegalArgumentException | IllegalStateException waitingForNativeStateSync) { allUnlocked = false; }
                    }
                }
                boolean[] buttons = {false, false};
                BlockLiftButtons.hasButtonsClient(floorPos(floor), buttons, (index, value) -> {});
                allButtons &= buttons[0] == (floor > 0) && buttons[1] == (floor < 2);
                TileEntity tile = client.theWorld.getTileEntity(8, fy, 5);
                try {
                    landingDoors[floor] = tile instanceof BlockPSDAPGDoorBase.BlockEntityBase ? ((BlockPSDAPGDoorBase.BlockEntityBase) tile).getDoorValue() : 0;
                } catch (IllegalArgumentException | IllegalStateException waitingForNativeDoorState) { landingDoors[floor] = 0; }
            }
            unlocked = allUnlocked; buttonStates = allButtons;
            if (lift == null) {
                liftId = 0; floorCount = 0; cabinX = cabinY = cabinZ = yaw = door = 0;
                riding = floorGeometry = configured = topInstruction = bottomInstruction = shouldRender = false; liftJson = new JsonObject();
            } else {
                liftId = lift.getId(); floorCount = lift.getFloorCount(); riding = VehicleRidingMovement.isRiding(liftId);
                MinecraftClientData.LiftWrapper wrapper = MinecraftClientData.getInstance().liftWrapperList.get(liftId);
                shouldRender = wrapper != null && wrapper.shouldRender;
                Vector position = lift.getPosition((a, b) -> ItemLiftRefresher.findPath(new org.mtr.mapping.holder.World(client.theWorld), a, b));
                cabinX = position.x + lift.getOffsetX(); cabinY = position.y + lift.getOffsetY(); cabinZ = position.z + lift.getOffsetZ();
                yaw = -Math.PI / 2 - lift.getAngle().angleRadians;
                door = lift.getDoorValue(); floorGeometry = floorGeometry(lift); configured = configured(lift);
                topInstruction = lift.hasInstruction(2).contains(LiftDirection.NONE); bottomInstruction = lift.hasInstruction(0).contains(LiftDirection.NONE);
                liftJson = Utilities.getJsonObjectFromData(lift);
            }
        }
        JsonObject geometry() {
            JsonObject value = new JsonObject(); value.addProperty("center_x", cabinX); value.addProperty("center_y", cabinY); value.addProperty("center_z", cabinZ);
            value.addProperty("original_renderer_yaw_radians", yaw);
            // RenderLifts deliberately caps the landing-door animation at its .75 doorway width.
            value.addProperty("original_landing_door_max", .75);
            value.addProperty("front_doorway_min_x", cabinX - .75); value.addProperty("front_doorway_max_x", cabinX + .75);
            value.addProperty("front_doorway_min_z", cabinZ - 1); value.addProperty("front_doorway_max_z", cabinZ - .75);
            value.addProperty("cabin_floor_min_z", cabinZ - .75); value.addProperty("cabin_floor_max_z", cabinZ + .75); return value;
        }
        JsonObject json() {
            JsonObject value = new JsonObject(); value.addProperty("client_sample_boundary", "RenderTick.END after original MTR world render");
            value.addProperty("native_screen",screen==null?"null":screen.getClass().getName());
            value.addProperty("render_frame", frame); value.addProperty("original_mtr_render_millis", mtrRenderedMillis);
            value.addProperty("client_tick", tick); value.addProperty("client_x", x); value.addProperty("client_feet_y", y); value.addProperty("client_z", z);
            value.addProperty("client_on_ground", onGround); value.addProperty("client_no_clip", noClip); value.addProperty("client_flying", flying);
            value.addProperty("client_motion_y", motionY); value.addProperty("original_renderer_should_render", shouldRender);
            value.add("actual_client_interest", interest());
            value.add("client_native_input", nativeInput); value.add("original_riding_state", originalRiding);
            value.addProperty("client_riding", riding); value.addProperty("lift_id", Long.toString(liftId)); value.add("cabin_geometry", geometry());
            value.addProperty("door", door); value.addProperty("client_relative_feet_y", y - cabinY);
            JsonArray landing = new JsonArray(); for (double entry : landingDoors) landing.add(new org.mtr.libraries.com.google.gson.JsonPrimitive(entry));
            value.add("landing_door_values_floor1_2_3", landing); value.add("original_client_lift", liftJson); return value;
        }
        JsonObject interest() {
            JsonObject value = new JsonObject(); value.addProperty("render_distance_chunks", renderDistanceChunks);
            value.addProperty("original_data_request_radius", requestRadius); value.addProperty("camera_block_x", cameraX);
            value.addProperty("camera_block_y", cameraY); value.addProperty("camera_block_z", cameraZ);
            value.addProperty("camera_inside_original_lift_interest_bounds", Utilities.isBetween(new Position(cameraX, cameraY, cameraZ),
                new Position(8, 72, 8), new Position(8, 88, 8), requestRadius));
            return value;
        }
    }
    private static final class StoredBlock {
        final int x, y, z, id, metadata;
        final NBTTagCompound tag;
        final Map<String, String> properties;
        StoredBlock(int x, int y, int z, int id, int metadata, NBTTagCompound tag, Map<String, String> properties) {
            this.x = x; this.y = y; this.z = z; this.id = id; this.metadata = metadata;
            this.tag = tag == null ? null : (NBTTagCompound) tag.copy(); this.properties = properties;
        }
    }
}
