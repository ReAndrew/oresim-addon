package oresim;

import baritone.api.BaritoneAPI;
import net.ccbluex.liquidbounce.event.events.GameTickEvent;
import net.ccbluex.liquidbounce.features.command.CommandManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.List;
import java.util.Set;

/** Development check (ORESIM_AUTOTEST=baritone): runs {@code .oresim mine coal} and watches Baritone dig towards a simulated coal ore. */
final class OreSimBaritoneTest {
    private static int stage, ticks;
    private static BlockPos first;
    private static double startDist = -1;
    private static int mined;
    private static final java.util.Set<BlockPos> seen = new java.util.HashSet<>();

    private OreSimBaritoneTest() {}

    static boolean enabled() {
        return "baritone".equals(System.getenv("ORESIM_AUTOTEST"));
    }

    static void install(net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon addon) {
        addon.on(GameTickEvent.class, event -> tick());
    }

    private static void log(String message, Object... args) {
        OreSimAddon.LOG.info("ORESIM-BARITONE " + message, args);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        try {
            switch (stage) {
                case 0 -> {
                    if (mc.level == null && mc.gui.screen() != null && ++ticks > 40) {
                        var settings = new LevelSettings("oresim-bar-" + System.currentTimeMillis() / 1000, GameType.CREATIVE,
                                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(settings.levelName(), settings, WorldOptions.defaultWithRandomSeed(),
                                WorldPresets::createNormalWorldDimensions, mc.gui.screen());
                        stage = 1;
                        ticks = 0;
                    }
                }
                case 1 -> {
                    if (mc.player != null && mc.level != null && ++ticks > 120) {
                        if (mc.gui.screen() != null) mc.gui.setScreen(null);
                        log("baritone loaded={} primary={}", BaritoneSupport.installed(), BaritoneAPI.getProvider().getPrimaryBaritone() != null);
                        OreSimAddon.module.setEnabled(true);
                        stage = 2;
                        ticks = 0;
                    }
                }
                case 2 -> {
                    if (++ticks == 200) {
                        log("chunk data={} coal={} diamond={}", OreSimAddon.module.chunkData().size(),
                                OreSimAddon.module.countFor(OreKind.COAL), OreSimAddon.module.countFor(OreKind.DIAMOND));
                        CommandManager.INSTANCE.execute("value set liquidbounce.modules.oreSim.baritone true");
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("mine diamond_ore");
                        stage = 3;
                        ticks = 0;
                    }
                }
                case 3 -> { // #mine diamond_ore must be taken over by OreSim
                    var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    var module = OreSimAddon.module;
                    if (++ticks == 60) {
                        var goals = module.baritoneGoals();
                        log("DIAMOND takeover running={} goals={} inControl={} nearest={}", module.baritoneRunning(), goals.size(),
                                baritone.getPathingControlManager().mostRecentInControl().map(p -> p.displayName0()).orElse("-"),
                                goals.isEmpty() ? null : goals.get(0));
                        baritone.getCommandManager().execute("cancel");
                        log("after cancel running={}", module.baritoneRunning());
                        stage = 4;
                        ticks = 0;
                    }
                }
                case 4 -> { // coal is near: let it dig there; any goal turning into air counts
                    var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    var module = OreSimAddon.module;
                    if (ticks == 0) baritone.getCommandManager().execute("mine coal_ore");
                    for (BlockPos g : module.baritoneGoals()) seen.add(g);
                    if (ticks % 40 == 5) {
                        var goals = module.baritoneGoals();
                        log("t={} inControl={} pathing={} goals={} nearest={} player={} seen={}", ticks,
                                baritone.getPathingControlManager().mostRecentInControl().map(p -> p.displayName0()).orElse("-"),
                                baritone.getPathingBehavior().isPathing(), goals.size(), goals.isEmpty() ? null : goals.get(0), mc.player.blockPosition(), seen.size());
                    }
                    for (BlockPos g : seen) if (mc.level.getBlockState(g).isAir()) mined++;
                    seen.removeIf(g -> mc.level.getBlockState(g).isAir());
                    if (mined >= 3) {
                        log("COAL MINED {} targets after {} ticks", mined, ticks);
                        stage = 5;
                        ticks = 0;
                    } else if (++ticks > 1600) {
                        log("TIMEOUT mined={}", mined);
                        stage = 5;
                        ticks = 0;
                    }
                }
                case 5 -> { // while OreSim drives, another command must win; a non-ore #mine must stay with Baritone
                    var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (ticks == 0) baritone.getCommandManager().execute("mine stone");
                    if (++ticks == 40) {
                        log("STONE running={} inControl={} (OreSim must have given way)", OreSimAddon.module.baritoneRunning(),
                                baritone.getPathingControlManager().mostRecentInControl().map(p -> p.displayName0()).orElse("-"));
                        baritone.getCommandManager().execute("cancel");
                        baritone.getCommandManager().execute("mine iron_ore");
                        stage = 8;
                        ticks = 0;
                    }
                }
                case 8 -> { // OreSim again, then a #goto takes over from it
                    var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (++ticks == 40) {
                        log("IRON again running={} inControl={}", OreSimAddon.module.baritoneRunning(),
                                baritone.getPathingControlManager().mostRecentInControl().map(p -> p.displayName0()).orElse("-"));
                        var p = mc.player.blockPosition();
                        baritone.getCommandManager().execute("goto " + p.getX() + " " + p.getY() + " " + (p.getZ() + 6));
                    } else if (ticks == 100) {
                        log("GOTO running={} inControl={} (OreSim must have given way)", OreSimAddon.module.baritoneRunning(),
                                baritone.getPathingControlManager().mostRecentInControl().map(p -> p.displayName0()).orElse("-"));
                        baritone.getCommandManager().execute("cancel");
                        stage = 6;
                    }
                }
                case 6 -> {
                    log("DONE");
                    stage = 7;
                    mc.stop();
                }
                default -> {}
            }
        } catch (Throwable t) {
            OreSimAddon.LOG.error("ORESIM-BARITONE test aborted", t);
            stage = 7;
        }
    }
}
