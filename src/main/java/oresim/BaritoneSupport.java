package oresim;

import net.fabricmc.loader.api.FabricLoader;

/** Kept apart from {@link BaritoneBridge} on purpose: touching that class links against Baritone, which may be absent. */
final class BaritoneSupport {
    private BaritoneSupport() {}

    static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("baritone");
    }
}
