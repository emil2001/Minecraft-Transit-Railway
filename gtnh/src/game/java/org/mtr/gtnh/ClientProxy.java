package org.mtr.gtnh;

@cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
public final class ClientProxy extends CommonProxy {
    @Override public void init() { org.mtr.mod.InitClient.init(); }
}
