package oresim;

import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class OreSimAddon extends LiquidBounceAddon {
    public static final Logger LOG = LogManager.getLogger("OreSim");
    public static OreSimModule module;

    @Override
    public void onInitialize() {
        module = new OreSimModule();
        registerModules(module);
        registerCommand(OreSimCommand::register);
        OreSimDevTest.install(this);
    }
}
