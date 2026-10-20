package oresim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.ccbluex.liquidbounce.features.command.brigadier.ClientCommandSource;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** {@code .oresim mine [ores]}, {@code .oresim stop}, {@code .oresim status}: drive Baritone over the simulated ores; {@code .seed}: set the seed. */
final class OreSimCommand {
    private OreSimCommand() {}

    static void register(CommandDispatcher<ClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<ClientCommandSource> root = LiteralArgumentBuilder.literal("oresim");
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("mine")
                .executes(ctx -> mine(Set.of()))
                .then(RequiredArgumentBuilder.<ClientCommandSource, String>argument("ores", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            for (OreKind kind : OreKind.values()) builder.suggest(kind.name().toLowerCase(Locale.ROOT));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> mine(parse(StringArgumentType.getString(ctx, "ores"))))));
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("stop").executes(ctx -> {
            OreSimAddon.module.baritoneStop();
            OreSimAddon.module.say("OreSim: Baritone stopped");
            return 1;
        }));
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("status").executes(ctx -> {
            var module = OreSimAddon.module;
            module.say("OreSim: " + (module.getRunning() ? "on" : "off") + ", Baritone "
                    + (module.baritoneRunning() ? "mining, " + module.baritoneGoals().size() + " goals" : "idle"));
            return 1;
        }));
        dispatcher.register(root);

        // .seed [<seed> [version]] / .seed clear, as in Meteor Rejects; the version is ignored (current game's ore table)
        dispatcher.register(LiteralArgumentBuilder.<ClientCommandSource>literal("seed")
                .executes(ctx -> {
                    var module = OreSimAddon.module;
                    String text = module.seedText().trim();
                    module.say("OreSim seed: " + (text.isEmpty() ? "from the singleplayer world" : text)
                            + (module.seed() == null ? "" : " (in use: " + module.seed() + ")"));
                    return 1;
                })
                .then(LiteralArgumentBuilder.<ClientCommandSource>literal("clear").executes(ctx -> {
                    OreSimAddon.module.setSeedText("");
                    OreSimAddon.module.say("OreSim: seed cleared");
                    return 1;
                }))
                .then(RequiredArgumentBuilder.<ClientCommandSource, String>argument("seed", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String seed = StringArgumentType.getString(ctx, "seed").trim().split("\\s+")[0];
                            OreSimAddon.module.setSeedText(seed);
                            OreSimAddon.module.say("OreSim: seed set to " + OreSimModule.parseSeed(seed)
                                    + (OreSimAddon.module.getRunning() ? "" : ", enable OreSim to see ores"));
                            return 1;
                        })));
    }

    private static int mine(Set<OreKind> kinds) {
        OreSimAddon.module.say("OreSim: " + OreSimAddon.module.baritoneMine(kinds));
        return 1;
    }

    private static Set<OreKind> parse(String text) {
        Set<OreKind> kinds = EnumSet.noneOf(OreKind.class);
        for (String word : text.split("[\\s,]+")) {
            for (OreKind kind : OreKind.values()) {
                if (kind.name().equalsIgnoreCase(word) || kind.valueName.equalsIgnoreCase(word)) kinds.add(kind);
            }
        }
        return kinds;
    }
}
