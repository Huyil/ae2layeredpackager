package io.github.ae2lp.ae2layeredpackager;

import net.minecraftforge.common.ForgeConfigSpec;

public class LPConfig {

    public enum CellPriority {
        DEFAULT,        // 优先 dual_storage_cell，不存在则回退 AE2 原版
        PREFER_VANILLA, // 始终使用 AE2 原版 item_storage_cell + fluid_storage_cell
        PREFER_DUAL     // 强制使用 expandedae dual_storage_cell，无 expandedae 时报错
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // 存储元件优先级
    public static final ForgeConfigSpec.EnumValue<CellPriority> CELL_PRIORITY = BUILDER
            .comment("Storage cell priority for intermediate patterns",
                    "DEFAULT - prefer dual_storage_cell (expandedae), fallback to vanilla AE2 cells",
                    "PREFER_VANILLA - always use AE2 vanilla item_storage_cell and fluid_storage_cell",
                    "PREFER_DUAL - force use expandedae dual_storage_cell, error if not available")
            .defineEnum("cellPriority", CellPriority.DEFAULT);

    // 编码延迟 (ms)
    public static final ForgeConfigSpec.LongValue DELAY_PER_OPERATION = BUILDER
            .comment("Delay per click operation in milliseconds")
            .defineInRange("delayPerOperation", 60, 0, Long.MAX_VALUE);

    public static final ForgeConfigSpec.LongValue DELAY_ADDITIONAL_PER_PATTERN = BUILDER
            .comment("Additional delay after each pattern encoding in milliseconds")
            .defineInRange("delayAdditionalPerPattern", 20, 0, Long.MAX_VALUE);

    // 批量导出配方上限
    public static final ForgeConfigSpec.IntValue BULK_EXPORT_MAX_RECIPES = BUILDER
            .comment("Maximum number of recipes allowed for bulk export (Ctrl + click + button).",
                    "If a category has more recipes than this limit, bulk export is disabled.",
                    "Set to a high value to effectively disable the limit.")
            .defineInRange("bulkExportMaxRecipes", 32, 1, 10000);

    static final ForgeConfigSpec SPEC = BUILDER.build();
}
