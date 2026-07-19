package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import com.mojang.logging.LogUtils;
import io.github.ae2lp.ae2layeredpackager.LPConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 存储元件分配器。
 * 计算所需容量，优先选择 dual_storage_cell (expandedae)，回退到 AE2 原版。
 * 所有 expandedae 引用均通过 Item ID 字符串，无 Java 类硬依赖。
 */
public class CellAllocator {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ==================== 存储元件 ID 列表 ====================

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

    // 每个等级的实际字节数 (kilobytes * 1024)
    // 来源: AE2 BasicStorageCell / expandedae DualStorageCell
    private static final long[] CELL_TOTAL_BYTES = {
            1L * 1024,    // 1k
            4L * 1024,    // 4k
            16L * 1024,   // 16k
            64L * 1024,   // 64k
            256L * 1024,  // 256k
    };

    // 每个等级的 bytesPerType (kilobytes * 8)
    // 来源: AE2 BasicStorageCell / expandedae DualStorageCell
    private static final long[] CELL_BYTES_PER_TYPE = {
            8L,    // 1k
            32L,   // 4k
            128L,  // 16k
            512L,  // 64k
            2048L, // 256k
    };

    // AE2 原版物品存储元件总类型数
    private static final int VANILLA_ITEM_TOTAL_TYPES = 63;
    // AE2 原版流体存储元件总类型数（流体更少）
    private static final int VANILLA_FLUID_TOTAL_TYPES = 18;
    // expandedae dual cell 总类型数
    private static final int DUAL_TOTAL_TYPES = 63;

    // AE2 amount_per_byte
    private static final long ITEM_AMOUNT_PER_BYTE = 8;
    private static final long FLUID_AMOUNT_PER_BYTE = 8000; // 8 桶 × 1000L/桶

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

        /**
         * 计算物料内容的规范签名（顺序无关），用于去重比较。
         * 两个 StepContents 签名相同 → 装到同一类型 cell 后产生完全相同的 cell NBT。
         */
        public String contentSignature() {
            List<String> itemSigs = new ArrayList<>();
            for (ItemStack stack : items) {
                String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                String nbt = stack.hasTag() ? stack.getTag().toString() : "";
                itemSigs.add(id + "x" + stack.getCount() + nbt);
            }
            Collections.sort(itemSigs);

            List<String> fluidSigs = new ArrayList<>();
            for (ItemStack marker : fluids) {
                if (marker.hasTag() && marker.getTag().contains("fluid_id")) {
                    String id = marker.getTag().getString("fluid_id");
                    int amount = marker.getTag().getInt("amount");
                    String ftag = marker.getTag().contains("fluid_tag")
                            ? marker.getTag().getCompound("fluid_tag").toString() : "";
                    fluidSigs.add(id + "x" + amount + ftag);
                }
            }
            Collections.sort(fluidSigs);

            return "I[" + String.join(",", itemSigs) + "]F[" + String.join(",", fluidSigs) + "]";
        }
    }

    /**
     * 为一步骤分配存储元件
     *
     * @param contents 该步骤的物料 (物品和流体)
     * @return 分配结果，null 表示无法分配真实 cell；此时可调用 allocateEmptyMarker() 获取占位标记
     */
    @Nullable
    public static CellAssignment allocate(StepContents contents) {
        if (contents.isEmpty()) return null;

        boolean hasItems = !contents.items.isEmpty();
        boolean hasFluids = !contents.fluids.isEmpty();
        boolean hasBoth = hasItems && hasFluids;

        // 检查 expandedae 是否可用
        boolean hasExpandedAE = checkModLoaded("expandedae");

        LPConfig.CellPriority priority = LPConfig.CELL_PRIORITY.get();

        List<ItemStack> emptyCells = new ArrayList<>();
        List<ItemStack> filledCells = new ArrayList<>();

        if (hasBoth) {
            // 既有物品又有流体
            if (priority == LPConfig.CellPriority.PREFER_DUAL && !hasExpandedAE) {
                return null; // 强制 dual 但不可用
            }

            if ((priority == LPConfig.CellPriority.DEFAULT && hasExpandedAE)
                    || priority == LPConfig.CellPriority.PREFER_DUAL) {
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
        LPConfig.CellPriority priority = LPConfig.CELL_PRIORITY.get();

        if (priority == LPConfig.CellPriority.PREFER_VANILLA) {
            return allocateVanillaCell(stacks, isFluid);
        }

        if (hasExpandedAE && priority != LPConfig.CellPriority.PREFER_VANILLA) {
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
     * dual cell 同时容纳物品和流体，amount_per_byte 取 max(8, 8000) = 8000
     * (expandedae DualCellInventory 实现)
     */
    @Nullable
    private static ItemStack allocateDualCell(StepContents contents) {
        int typeCount = contents.getTotalTypes();
        long totalAmount = sumItemCounts(contents.items) + sumFluidAmounts(contents.fluids);

        for (int i = 0; i < DUAL_CELL_TIERS.length; i++) {
            if (fitsInTier(typeCount, totalAmount, i, DUAL_TOTAL_TYPES, FLUID_AMOUNT_PER_BYTE)) {
                Item cell = ForgeRegistries.ITEMS.getValue(new ResourceLocation(DUAL_CELL_TIERS[i]));
                if (cell != null) {
                    LOGGER.info("allocateDualCell: tier={} ({} bytes, {} bpt), {} types, total amount {}",
                            DUAL_CELL_TIERS[i], CELL_TOTAL_BYTES[i], CELL_BYTES_PER_TYPE[i],
                            typeCount, totalAmount);
                    return new ItemStack(cell);
                }
            }
        }
        LOGGER.warn("allocateDualCell: no tier fits {} types, amount={}", typeCount, totalAmount);
        return null;
    }

    /**
     * 分配 AE2 原版存储元件
     */
    @Nullable
    private static ItemStack allocateVanillaCell(List<ItemStack> stacks, boolean isFluid) {
        int typeCount = stacks.size();
        long totalAmount = isFluid ? sumFluidAmounts(stacks) : sumItemCounts(stacks);
        int totalTypes = isFluid ? VANILLA_FLUID_TOTAL_TYPES : VANILLA_ITEM_TOTAL_TYPES;
        long amountPerByte = isFluid ? FLUID_AMOUNT_PER_BYTE : ITEM_AMOUNT_PER_BYTE;
        String[] tiers = isFluid ? VANILLA_FLUID_CELL_TIERS : VANILLA_ITEM_CELL_TIERS;

        for (int i = 0; i < tiers.length; i++) {
            if (fitsInTier(typeCount, totalAmount, i, totalTypes, amountPerByte)) {
                Item cell = ForgeRegistries.ITEMS.getValue(new ResourceLocation(tiers[i]));
                if (cell != null) {
                    LOGGER.info("allocateVanillaCell({}): tier={} ({} bytes, {} bpt), {} types, total amount {}",
                            isFluid ? "fluid" : "item", tiers[i], CELL_TOTAL_BYTES[i],
                            CELL_BYTES_PER_TYPE[i], typeCount, totalAmount);
                    return new ItemStack(cell);
                }
            }
        }
        LOGGER.warn("allocateVanillaCell({}): no tier fits {} types, amount={}",
                isFluid ? "fluid" : "item", typeCount, totalAmount);
        return null;
    }

    /**
     * 检查第 i 层级是否能容纳给定的类型数和总量
     * 公式: requiredBytes = typeCount * bytesPerType + ceil(totalAmount / amountPerByte)
     * 必须满足: requiredBytes <= totalBytes AND typeCount <= totalTypes
     */
    private static boolean fitsInTier(int typeCount, long totalAmount, int tierIndex,
                                       int totalTypes, long amountPerByte) {
        if (typeCount > totalTypes) return false;
        long bytesForTypes = (long) typeCount * CELL_BYTES_PER_TYPE[tierIndex];
        long bytesForAmount = (totalAmount + amountPerByte - 1) / amountPerByte; // ceil
        long required = bytesForTypes + bytesForAmount;
        return required <= CELL_TOTAL_BYTES[tierIndex];
    }

    /**
     * 物品列表的总数量 (sum of count)
     */
    private static long sumItemCounts(List<ItemStack> items) {
        long total = 0;
        for (ItemStack stack : items) {
            total += stack.getCount();
        }
        return total;
    }

    /**
     * 流体列表的总量 (sum of amount in mB / L)
     * 流体用 ItemStack 包装，amount 存在 NBT 的 "amount" 字段
     */
    private static long sumFluidAmounts(List<ItemStack> fluidMarkers) {
        long total = 0;
        for (ItemStack marker : fluidMarkers) {
            if (marker.hasTag() && marker.getTag().contains("amount")) {
                total += marker.getTag().getInt("amount");
            }
        }
        return total;
    }

    /**
     * 创建装好物品的存储元件 NBT
     *
     * 通过 AE2 全局 StorageCells.getCellInventory() 获取该 cell 的 inventory。
     * 该方法会自动路由到正确的 cell 实现类（AE2 BasicCellInventory / expandedae DualCellInventory 等），
     * 让其 persist() 写出与 AE2 内部 HashMap 迭代顺序一致的 keys/amts 顺序。
     *
     * 如果 cell 类型未知（getCellInventory 返回 null），回退到手动 NBT 构造。
     * 手动构造时按经验把流体放前面（多数实际 cell 的 HashMap 迭代顺序如此）。
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
        filled.setTag(null); // 清空，从干净状态开始

        // 通过 AE2 全局 API 获取该 cell 的 inventory
        StorageCell inv = StorageCells.getCellInventory(filled, null);
        if (inv != null) {
            return fillViaStorageCell(inv, filled, items, fluids);
        }

        // 回退：手动构造 NBT
        LOGGER.warn("StorageCells.getCellInventory returned null for {}, falling back to manual NBT. " +
                "Item ID: {}", ForgeRegistries.ITEMS.getKey(emptyCell.getItem()));
        return fillViaManualNbt(filled, items, fluids);
    }

    /**
     * 通过 AE2 StorageCell 注入物品，让其 persist() 写出标准格式 NBT
     */
    private static ItemStack fillViaStorageCell(StorageCell inv, ItemStack filled,
                                                 @Nullable List<ItemStack> items,
                                                 @Nullable List<ItemStack> fluids) {
        int itemCount = items != null ? items.size() : 0;
        int fluidCount = fluids != null ? fluids.size() : 0;

        if (items != null) {
            for (ItemStack stack : items) {
                if (stack.isEmpty()) continue;
                AEItemKey key = AEItemKey.of(stack);
                if (key == null) continue;
                inv.insert(key, stack.getCount(), Actionable.MODULATE, IActionSource.empty());
            }
        }
        if (fluids != null) {
            for (ItemStack marker : fluids) {
                AEFluidKey fluidKey = fluidKeyFromMarker(marker);
                if (fluidKey == null) continue;
                long amount = fluidAmountFromMarker(marker);
                if (amount <= 0) continue;
                inv.insert(fluidKey, amount, Actionable.MODULATE, IActionSource.empty());
            }
        }
        inv.persist();

        LOGGER.info("createFilledCell ({}): {} items + {} fluids → tag={}",
                inv.getClass().getSimpleName(), itemCount, fluidCount, filled.getTag());
        return filled;
    }

    /**
     * 从流体标记 ItemStack 提取 AEFluidKey
     * 标记格式: {fluid_id: "namespace:path", amount: N, fluid_tag: {...}}
     */
    @Nullable
    private static AEFluidKey fluidKeyFromMarker(ItemStack marker) {
        if (!marker.hasTag() || !marker.getTag().contains("fluid_id")) return null;
        String fluidId = marker.getTag().getString("fluid_id");
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse(fluidId));
        if (fluid == null) return null;
        if (marker.getTag().contains("fluid_tag")) {
            return AEFluidKey.of(fluid, marker.getTag().getCompound("fluid_tag"));
        }
        return AEFluidKey.of(fluid);
    }

    private static long fluidAmountFromMarker(ItemStack marker) {
        if (!marker.hasTag() || !marker.getTag().contains("amount")) return 0;
        return marker.getTag().getInt("amount");
    }

    /**
     * 手动构造 cell NBT（fallback）
     * 流体放前面、物品放后面（经验上更接近 AE2/expandedae HashMap 的实际迭代顺序）
     */
    private static ItemStack fillViaManualNbt(ItemStack filled,
                                               @Nullable List<ItemStack> items,
                                               @Nullable List<ItemStack> fluids) {
        ListTag keys = new ListTag();
        List<Long> amounts = new ArrayList<>();
        long totalCount = 0;

        // 流体先加
        if (fluids != null) {
            for (ItemStack stack : fluids) {
                CompoundTag keyTag = new CompoundTag();
                keyTag.putString("#c", "ae2:f");
                String fluidId;
                long fluidAmount;
                if (stack.hasTag() && stack.getTag().contains("fluid_id")) {
                    fluidId = stack.getTag().getString("fluid_id");
                    fluidAmount = stack.getTag().getInt("amount");
                    if (stack.getTag().contains("fluid_tag")) {
                        keyTag.put("tag", stack.getTag().getCompound("fluid_tag").copy());
                    }
                } else {
                    fluidId = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                    fluidAmount = stack.getCount();
                }
                keyTag.putString("id", fluidId);
                keys.add(keyTag);
                amounts.add(fluidAmount);
                totalCount += fluidAmount;
            }
        }

        // 物品后加
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

        CompoundTag tag = filled.getOrCreateTag();
        tag.put("keys", keys);
        tag.putLongArray("amts", amounts.stream().mapToLong(Long::longValue).toArray());
        tag.putLong("ic", totalCount);

        LOGGER.warn("createFilledCell (manual fallback, fluids first): {} items + {} fluids → tag={}",
                items != null ? items.size() : 0,
                fluids != null ? fluids.size() : 0,
                filled.getTag());
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

    /**
     * 提取 cell 内的实际内容为 GenericStack 列表（用于 cell 输入展开功能）。
     * 读取 cell NBT 的 keys/amts 数组，按顺序还原为 AEItemKey/AEFluidKey。
     * 返回 null 表示不是 cell 或读取失败。
     */
    @Nullable
    public static List<GenericStack> extractCellContents(ItemStack cellStack) {
        if (!isCellFilled(cellStack)) return null;
        CompoundTag tag = cellStack.getTag();
        if (tag == null) return null;

        long[] amts = tag.getLongArray("amts");
        ListTag keys = tag.getList("keys", Tag.TAG_COMPOUND);
        if (amts.length != keys.size()) return null;

        List<GenericStack> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            CompoundTag keyTag = keys.getCompound(i);
            String channel = keyTag.getString("#c");
            String id = keyTag.getString("id");
            long amount = amts[i];
            if (amount <= 0) continue;

            if ("ae2:i".equals(channel)) {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
                if (item != null) {
                    ItemStack is = new ItemStack(item, 1);
                    if (keyTag.contains("tag", Tag.TAG_COMPOUND)) {
                        is.setTag(keyTag.getCompound("tag").copy());
                    }
                    result.add(new GenericStack(AEItemKey.of(is), amount));
                }
            } else if ("ae2:f".equals(channel)) {
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse(id));
                if (fluid != null) {
                    CompoundTag fluidTag = keyTag.contains("tag", Tag.TAG_COMPOUND)
                            ? keyTag.getCompound("tag").copy() : null;
                    AEFluidKey key = fluidTag != null
                            ? AEFluidKey.of(fluid, fluidTag) : AEFluidKey.of(fluid);
                    result.add(new GenericStack(key, amount));
                }
            }
        }
        return result;
    }
}
