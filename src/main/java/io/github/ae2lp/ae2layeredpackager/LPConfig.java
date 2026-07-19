package io.github.ae2lp.ae2layeredpackager;

import net.minecraftforge.common.ForgeConfigSpec;

public class LPConfig {

    public enum CellPriority {
        DEFAULT,        // 优先 dual_storage_cell，不存在则回退 AE2 原版
        PREFER_VANILLA, // 始终使用 AE2 原版 item_storage_cell + fluid_storage_cell
        PREFER_DUAL     // 强制使用 expandedae dual_storage_cell，无 expandedae 时报错
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ====== 多样板与编码行为 ======

    // 分层配方多样板编码开关
    public static final ForgeConfigSpec.ConfigValue<Boolean> ENABLE_LAYERED_MULTIPATTERN = BUILDER
            .comment("Enable multi-pattern encoding for layered GT recipes.",
                    "When enabled, layered recipes are encoded into N intermediate patterns + 1 assembly pattern.",
                    "When disabled, layered recipes fall back to single combined pattern encoding.",
                    "Note: multi-pattern requires external cell un-packing mechanism to actually automate.",
                    "See README for known limitations.")
            .define("enableLayeredMultiPattern", false);

    // 自动电路板（保留 programmed_circuit 的 Configuration NBT）
    public static final ForgeConfigSpec.ConfigValue<Boolean> AUTO_ADD_CIRCUIT = BUILDER
            .comment("Auto-preserve programmed_circuit Configuration NBT (0~32) when encoding GT recipes.",
                    "When enabled, GT recipe NBT is parsed directly to retain NBT that EMI would lose.",
                    "When disabled, EMI stacks are used (faster but loses circuit Configuration NBT).")
            .define("autoAddCircuit", true);

    // 输入 cell 展开功能
    public static final ForgeConfigSpec.ConfigValue<Boolean> EXPAND_CELL_INPUTS = BUILDER
            .comment("When a recipe input is a filled storage cell, auto-generate an additional pattern",
                    "that uses the cell's actual contents as direct inputs (no cell needed).",
                    "Supports recipes with multiple cell inputs.",
                    "Useful when you don't have empty cells but want to use cell-based recipes.")
            .define("expandCellInputs", true);

    // ====== 存储 ======

    // 存储元件优先级
    public static final ForgeConfigSpec.EnumValue<CellPriority> CELL_PRIORITY = BUILDER
            .comment("Storage cell priority for intermediate patterns",
                    "DEFAULT - prefer dual_storage_cell (expandedae), fallback to vanilla AE2 cells",
                    "PREFER_VANILLA - always use AE2 vanilla item_storage_cell and fluid_storage_cell",
                    "PREFER_DUAL - force use expandedae dual_storage_cell, error if not available")
            .defineEnum("cellPriority", CellPriority.DEFAULT);

    // ====== 批量导出 ======

    // 批量导出配方上限
    public static final ForgeConfigSpec.IntValue BULK_EXPORT_MAX_RECIPES = BUILDER
            .comment("Maximum number of recipes allowed for bulk export (Ctrl + click + button).",
                    "If a category has more recipes than this limit, bulk export is disabled.",
                    "Set to a high value to effectively disable the limit.")
            .defineInRange("bulkExportMaxRecipes", 32, 1, 10000);

    // 空样板来源（终端槽 → 背包 → ME 网络）
    public static final ForgeConfigSpec.ConfigValue<Boolean> SOURCE_BLANK_PATTERNS = BUILDER
            .comment("When bulk export runs out of blank patterns in the encoding terminal slot,",
                    "automatically pull more from player inventory and ME network.",
                    "Disable to only use what's in the encoding terminal slot.")
            .define("sourceBlankPatterns", true);

    // ====== 编码延迟 ======

    public static final ForgeConfigSpec.LongValue DELAY_PER_OPERATION = BUILDER
            .comment("Delay per click operation in milliseconds")
            .defineInRange("delayPerOperation", 60, 0, Long.MAX_VALUE);

    public static final ForgeConfigSpec.LongValue DELAY_ADDITIONAL_PER_PATTERN = BUILDER
            .comment("Additional delay after each pattern encoding in milliseconds")
            .defineInRange("delayAdditionalPerPattern", 20, 0, Long.MAX_VALUE);

    static final ForgeConfigSpec SPEC = BUILDER.build();
}
