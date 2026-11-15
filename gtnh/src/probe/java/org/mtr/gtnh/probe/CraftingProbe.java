package org.mtr.gtnh.probe;

import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.oredict.*;
import org.mtr.libraries.com.google.gson.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Test-only pinned crafting acceptance. Never registers items, aliases, ores or recipes. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_crafting_probe",name="MTR native crafting probe",version="0",
    dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class CraftingProbe {
    private static String requiredProbePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty() || !new java.io.File(value).isAbsolute()) {
            throw new IllegalStateException("Set -D" + property + "=<absolute path to the isolated probe input>");
        }
        return value;
    }
    private static final Path COPIED=Paths.get(requiredProbePath("mtr.probeClient"));
    private static final Path FIXTURE=Paths.get(requiredProbePath("mtr.probeCraftingFixture"));
    private static final String FIXTURE_SHA="c89e9ba25ddac610ba69b48c24747e501e5a3508892167536e59ee96215dae96";
    private enum Stage { MENU,WORLD,SETUP,RECIPES,CASES,CLOSE,FINISHED }
    private volatile Stage stage=Stage.MENU;
    private volatile Throwable failure;
    private volatile UUID playerId;
    private long startedAt;
    private int menuTicks,waitTicks,recipeIndex,caseIndex,positiveCases,negativeCases;
    private int tableWrites,inventorySetupWrites,matrixSetupWrites,scratchMatrixWrites,cursorSetupWrites,outputInventoryAdds;
    private String runId,saveName;
    private Path save;
    private boolean copyVerified,checksComplete,normalClose;
    private JsonObject fixture;
    private ContainerWorkbench bench;
    private final JsonObject report=new JsonObject();
    private final JsonArray recipeEvidence=new JsonArray(),caseEvidence=new JsonArray(),ingredientEvidence=new JsonArray();
    private final Map<String,IRecipe> recipesById=new LinkedHashMap<>();
    private final Map<String,JsonObject> expectedById=new LinkedHashMap<>();
    private final Map<String,Integer> expectedInventory=new TreeMap<>();
    private final Set<String> observedIngredients=new HashSet<>();

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        startedAt=System.currentTimeMillis();FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||stage==Stage.FINISHED)return;
        Minecraft client=Minecraft.getMinecraft();
        try {
            if(failure!=null||stage==Stage.CLOSE){finish(client);return;}
            require(System.currentTimeMillis()-startedAt<10*60*1000L,"Crafting probe deadline in "+stage);
            if(stage==Stage.MENU) {
                if(!Loader.instance().hasReachedState(LoaderState.AVAILABLE)||client.theWorld!=null||client.currentScreen==null){menuTicks=0;return;}
                String screen=client.currentScreen.getClass().getName();
                if(!screen.equals("net.minecraft.client.gui.GuiMainMenu")&&!screen.equals("lumien.custommainmenu.gui.GuiCustom")){menuTicks=0;return;}
                if(++menuTicks<60)return;
                reserve(client);client.gameSettings.pauseOnLostFocus=false;
                report.addProperty("loader_available_before_world",true);
                stage=Stage.WORLD;
                client.launchIntegratedServer(saveName,"MTR crafting acceptance "+runId,
                    new WorldSettings(0x43524146544d5452L,WorldSettings.GameType.SURVIVAL,false,false,WorldType.FLAT)
                        .func_82750_a("2;7,2x3,2;1;"));return;
            }
            if(stage==Stage.WORLD) {
                if(client.theWorld==null||client.thePlayer==null||client.getIntegratedServer()==null)return;
                owned(client.getIntegratedServer());playerId=client.thePlayer.getUniqueID();stage=Stage.SETUP;
            }
            require(client.theWorld!=null&&client.thePlayer!=null,"Owned crafting world/player disappeared");
            owned(client.getIntegratedServer());
            require(playerId.equals(client.thePlayer.getUniqueID())&&client.thePlayer.dimension==0,"Wrong client player/world");
            require(!client.gameSettings.pauseOnLostFocus,"Background pause was re-enabled");
            if(client.currentScreen!=null&&!(client.currentScreen instanceof net.minecraft.client.gui.inventory.GuiCrafting))client.displayGuiScreen(null);
            KeyBinding.unPressAllKeys();
        } catch(Throwable problem){fail(problem);}
    }
    @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END||failure!=null)return;
        Stage current=stage;if(current!=Stage.SETUP&&current!=Stage.RECIPES&&current!=Stage.CASES)return;
        try {
            MinecraftServer server=MinecraftServer.getServer();owned(server);EntityPlayerMP player=player(server);
            if(player==null){require(++waitTicks<200,"Native integrated-server player missing");return;}
            WorldServer world=server.worldServerForDimension(0);
            require(player.worldObj==world&&player.dimension==0,"Wrong native server world");
            require(Loader.instance().hasReachedState(LoaderState.AVAILABLE),"Recipe inspection before load complete");
            if(current==Stage.SETUP){setup(world,player);stage=Stage.RECIPES;return;}
            if(current==Stage.RECIPES) {
                JsonArray records=fixture.getAsJsonArray("recipes");
                // Bound each tick; native matching always sees the actual integrated WorldServer.
                for(int n=0;n<4&&recipeIndex<records.size();n++,recipeIndex++)checkRecipe(records.get(recipeIndex).getAsJsonObject(),world);
                if(recipeIndex==records.size()){require(recipeEvidence.size()==308,"Incomplete native recipe inspection");stage=Stage.CASES;}
                return;
            }
            JsonArray cases=fixture.getAsJsonArray("cases");
            if(caseIndex<cases.size()){checkCase(cases.get(caseIndex++).getAsJsonObject(),world,player);return;}
            require(positiveCases==47&&negativeCases==45,"Incomplete pinned crafting cases");
            assertInventory(player);report.add("expected_saved_inventory",inventoryJson(player));
            report.addProperty("native_server_player_uuid",playerId.toString());
            report.addProperty("native_player_survival",!player.capabilities.isCreativeMode);
            checksComplete=true;stage=Stage.CLOSE;
        } catch(Throwable problem){fail(problem);}
    }

    private void reserve(Minecraft client)throws Exception {
        require(client.theWorld==null&&client.getIntegratedServer()==null,"Refusing to replace an open world");
        require(checked(client.mcDataDir.toPath()).equals(checked(COPIED)),"Wrong copied client");
        for(ModContainer mod:Loader.instance().getActiveModList())
            require(!mod.getModId().startsWith("mtr_gtnh_")||!mod.getModId().endsWith("_probe")||mod.getModId().equals("mtr_gtnh_crafting_probe"),"Another runtime probe is active");
        byte[] bytes=Files.readAllBytes(FIXTURE);require(sha(bytes).equals(FIXTURE_SHA),"Pinned fixture SHA mismatch");
        fixture=new JsonParser().parse(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        require(fixture.get("schema").getAsInt()==1&&fixture.getAsJsonArray("recipes").size()==308,"Wrong pinned fixture");
        runId=UUID.randomUUID().toString();saveName="MTR-crafting-acceptance-"+runId;
        Path saves=COPIED.resolve("saves");Files.createDirectories(saves);
        require(checked(saves).getParent().equals(checked(COPIED)),"Copied saves directory escaped");
        save=Files.createDirectory(saves.resolve(saveName));
        Files.write(save.resolve("mtr-crafting-probe-owner.txt"),runId.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        copyVerified=true;ownedSave();
        report.addProperty("run_id",runId);report.addProperty("save_directory",save.toString());
        report.addProperty("client_directory",COPIED.toString());report.addProperty("fixture_sha256",FIXTURE_SHA);
        report.addProperty("fresh_save_reserved",true);report.addProperty("existing_save_loaded",false);
        report.addProperty("registry_or_ore_or_recipe_writes",0);report.addProperty("teleports",0);report.addProperty("locomotion_inputs",0);
    }
    private void setup(WorldServer world,EntityPlayerMP player)throws Exception {
        require(!world.isRemote&&!player.capabilities.isCreativeMode,"Expected actual native survival player");
        report.addProperty("native_world_class",world.getClass().getName());
        report.add("player_inventory_before_setup",inventoryJson(player));
        // All explicit inventory setup is confined to the new owned player. No accepted save is opened.
        for(int i=0;i<player.inventory.getSizeInventory();i++){player.inventory.setInventorySlotContents(i,null);inventorySetupWrites++;}
        player.inventory.setItemStack(null);cursorSetupWrites++;
        int x=MathHelper.floor_double(player.posX)+1,y=MathHelper.floor_double(player.boundingBox.minY),z=MathHelper.floor_double(player.posZ);
        require(world.getChunkProvider().chunkExists(x>>4,z>>4),"Table chunk not naturally loaded");
        Chunk chunk=(Chunk)world.theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x>>4,z>>4));
        require(chunk!=null&&!(chunk instanceof EmptyChunk)&&chunk.isChunkLoaded,"Table chunk not ready");
        require(world.isAirBlock(x,y,z),"Adjacent native table position is occupied");
        require(world.setBlock(x,y,z,net.minecraft.init.Blocks.crafting_table),"Native crafting table placement failed");tableWrites++;
        player.displayGUIWorkbench(x,y,z);
        require(player.openContainer instanceof ContainerWorkbench,"Native workbench did not open the ordinary server container");
        bench=(ContainerWorkbench)player.openContainer;
        require(bench.canInteractWith(player),"Native workbench access denied");
        report.addProperty("native_workbench_open_calls",1);report.addProperty("native_workbench_window_id",bench.windowId);
        JsonObject table=new JsonObject();table.addProperty("x",x);table.addProperty("y",y);table.addProperty("z",z);report.add("native_workbench",table);
        inspectNativeRecipes();inspectGroups();
    }
    private void inspectNativeRecipes()throws Exception {
        Map<String,List<IRecipe>> nativeBySignature=new HashMap<>();int total=0,shaped=0,shapeless=0;Set<String> outputs=new HashSet<>();
        for(Object value:CraftingManager.getInstance().getRecipeList()) {
            IRecipe recipe=(IRecipe)value;ItemStack output=recipe.getRecipeOutput();if(output==null)continue;
            String id=Item.itemRegistry.getNameForObject(output.getItem());if(id==null||!id.startsWith("mtr:")&&!id.startsWith("mtrsteamloco:"))continue;
            String signature=nativeSignature(recipe);List<IRecipe> list=nativeBySignature.get(signature);
            if(list==null){list=new ArrayList<>();nativeBySignature.put(signature,list);}list.add(recipe);total++;outputs.add(id);
            if(recipe instanceof ShapedOreRecipe)shaped++;else if(recipe instanceof ShapelessOreRecipe)shapeless++;else throw new AssertionError("Unknown native MTR recipe type "+recipe.getClass());
        }
        require(total==308&&shaped==167&&shapeless==141&&outputs.size()==261,"Native recipe totals changed after load complete: "+total+"/"+shaped+"/"+shapeless+"/"+outputs.size());
        for(JsonElement value:fixture.getAsJsonArray("recipes")) {
            JsonObject expected=value.getAsJsonObject();String id=expected.get("id").getAsString();
            try(InputStream input=getClass().getClassLoader().getResourceAsStream(expected.get("resource").getAsString())) {
                require(input!=null&&sha(read(input)).equals(expected.get("sha256").getAsString()),"Original recipe resource changed: "+id);
            }
            List<IRecipe> list=nativeBySignature.get(expectedSignature(expected));
            require(list!=null&&!list.isEmpty(),"Missing exact native recipe signature: "+id);
            require(recipesById.put(id,list.remove(list.size()-1))==null,"Duplicate fixture recipe ID "+id);expectedById.put(id,expected);
        }
        for(List<IRecipe> remaining:nativeBySignature.values())require(remaining.isEmpty(),"Unexpected extra native recipe signature");
        report.addProperty("native_recipes",total);report.addProperty("shaped",shaped);report.addProperty("shapeless",shapeless);report.addProperty("outputs",outputs.size());
    }
    private void inspectGroups() {
        JsonObject groups=new JsonObject();
        for(Map.Entry<String,JsonElement> entry:fixture.getAsJsonObject("private_groups").entrySet()) {
            List<ItemStack> actual=OreDictionary.getOres(entry.getKey());String expected=options(entry.getValue().getAsJsonArray());
            require(optionList(actual).equals(expected),"Private native Ore group differs: "+entry.getKey());groups.add(entry.getKey(),stacksJson(actual));
        }
        for(Map.Entry<String,JsonElement> entry:fixture.getAsJsonObject("native_ore_required").entrySet()) {
            List<ItemStack> actual=OreDictionary.getOres(entry.getKey());require(!actual.isEmpty(),"Empty native Ore group "+entry.getKey());
            for(JsonElement required:entry.getValue().getAsJsonArray()) {
                ItemStack target=stack(required.getAsJsonArray());boolean found=false;
                for(ItemStack candidate:actual)if(OreDictionary.itemMatches(candidate,target,false)){found=true;break;}
                require(found,"Native Ore group misses known vanilla variant: "+entry.getKey()+" "+key(target));
            }
            groups.add(entry.getKey(),stacksJson(actual));
        }
        report.add("actual_native_ore_groups",groups);
    }
    private void checkRecipe(JsonObject expected,WorldServer world)throws Exception {
        String id=expected.get("id").getAsString();IRecipe nativeRecipe=recipesById.get(id);
        InventoryCrafting grid=grid(expectedGrid(expected));
        require(nativeRecipe.matches(grid,world),"Original expected grid rejected: "+id);
        ItemStack output=stack(expected.getAsJsonArray("output"));assertOutput(nativeRecipe.getCraftingResult(grid),output,id);
        assertManager(grid,world,output,id);
        JsonObject value=new JsonObject();value.addProperty("id",id);value.addProperty("native_class",nativeRecipe.getClass().getName());
        value.addProperty("signature_sha256",sha(nativeSignature(nativeRecipe).getBytes(StandardCharsets.UTF_8)));value.add("actual_result",stackJson(output));recipeEvidence.add(value);
    }
    private void checkCase(JsonObject test,WorldServer world,EntityPlayerMP player) {
        String name=test.get("name").getAsString(),id=test.get("recipe").getAsString();IRecipe recipe=recipesById.get(id);require(recipe!=null,"Unknown pinned case recipe "+id);
        ItemStack[] inputs=new ItemStack[9];JsonArray source=test.getAsJsonArray("grid");
        for(int i=0;i<9;i++)if(!source.get(i).isJsonNull())inputs[i]=stack(source.get(i).getAsJsonArray());
        InventoryCrafting matrix=grid(inputs);boolean positive=test.get("positive").getAsBoolean();
        ItemStack expected=stack(expectedById.get(id).getAsJsonArray("output"));
        JsonObject consumption=null;
        if(!positive) {
            require(!recipe.matches(matrix,world),"Native recipe accepted wrong input: "+name);
            require(!same(CraftingManager.getInstance().findMatchingRecipe(matrix,world),expected),"Native manager accepted wrong input for target output: "+name);negativeCases++;
        } else {
            assertManager(matrix,world,expected,name);consumption=consume(inputs,expected,world,player,name);positiveCases++;
        }
        JsonObject evidence=new JsonObject();evidence.addProperty("case",name);evidence.addProperty("recipe",id);evidence.addProperty("positive",positive);
        evidence.addProperty("ordinary_native_result_take_and_consumption",positive);if(consumption!=null)evidence.add("consumption",consumption);caseEvidence.add(evidence);
    }
    private JsonObject consume(ItemStack[] inputs,ItemStack output,WorldServer world,EntityPlayerMP player,String label) {
        require(player.openContainer==bench&&bench.canInteractWith(player)&&!player.capabilities.isCreativeMode,"Ordinary survival workbench unavailable: "+label);
        require(player.inventory.getItemStack()==null,"Unexpected native cursor before craft");
        for(int i=0;i<9;i++){ItemStack value=inputs[i]==null?null:inputs[i].copy();if(value!=null)value.stackSize=2;bench.craftMatrix.setInventorySlotContents(i,value);matrixSetupWrites++;}
        assertOutput(bench.craftResult.getStackInSlot(0),output,label);
        JsonObject evidence=new JsonObject();evidence.addProperty("actual_player_open_container",player.openContainer.getClass().getName());
        evidence.addProperty("native_window_id",bench.windowId);evidence.add("matrix_before",matrixJson(bench.craftMatrix));
        evidence.add("native_result_before_take",stackJson(bench.craftResult.getStackInSlot(0)));
        bench.slotClick(0,0,0,player); // Vanilla ordinary left-click result slot; invokes SlotCrafting hooks and consumption.
        ItemStack taken=player.inventory.getItemStack();assertOutput(taken,output,label+" native cursor");
        for(int i=0;i<9;i++) {
            ItemStack remaining=bench.craftMatrix.getStackInSlot(i);
            if(inputs[i]==null)require(remaining==null,"Empty crafting slot changed: "+label);
            else require(remaining!=null&&remaining.stackSize==1&&key(remaining).equals(key(inputs[i])),"Crafting must consume exactly one from every occupied slot: "+label+" slot "+i);
        }
        evidence.add("matrix_after_native_take",matrixJson(bench.craftMatrix));evidence.add("native_cursor_after_take",stackJson(taken));
        String item=key(output);expectedInventory.put(item,expectedInventory.containsKey(item)?expectedInventory.get(item)+output.stackSize:output.stackSize);
        require(player.inventory.addItemStackToInventory(taken),"Native output inventory is full: "+label);outputInventoryAdds++;
        require(taken.stackSize==0,"Native output was not completely stored");player.inventory.setItemStack(null);cursorSetupWrites++;
        for(int i=0;i<9;i++){bench.craftMatrix.setInventorySlotContents(i,null);matrixSetupWrites++;}
        require(bench.craftResult.getStackInSlot(0)==null,"Native result survived an empty matrix");assertInventory(player);
        evidence.add("actual_inventory_after_store",inventoryJson(player));return evidence;
    }
    private static JsonArray matrixJson(InventoryCrafting matrix) {
        JsonArray result=new JsonArray();for(int i=0;i<matrix.getSizeInventory();i++) {
            ItemStack stack=matrix.getStackInSlot(i);if(stack==null)continue;JsonObject value=stackJson(stack);value.addProperty("slot",i);result.add(value);
        }return result;
    }
    private void assertManager(InventoryCrafting grid,WorldServer world,ItemStack expected,String label) {
        assertOutput(CraftingManager.getInstance().findMatchingRecipe(grid,world),expected,label+" CraftingManager");
        for(Object candidate:CraftingManager.getInstance().getRecipeList()) {
            IRecipe recipe=(IRecipe)candidate;
            if(recipe.matches(grid,world))require(same(recipe.getCraftingResult(grid),expected),"Divergent native crafting collision: "+label+" vs "+recipe.getClass().getName());
        }
    }
    private String expectedSignature(JsonObject recipe) {
        List<String> inputs=new ArrayList<>();for(JsonElement value:recipe.getAsJsonArray("slots"))inputs.add(value.isJsonNull()?"empty":ingredient(value.getAsJsonObject()));
        boolean shaped=recipe.get("type").getAsString().equals("shaped");if(!shaped)Collections.sort(inputs);
        return (shaped?"shaped:"+recipe.get("width").getAsInt()+"x"+recipe.get("height").getAsInt():"shapeless")+":"+key(stack(recipe.getAsJsonArray("output")))+"*"+recipe.getAsJsonArray("output").get(2).getAsInt()+":"+inputs;
    }
    private static String nativeSignature(IRecipe recipe)throws Exception {
        List<String> inputs=new ArrayList<>();String kind;
        if(recipe instanceof ShapedOreRecipe) {
            for(Object value:((ShapedOreRecipe)recipe).getInput())inputs.add(nativeIngredient(value));
            Field width=ShapedOreRecipe.class.getDeclaredField("width"),height=ShapedOreRecipe.class.getDeclaredField("height");width.setAccessible(true);height.setAccessible(true);
            kind="shaped:"+width.getInt(recipe)+"x"+height.getInt(recipe);
        } else if(recipe instanceof ShapelessOreRecipe) {
            for(Object value:((ShapelessOreRecipe)recipe).getInput())inputs.add(nativeIngredient(value));Collections.sort(inputs);kind="shapeless";
        } else throw new AssertionError("Unknown native recipe type "+recipe.getClass());
        ItemStack output=recipe.getRecipeOutput();return kind+":"+key(output)+"*"+output.stackSize+":"+inputs;
    }
    private String ingredient(JsonObject ingredient) {
        return ingredient.has("ore")?optionList(OreDictionary.getOres(ingredient.get("ore").getAsString())):options(ingredient.getAsJsonArray("options"));
    }
    private static String nativeIngredient(Object input) {
        if(input==null)return "empty";
        if(input instanceof ItemStack)return optionList(Collections.singletonList((ItemStack)input));
        if(input instanceof List) {
            List<ItemStack> stacks=new ArrayList<>();for(Object value:(List<?>)input){require(value instanceof ItemStack,"Unknown native ingredient alternative "+value);stacks.add((ItemStack)value);}return optionList(stacks);
        }
        throw new AssertionError("Unknown native ingredient input "+input.getClass());
    }
    private String options(JsonArray alternatives) {List<ItemStack> stacks=new ArrayList<>();for(JsonElement value:alternatives)stacks.add(stack(value.getAsJsonArray()));return optionList(stacks);}
    private static String optionList(List<ItemStack> alternatives) {Set<String> values=new TreeSet<>();for(ItemStack stack:alternatives)values.add(key(stack));return values.toString();}
    private ItemStack[] expectedGrid(JsonObject recipe) {
        ItemStack[] result=new ItemStack[9];JsonArray slots=recipe.getAsJsonArray("slots");boolean shaped=recipe.get("type").getAsString().equals("shaped");
        int width=shaped?recipe.get("width").getAsInt():3;
        for(int i=0;i<slots.size();i++)if(!slots.get(i).isJsonNull()) {
            JsonObject ingredient=slots.get(i).getAsJsonObject();ItemStack chosen;
            if(ingredient.has("ore")) {
                String ore=ingredient.get("ore").getAsString();require(!OreDictionary.getOres(ore).isEmpty(),"Empty native Ore input");
                chosen=stack(fixture.getAsJsonObject("native_ore_required").getAsJsonArray(ore).get(0).getAsJsonArray());
            } else chosen=stack(ingredient.getAsJsonArray("options").get(0).getAsJsonArray());
            result[shaped?(i/width)*3+i%width:i]=chosen;
        }return result;
    }
    private ItemStack stack(JsonArray value) {
        String id=value.get(0).getAsString();String[] parts=id.split(":",2);require(parts.length==2,"Bad fixture item "+id);
        Item item=GameRegistry.findItem(parts[0],parts[1]);require(item!=null,"Missing actual native item "+id);
        int meta=value.get(1).getAsInt(),count=value.size()>2?value.get(2).getAsInt():1;ItemStack stack=new ItemStack(item,count,meta);
        require(Item.itemRegistry.getNameForObject(item).equals(id),"Native item identity/namespace changed "+id);
        if(observedIngredients.add(key(stack)))ingredientEvidence.add(stackJson(stack));return stack;
    }
    private InventoryCrafting grid(ItemStack[] values) {
        InventoryCrafting grid=new InventoryCrafting(new Container(){public boolean canInteractWith(EntityPlayer player){return true;}},3,3);
        for(int i=0;i<values.length;i++){grid.setInventorySlotContents(i,values[i]==null?null:values[i].copy());scratchMatrixWrites++;}return grid;
    }
    private static boolean same(ItemStack actual,ItemStack expected) {return actual!=null&&expected!=null&&actual.stackSize==expected.stackSize&&key(actual).equals(key(expected));}
    private static void assertOutput(ItemStack actual,ItemStack expected,String label){require(same(actual,expected),"Wrong native crafting output: "+label+" expected "+key(expected)+"*"+expected.stackSize+" actual "+(actual==null?"null":key(actual)+"*"+actual.stackSize));}
    private static String key(ItemStack value){require(value!=null&&value.getItem()!=null,"Invalid native ItemStack");return Item.itemRegistry.getNameForObject(value.getItem())+"@"+value.getItemDamage()+(value.hasTagCompound()?"#"+value.getTagCompound():"");}
    private static JsonObject stackJson(ItemStack value) {
        JsonObject result=new JsonObject();result.addProperty("id",Item.itemRegistry.getNameForObject(value.getItem()));result.addProperty("numeric_id",Item.getIdFromItem(value.getItem()));
        result.addProperty("metadata",value.getItemDamage());result.addProperty("count",value.stackSize);
        try{result.addProperty("unlocalized_name",value.getUnlocalizedName());}catch(RuntimeException invalidWildcard){result.addProperty("unlocalized_name_error",invalidWildcard.toString());}
        if(value.hasTagCompound())result.addProperty("nbt",value.getTagCompound().toString());return result;
    }
    private static JsonArray stacksJson(List<ItemStack> values){JsonArray result=new JsonArray();for(ItemStack value:values)result.add(stackJson(value));return result;}
    private static JsonArray inventoryJson(EntityPlayerMP player) {
        JsonArray result=new JsonArray();for(int i=0;i<player.inventory.getSizeInventory();i++)if(player.inventory.getStackInSlot(i)!=null){JsonObject value=stackJson(player.inventory.getStackInSlot(i));value.addProperty("slot",i);result.add(value);}return result;
    }
    private void assertInventory(EntityPlayerMP player) {
        Map<String,Integer> actual=new TreeMap<>();for(int i=0;i<player.inventory.getSizeInventory();i++) {
            ItemStack value=player.inventory.getStackInSlot(i);if(value!=null)actual.put(key(value),(actual.containsKey(key(value))?actual.get(key(value)):0)+value.stackSize);
        }require(actual.equals(expectedInventory),"Native inventory differs from ordinary crafted outputs: "+actual+" expected "+expectedInventory);
    }
    private EntityPlayerMP player(MinecraftServer server) {
        for(Object candidate:server.getConfigurationManager().playerEntityList){EntityPlayerMP player=(EntityPlayerMP)candidate;if(playerId.equals(player.getUniqueID()))return player;}return null;
    }
    private void owned(MinecraftServer server)throws IOException {
        require(copyVerified&&server!=null&&saveName.equals(server.getFolderName()),"Refusing foreign native integrated server");ownedSave();
        WorldServer world=server.worldServerForDimension(0);require(world!=null&&checked(world.getSaveHandler().getWorldDirectory().toPath()).equals(checked(save)),"Native save path differs from fresh owned save");
    }
    private void ownedSave()throws IOException {
        require(copyVerified&&save!=null&&save.getFileName().toString().equals(saveName)&&saveName.equals("MTR-crafting-acceptance-"+runId),"Invalid fresh save identity");
        require(checked(save).getParent().equals(checked(COPIED.resolve("saves"))),"Owned save escaped copied client");
        Path marker=save.resolve("mtr-crafting-probe-owner.txt");require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&runId.equals(new String(Files.readAllBytes(marker),StandardCharsets.UTF_8)),"Fresh save owner marker changed");
    }
    private static Path checked(Path path)throws IOException {Path absolute=path.toAbsolutePath().normalize();for(Path part=absolute;part!=null;part=part.getParent())require(!Files.isSymbolicLink(part),"Symlink in native probe path "+path);return absolute.toRealPath();}
    private void fail(Throwable problem){if(failure==null){problem.printStackTrace();failure=problem;}stage=Stage.CLOSE;}
    private void finish(Minecraft client) {
        if(stage==Stage.FINISHED)return;stage=Stage.FINISHED;
        try {
            if(client.getIntegratedServer()!=null) {
                owned(client.getIntegratedServer());if(client.theWorld!=null)client.theWorld.sendQuittingDisconnectingPacket();
                client.loadWorld(null);client.displayGuiScreen(new GuiMainMenu());normalClose=true;
                require(client.getIntegratedServer()==null&&client.theWorld==null,"Native server did not stop");
            }
            if(failure==null){require(checksComplete&&normalClose,"Incomplete native checks/normal close");verifySavedPlayer();}
        } catch(Throwable problem){if(failure==null)failure=problem;problem.printStackTrace();}
        report.addProperty("passed",false);report.addProperty("provisional",true);report.addProperty("parent_acceptance_pending",true);
        report.addProperty("checks_passed",failure==null&&checksComplete);report.addProperty("saved_and_closed_normally",normalClose);
        report.addProperty("native_process_exit_observed",false);report.addProperty("positive_consumption_cases",positiveCases);report.addProperty("negative_cases",negativeCases);
        report.addProperty("table_block_writes",tableWrites);report.addProperty("inventory_setup_slot_writes",inventorySetupWrites);
        report.addProperty("workbench_fixture_matrix_slot_writes",matrixSetupWrites);report.addProperty("scratch_fixture_matrix_slot_writes",scratchMatrixWrites);report.addProperty("cursor_setup_writes",cursorSetupWrites);report.addProperty("crafted_output_inventory_adds",outputInventoryAdds);
        report.addProperty("native_stack_variants_observed_including_outputs",observedIngredients.size());report.add("native_ingredient_evidence",ingredientEvidence);
        report.add("recipes",recipeEvidence);report.add("cases",caseEvidence);if(failure!=null)report.addProperty("error",failure.toString());
        try {
            if(copyVerified){ownedSave();Files.write(COPIED.resolve("mtr-crafting-probe-"+runId+".json"),report.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
            System.out.println((failure==null?"MTR_CRAFTING_PROBE_RAW_READY ":"MTR_CRAFTING_PROBE_FAIL ")+report);
            if(copyVerified)client.shutdown();
        } catch(Throwable problem){throw new IllegalStateException("Cannot preserve native crafting raw evidence",problem);}
    }
    private void verifySavedPlayer()throws Exception {
        ownedSave();require(Files.isRegularFile(save.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS),"Actual native level.dat missing");
        Path player=save.resolve("playerdata").resolve(playerId.toString()+".dat");require(Files.isRegularFile(player,LinkOption.NOFOLLOW_LINKS),"Actual native player NBT missing");
        NBTTagCompound tag;try(InputStream input=Files.newInputStream(player)){tag=CompressedStreamTools.readCompressed(input);}
        NBTTagList inventory=tag.getTagList("Inventory",10);Map<String,Integer> counts=new TreeMap<>();JsonArray saved=new JsonArray();
        for(int i=0;i<inventory.tagCount();i++) {
            NBTTagCompound entry=inventory.getCompoundTagAt(i);ItemStack stack=ItemStack.loadItemStackFromNBT(entry);require(stack!=null,"Unknown saved native ItemStack");
            String key=key(stack);counts.put(key,(counts.containsKey(key)?counts.get(key):0)+stack.stackSize);
            JsonObject value=stackJson(stack);value.addProperty("slot",entry.getByte("Slot")&255);saved.add(value);
        }
        require(counts.equals(expectedInventory),"Native player save lost/changed crafted outputs: "+counts);
        report.add("saved_native_player_inventory",saved);report.addProperty("native_player_save_readback",true);
        report.addProperty("saved_player_file",player.toString());report.addProperty("saved_player_sha256",sha(Files.readAllBytes(player)));
        report.addProperty("level_dat_sha256",sha(Files.readAllBytes(save.resolve("level.dat"))));
    }
    private static byte[] read(InputStream input)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))>=0)out.write(buffer,0,n);return out.toByteArray();}
    private static String sha(byte[] bytes)throws Exception {StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format("%02x",value&255));return result.toString();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
