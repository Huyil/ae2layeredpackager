package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储元件分配器。
 * 计算所需容量，优先选择 dual_storage_cell (expandedae)，回退到 AE2 原版。
 * 所有 expandedae 引用均通过 Item ID 字符串，无 Java 类硬依赖。
 */
public class CellAllocator {

    // ==================== 存储元件 ID 列表 ====================

    // expandedae dual_storage_cell (物品+流体双支持)
    public static final String[] DUAL_CELL_TIERS = {
            "expandedae:dual_storage_cell_1k",
            "expandedae:dual_storage_cell_4k",
            "expandedae:dual_storage_cell_16k",
            "expandedae:dual_storage_cell_64k",
            "expandedae:dual_storage_cell_256k",
    };

    // AE2 原版物品存储元件
    public static final String[] VANILLA_ITEM_CELL_TIERS = {
            "ae2:item_storage_cell_1k",
            "ae2:item_storage_cell_4k",
            "ae2:item_storage_cell_16k",
            "ae2:item_storage_cell_64k",
            "ae2:item_storage_cell_256k",
    };

    // AE2 原版流体存储元件
    public static final String[] VANILLA_FLUID_CELL_TIERS = {
            "ae2:fluid_storage_cell_1k",
            "ae2:fluid_storage_cell_4k",
            "ae2:fluid_storage_cell_16k",
            "ae2:fluid_storage_cell_64k",
            "ae2:fluid_storage_cell_256k",
    };

    // AE2 存储元件容量 (字节)
    private static final long[] CELL_CAPACITIES = {
            1024 * 8,    // 1k  = 8192 bytes
            1024 * 32,   // 4k  = 32768
            1024 * 128,  // 16k = 131072
            1024 * 512,  // 64k = 524288
            1024 * 2048, // 256k = 2097152
    };

    // AE2 存储元件类型槽数
    private static final int[] CELL_TYPE_SLOTS = {
            3,   // 1k
            6,   // 4k
            12,  // 16k
            24,  // 64k
            48,  // 256k
    };

    // 每种物品类型占用的字节数
    private static final long BYTES_PER_TYPE = 8;

    /**
     * 分配结果：一个步骤需要的存储元件列表
     */
    public static class CellAssignment {
        public final List<ItemStack> emptyCells;   // 空存储元件 (输入用)
        public final List<ItemStack> filledCells;  // 装好物品的存储元件 (输出用)
        public final boolean hasItems;
        public final boolean hasFluids;

        public CellAssignment(List<ItemStack> emptyCells, List<ItemStack> filledCells,
                              boolean hasItems, boolean hasFluids) {
            this.emptyCells = emptyCells;
            this.filledCells = filledCells;
            this.hasItems = hasItems;
            this.hasFluids = hasFluids;
        }
    }

    /**
     * 步骤物料信息
     */
    public static class StepContents {
        public final List<ItemStack> items;
        public final List<ItemStack> fluids; // FluidStack 用 ItemStack 包装

        public StepContents(List<ItemStack> items, List<ItemStack> fluids) {
            this.items = items;
            this.fluids = fluids;
        }

        public boolean isEmpty() {
            return items.isEmpty() && fluids.isEmpty();
        }

        public int getTotalTypes() {
            return items.size() + fluids.size();
        }
    }

    /**
     * 为一步骤分配存储元件
     *
     * @param contents 该步骤的物料 (物品和流体)
     * @return 分配结果，或 null 如果无法分配
     */
    @Nullable
    public static CellAssignment allocate(StepContents contents) {
        if (contents.isEmpty()) return null;

        boolean hasItems = !contents.items.isEmpty();
        boolean hasFluids = !contents.fluids.isEmpty();
        boolean hasBoth = hasItems && hasFluids;

        // 检查 expandedae 是否可用
        boolean hasExpandedAE = checkModLoaded("expandedae");

        CellPriority priority = CellPriority.DEFAULT; // Will be read from config

        List<ItemStack> emptyCells = new ArrayList<>();
        List<ItemStack> filledCells = new ArrayList<>();

        if (hasBoth) {
            // 既有物品又有流体
            if (priority == CellPriority.PREFER_DUAL && !hasExpandedAE) {
                return null; // 强制 dual 但不可用
            }

            if ((priority == CellPriority.DEFAULT && hasExpandedAE)
                    || priority == CellPriority.PREFER_DUAL) {
                // 尝试 dual_storage_cell
                ItemStack dualCell = allocateDualCell(contents);
                if (dualCell != null) {
                    emptyCells.add(dualCell);
                    filledCells.add(createFilledCell(dualCell, contents));
                    return new CellAssignment(emptyCells, filledCells, true, true);
                }
            }

            // 回退：物品和流体分开
            ItemStack itemCell = allocateVanillaCell(contents.items, false);
            ItemStack fluidCell = allocateVanillaCell(contents.fluids, true);
            if (itemCell != null && fluidCell != null) {
                emptyCells.add(itemCell);
                emptyCells.add(fluidCell);
                filledCells.add(createFilledCell(itemCell, contents.items, null));
                filledCells.add(createFilledCell(fluidCell, null, contents.fluids));
                return new CellAssignment(emptyCells, filledCells, true, true);
            }
        } else if (hasItems) {
            // 只有物品
            ItemStack cell = allocatePreferredCell(contents.items, false);
            if (cell != null) {
                emptyCells.add(cell);
                filledCells.add(createFilledCell(cell, contents.items, null));
                return new CellAssignment(emptyCells, filledCells, true, false);
            }
        } else if (hasFluids) {
            // 只有流体
            ItemStack cell = allocatePreferredCell(contents.fluids, true);
            if (cell != null) {
                emptyCells.add(cell);
                filledCells.add(createFilledCell(cell, null, contents.fluids));
                return new CellAssignment(emptyCells, filledCells, false, true);
            }
        }

        return null;
    }

    /**
     * 根据配置偏好分配存储元件
     */
    @Nullable
    private static ItemStack allocatePreferredCell(List<ItemStack> stacks, boolean isFluid) {
        boolean hasExpandedAE = checkModLoaded("expandedae");
        CellPriority priority = CellPriority.DEFAULT; // TODO: read from config

        if (priority == CellPriority.PREFER_VANILLA) {
            return allocateVanillaCell(stacks, isFluid);
        }

        if (hasExpandedAE && priority != CellPriority.PREFER_VANILLA) {
            // 尝试 dual_storage_cell
            StepContents contents = isFluid
                    ? new StepContents(List.of(), stacks)
                    : new StepContents(stacks, List.of());
            ItemStack dual = allocateDualCell(contents);
            if (dual != null) return dual;
        }

        return allocateVanillaCell(stacks, isFluid);
    }

    /**
     * 分配 dual_storage_cell
     */
    @Nullable
    private static ItemStack allocateDualCell(StepContents contents) {
        int typeCount = contents.getTotalTypes();
        long estimatedBytes = estimateBytes(contents);

        for (int i = 0; i < DUAL_CELL_TIERS.length; i++) {
            if (typeCount <= CELL_TYPE_SLOTS[i] && estimatedBytes <= CELL_CAPACITIES[i]) {
                Item cell = ForgeRegistries.ITEMS.getValue(new ResourceLocation(DUAL_CELL_TIERS[i]));
                if (cell != null) {
                    return new ItemStack(cell);
                }
            }
        }
        return null;
    }

    /**
     * 分配 AE2 原版存储元件
     */
    @Nullable
    private static ItemStack allocateVanillaCell(List<ItemStack> stacks, boolean isFluid) {
        int typeCount = stacks.size();
        long estimatedBytes = estimateBytes(stacks);

        String[] tiers = isFluid ? VANILLA_FLUID_CELL_TIERS : VANILLA_ITEM_CELL_TIERS;

        for (int i = 0; i < tiers.length; i++) {
            if (typeCount <= CELL_TYPE_SLOTS[i] && estimatedBytes <= CELL_CAPACITIES[i]) {
                Item cell = ForgeRegistries.ITEMS.getValue(new ResourceLocation(tiers[i]));
                if (cell != null) {
                    return new ItemStack(cell);
                }
            }
        }
        return null;
    }

    /**
     * 估算物料所需存储字节数
     */
    private static long estimateBytes(StepContents contents) {
        long total = 0;
        for (ItemStack stack : contents.items) {
            total += BYTES_PER_TYPE + estimateItemBytes(stack);
        }
        for (ItemStack stack : contents.fluids) {
            total += BYTES_PER_TYPE + 8; // 流体固定 8 bytes
        }
        return total;
    }

    private static long estimateBytes(List<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) {
            total += BYTES_PER_TYPE + estimateItemBytes(stack);
        }
        return total;
    }

    private static long estimateItemBytes(ItemStack stack) {
        // 粗略估算：每个物品额外占 1-8 bytes
        return Math.min(8, (stack.getCount() + 63) / 64);
    }

    /**
     * 创建装好物品的存储元件 NBT
     *
     * @param emptyCell 空存储元件 ItemStack
     * @param items     要存入的物品列表
     * @param fluids    要存入的流体列表 (ItemStack 包裹的 FluidStack，可为 null)
     * @return 装好物品的存储元件的 ItemStack
     */
    public static ItemStack createFilledCell(ItemStack emptyCell,
                                              @Nullable List<ItemStack> items,
                                              @Nullable List<ItemStack> fluids) {
        ItemStack filled = emptyCell.copy();

        // 使用空的 AE 存储 NBT 结构标准格式
        // ic=totalCount, keys=[CompoundTag...], amts=[long...]
        // 这里使用通用 NBT 构造，不依赖 AE2 内部 API
        // 运行时 AE2 的 StorageCell 会通过其 ItemInventory 读取这些 NBT

        ListTag keys = new ListTag();
        List<Long> amounts = new ArrayList<>();
        long totalCount = 0;

        if (items != null) {
            for (ItemStack stack : items) {
                CompoundTag keyTag = new CompoundTag();
                keyTag.putString("#c", "ae2:i");
                keyTag.putString("id", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                if (stack.getTag() != null && !stack.getTag().isEmpty()) {
                    keyTag.put("tag", stack.getTag().copy());
                }
                keys.add(keyTag);
                amounts.add((long) stack.getCount());
                totalCount += stack.getCount();
            }
        }

        if (fluids != null) {
            for (ItemStack stack : fluids) {
                CompoundTag keyTag = new CompoundTag();
                keyTag.putString("#c", "ae2:f");
                keyTag.putString("id", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                keys.add(keyTag);
                amounts.add((long) stack.getCount());
                totalCount += stack.getCount();
            }
        }

        CompoundTag tag = filled.getOrCreateTag();
        tag.put("keys", keys);
        tag.putLongArray("amts", amounts.stream().mapToLong(Long::longValue).toArray());
        tag.putLong("ic", totalCount);

        return filled;
    }

    /**
     * 为单个物料列表创建装满的存储元件 (不含流体)
     */
    public static ItemStack createFilledCell(ItemStack emptyCell, StepContents contents) {
        return createFilledCell(emptyCell, contents.items, contents.fluids);
    }

    /**
     * 检测 mod 是否加载
     */
    private static boolean checkModLoaded(String modId) {
        try {
            return net.minecraftforge.fml.ModList.get().isLoaded(modId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否 dual_storage_cell (通过 Item ID)
     */
    public static boolean isDualCell(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id.getNamespace().equals("expandedae")
                && id.getPath().startsWith("dual_storage_cell_");
    }

    /**
     * 判断是否已装有物品的存储元件 (检查 NBT)
     */
    public static boolean isCellFilled(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("ic") && tag.getLong("ic") > 0;
    }

    private enum CellPriority {
        DEFAULT, PREFER_VANILLA, PREFER_DUAL
    }
}
