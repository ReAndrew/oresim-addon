package oresim;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** The ores OreSim can show, with the colour of their outline. */
public enum OreKind {
    COAL("Coal", 0xFF2F2C36),
    IRON("Iron", 0xFFECAD77),
    GOLD("Gold", 0xFFF7E51E),
    REDSTONE("Redstone", 0xFFF50717),
    DIAMOND("Diamond", 0xFF21F4FF),
    LAPIS("Lapis", 0xFF081ABD),
    COPPER("Copper", 0xFFEF9700),
    EMERALD("Emerald", 0xFF1BD12D),
    QUARTZ("Quartz", 0xFFCDCDCD),
    DEBRIS("AncientDebris", 0xFFD11BF5);

    public final String valueName;
    public final int argb;

    OreKind(String valueName, int argb) {
        this.valueName = valueName;
        this.argb = argb;
    }

    /** Whether the block is any ore OreSim knows about. */
    public static boolean isOre(Block block) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    /** The ore a feature places, judged by the blocks it writes; null for dirt, gravel, tuff and the like. */
    public static OreKind of(List<Block> placed) {
        for (Block block : placed) {
            if (block == Blocks.ANCIENT_DEBRIS) return DEBRIS;
            if (block == Blocks.NETHER_QUARTZ_ORE) return QUARTZ;
            if (block == Blocks.NETHER_GOLD_ORE) return GOLD;
            String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (!path.endsWith("_ore")) continue;
            for (OreKind kind : values()) {
                String key = kind.name().toLowerCase(java.util.Locale.ROOT);
                if (path.equals(key + "_ore") || path.equals("deepslate_" + key + "_ore")) return kind;
            }
        }
        return null;
    }
}
