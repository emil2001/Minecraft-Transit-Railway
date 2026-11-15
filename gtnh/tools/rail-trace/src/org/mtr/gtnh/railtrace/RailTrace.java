package org.mtr.gtnh.railtrace;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;

@Mod(modid = "mtr_rail_trace", name = "MTR rail render trace", version = "0.1",
        dependencies = "required-after:mtr@[4.0.5-gtnh.18,)", acceptableRemoteVersions = "*")
public final class RailTrace {
    @SidedProxy(clientSide = "org.mtr.gtnh.railtrace.RailTraceClient",
            serverSide = "org.mtr.gtnh.railtrace.RailTrace$CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    public static class CommonProxy {
        public void init() {}
    }
}
