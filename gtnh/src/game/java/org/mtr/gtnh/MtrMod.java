package org.mtr.gtnh;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.*;
import org.mtr.mod.Init;

@Mod(modid="mtr",name="Minecraft Transit Railway",version="4.0.5-gtnh.2",acceptedMinecraftVersions="[1.7.10]",
    dependencies="required-after:Forge@[10.13.4.1614,);after:angelica;after:lwjgl3ify")
public final class MtrMod {
    @SidedProxy(clientSide="org.mtr.gtnh.ClientProxy",serverSide="org.mtr.gtnh.CommonProxy")
    public static CommonProxy proxy;
    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) { Init.init(); }
    @Mod.EventHandler public void init(FMLInitializationEvent event) { proxy.init(); }
    @Mod.EventHandler public void postInit(FMLPostInitializationEvent event) { Init.REGISTRY.registerRecipes(); }
    @Mod.EventHandler public void serverStarted(FMLServerStartedEvent event) {
        Init.REGISTRY.eventRegistry.serverStarted(cpw.mods.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());
    }
    @Mod.EventHandler public void serverStopping(FMLServerStoppingEvent event) {
        Init.REGISTRY.eventRegistry.serverStopping(cpw.mods.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance());
    }
}
