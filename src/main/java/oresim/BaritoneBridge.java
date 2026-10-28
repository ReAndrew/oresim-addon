package oresim;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets Baritone mine the simulated ores. Rejects hooks Baritone's obfuscated MineProcess with a mixin; this
 * registers a process of its own through the public API instead, so it keeps working across Baritone builds.
 * Only touch this class when {@link BaritoneSupport#installed()} is true (it links against Baritone).
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
        composite = null;
        sinceRefresh = 0;
        resetPickup();
        active = true;
        return "Baritone is mining the simulated " + kinds;
    }

    void stop() {
        active = false;
        goals = List.of();
        resetPickup();
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

    /** How often the goal list is rebuilt while nothing changed (ticks); rebuilding re-plans the path. */
    private static final int REFRESH = 100;

    private Goal composite;
    private int sinceRefresh;

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        IBaritone baritone = registeredWith;
        if (baritone != null) {
            if (otherProcessActive(baritone)) return giveWay();
            if (baritone.getMineProcess().isActive()) {
                // The user typed another #mine: follow it when it is an ore, give way when it is not.
                Set<OreKind> wanted = mineTarget(baritone);
                if (wanted.isEmpty()) return giveWay();
                if (!wanted.equals(kinds)) {
                    kinds.clear();
                    kinds.addAll(wanted);
                    goals = List.of();
                }
            }
        }

        if (module.pickUpDrops()) {
            noteMined();
            PathingCommand pickup = pickUp(calcFailed);
            if (pickup != null) return pickup;
        } else {
            resetPickup();
        }

        boolean refresh = goals.isEmpty() || ++sinceRefresh >= REFRESH || calcFailed;
        if (!refresh) {
            for (BlockPos pos : goals) {
                if (module.isMinedOut(pos)) {
                    refresh = true;
                    break;
                }
            }
        }
        if (!refresh) return new PathingCommand(composite, PathingCommandType.REVALIDATE_GOAL_AND_PATH);

        if (calcFailed && !goals.isEmpty()) module.skip(goals.get(0)); // the nearest one is unreachable
        sinceRefresh = 0;
        List<BlockPos> next = module.nearestOres(kinds, GOALS, goals);
        if (next.isEmpty()) {
            module.say("OreSim: no simulated ore left in range, Baritone stopped");
            return giveWay();
        }

        boolean changed = !next.equals(goals);
        goals = next;
        if (changed || composite == null) {
            List<Goal> parts = new ArrayList<>(next.size());
            for (BlockPos pos : next) parts.add(new GoalTwoBlocks(pos));
            composite = new GoalComposite(parts.toArray(new Goal[0]));
        }
        return new PathingCommand(composite, changed ? PathingCommandType.SET_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    /** How long after mining a block its drop is still looked for (ticks): the item appears a few ticks after the break. */
    private static final int DROP_WINDOW = 40;
    /** Longest time spent walking to a single item before it is given up on (ticks). */
    private static final int ITEM_TIMEOUT = 120;
    /** How far from the mined block a drop can have flown or been pushed (blocks). */
    private static final double DROP_RADIUS = 6.0;

    /** Mined goal blocks whose drops may still lie around, with the tick they were mined at. */
    private final Map<BlockPos, Integer> minedAt = new HashMap<>();
    private final Set<Integer> abandonedItems = new HashSet<>();
    private int tickCount;
    private int itemId = -1;
    private int itemSince;
    private BlockPos itemGoal;

    private void resetPickup() {
        minedAt.clear();
        abandonedItems.clear();
        itemId = -1;
        itemGoal = null;
    }

    /** Remembers goal blocks that just turned to air, to pick up what they dropped. */
    private void noteMined() {
        tickCount++;
        for (BlockPos pos : goals) {
            if (module.isMinedOut(pos)) minedAt.putIfAbsent(pos, tickCount);
        }
        minedAt.values().removeIf(tick -> tickCount - tick > DROP_WINDOW + ITEM_TIMEOUT);
    }

    /**
     * Walks to the nearest item dropped by a block mined a moment ago; null when there is nothing to collect
     * (mining then goes on). Items that cannot be reached, or that take too long, are given up on.
     */
    private PathingCommand pickUp(boolean calcFailed) {
        if (minedAt.isEmpty() || mc.level == null || mc.player == null) return null;
        if (calcFailed && itemId != -1) {
            abandonedItems.add(itemId);
            itemId = -1;
        }

        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Integer> mined : minedAt.entrySet()) {
            BlockPos pos = mined.getKey();
            AABB area = new AABB(pos).inflate(DROP_RADIUS);
            for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, area)) {
                if (item.isRemoved() || abandonedItems.contains(item.getId())) continue;
                double dist = item.distanceToSqr(mc.player);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = item;
                }
            }
        }

        if (best == null) {
            // Nothing lying around: keep waiting only while a fresh drop may still show up.
            boolean waiting = false;
            for (int tick : minedAt.values()) waiting |= tickCount - tick <= DROP_WINDOW;
            itemId = -1;
            itemGoal = null;
            if (!waiting) minedAt.clear();
            return null;
        }

        if (best.getId() != itemId) {
            itemId = best.getId();
            itemSince = tickCount;
            itemGoal = null;
        } else if (tickCount - itemSince > ITEM_TIMEOUT) {
            abandonedItems.add(itemId);
            itemId = -1;
            itemGoal = null;
            return null;
        }

        BlockPos target = best.blockPosition();
        boolean changed = !target.equals(itemGoal);
        itemGoal = target;
        return new PathingCommand(new GoalBlock(target),
                changed ? PathingCommandType.SET_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand giveWay() {
        active = false;
        goals = List.of();
        composite = null;
        resetPickup();
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    /** Whether the user started something else (#goto, #follow, ...): it wins over a mining run that is already going. */
    private boolean otherProcessActive(IBaritone baritone) {
        return baritone.getCustomGoalProcess().isActive() || baritone.getGetToBlockProcess().isActive()
                || baritone.getFollowProcess().isActive() || baritone.getExploreProcess().isActive()
                || baritone.getFarmProcess().isActive() || baritone.getBuilderProcess().isActive()
                || baritone.getElytraProcess().isActive();
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
