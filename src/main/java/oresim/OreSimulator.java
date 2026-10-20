package oresim;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays vanilla ore generation for one chunk: same decoration seed, same feature seeding, same
 * placement-modifier chain and vein shape, so the positions match what the server generated. The
 * client's own blocks decide which positions are real (an ore only exists where there is solid
 * rock) because the client sees the world after generation.
 */
public final class OreSimulator {
    /** Development hook: when set, every predicted position is also recorded per feature. */
    static java.util.Map<OreTable.Ore, LongArrayList> debugByFeature;

    private final ClientLevel level;
    private final OreTable table;
    private final long seed;
    private final boolean airCheck;
    private final WorldGenerationContext heightContext;

    public OreSimulator(ClientLevel level, OreTable table, long seed, boolean airCheck) {
        this.level = level;
        this.table = table;
        this.seed = seed;
        this.airCheck = airCheck;
        this.heightContext = WorldGenerationContext.of(level);
    }

    /** Ore positions (packed {@link BlockPos#asLong}) of the vein features that start in this chunk. */
    public Map<OreKind, LongArrayList> simulate(ChunkPos chunkPos) {
        Set<ResourceKey<Biome>> biomes = new HashSet<>();
        ChunkPos.rangeClosed(chunkPos, 1).forEach(pos -> {
            ChunkAccess chunk = level.getChunk(pos.x(), pos.z(), ChunkStatus.BIOMES, false);
            if (chunk == null) return;
            for (LevelChunkSection section : chunk.getSections()) {
                section.getBiomes().getAll(entry -> entry.unwrapKey().ifPresent(biomes::add));
            }
        });

        Set<OreTable.Ore> ores = new HashSet<>();
        for (ResourceKey<Biome> biome : biomes) ores.addAll(table.oresIn(biome));

        Map<OreKind, LongArrayList> result = new EnumMap<>(OreKind.class);
        int originX = chunkPos.x() << 4;
        int originZ = chunkPos.z() << 4;
        WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
        long populationSeed = random.setDecorationSeed(seed, originX, originZ);

        for (OreTable.Ore ore : ores) {
            random.setFeatureSeed(populationSeed, ore.index(), ore.step());
            LongArrayList out = result.computeIfAbsent(ore.kind(), k -> new LongArrayList());
            int before = out.size();
            place(ore, ore.placement(), 0, new BlockPos(originX, level.getMinY(), originZ), random, out);
            if (debugByFeature != null) {
                LongArrayList mine = debugByFeature.computeIfAbsent(ore, k -> new LongArrayList());
                for (int i = before; i < out.size(); i++) mine.add(out.getLong(i));
            }
        }
        result.values().removeIf(LongArrayList::isEmpty);
        return result;
    }

    /** Walks the placement modifiers depth-first, as vanilla's lazy stream does. */
    private void place(OreTable.Ore ore, List<PlacementModifier> modifiers, int at, BlockPos pos, WorldgenRandom random, LongArrayList out) {
        if (at == modifiers.size()) {
            if (ore.scattered()) scattered(ore, pos, random, out);
            else vein(ore, pos, random, out);
            return;
        }

        PlacementModifier modifier = modifiers.get(at);
        if (modifier instanceof CountPlacement count) {
            int n = count.count(random, pos);
            for (int i = 0; i < n; i++) place(ore, modifiers, at + 1, pos, random, out);
        } else if (modifier instanceof RarityFilter rarity) {
            if (random.nextFloat() < 1.0F / rarity.chance()) place(ore, modifiers, at + 1, pos, random, out);
        } else if (modifier instanceof InSquarePlacement) {
            int x = random.nextInt(16) + pos.getX();
            int z = random.nextInt(16) + pos.getZ();
            place(ore, modifiers, at + 1, new BlockPos(x, pos.getY(), z), random, out);
        } else if (modifier instanceof HeightRangePlacement height) {
            int y = height.height().sample(random, heightContext);
            place(ore, modifiers, at + 1, new BlockPos(pos.getX(), y, pos.getZ()), random, out);
        } else if (modifier instanceof BiomeFilter) {
            if (biomeAllows(ore, pos)) place(ore, modifiers, at + 1, pos, random, out);
        } else {
            place(ore, modifiers, at + 1, pos, random, out);
        }
    }

    private boolean biomeAllows(OreTable.Ore ore, BlockPos pos) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return true;
        var key = level.getBiome(pos).unwrapKey();
        return key.isEmpty() || table.oresIn(key.get()).contains(ore);
    }

    // Vanilla AbstractOreFeature / OreFeature / ScatteredOreFeature.

    private void scattered(OreTable.Ore ore, BlockPos origin, WorldgenRandom random, LongArrayList out) {
        int size = ore.feature().size();
        float discard = ore.feature().discardChanceOnAirExposure();
        int tries = random.nextInt(size + 1);
        for (int i = 0; i < tries; i++) {
            int max = Math.min(i, 7);
            int x = origin.getX() + offset(random, max);
            int y = origin.getY() + offset(random, max);
            int z = origin.getZ() + offset(random, max);
            BlockPos pos = new BlockPos(x, y, z);
            if (accepts(pos, ore, random)) out.add(pos.asLong());
        }
    }

    private static int offset(WorldgenRandom random, int max) {
        return Math.round((random.nextFloat() - random.nextFloat()) * max);
    }

    private void vein(OreTable.Ore ore, BlockPos origin, WorldgenRandom random, LongArrayList out) {
        int size = ore.feature().size();
        float discard = ore.feature().discardChanceOnAirExposure();

        float dir = random.nextFloat() * (float) Math.PI;
        float spreadXY = size / 8.0F;
        int maxRadius = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double x0 = origin.getX() + Math.sin(dir) * spreadXY;
        double x1 = origin.getX() - Math.sin(dir) * spreadXY;
        double z0 = origin.getZ() + Math.cos(dir) * spreadXY;
        double z1 = origin.getZ() - Math.cos(dir) * spreadXY;
        double y0 = origin.getY() + random.nextInt(3) - 2;
        double y1 = origin.getY() + random.nextInt(3) - 2;
        int xStart = origin.getX() - Mth.ceil(spreadXY) - maxRadius;
        int yStart = origin.getY() - 2 - maxRadius;
        int zStart = origin.getZ() - Mth.ceil(spreadXY) - maxRadius;
        int sizeXZ = 2 * (Mth.ceil(spreadXY) + maxRadius);
        int sizeY = 2 * (2 + maxRadius);

        boolean reachable = false;
        for (int x = xStart; x <= xStart + sizeXZ && !reachable; x++) {
            for (int z = zStart; z <= zStart + sizeXZ; z++) {
                if (yStart <= surface(x, z)) {
                    reachable = true;
                    break;
                }
            }
        }
        if (!reachable) return;

        BitSet tested = new BitSet(sizeXZ * sizeY * sizeXZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double[] data = new double[size * 4];

        for (int i = 0; i < size; i++) {
            float step = (float) i / size;
            data[i * 4] = Mth.lerp(step, x0, x1);
            data[i * 4 + 1] = Mth.lerp(step, y0, y1);
            data[i * 4 + 2] = Mth.lerp(step, z0, z1);
            double ss = random.nextDouble() * size / 16.0;
            data[i * 4 + 3] = ((Mth.sin((float) Math.PI * step) + 1.0F) * ss + 1.0) / 2.0;
        }

        for (int i1 = 0; i1 < size - 1; i1++) {
            if (data[i1 * 4 + 3] <= 0.0) continue;
            for (int i2 = i1 + 1; i2 < size; i2++) {
                if (data[i2 * 4 + 3] <= 0.0) continue;
                double dx = data[i1 * 4] - data[i2 * 4];
                double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
                double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
                double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0) data[i2 * 4 + 3] = -1.0;
                    else data[i1 * 4 + 3] = -1.0;
                }
            }
        }

        for (int i = 0; i < size; i++) {
            double r = data[i * 4 + 3];
            if (r < 0.0) continue;
            double xx = data[i * 4], yy = data[i * 4 + 1], zz = data[i * 4 + 2];
            int xMin = Math.max(Mth.floor(xx - r), xStart);
            int yMin = Math.max(Mth.floor(yy - r), yStart);
            int zMin = Math.max(Mth.floor(zz - r), zStart);
            int xMax = Math.max(Mth.floor(xx + r), xMin);
            int yMax = Math.max(Mth.floor(yy + r), yMin);
            int zMax = Math.max(Mth.floor(zz + r), zMin);

            for (int x = xMin; x <= xMax; x++) {
                double xd = (x + 0.5 - xx) / r;
                if (xd * xd >= 1.0) continue;
                for (int y = yMin; y <= yMax; y++) {
                    double yd = (y + 0.5 - yy) / r;
                    if (xd * xd + yd * yd >= 1.0) continue;
                    for (int z = zMin; z <= zMax; z++) {
                        double zd = (z + 0.5 - zz) / r;
                        if (xd * xd + yd * yd + zd * zd >= 1.0 || level.isOutsideBuildHeight(y)) continue;
                        int bit = x - xStart + (y - yStart) * sizeXZ + (z - zStart) * sizeXZ * sizeY;
                        if (tested.get(bit)) continue;
                        tested.set(bit);
                        pos.set(x, y, z);
                        if (accepts(pos, ore, random)) out.add(pos.asLong());
                    }
                }
            }
        }
    }

    /** Vanilla's {@code canPlaceOre}: the block must be rock, then the air-exposure roll. */
    private boolean accepts(BlockPos pos, OreTable.Ore ore, WorldgenRandom random) {
        float discard = ore.feature().discardChanceOnAirExposure();
        if (airCheck && !isTarget(pos, ore, random)) return false;
        boolean skip = discard <= 0.0F || (discard < 1.0F && random.nextFloat() >= discard);
        return skip || !adjacentToAir(pos);
    }

    /**
     * Vanilla only places an ore where the block matches one of the feature's targets. The client sees the
     * world after generation, so a position that already holds this feature's ore, or another ore, counts
     * too; dirt, gravel and the like (written by earlier features) do not.
     */
    private boolean isTarget(BlockPos pos, OreTable.Ore ore, WorldgenRandom random) {
        if (!loaded(pos)) return true;
        BlockState state = level.getBlockState(pos);
        for (BlockReplacement replacement : ore.feature().targetStates()) {
            if (replacement.target().test(state, pos, random) || state.is(replacement.state().getBlock())) return true;
        }
        return OreKind.isOre(state.getBlock());
    }

    private boolean adjacentToAir(BlockPos pos) {
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            if (loaded(neighbour) && level.getBlockState(neighbour).isAir()) return true;
        }
        return false;
    }

    private boolean loaded(BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private int surface(int x, int z) {
        var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return chunk == null ? Integer.MAX_VALUE : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
    }
}
