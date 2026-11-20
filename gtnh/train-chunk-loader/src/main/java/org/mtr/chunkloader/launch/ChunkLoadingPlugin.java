package org.mtr.chunkloader.launch;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(2000)
@IFMLLoadingPlugin.TransformerExclusions("org.mtr.chunkloader.launch.")
public final class ChunkLoadingPlugin implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[] {
                "org.mtr.chunkloader.route.RouteTransformer",
                "org.mtr.chunkloader.watch.PassengerViewTransformer"
        };
    }

    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public String getAccessTransformerClass() { return null; }
    @Override public void injectData(Map<String, Object> data) {}
}
