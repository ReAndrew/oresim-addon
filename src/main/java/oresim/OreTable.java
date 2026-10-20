package oresim;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ore features of one dimension, read from the vanilla world preset. Everything here (features,
 * generation steps, indices) comes from the same registry, because vanilla seeds each feature from its
 * index within its step.
 */
public final class OreTable {
    /** One placed ore feature with the numbers that seed it. */
    public record Ore(PlacedFeature placed, AbstractOreFeature feature, OreKind kind, int step, int index,
                      boolean scattered, List<PlacementModifier> placement) {}

    private final Map<ResourceKey<Biome>, List<Ore>> byBiome = new HashMap<>();
    private final List<Ore> fallback = new ArrayList<>();
    private final List<Ore> all = new ArrayList<>();
    private ChunkGenerator generator;

    private OreTable() {}

    public static OreTable build(ResourceKey<LevelStem> dimension) {
        var provider = VanillaRegistries.createWorldLookup();
        LevelStem stem = WorldPresets.createNormalWorldDimensions(provider).get(dimension).orElseThrow();

        List<Holder<Biome>> biomes = List.copyOf(stem.generator().getBiomeSource().possibleBiomes());
        List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(
                biomes, biome -> biome.value().getGenerationSettings().features(), true);

        Map<PlacedFeature, Ore> ores = new HashMap<>();
        for (int step = 0; step < steps.size(); step++) {
            var data = steps.get(step);
            for (PlacedFeature placed : data.features()) {
                if (!(placed.feature().value() instanceof AbstractOreFeature ore)) continue;
                List<Block> written = new ArrayList<>();
                for (BlockReplacement replacement : ore.targetStates()) written.add(replacement.state().getBlock());
                OreKind kind = OreKind.of(written);
                if (kind == null) continue;
                ores.put(placed, new Ore(placed, ore, kind, step, data.indexMapping().applyAsInt(placed),
                        ore instanceof ScatteredOreFeature, placed.placement()));
            }
        }

        OreTable table = new OreTable();
        table.generator = stem.generator();
        table.all.addAll(ores.values());
        for (Holder<Biome> biome : biomes) {
            List<Ore> list = new ArrayList<>();
            for (HolderSet<PlacedFeature> set : biome.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> holder : set) {
                    Ore ore = ores.get(holder.value());
                    if (ore != null) list.add(ore);
                }
            }
            biome.unwrapKey().ifPresent(key -> table.byBiome.put(key, list));
            if (table.fallback.isEmpty()) table.fallback.addAll(list);
        }
        return table;
    }

    /** Ores generated in a biome; a biome the preset does not know gets the first biome's ores. */
    public List<Ore> oresIn(ResourceKey<Biome> biome) {
        return byBiome.getOrDefault(biome, fallback);
    }

    /** The preset's generator: it knows the generation height (the nether is 128 tall in a 256 tall level). */
    public ChunkGenerator generator() {
        return generator;
    }

    public List<Ore> all() {
        return all;
    }

    public boolean isEmpty() {
        return byBiome.isEmpty();
    }
}
