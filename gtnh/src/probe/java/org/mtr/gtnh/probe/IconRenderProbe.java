package org.mtr.gtnh.probe;

import com.google.gson.*;
import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.*;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.Slot;
import net.minecraft.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.*;
import net.minecraft.world.*;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.client.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.*;
import org.mtr.mod.CreativeModeTabs;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicitly selected test mod: captures the real native creative inventory in a new owned save. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_icon_probe", name="MTR native creative icon probe", version="0", dependencies="required-after:mtr", acceptableRemoteVersions="*")
public final class IconRenderProbe {
    private enum Stage { MENU, WORLD, GUI_INITIAL, GUI_SIGNALS, DASHBOARD, CLOSE, FINISHED }
    private Stage stage = Stage.MENU;
    private final JsonObject report = new JsonObject();
    private final List<ItemStack> stacks = new ArrayList<>();
    private final List<Integer> catalogIndices = new ArrayList<>();
    private List<ItemStack> catalog;
    private int firstVisibleIndex;
    private final String runId = System.getProperty("mtr.iconRun", "");
    private long started;
    private int menuTicks, readyTicks, drawnFrames;
    private boolean copyVerified, oldPause;
    private File copiedClient, saveDirectory;
    private String saveName;
    private Throwable failure;
    private GuiContainerCreative screen;
    private org.mtr.mod.screen.DashboardScreen dashboard;
    private JsonObject frameStart;
    private PrintStream previousErr, observingErr;
    private final ByteArrayOutputStream renderingErrors = new ByteArrayOutputStream();
    private boolean stderrTruncated;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        started = System.currentTimeMillis();
        FMLCommonHandler.instance().bus().register(this);
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage == Stage.FINISHED) return;
        Minecraft client = Minecraft.getMinecraft();
        try {
            if (stage == Stage.CLOSE) { finish(client); return; }
            require(System.currentTimeMillis() - started < 210_000, "Icon probe deadline in " + stage);
            if (stage == Stage.MENU) {
                if (!Loader.instance().hasReachedState(LoaderState.AVAILABLE) || client.theWorld != null || client.currentScreen == null) { menuTicks = 0; return; }
                String name = client.currentScreen.getClass().getName();
                if (!name.equals("net.minecraft.client.gui.GuiMainMenu") && !name.equals("lumien.custommainmenu.gui.GuiCustom")) { menuTicks = 0; return; }
                if (++menuTicks < 60) return;
                reserve(client); oldPause = client.gameSettings.pauseOnLostFocus; client.gameSettings.pauseOnLostFocus = false;
                report.addProperty("menu_class", name); stage = Stage.WORLD;
                client.launchIntegratedServer(saveName, saveName, new WorldSettings(0x4D545249434F4EL, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT)
                    .enableCommands().func_82750_a("2;7,2x3,2;1;"));
            } else if (stage == Stage.WORLD) {
                if (client.theWorld == null || client.thePlayer == null || client.getIntegratedServer() == null || client.playerController == null) return;
                owned(client.getIntegratedServer());
                if (!client.playerController.isInCreativeMode() || !client.thePlayer.capabilities.isCreativeMode) return;
                int x = MathHelper.floor_double(client.thePlayer.posX) >> 4, z = MathHelper.floor_double(client.thePlayer.posZ) >> 4;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!client.theWorld.getChunkProvider().chunkExists(x + dx, z + dz)) { readyTicks = 0; return; }
                if (++readyTicks < 10) return;
                openCreative(client); stage = Stage.GUI_INITIAL;
            } else if (stage == Stage.GUI_INITIAL || stage == Stage.GUI_SIGNALS || stage == Stage.DASHBOARD) owned(client.getIntegratedServer());
        } catch (Throwable problem) { fail("client_tick", problem); finish(client); }
    }
    private void reserve(Minecraft client) throws Exception {
        String path = System.getProperty("mtr.probeClient", "");
        require(new File(path).isAbsolute(), "Set -Dmtr.probeClient=<absolute disposable copied .minecraft>");
        copiedClient = new File(path).getCanonicalFile();
        require(client.mcDataDir.getCanonicalFile().equals(copiedClient), "Refusing a different client directory");
        require(UUID.fromString(runId).toString().equals(runId), "Set -Dmtr.iconRun=<fresh canonical UUID>");
        require(client.theWorld == null && client.getIntegratedServer() == null, "Refusing an existing open world");
        Path saves = copiedClient.toPath().resolve("saves"); Files.createDirectories(saves);
        require(!Files.isSymbolicLink(saves) && saves.toRealPath().getParent().equals(copiedClient.toPath().toRealPath()), "Saves escaped copied client");
        require(!Files.exists(reportPath(), LinkOption.NOFOLLOW_LINKS), "Run report already exists");
        saveName = "MTR-icon-probe-" + runId;
        saveDirectory = Files.createDirectory(saves.resolve(saveName)).toFile();
        Files.write(saveDirectory.toPath().resolve("mtr-icon-probe-owner.txt"), runId.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        copyVerified = true;
        report.addProperty("run_id", runId); report.addProperty("client_directory", copiedClient.toString()); report.addProperty("save_directory", saveDirectory.toString());
        report.addProperty("new_uuid_save_reserved", true); report.addProperty("existing_save_loaded", false);
    }
    private void owned(MinecraftServer server) throws Exception {
        require(copyVerified && server != null && saveName.equals(server.getFolderName()), "Foreign integrated server");
        Path marker = saveDirectory.toPath().resolve("mtr-icon-probe-owner.txt");
        require(!Files.isSymbolicLink(saveDirectory.toPath()) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            && runId.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)), "Owned save marker changed");
        WorldServer world = server.worldServerForDimension(0);
        require(world != null && world.getSaveHandler() instanceof SaveHandler
            && ((SaveHandler) world.getSaveHandler()).getWorldDirectory().getCanonicalFile().equals(saveDirectory.getCanonicalFile()), "Integrated save identity differs");
    }
    @SuppressWarnings("unchecked") private void openCreative(Minecraft client) throws Exception {
        screen = new GuiContainerCreative(client.thePlayer); client.displayGuiScreen(screen);
        nativeMethod(GuiContainerCreative.class, "setCurrentCreativeTab", "func_147050_b", CreativeTabs.class).invoke(screen, CreativeModeTabs.CORE.creativeModeTab);
        String[] colors = {"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
        for (String color : colors) add("signal_connector_" + color);
        for (String id : new String[]{"rail_connector_20","rail_remover","creative_driver_key","brush","dashboard"}) add(id);
        Object container = screen.inventorySlots; Field list = null;
        for (Field candidate : container.getClass().getDeclaredFields()) if (!Modifier.isStatic(candidate.getModifiers()) && List.class.isAssignableFrom(candidate.getType())) { require(list == null, "Ambiguous creative item list"); list = candidate; }
        require(list != null, "Missing creative item list"); list.setAccessible(true);
        catalog = (List<ItemStack>) list.get(container);
        JsonArray ordered = new JsonArray();
        for (int i = 0; i < catalog.size(); i++) ordered.add(catalogEntry(i, catalog.get(i)));
        report.add("original_core_catalog", ordered); report.addProperty("original_core_catalog_size", catalog.size());
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack selected = null;
            for (int index = 0; index < catalog.size(); index++) {
                ItemStack candidate = catalog.get(index);
                if (candidate.getItem() == stacks.get(i).getItem()) { selected = candidate.copy(); catalogIndices.add(index); break; }
            }
            require(selected != null, "Requested item absent from actual CORE catalog: " + stacks.get(i).getItem()); stacks.set(i, selected);
        }
        report.addProperty("uses_original_core_catalog_stacks", true); report.addProperty("preserves_full_original_catalog_order", true);
        nativeMethod(container.getClass(), "scrollTo", "func_148329_a", float.class).invoke(container, 0F);
        Mouse.setCursorPosition(0, 0);
        previousErr = System.err;
        observingErr = new PrintStream(new OutputStream() {
            @Override public void write(int value) { write(new byte[]{(byte)value}, 0, 1); }
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                previousErr.write(bytes, offset, length);
                int accepted = Math.min(length, 262144 - renderingErrors.size());
                if (accepted > 0) renderingErrors.write(bytes, offset, accepted);
                if (accepted < length) stderrTruncated = true;
            }
        }, true, "UTF-8");
        System.setErr(observingErr);
        report.addProperty("screen_class", screen.getClass().getName()); report.addProperty("creative_tab", CreativeModeTabs.CORE.creativeModeTab.getTabLabel());
    }
    private static JsonObject catalogEntry(int index, ItemStack stack) {
        JsonObject value = new JsonObject(); value.addProperty("catalog_index", index);
        value.addProperty("item", Item.itemRegistry.getNameForObject(stack.getItem()));
        value.addProperty("stack_size", stack.stackSize); value.addProperty("damage", stack.getItemDamage()); return value;
    }
    private void captureInitial(Minecraft client) throws Exception {
        JsonObject initial = new JsonObject(); report.add("initial_catalog_render", initial);
        initial.addProperty("drawn_creative_frames", drawnFrames); initial.addProperty("first_visible_catalog_index", 0);
        initial.add("gl_frame_start", frameStart); initial.add("gl_before_capture", glState());
        JsonArray visible = new JsonArray(); initial.add("visible_slots", visible);
        for (int i = 0; i < Math.min(45, catalog.size()); i++) {
            Slot slot = (Slot) screen.inventorySlots.inventorySlots.get(i);
            require(slot.getStack() != null && slot.getStack().getItem() == catalog.get(i).getItem(), "Initial creative catalog order differs at " + i);
            JsonObject value = catalogEntry(i, slot.getStack()); value.addProperty("slot", i); visible.add(value);
        }
        initial.addProperty("creative_render_stderr", new String(renderingErrors.toByteArray(), StandardCharsets.UTF_8));
        screenshot(client, initial, "-initial");
    }
    private void scrollToSignals(Minecraft client) throws Exception {
        int firstSignal = Collections.min(catalogIndices.subList(0, 16)), lastSignal = Collections.max(catalogIndices.subList(0, 16));
        int maxRow = Math.max(0, catalog.size() / 9 - 4), row = Math.max(0, lastSignal / 9 - 4);
        row = Math.min(row, maxRow); firstVisibleIndex = row * 9;
        require(firstSignal >= firstVisibleIndex && lastSignal < firstVisibleIndex + 45, "All 16 signal colors cannot fit together in the original creative grid");
        float scroll = maxRow == 0 ? 0 : row / (float) maxRow;
        ReflectionHelper.setPrivateValue(GuiContainerCreative.class, screen, scroll, "currentScroll", "field_147067_x");
        nativeMethod(screen.inventorySlots.getClass(), "scrollTo", "func_148329_a", float.class).invoke(screen.inventorySlots, scroll);
        int magentaSlot = catalogIndices.get(2) - firstVisibleIndex;
        Slot hovered = (Slot) screen.inventorySlots.inventorySlots.get(magentaSlot);
        require(hovered.getStack() != null && hovered.getStack().getItem() == stacks.get(2).getItem(), "Native scrolling did not expose the original magenta signal slot");
        int left = ReflectionHelper.getPrivateValue(GuiContainer.class, screen, "guiLeft", "field_147003_i");
        int top = ReflectionHelper.getPrivateValue(GuiContainer.class, screen, "guiTop", "field_147009_r");
        int scale = new ScaledResolution(client, client.displayWidth, client.displayHeight).getScaleFactor();
        Mouse.setCursorPosition((left + hovered.xDisplayPosition + 8) * scale, client.displayHeight - (top + hovered.yDisplayPosition + 8) * scale);
        report.addProperty("signal_scroll_fraction", scroll); report.addProperty("first_visible_catalog_index", firstVisibleIndex);
        report.addProperty("requested_hover_item", "mtr:signal_connector_magenta"); report.addProperty("requested_hover_slot", magentaSlot);
    }
    private void add(String id) {
        Item item = (Item) Item.itemRegistry.getObject("mtr:" + id);
        require(item != null, "Missing registered item mtr:" + id); stacks.add(new ItemStack(item));
    }
    private static Method nativeMethod(Class<?> type, String mapped, String srg, Class<?> argument) throws NoSuchMethodException {
        for (String name : new String[]{mapped, srg}) {
            try {
                Method method = type.getDeclaredMethod(name, argument);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + mapped);
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (stage == Stage.DASHBOARD) {
            if (client.currentScreen != dashboard) return;
            if (event.phase == TickEvent.Phase.START) { frameStart = glState(); return; }
            if (++drawnFrames < 16) return;
            try {
                JsonObject evidence = new JsonObject(); report.add("dashboard_smoke", evidence);
                evidence.addProperty("screen_class", client.currentScreen.getClass().getName()); evidence.addProperty("drawn_gui_frames", drawnFrames);
                evidence.add("gl_frame_start", frameStart); evidence.add("gl_before_capture", glState());
                screenshot(client, evidence, "-dashboard"); report.addProperty("dashboard_pass", true);
            } catch (Throwable problem) { fail("dashboard", problem); }
            finally { stage = Stage.CLOSE; }
            return;
        }
        if ((stage != Stage.GUI_INITIAL && stage != Stage.GUI_SIGNALS) || client.currentScreen != screen) return;
        if (event.phase == TickEvent.Phase.START) { frameStart = glState(); return; }
        if (++drawnFrames < 8) return;
        if (stage == Stage.GUI_INITIAL) {
            try { captureInitial(client); scrollToSignals(client); drawnFrames = 0; stage = Stage.GUI_SIGNALS; }
            catch (Throwable problem) { fail("initial_catalog", problem); stage = Stage.CLOSE; }
            return;
        }
        try { capture(client); } catch (Throwable problem) { fail("capture", problem); }
        finally {
            try { screenshot(client, report, ""); } catch (Throwable problem) { fail("screenshot", problem); }
            report.add("gl_after_capture", glState()); stage = Stage.CLOSE;
            if (failure == null) try {
                dashboard = new org.mtr.mod.screen.DashboardScreen(org.mtr.core.data.TransportMode.TRAIN);
                client.displayGuiScreen(dashboard); drawnFrames = 0; stage = Stage.DASHBOARD;
            } catch (Throwable problem) { fail("open_dashboard", problem); }
        }
    }
    private void capture(Minecraft client) throws Exception {
        report.addProperty("sampling_boundary", "RenderTick.END after actual GuiContainerCreative slot drawing");
        report.addProperty("drawn_creative_frames", drawnFrames); report.add("gl_frame_start", frameStart); report.add("gl_before_capture", glState());
        int left = ReflectionHelper.getPrivateValue(GuiContainer.class, screen, "guiLeft", "field_147003_i");
        int top = ReflectionHelper.getPrivateValue(GuiContainer.class, screen, "guiTop", "field_147009_r");
        int scale = new ScaledResolution(client, client.displayWidth, client.displayHeight).getScaleFactor();
        report.addProperty("display_width", client.displayWidth); report.addProperty("display_height", client.displayHeight); report.addProperty("gui_scale", scale);
        report.addProperty("native_mouse_x", Mouse.getX()); report.addProperty("native_mouse_y", Mouse.getY());
        Class<?> nei = Class.forName("codechicken.nei.NEIClientConfig");
        for (String name : new String[]{"isEnabled", "isHidden", "isLoaded"}) report.addProperty("nei_" + name, (Boolean) nei.getMethod(name).invoke(null));
        JsonArray slots = new JsonArray(), skipped = new JsonArray(); report.add("slots", slots); report.add("targets_not_visible", skipped);
        Set<String> signals = new HashSet<>(); int visibleSignals = 0; GL11.glFinish();
        for (int i = 0; i < stacks.size(); i++) {
            int catalogIndex = catalogIndices.get(i), slotIndex = catalogIndex - firstVisibleIndex;
            if (slotIndex < 0 || slotIndex >= 45) { skipped.add(catalogEntry(catalogIndex, stacks.get(i))); continue; }
            Slot slot = (Slot) screen.inventorySlots.inventorySlots.get(slotIndex); int size = 16 * scale;
            require(slot.getStack() != null && slot.getStack().getItem() == stacks.get(i).getItem(), "Original creative target differs at slot " + slotIndex);
            int x = (left + slot.xDisplayPosition) * scale, y = client.displayHeight - (top + slot.yDisplayPosition + 16) * scale;
            require(x >= 0 && y >= 0 && x + size <= client.displayWidth && y + size <= client.displayHeight, "Slot outside actual framebuffer");
            ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
            GL11.glReadPixels(x, y, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            byte[] bytes = new byte[pixels.capacity()]; pixels.get(bytes); String hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JsonObject value = new JsonObject(); value.addProperty("slot", slotIndex); value.addProperty("catalog_index", catalogIndex); value.addProperty("item", Item.itemRegistry.getNameForObject(stacks.get(i).getItem()));
            value.addProperty("stack_size", slot.getStack().stackSize); value.addProperty("requested_stack_size", stacks.get(i).stackSize); value.addProperty("damage", slot.getStack().getItemDamage());
            value.addProperty("pixel_x", x); value.addProperty("pixel_y", y); value.addProperty("pixel_size", size); value.addProperty("rgba_sha256", hash);
            try {
                IItemRenderer renderer = MinecraftForgeClient.getItemRenderer(stacks.get(i), IItemRenderer.ItemRenderType.INVENTORY);
                value.addProperty("inventory_renderer_class", renderer == null ? "null" : renderer.getClass().getName());
            } catch (Throwable problem) { fail("slot_" + i + "_renderer", problem); }
            slots.add(value);
            if (i < 16) { signals.add(hash); visibleSignals++; }
        }
        report.addProperty("visible_signal_count", visibleSignals); require(visibleSignals == 16, "All 16 original signal colors must be visible together");
        report.add("slots", slots); report.addProperty("distinct_signal_hashes", signals.size()); report.addProperty("signal_pass", signals.size() >= 8);
        try { report.add("legacy_item_context", atlasDiagnostics(client)); } catch (Throwable problem) { fail("atlas_diagnostic", problem); }
        require(signals.size() >= 8, "Expected >=8 distinct native signal icons, got " + signals.size());
        require(mtrRenderingErrorCount() == 0, "NEI caught an MTR item rendering exception; distinct signal icons do not make this run pass");
        require(!stderrTruncated, "Rendering stderr was truncated");
    }
    private int mtrRenderingErrorCount() {
        String stderr = new String(renderingErrors.toByteArray(), StandardCharsets.UTF_8); int count = 0;
        for (String line : stderr.split("\\r?\\n")) if (line.contains("Error while rendering:") && (line.contains("item.mtr.") || line.contains("mtr:"))) count++;
        report.addProperty("mtr_item_render_error_count", count); return count;
    }
    @SuppressWarnings("unchecked") private JsonObject atlasDiagnostics(Minecraft client) throws Exception {
        Object context = field(Class.forName("org.mtr.mapping.registry.LegacyItemRenderers"), "active"); require(context != null, "No active item renderer context");
        TextureMap atlas = (TextureMap) field(context, "atlas"); Map<Object,Object> items = (Map<Object,Object>) field(context, "items"), icons = (Map<Object,Object>) field(context, "icons");
        Object models = field(context, "models"); JsonObject result = new JsonObject(); report.add("legacy_item_context", result);
        result.addProperty("context_class", context.getClass().getName()); result.addProperty("models_class", models.getClass().getName()); result.addProperty("item_count", items.size()); result.addProperty("icon_count", icons.size());
        result.addProperty("atlas_class", atlas.getClass().getName()); result.addProperty("atlas_gl_id", atlas.getGlTextureId());
        ITextureObject itemAtlas = client.getTextureManager().getTexture(TextureMap.locationItemsTexture), blockAtlas = client.getTextureManager().getTexture(TextureMap.locationBlocksTexture);
        result.addProperty("items_gl_id", itemAtlas == null ? -1 : itemAtlas.getGlTextureId()); result.addProperty("blocks_gl_id", blockAtlas == null ? -1 : blockAtlas.getGlTextureId()); result.addProperty("atlas_is_texture_manager_items", atlas == itemAtlas);
        result.addProperty("tessellator_class", Tessellator.class.getName()); result.addProperty("tessellator_instance_class", Tessellator.instance.getClass().getName());
        Method select = context.getClass().getDeclaredMethod("select", ItemStack.class, EntityLivingBase.class); select.setAccessible(true);
        JsonArray selected = new JsonArray(); result.add("selected_items", selected);
        for (ItemStack stack : stacks) {
            JsonObject value = new JsonObject(); value.addProperty("item", Item.itemRegistry.getNameForObject(stack.getItem())); value.addProperty("root_model", String.valueOf(items.get(stack.getItem())));
            selected.add(value); Object model;
            try { model = select.invoke(context, stack, null); }
            catch (Throwable problem) { fail("model_selection_" + (selected.size() - 1), problem); continue; }
            JsonArray layers = new JsonArray();
            for (Object texture : (List<?>) field(model, "layers")) {
                IIcon icon = (IIcon) icons.get(texture); JsonObject layer = new JsonObject(); layer.addProperty("texture", texture.toString());
                if (icon != null) {
                    layer.addProperty("icon_class", icon.getClass().getName()); layer.addProperty("icon_name", icon.getIconName()); layer.addProperty("width", icon.getIconWidth()); layer.addProperty("height", icon.getIconHeight());
                    layer.addProperty("min_u", icon.getMinU()); layer.addProperty("max_u", icon.getMaxU()); layer.addProperty("min_v", icon.getMinV()); layer.addProperty("max_v", icon.getMaxV());
                    layer.addProperty("identity_is_uploaded_icon", icon == atlas.getAtlasSprite(texture.toString()));
                } else layer.addProperty("missing", true);
                layers.add(layer);
            }
            value.add("layers", layers);
        }
        result.add("selected_items", selected); return result;
    }
    private void screenshot(Minecraft client, JsonObject evidence, String suffix) throws Exception {
        Path directory = copiedClient.toPath().resolve("screenshots"); Files.createDirectories(directory);
        require(!Files.isSymbolicLink(directory) && directory.toRealPath().getParent().equals(copiedClient.toPath().toRealPath()), "Screenshots escaped copied client");
        String name = "mtr-icon-probe-" + runId + suffix + ".png"; Path target = directory.resolve(name);
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Screenshot already exists");
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), pack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT), unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try { evidence.addProperty("screenshot_message", ScreenShotHelper.saveScreenshot(client.mcDataDir, name, client.displayWidth, client.displayHeight, client.getFramebuffer()).getUnformattedText()); }
        finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding); GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, pack); GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpack); }
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS), "Screenshot was not written"); evidence.addProperty("screenshot", target.toString());
    }
    private void finish(Minecraft client) {
        if (stage == Stage.FINISHED) return; report.addProperty("terminal_stage", stage.name()); stage = Stage.FINISHED;
        boolean safeExit = copyVerified && client.theWorld == null && client.getIntegratedServer() == null;
        try {
            if (client.getIntegratedServer() != null) {
                owned(client.getIntegratedServer()); if (client.theWorld != null) client.theWorld.sendQuittingDisconnectingPacket();
                client.loadWorld(null); client.displayGuiScreen(new GuiMainMenu()); safeExit = true;
                require(new File(saveDirectory, "level.dat").isFile(), "Owned world was not saved"); report.addProperty("saved_and_closed_normally", true);
            }
        } catch (Throwable problem) { fail("close", problem); }
        if (observingErr != null) {
            observingErr.flush(); if (System.err == observingErr) System.setErr(previousErr);
            report.addProperty("creative_render_stderr", new String(renderingErrors.toByteArray(), StandardCharsets.UTF_8)); report.addProperty("creative_render_stderr_truncated", stderrTruncated);
            if (mtrRenderingErrorCount() != 0) fail("mtr_item_rendering", new IllegalStateException("Captured MTR item rendering exception"));
            if (stderrTruncated) fail("rendering_stderr", new IllegalStateException("Rendering stderr was truncated"));
        }
        report.addProperty("elapsed_ms", System.currentTimeMillis() - started); report.addProperty("passed", failure == null
            && report.has("signal_pass") && report.get("signal_pass").getAsBoolean() && report.has("dashboard_pass") && report.get("dashboard_pass").getAsBoolean());
        if (failure != null) report.addProperty("error", failure.toString());
        try {
            if (copyVerified) { client.gameSettings.pauseOnLostFocus = oldPause; Files.write(reportPath(), report.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW); }
            System.out.println("MTR_ICON_PROBE_RESULT " + report);
        } catch (Throwable problem) { problem.printStackTrace(); }
        finally { if (safeExit) client.shutdown(); }
    }
    private Path reportPath() { return copiedClient.toPath().resolve("mtr-icon-probe-" + runId + ".json"); }
    private void fail(String boundary, Throwable problem) {
        if (failure == null) failure = problem;
        StringWriter trace = new StringWriter(); problem.printStackTrace(new PrintWriter(trace));
        report.addProperty(boundary + "_error", trace.toString()); problem.printStackTrace(System.err);
    }
    private static Object field(Object object, String name) throws Exception { Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass(); Field value = type.getDeclaredField(name); value.setAccessible(true); return value.get(object instanceof Class ? null : object); }
    private static JsonObject glState() { JsonObject value = new JsonObject(); value.addProperty("active_texture", GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)); value.addProperty("texture_binding_2d", GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)); return value; }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return value.toString(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
