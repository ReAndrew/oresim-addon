package oresim;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.ccbluex.liquidbounce.event.events.BlockChangeEvent;
import net.ccbluex.liquidbounce.event.events.ChunkLoadEvent;
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent;
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent;
import net.ccbluex.liquidbounce.features.module.ClientModule;
import net.ccbluex.liquidbounce.features.module.ModuleCategories;
import net.ccbluex.liquidbounce.render.RenderShortcutsKt;
import net.ccbluex.liquidbounce.render.engine.type.Color4b;
import net.ccbluex.liquidbounce.utils.input.InputBind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.phys.AABB;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows where ores generated, computed from the world seed. In singleplayer the seed is read from the
 * integrated server; on a server set the "Seed" value.
 */
public final class OreSimModule extends ClientModule {
    private final Minecraft mc = Minecraft.getInstance();

    private final net.ccbluex.liquidbounce.config.types.Value<Integer> chunkRange = integer("ChunkRange", 5, 1, 12);
    private final net.ccbluex.liquidbounce.config.types.Value<Boolean> airCheck = bool("AirCheck", true);
    private final net.ccbluex.liquidbounce.config.types.Value<String> seedText = text("Seed", "");
    private final Map<OreKind, net.ccbluex.liquidbounce.config.types.Value<Boolean>> enabledKinds = new EnumMap<>(OreKind.class);

    private final Map<Long, Map<OreKind, LongArrayList>> chunks = new HashMap<>();
    private OreTable table;
    private Long seed;
    private boolean pendingReload;
    private net.minecraft.resources.ResourceKey<Level> tableDimension;

    public OreSimModule() {
        super("OreSim", ModuleCategories.RENDER, InputConstants.UNKNOWN.getValue(), InputBind.BindAction.TOGGLE,
                false, false, false, false, List.of("Xray"), false);
        for (OreKind kind : OreKind.values()) enabledKinds.put(kind, bool(kind.valueName, true));

        on(WorldRenderEvent.class, this::render);
        on(ChunkLoadEvent.class, event -> onChunk(event.getX(), event.getZ()));
        on(BlockChangeEvent.class, this::onBlockChange);
        on(WorldChangeEvent.class, event -> {
            reset();
            if (event.getWorld() != null) pendingReload = true;
        });
        on(net.ccbluex.liquidbounce.event.events.GameTickEvent.class, event -> {
            if (pendingReload && mc.level != null && mc.player != null) {
                pendingReload = false;
                reload();
            }
        });
        seedText.onChanged(text -> reload());
    }

    @Override
    public void onEnabled() {
        reload();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    private void reset() {
        chunks.clear();
        table = null;
        seed = null;
        tableDimension = null;
    }

    /** Positions held for a chunk, for tests. */
    public int countFor(OreKind kind) {
        int total = 0;
        for (var map : chunks.values()) {
            LongArrayList list = map.get(kind);
            if (list != null) total += list.size();
        }
        return total;
    }

    public Map<Long, Map<OreKind, LongArrayList>> chunkData() {
        return chunks;
    }

    public OreTable table() {
        return table;
    }

    public ClientLevel level() {
        return mc.level;
    }

    /** Seed in use: the Seed value, else the integrated server's, else unknown. */
    public Long resolveSeed() {
        String text = seedText.get().trim();
        if (!text.isEmpty()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return (long) text.hashCode();
            }
        }
        var server = mc.getSingleplayerServer();
        return server == null ? null : server.overworld().getSeed();
    }

    public void reload() {
        chunks.clear();
        table = null;
        seed = resolveSeed();
        if (mc.level == null || seed == null) return;

        try {
            tableDimension = mc.level.dimension();
            table = OreTable.build(stemOf(tableDimension));
        } catch (Throwable t) {
            OreSimAddon.LOG.error("Could not read the ore table", t);
            table = null;
            return;
        }
        if (mc.player == null) return;
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        int range = chunkRange.get() + 1;
        for (int x = cx - range; x <= cx + range; x++) {
            for (int z = cz - range; z <= cz + range; z++) onChunk(x, z);
        }
    }

    private static net.minecraft.resources.ResourceKey<LevelStem> stemOf(net.minecraft.resources.ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) return LevelStem.NETHER;
        if (dimension == Level.END) return LevelStem.END;
        return LevelStem.OVERWORLD;
    }

    /**
     * A chunk's veins reach into its neighbours, so a chunk is simulated once all eight are loaded
     * (otherwise unknown blocks would be guessed and the random sequence would drift). A newly loaded
     * chunk can complete the ring of any of its neighbours.
     */
    private void onChunk(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) simulateChunk(x + dx, z + dz);
        }
    }

    private boolean ringLoaded(ClientLevel level, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(x + dx, z + dz) == null) return false;
            }
        }
        return true;
    }

    private void simulateChunk(int x, int z) {
        ClientLevel level = mc.level;
        if (!getRunning() || level == null || table == null || seed == null || level.dimension() != tableDimension) return;
        long key = ChunkPos.pack(x, z);
        if (chunks.containsKey(key) || !ringLoaded(level, x, z)) return;

        try {
            chunks.put(key, new OreSimulator(level, table, seed, airCheck.get()).simulate(new ChunkPos(x, z)));
        } catch (Throwable t) {
            OreSimAddon.LOG.error("Ore simulation failed for chunk {} {}", x, z, t);
            chunks.put(key, Map.of());
        }
    }

    private void onBlockChange(BlockChangeEvent event) {
        if (event.getNewState().canOcclude() || !airCheck.get()) return;
        BlockPos pos = event.getBlockPos();
        long packed = pos.asLong();
        for (Map.Entry<Long, Map<OreKind, LongArrayList>> entry : chunks.entrySet()) {
            // A vein can spill into neighbouring chunks: check the origin chunk and its neighbours.
            int cx = ChunkPos.getX(entry.getKey()), cz = ChunkPos.getZ(entry.getKey());
            if (Math.abs(cx - (pos.getX() >> 4)) > 1 || Math.abs(cz - (pos.getZ() >> 4)) > 1) continue;
            for (LongArrayList list : entry.getValue().values()) list.rem(packed);
        }
    }

    private void render(WorldRenderEvent event) {
        if (mc.player == null || chunks.isEmpty()) return;
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        int range = chunkRange.get();

        RenderShortcutsKt.withPositionRelativeToCamera(event.getEnvironment(), env -> {
            for (int x = cx - range; x <= cx + range; x++) {
                for (int z = cz - range; z <= cz + range; z++) {
                    Map<OreKind, LongArrayList> chunk = chunks.get(ChunkPos.pack(x, z));
                    if (chunk == null) continue;
                    for (Map.Entry<OreKind, LongArrayList> entry : chunk.entrySet()) {
                        if (!enabledKinds.get(entry.getKey()).get()) continue;
                        Color4b outline = new Color4b(entry.getKey().argb);
                        LongArrayList positions = entry.getValue();
                        for (int i = 0; i < positions.size(); i++) {
                            long p = positions.getLong(i);
                            AABB box = new AABB(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p),
                                    BlockPos.getX(p) + 1, BlockPos.getY(p) + 1, BlockPos.getZ(p) + 1);
                            RenderShortcutsKt.drawBox(env, box, Color4b.TRANSPARENT, outline, -1, -1, true);
                        }
                    }
                }
            }
        });
    }
}
