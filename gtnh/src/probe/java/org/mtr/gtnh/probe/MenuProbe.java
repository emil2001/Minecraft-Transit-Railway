package org.mtr.gtnh.probe;

import cpw.mods.fml.common.*;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import org.mtr.libraries.com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Test-only probe included solely by -PmtrGameProbe; never part of the delivered mod. */
@SideOnly(Side.CLIENT)
@Mod(modid="mtr_gtnh_probe",name="MTR GTNH acceptance probe",version="0",dependencies="required-after:mtr",acceptableRemoteVersions="*")
public final class MenuProbe {
    private int stableTicks;
    private int diagnosticTicks;
    private String lastScreen;
    private boolean finished;
    @Mod.EventHandler public void init(FMLInitializationEvent event) { FMLCommonHandler.instance().bus().register(this); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase!=TickEvent.Phase.END) return;
        Minecraft client=Minecraft.getMinecraft();
        String observed=client.currentScreen==null?"null":client.currentScreen.getClass().getName();
        if (!observed.equals(lastScreen)) {
            System.out.println("MTR_MENU_PROBE_SCREEN "+observed+" available="+Loader.instance().hasReachedState(LoaderState.AVAILABLE));
            lastScreen=observed;
        }
        if (++diagnosticTicks==100) {
            ScreenShotHelper.saveScreenshot(client.mcDataDir,"mtr-probe-diagnostic.png",client.displayWidth,client.displayHeight,client.getFramebuffer());
        }
        if (!Loader.instance().hasReachedState(LoaderState.AVAILABLE)) return;
        if (client.theWorld!=null || client.currentScreen==null) { stableTicks=0; return; }
        String screen=client.currentScreen.getClass().getName();
        if (!screen.equals("net.minecraft.client.gui.GuiMainMenu") && !screen.equals("lumien.custommainmenu.gui.GuiCustom")) return;
        if (++stableTicks<60) return;
        finished=true;
        try {
            JsonObject result=new JsonObject();
            result.addProperty("menu_class",screen);
            result.addProperty("world_opened",false);
            int blocks=0,items=0;
            for (Object block:net.minecraft.block.Block.blockRegistry)
                if (net.minecraft.block.Block.blockRegistry.getNameForObject(block).startsWith("mtr:")) blocks++;
            for (Object item:net.minecraft.item.Item.itemRegistry)
                if (net.minecraft.item.Item.itemRegistry.getNameForObject(item).startsWith("mtr:")) items++;
            if (blocks==0 || items==0) throw new AssertionError("MTR registries were not installed");
            result.addProperty("mtr_blocks",blocks); result.addProperty("mtr_items",items);
            try (java.io.InputStream stream=client.getResourceManager().getResource(new net.minecraft.util.ResourceLocation("mtr:font/mtr.json")).getInputStream()) {
                if (stream.read()<0) throw new AssertionError("MTR font resource is empty");
            }
            String screenshot="mtr-probe-menu-"+System.currentTimeMillis()+".png";
            ScreenShotHelper.saveScreenshot(client.mcDataDir,screenshot,client.displayWidth,client.displayHeight,client.getFramebuffer());
            File screenshotFile=new File(new File(client.mcDataDir,"screenshots"),screenshot);
            if (!screenshotFile.isFile()) throw new AssertionError("The actual game framebuffer was not saved");
            result.addProperty("screenshot",screenshotFile.getAbsolutePath());
            Files.write(new File(client.mcDataDir,"mtr-menu-probe.json").toPath(),result.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("MTR_MENU_PROBE_PASS "+result);
            client.shutdown();
        } catch (Throwable failure) {
            failure.printStackTrace();
            throw new IllegalStateException("MTR menu acceptance probe failed",failure);
        }
    }
}
