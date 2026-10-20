package oresim;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.ccbluex.liquidbounce.event.events.GameTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Development check, enabled by the ORESIM_AUTOTEST environment variable: makes a world, flies into the
 * rock, enables the module, compares its prediction with the real ores, takes a screenshot and quits.
 */
final class OreSimDevTest {
    private static final boolean ENABLED = System.getenv("ORESIM_AUTOTEST") != null && !"baritone".equals(System.getenv("ORESIM_AUTOTEST"));
    private static final int RADIUS = 3;
    /** ORESIM_AUTOTEST=world:<save name> opens an existing save instead of creating one. */
    private static final String WORLD = System.getenv("ORESIM_AUTOTEST") != null && System.getenv("ORESIM_AUTOTEST").startsWith("world:")
            ? System.getenv("ORESIM_AUTOTEST").substring(6)
            : System.getenv("ORESIM_AUTOTEST") != null && System.getenv("ORESIM_AUTOTEST").startsWith("prepare:") ? System.getenv("ORESIM_AUTOTEST").substring(8) : null;
    /** prepare:<save name>: only lets the game upgrade an older save (it then drops to the title screen, and the world stays locked until exit). */
    private static final boolean PREPARE = System.getenv("ORESIM_AUTOTEST") != null && System.getenv("ORESIM_AUTOTEST").startsWith("prepare:");
    private static int stage, ticks, reopen;

    private OreSimDevTest() {}

    static void install(net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon addon) {
        if ("baritone".equals(System.getenv("ORESIM_AUTOTEST"))) {
            OreSimBaritoneTest.install(addon);
            return;
        }
        if (!ENABLED) return;
        addon.on(GameTickEvent.class, event -> tick());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        try {
            switch (stage) {
                case 0 -> {
                    if (mc.level == null && mc.gui.screen() != null && ++ticks > 40 && WORLD != null) {
                        // A world a vanilla server of another version generated (tools/genworld.sh): open it as it is.
                        OreSimAddon.LOG.info("ORESIM opening {} (exists={})", WORLD, java.nio.file.Files.isDirectory(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD)));
                        mc.createWorldOpenFlows().openWorld(WORLD, () -> OreSimAddon.LOG.error("ORESIM could not open {}", WORLD));
                        OreSimAddon.LOG.info("ORESIM openWorld returned, screen={}", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
                        stage = 1;
                        ticks = 0;
                    } else if (mc.level == null && mc.gui.screen() != null && ticks > 40 && WORLD == null) {
                        var settings = new LevelSettings("oresim-test-" + System.currentTimeMillis() / 1000, GameType.CREATIVE,
                                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(settings.levelName(), settings, WorldOptions.defaultWithRandomSeed(),
                                WorldPresets::createNormalWorldDimensions, mc.gui.screen());
                        stage = 1;
                        ticks = 0;
                    }
                }
                case 1 -> {
                    if (WORLD != null && mc.player == null && (++ticks % 100 == 0 || ticks < 6)) {
                        if (ticks == 300) net.minecraft.client.Screenshot.grab(mc.gameDirectory, "oresim-stuck.png", mc.gameRenderer.mainRenderTarget(), 1, c -> {});
                        OreSimAddon.LOG.info("ORESIM waiting, screen={}", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getName());
        // After the one-time upgrade of an older save the game drops back to the title screen with the save still locked.
                        if (PREPARE && mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen && ticks > 250) {
                            OreSimAddon.LOG.info("ORESIM PREPARED {}", WORLD);
                            stage = 4;
                            mc.stop();
                            return;
                        }
                        // An older save asks before it is upgraded ("load without backup" is the second button).
                        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup) {
                            var buttons = new java.util.ArrayList<net.minecraft.client.gui.components.Button>();
                            for (var child : backup.children()) if (child instanceof net.minecraft.client.gui.components.Button bt) buttons.add(bt);
                            for (var bt : buttons) OreSimAddon.LOG.info("ORESIM backup button: {}", bt.getMessage().getString());
                            if (buttons.size() >= 2) buttons.get(1).onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                        }
                    }
                    if (mc.player != null && mc.level != null && ++ticks > 60) {
                        if (mc.gui.screen() != null) mc.gui.setScreen(null);
                        mc.player.connection.sendCommand("gamemode spectator");
                        mc.player.connection.sendCommand("tp @s ~ -50 ~ 0 20");
                        stage = 2;
                        ticks = 0;
                    }
                }
                case 2 -> {
                    if (++ticks == 200) {
                        OreSimulator.debugByFeature = new java.util.HashMap<>();
                        OreSimAddon.module.setEnabled(true);
                    } else if (ticks == 400) {
                        OreSimAddon.LOG.info("ORESIM === overworld");
                        OreSimAddon.LOG.info("ORESIM baritone value present={} installed={}",
                                net.ccbluex.liquidbounce.config.ConfigSystem.INSTANCE.findValueByKey("liquidbounce.modules.oreSim.baritone") != null, BaritoneSupport.installed());
                        net.ccbluex.liquidbounce.features.command.CommandManager.INSTANCE.execute("value set liquidbounce.modules.oreSim.baritone true");
                        report(mc);
                        net.minecraft.client.Screenshot.grab(mc.gameDirectory, "oresim.png", mc.gameRenderer.mainRenderTarget(), 1, c -> {});
                    } else if (ticks == 420) {
                        mc.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0 20 0 0 20");
                        stage = 3;
                        ticks = 0;
                    }
                }
                case 3 -> {
                    if (++ticks == 600) {
                        OreSimAddon.LOG.info("ORESIM === nether, dimension={}", mc.level.dimension());
                        report(mc);
                        net.minecraft.client.Screenshot.grab(mc.gameDirectory, "oresim-nether.png", mc.gameRenderer.mainRenderTarget(), 1, c -> {});
                    } else if (ticks == 620) {
                        var commands = net.ccbluex.liquidbounce.features.command.CommandManager.INSTANCE;
                        commands.execute("seed 12345f");
                        OreSimAddon.LOG.info("ORESIM .seed 12345f -> {}", OreSimAddon.module.resolveSeed());
                        commands.execute("seed clear");
                        OreSimAddon.LOG.info("ORESIM .seed clear -> {}", OreSimAddon.module.resolveSeed());
                        commands.execute("seed");
                    } else if (ticks == 640) {
                        OreSimAddon.LOG.info("ORESIM DONE");
                        stage = 4;
                        mc.stop();
                    }
                }
                default -> {}
            }
        } catch (Throwable t) {
            OreSimAddon.LOG.error("ORESIM test aborted", t);
            stage = 4;
        }
    }

    /** Prints the feature order of the real generator next to the one OreSim derived. */
    private static void compareOrder(Minecraft mc) throws Exception {
        var generator = mc.getSingleplayerServer().getLevel(mc.level.dimension()).getChunkSource().getGenerator();
        var field = net.minecraft.world.level.chunk.ChunkGenerator.class.getDeclaredField("featuresPerStep");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var steps = ((java.util.function.Supplier<java.util.List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData>>) field.get(generator)).get();
        StringBuilder real = new StringBuilder(), mine = new StringBuilder();
        for (int step = 0; step < steps.size(); step++) {
            var features = steps.get(step).features();
            for (int i = 0; i < features.size(); i++) {
                if (features.get(i).feature().value() instanceof net.minecraft.world.level.levelgen.feature.AbstractOreFeature ore) {
                    var written = new java.util.ArrayList<net.minecraft.world.level.block.Block>();
                    for (var r : ore.targetStates()) written.add(r.state().getBlock());
                    real.append(step).append(':').append(i).append(':').append(OreKind.of(written)).append('/').append(ore.size()).append('#').append(features.get(i).placement().get(0)).append(' ');
                }
            }
        }
        var list = new java.util.ArrayList<>(OreSimAddon.module.table().all());
        list.sort(java.util.Comparator.comparingInt((OreTable.Ore o) -> o.step()).thenComparingInt(OreTable.Ore::index));
        for (var o : list) mine.append(o.step()).append(':').append(o.index()).append(':').append(o.kind()).append('/').append(o.feature().size()).append('#').append(o.placement().get(0)).append(' ');
        var biomeSeen = new java.util.TreeSet<String>();
        var pc = mc.player.chunkPosition();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            var ch = mc.level.getChunkSource().getChunkNow(pc.x() + dx, pc.z() + dz);
            if (ch == null) continue;
            for (var section : ch.getSections()) section.getBiomes().getAll(h -> h.unwrapKey().ifPresent(k -> biomeSeen.add(k.identifier().getPath())));
        }
        OreSimAddon.LOG.info("ORESIM biomes in area: {}", biomeSeen);
        for (var key : new java.util.ArrayList<>(biomeSeen)) {
            var rk = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, net.minecraft.resources.Identifier.withDefaultNamespace(key));
            StringBuilder b = new StringBuilder();
            for (var o : OreSimAddon.module.table().oresIn(rk)) b.append(o.step()).append(':').append(o.index()).append(' ');
            OreSimAddon.LOG.info("ORESIM ores in {}: {}", key, b);
        }
        OreSimAddon.LOG.info("ORESIM order real: {}", real);
        OreSimAddon.LOG.info("ORESIM order mine: {}", mine);
    }

    private static boolean outer(long l, int pcx, int pcz) {
        return Math.abs((BlockPos.getX(l) >> 4) - pcx) > RADIUS - 1 || Math.abs((BlockPos.getZ(l) >> 4) - pcz) > RADIUS - 1;
    }

    private static void report(Minecraft mc) throws Exception {
        compareOrder(mc);
        var level = mc.level;
        int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
        Long seed = OreSimAddon.module.resolveSeed();
        long allPositions = 0;
        for (var chunkOres : OreSimAddon.module.chunkData().values()) for (var list : chunkOres.values()) allPositions += list.size();
        OreSimAddon.LOG.info("ORESIM seed={} player chunk={},{} loaded chunks={} positions={}", seed, pcx, pcz, OreSimAddon.module.chunkData().size(), allPositions);

        Map<OreKind, Set<Long>> predicted = new EnumMap<>(OreKind.class);
        Map<OreKind, Set<Long>> real = new EnumMap<>(OreKind.class);
        for (OreKind kind : OreKind.values()) {
            predicted.put(kind, new HashSet<>());
            real.put(kind, new HashSet<>());
        }

        for (int cx = pcx - RADIUS; cx <= pcx + RADIUS; cx++) {
            for (int cz = pcz - RADIUS; cz <= pcz + RADIUS; cz++) {
                var data = OreSimAddon.module.chunkData().get(net.minecraft.world.level.ChunkPos.pack(cx, cz));
                if (data != null) {
                    for (var e : data.entrySet()) for (int i = 0; i < e.getValue().size(); i++) predicted.get(e.getKey()).add(e.getValue().getLong(i));
                }
                ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            pos.set((cx << 4) + x, y, (cz << 4) + z);
                            String path = BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).getPath();
                            if (!path.endsWith("_ore") && !path.equals("ancient_debris")) continue;
                            for (OreKind kind : OreKind.values()) {
                                String key = kind.name().toLowerCase(java.util.Locale.ROOT);
                                if (path.equals(key + "_ore") || path.equals("deepslate_" + key + "_ore")
                                        || (kind == OreKind.DEBRIS && path.equals("ancient_debris"))
                                        || (kind == OreKind.GOLD && path.equals("nether_gold_ore"))
                                        || (kind == OreKind.QUARTZ && path.equals("nether_quartz_ore"))) {
                                    real.get(kind).add(pos.asLong());
                                }
                            }
                        }
                    }
                }
            }
        }

        // Veins spill a few blocks past the chunk they start in, so positions in the outermost ring of
        // scanned chunks are only half known; compare the inner chunks only.
        for (OreKind kind : OreKind.values()) {
            predicted.get(kind).removeIf(l -> outer(l, pcx, pcz));
            real.get(kind).removeIf(l -> outer(l, pcx, pcz));
        }
        for (OreKind kind : new OreKind[]{OreKind.COAL, OreKind.IRON}) {
            StringBuilder bands = new StringBuilder();
            for (int y0 = level.getMinY(); y0 < level.getMaxY(); y0 += 32) {
                int pc = 0, rc = 0, mc2 = 0;
                for (long l : predicted.get(kind)) if (BlockPos.getY(l) >= y0 && BlockPos.getY(l) < y0 + 32) { pc++; if (real.get(kind).contains(l)) mc2++; }
                for (long l : real.get(kind)) if (BlockPos.getY(l) >= y0 && BlockPos.getY(l) < y0 + 32) rc++;
                if (pc + rc > 0) bands.append(y0).append(": p=").append(pc).append(" r=").append(rc).append(" m=").append(mc2).append(" | ");
            }
            OreSimAddon.LOG.info("ORESIM bands {} {}", kind, bands);
        }
        var byFeature = OreSimulator.debugByFeature;
        if (byFeature != null) {
            for (var entry : byFeature.entrySet()) {
                var ore = entry.getKey();
                int total = 0, hits = 0;
                for (int i = 0; i < entry.getValue().size(); i++) {
                    long l = entry.getValue().getLong(i);
                    int cx = BlockPos.getX(l) >> 4, cz = BlockPos.getZ(l) >> 4;
                    if (Math.abs(cx - pcx) > RADIUS - 1 || Math.abs(cz - pcz) > RADIUS - 1) continue;
                    total++;
                    if (real.get(ore.kind()).contains(l)) hits++;
                }
                if (ore.kind() == OreKind.GOLD || ore.kind() == OreKind.QUARTZ) {
                    long sy = 0; int n = entry.getValue().size(), miny = 999, maxy = -999;
                    for (int i = 0; i < n; i++) { int y = BlockPos.getY(entry.getValue().getLong(i)); sy += y; miny = Math.min(miny, y); maxy = Math.max(maxy, y); }
                    long ry = 0; int rn = 0, rminy = 999, rmaxy = -999;
                    for (long l : real.get(ore.kind())) { int y = BlockPos.getY(l); ry += y; rn++; rminy = Math.min(rminy, y); rmaxy = Math.max(rmaxy, y); }
                    OreSimAddon.LOG.info("ORESIM y {} idx={} pred n={} y={}..{} mean={} | real n={} y={}..{} mean={}", ore.kind(), ore.index(), n, miny, maxy, n == 0 ? 0 : sy / n, rn, rminy, rmaxy, rn == 0 ? 0 : ry / rn);
                }
                OreSimAddon.LOG.info("ORESIM feature {} step={} index={} size={} discard={} {} positions={} real-hit={}%", ore.kind(), ore.step(), ore.index(),
                        ore.feature().size(), ore.feature().discardChanceOnAirExposure(), ore.scattered() ? "scattered" : "vein", total, total == 0 ? 0 : 100 * hits / total);
            }
            byFeature.clear();
        }
        for (OreKind kind : new OreKind[]{OreKind.COAL, OreKind.COPPER}) {
            Map<String, Integer> blocks = new java.util.TreeMap<>();
            for (long l : predicted.get(kind)) {
                if (real.get(kind).contains(l)) continue;
                var chunk = level.getChunkSource().getChunkNow(BlockPos.getX(l) >> 4, BlockPos.getZ(l) >> 4);
                if (chunk == null) continue;
                blocks.merge(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(BlockPos.of(l)).getBlock()).getPath(), 1, Integer::sum);
            }
            var top = new java.util.ArrayList<>(blocks.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            OreSimAddon.LOG.info("ORESIM missed {} lands on {}", kind, top.subList(0, Math.min(8, top.size())));
        }
        for (OreKind kind : OreKind.values()) {
            Set<Long> p = predicted.get(kind), r = real.get(kind);
            if (p.isEmpty() && r.isEmpty()) continue;
            Set<Long> hit = new HashSet<>(p);
            hit.retainAll(r);
            OreSimAddon.LOG.info("ORESIM {} predicted={} real={} matched={} precision={}% recall={}%", kind, p.size(), r.size(), hit.size(),
                    p.isEmpty() ? 0 : 100 * hit.size() / p.size(), r.isEmpty() ? 0 : 100 * hit.size() / r.size());
        }
    }
}
