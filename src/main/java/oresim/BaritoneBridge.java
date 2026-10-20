package oresim;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Lets Baritone mine the simulated ores. Rejects hooks Baritone's obfuscated MineProcess with a mixin; this
 * registers a process of its own through the public API instead, so it keeps working across Baritone builds.
 * Only touch this class when {@link #available()} is true (it links against Baritone).
 */
final class BaritoneBridge implements IBaritoneProcess {
    /** Nearest simulated ores handed to the pathfinder at once. */
    private static final int GOALS = 16;

    private final Minecraft mc = Minecraft.getInstance();
    private final OreSimModule module;
    private final Set<OreKind> kinds = EnumSet.noneOf(OreKind.class);
    private IBaritone registeredWith;
    private boolean active;
    private List<BlockPos> goals = List.of();

    BaritoneBridge(OreSimModule module) {
        this.module = module;
    }

    /**
     * What Baritone's own mine process is looking for ({@code #mine diamond_ore}), as ore kinds. Read by field
     * type because the implementation's member names are obfuscated; empty when it is idle or mines a non-ore.
     */
    static Set<OreKind> mineTarget(IBaritone baritone) {
        var process = baritone.getMineProcess();
        Set<OreKind> kinds = EnumSet.noneOf(OreKind.class);
        if (!process.isActive()) return kinds;
        try {
            for (var field : process.getClass().getDeclaredFields()) {
                if (field.getType() != baritone.api.utils.BlockOptionalMetaLookup.class) continue;
                field.setAccessible(true);
                var lookup = (baritone.api.utils.BlockOptionalMetaLookup) field.get(process);
                if (lookup == null) continue;
                for (var meta : lookup.blocks()) {
                    OreKind kind = OreKind.of(List.of(meta.getBlock()));
                    if (kind != null) kinds.add(kind);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            OreSimAddon.LOG.warn("Could not read Baritone's mine target", e);
        }
        return kinds;
    }

    /** Called every tick while "Baritone" is on: takes over when the user starts {@code #mine <ore>}. */
    void watchMine() {
        if (active) return;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) return;
        Set<OreKind> target = mineTarget(baritone);
        if (target.isEmpty()) return;
        module.say("OreSim: " + start(target));
    }

    static boolean available() {
        return FabricLoader.getInstance().isModLoaded("baritone");
    }

    /** Starts mining the given ore kinds (all kinds the module shows when empty). Returns a status line. */
    String start(Set<OreKind> wanted) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) return "Baritone is not ready yet";
        if (registeredWith != baritone) {
            baritone.getPathingControlManager().registerProcess(this);
            registeredWith = baritone;
        }
        kinds.clear();
        kinds.addAll(wanted.isEmpty() ? EnumSet.allOf(OreKind.class) : wanted);
        goals = List.of();
        active = true;
        return "Baritone is mining the simulated " + kinds;
    }

    void stop() {
        active = false;
        goals = List.of();
        IBaritone baritone = registeredWith;
        if (baritone != null) baritone.getPathingBehavior().cancelEverything();
    }

    boolean running() {
        return active;
    }

    List<BlockPos> goals() {
        return goals;
    }

    @Override
    public boolean isActive() {
        return active && module.getRunning() && mc.player != null && mc.level != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        List<BlockPos> next = module.nearestOres(kinds, GOALS, goals);
        if (calcFailed && !goals.isEmpty()) {
            // The nearest one is unreachable: skip it rather than retrying forever.
            module.skip(goals.get(0));
            next = module.nearestOres(kinds, GOALS, goals);
        }
        if (next.isEmpty()) {
            active = false;
            goals = List.of();
            module.say("OreSim: no simulated ore left in range, Baritone stopped");
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        boolean changed = !next.equals(goals);
        goals = next;
        List<Goal> parts = new ArrayList<>(next.size());
        for (BlockPos pos : next) parts.add(new GoalTwoBlocks(pos));
        return new PathingCommand(new GoalComposite(parts.toArray(new Goal[0])),
                changed ? PathingCommandType.SET_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public double priority() {
        // Above Baritone's own mine process, which then loses control and cancels itself.
        return DEFAULT_PRIORITY + 1;
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        // Another process (a #goto, say) took over: the user's newer command wins.
        active = false;
        goals = List.of();
    }

    @Override
    public String displayName0() {
        return "OreSim " + kinds;
    }
}
