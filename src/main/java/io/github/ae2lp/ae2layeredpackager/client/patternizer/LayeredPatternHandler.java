package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import appeng.integration.modules.jeirei.EncodingHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.screen.RecipeScreen;
import io.github.ae2lp.ae2layeredpackager.LPConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;


/**
 * EMI 配方处理器 — 处理 GT 分层配方。点击 "+" 按钮触发多样板编码。
 */
public class LayeredPatternHandler<T extends PatternEncodingTermMenu> implements EmiRecipeHandler<T> {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public EmiPlayerInventory getInventory(AbstractContainerScreen<T> screen) {
        return new EmiPlayerInventory(
                screen.getMenu().slots.stream()
                        .map(Slot::getItem)
                        .map(EmiStack::of)
                        .toList());
    }

    @Override
    public boolean supportsRecipe(EmiRecipe recipe) {
        return recipe.getClass().getName().equals("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe");
    }

    @Override
    public boolean canCraft(EmiRecipe recipe, EmiCraftContext<T> context) {
        return true;
    }

    @Override
    public boolean craft(EmiRecipe recipe, EmiCraftContext<T> context) {
        T menu = context.getScreenHandler();

        // Ctrl + 点击 + 按钮 → 批量导出整个机器类别
        if (isBulkExportTriggered()) {
            boolean bulkDone = bulkExportCategory(menu, recipe);
            if (bulkDone) {
                if (Minecraft.getInstance().screen instanceof RecipeScreen e) {
                    e.onClose();
                }
                return true;
            }
            // 批量未执行（超上限/工作台/空类别）→ 降级到单配方编码
        }

        boolean isLayered = LayeredRecipeDetector.isLayeredRecipe(recipe);
        LOGGER.info("craft called, isLayered={}", isLayered);

        if (isLayered) {
            encodeLayeredSynchronous(menu, recipe);
        } else {
            try {
                // 优先从 GT recipe NBT 解析（保留 programmed_circuit 等 NBT）
                var gtContents = LayeredRecipeDetector.getGTRecipeContents(recipe);
                List<List<GenericStack>> inputs;
                List<GenericStack> outputs;
                if (gtContents != null
                        && (!gtContents.itemInputs.isEmpty() || !gtContents.fluidInputs.isEmpty()
                        || !gtContents.itemOutputs.isEmpty() || !gtContents.fluidOutputs.isEmpty())) {
                    inputs = gtInputsToGenericStacks(gtContents);
                    outputs = gtOutputsToGenericStacks(gtContents);
                    LOGGER.info("Pattern encoded via GT recipe NBT (NBT preserved)");
                } else {
                    inputs = ofInputs(recipe);
                    outputs = ofOutputs(recipe);
                    LOGGER.info("Pattern encoded via EMI stacks (fallback)");
                }
                EncodingHelper.encodeProcessingRecipe(menu, inputs, outputs);
            } catch (Exception e) {
                LOGGER.error("Failed to encode pattern", e);
            }
        }

        if (Minecraft.getInstance().screen instanceof RecipeScreen e) {
            e.onClose();
        }
        return true;
    }

    /**
     * 判断是否触发了批量导出（Ctrl + 在 RecipeScreen 上点击 +）
     */
    private boolean isBulkExportTriggered() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof RecipeScreen)) return false;
        long handle = mc.getWindow().getWindow();
        return InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    /**
     * 批量导出当前配方所属类别下的所有配方（不含 vanilla 工作台）。
     * - 若 RecipeScreen.resolve 是物品（U 键查询）：只导出含该物品作为输入的配方
     * - 若 RecipeScreen.resolve 为空（直接查看机器类别）：导出全部配方
     * 两种模式都受 LPConfig.BULK_EXPORT_MAX_RECIPES 限制。
     */
    private boolean bulkExportCategory(T menu, EmiRecipe triggerRecipe) {
        var category = triggerRecipe.getCategory();
        if (category == null) {
            LOGGER.warn("Bulk export: trigger recipe has no category");
            return false;
        }
        ResourceLocation categoryId = category.getId();

        // 跳过 vanilla 工作台
        if (isVanillaCrafting(categoryId)) {
            notifyPlayer("§c批量导出已取消：vanilla 工作台不支持 (" + categoryId + ")");
            LOGGER.info("Bulk export: skipping vanilla crafting category {}", categoryId);
            return false;
        }

        // 优先从 RecipeScreen.recipes 字段读取（当前 EMI 会话过滤后的列表，按 tab 分机器）
        // 这样导出的是"当前机器 tab 下的配方"，而不是整个类别的全部注册配方
        List<EmiRecipe> allRecipes = null;
        if (Minecraft.getInstance().screen instanceof RecipeScreen rs) {
            try {
                var recipesField = RecipeScreen.class.getDeclaredField("recipes");
                recipesField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var recipesMap = (java.util.Map<dev.emi.emi.api.recipe.EmiRecipeCategory,
                        List<dev.emi.emi.api.recipe.EmiRecipe>>) recipesField.get(rs);
                allRecipes = recipesMap.get(category);
                LOGGER.info("Bulk export: got {} recipes from RecipeScreen.recipes[{}]",
                        allRecipes != null ? allRecipes.size() : 0, categoryId);
            } catch (Exception e) {
                LOGGER.warn("Bulk export: failed to read RecipeScreen.recipes, falling back to manager", e);
            }
        }

        // 回退：用全局 registry
        if (allRecipes == null || allRecipes.isEmpty()) {
            try {
                allRecipes = EmiRecipes.manager.getRecipes(category);
                LOGGER.info("Bulk export: fallback to manager, got {} recipes", allRecipes.size());
            } catch (Exception e) {
                LOGGER.error("Bulk export: failed to get recipes for category {}", categoryId, e);
                notifyPlayer("§c批量导出失败：无法获取类别配方");
                return false;
            }
        }
        if (allRecipes == null || allRecipes.isEmpty()) {
            LOGGER.warn("Bulk export: no recipes in category {}", categoryId);
            notifyPlayer("§c批量导出已取消：类别下没有配方");
            return false;
        }

        // 区分物品查询（U 键）vs 机器查询
        // RecipeScreen.resolve 非空 = 物品查询（按 U 查询某个物品的用途）
        // RecipeScreen.resolve 为空 = 机器查询（直接查看某个机器的所有配方）
        List<EmiRecipe> toEncode;
        String queryMode;
        EmiIngredient resolve = RecipeScreen.resolve;
        if (resolve != null && !resolve.isEmpty()) {
            // 物品查询：只编码包含该物品作为输入的配方
            queryMode = "item-query (resolve=" + resolve + ")";
            var resolveKeys = new java.util.HashSet<AEItemKey>();
            for (var es : resolve.getEmiStacks()) {
                ItemStack is = es.getItemStack();
                if (!is.isEmpty()) {
                    resolveKeys.add(AEItemKey.of(is));
                }
            }
            toEncode = new ArrayList<>();
            for (var r : allRecipes) {
                if (recipeContainsAnyInput(r, resolveKeys)) {
                    toEncode.add(r);
                }
            }
            LOGGER.info("Bulk export: item-query mode, filtered {}/{} recipes containing {}",
                    toEncode.size(), allRecipes.size(), resolveKeys);
        } else {
            // 机器查询：导出全部
            queryMode = "machine-query (all)";
            toEncode = allRecipes;
        }

        if (toEncode.isEmpty()) {
            notifyPlayer("§c批量导出已取消：过滤后没有可编码的配方");
            LOGGER.warn("Bulk export: no recipes left after filtering");
            return false;
        }

        // 检查上限
        int max = LPConfig.BULK_EXPORT_MAX_RECIPES.get();
        if (toEncode.size() > max) {
            LOGGER.warn("Bulk export: {} has {} recipes, exceeds limit {}",
                    queryMode, toEncode.size(), max);
            notifyPlayer("§c批量导出已取消：配方数 " + toEncode.size() + " 超过上限 " + max);
            return false;
        }

        // 找到编码样板槽
        PatternEncodingTermMenu petMenu = (PatternEncodingTermMenu) menu;
        int encodedPatternSlot = -1;
        {
            var slots = petMenu.getSlots(SlotSemantics.ENCODED_PATTERN);
            if (!slots.isEmpty()) encodedPatternSlot = slots.get(0).index;
        }

        // 检查空白样板数量（跳过分层配方后需要的实际数量）
        int nonLayeredCount = 0;
        for (var r : toEncode) {
            if (!LayeredRecipeDetector.isLayeredRecipe(r)) nonLayeredCount++;
        }
        int blankCount = countBlankPatterns(petMenu);
        if (blankCount < nonLayeredCount) {
            LOGGER.warn("Bulk export: not enough blank patterns. Have {}, need {}",
                    blankCount, nonLayeredCount);
            notifyPlayer("§c批量导出已取消：空白样板不足（" + blankCount + "/" + nonLayeredCount + "）");
            return false;
        }

        LOGGER.info("Bulk export: encoding {} recipes for category {} (mode={}, limit={}, blank={})",
                toEncode.size(), categoryId, queryMode, max, blankCount);
        notifyPlayer("§a开始批量导出 " + toEncode.size() + " 个配方（" + queryMode.split(" ")[0] + "，空白样板 " + blankCount + "）...");

        int success = 0;
        int failed = 0;
        int skipped = 0;
        for (EmiRecipe r : toEncode) {
            try {
                // 跳过分层配方（批量中不处理，避免复杂的多样板逻辑）
                if (LayeredRecipeDetector.isLayeredRecipe(r)) {
                    LOGGER.info("Bulk export: skipping layered recipe {}", r.getId());
                    skipped++;
                    continue;
                }
                encodeSingleRecipe(menu, r);
                callReflectEncode((PatternEncodingTermMenu) menu);
                quickMoveEncoded(petMenu, encodedPatternSlot);
                success++;
            } catch (Exception e) {
                LOGGER.error("Bulk export: failed to encode recipe {}", r.getId(), e);
                failed++;
            }
        }

        String msg = String.format("§a批量导出完成：成功 %d 个", success);
        if (skipped > 0) msg += String.format("§e，跳过 %d 个（分层配方）", skipped);
        if (failed > 0) msg += String.format("§c，失败 %d 个", failed);
        notifyPlayer(msg);
        LOGGER.info("Bulk export done: {} ok, {} skipped, {} failed", success, skipped, failed);

        return true;
    }

    /**
     * 判断配方是否包含任一指定 key 作为输入
     */
    private boolean recipeContainsAnyInput(EmiRecipe recipe, java.util.Set<AEItemKey> keys) {
        if (keys.isEmpty()) return false;
        for (var input : recipe.getInputs()) {
            for (var es : input.getEmiStacks()) {
                ItemStack is = es.getItemStack();
                if (!is.isEmpty()) {
                    if (keys.contains(AEItemKey.of(is))) return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否为 vanilla 工作台类别
     */
    private boolean isVanillaCrafting(ResourceLocation categoryId) {
        return "minecraft".equals(categoryId.getNamespace())
                && categoryId.getPath().contains("crafting");
    }

    /**
     * 在玩家聊天栏显示消息
     */
    private void notifyPlayer(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(message), false);
        }
    }

    /**
     * 同步多样板编码: 输入打包模型
     * 与 PatternizeManager.encodeLayeredRecipe 等价，但不使用异步延迟链。
     * 每次 encode 后立即 QUICK_MOVE 取出编码样板，避免被下一次覆盖。
     * 每一步（含最后一步）的 inputs 都打包到 cell。
     */
    private void encodeLayeredSynchronous(PatternEncodingTermMenu menu, EmiRecipe recipe) {
        var steps = LayeredRecipeDetector.getStepContents(recipe);
        if (steps == null || steps.size() < 2) {
            LOGGER.warn("Cannot parse steps or only one step, encoding single combined pattern");
            try {
                EncodingHelper.encodeProcessingRecipe(menu, ofInputs(recipe), ofOutputs(recipe));
                callReflectEncode(menu);
            } catch (Exception e) {
                LOGGER.error("Failed to encode combined pattern", e);
            }
            return;
        }

        int lastStepIdx = steps.size() - 1;

        int encodedPatternSlot = -1;
        var slots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        if (!slots.isEmpty()) encodedPatternSlot = slots.get(0).index;

        LOGGER.info("Layered: {} steps detected, encoding {} intermediate + 1 assembly",
                steps.size(), steps.size());

        try {
            // Phase 1: 为每一步的 INPUTS 分配 cell，按签名去重
            // - stepToCell: 每个 step 索引 → 对应的 cell（重复 step 复用同一个 cell）
            // - uniqueCells / uniqueStepIndices: 仅包含每个 unique cell 的第一次出现
            java.util.Map<Integer, CellAllocator.CellAssignment> stepToCell = new java.util.HashMap<>();
            java.util.Map<String, Integer> seenSignatures = new java.util.HashMap<>();
            List<CellAllocator.CellAssignment> uniqueCells = new ArrayList<>();
            List<Integer> uniqueStepIndices = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                var cellContent = new CellAllocator.StepContents(step.itemInputs, step.fluidInputs);
                String sig = cellContent.contentSignature();

                if (seenSignatures.containsKey(sig)) {
                    int dupStepIdx = seenSignatures.get(sig);
                    stepToCell.put(i, stepToCell.get(dupStepIdx));
                    LOGGER.info("Step {}: cell content duplicates step {}, reusing cell (no new intermediate pattern)",
                            i + 1, dupStepIdx + 1);
                    continue;
                }

                var cells = CellAllocator.allocate(cellContent);
                if (cells == null) {
                    LOGGER.warn("Step {}: cannot allocate cell for {} items + {} fluids, skipping",
                            i + 1, step.itemInputs.size(), step.fluidInputs.size());
                    continue;
                }
                seenSignatures.put(sig, i);
                stepToCell.put(i, cells);
                uniqueCells.add(cells);
                uniqueStepIndices.add(i);
                LOGGER.info("Step {}: allocated cell for {} item input(s) + {} fluid input(s)",
                        i + 1, step.itemInputs.size(), step.fluidInputs.size());
            }

            if (uniqueCells.isEmpty()) {
                LOGGER.error("No cells allocated, falling back to single combined");
                EncodingHelper.encodeProcessingRecipe(menu, ofInputs(recipe), ofOutputs(recipe));
                callReflectEncode(menu);
                return;
            }

            // Phase 2: 只对 unique cells 编码中间样板
            for (int idx = 0; idx < uniqueCells.size(); idx++) {
                int stepIdx = uniqueStepIndices.get(idx);
                var step = steps.get(stepIdx);
                var cells = uniqueCells.get(idx);

                List<List<GenericStack>> inputs = new ArrayList<>();
                for (ItemStack ec : cells.emptyCells) {
                    inputs.add(List.of(new GenericStack(AEItemKey.of(ec), ec.getCount())));
                }
                for (ItemStack is : step.itemInputs) {
                    inputs.add(List.of(new GenericStack(AEItemKey.of(is), is.getCount())));
                }
                for (ItemStack fm : step.fluidInputs) {
                    GenericStack fs = toFluidGenericStack(fm);
                    if (fs != null) inputs.add(List.of(fs));
                }

                List<GenericStack> outputs = new ArrayList<>();
                for (ItemStack fc : cells.filledCells) {
                    outputs.add(new GenericStack(AEItemKey.of(fc), fc.getCount()));
                }

                EncodingHelper.encodeProcessingRecipe(menu, inputs, outputs);
                callReflectEncode(menu);
                quickMoveEncoded(menu, encodedPatternSlot);
                LOGGER.info("Step {}: intermediate encoded. {} input slot(s) → {} filled cell(s)",
                        stepIdx + 1, inputs.size(), outputs.size());
            }

            // Phase 3: 组装样板 (按原始 step 顺序消费 filled cell，重复的 cell 也占独立槽)
            List<List<GenericStack>> assemblyInputs = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                var cells = stepToCell.get(i);
                if (cells == null) continue;
                for (ItemStack fc : cells.filledCells) {
                    assemblyInputs.add(List.of(new GenericStack(AEItemKey.of(fc), fc.getCount())));
                }
            }

            List<GenericStack> assemblyOutputs = new ArrayList<>();
            var lastStep = steps.get(lastStepIdx);
            for (ItemStack is : lastStep.itemOutputs) {
                assemblyOutputs.add(new GenericStack(AEItemKey.of(is), is.getCount()));
            }
            for (ItemStack fm : lastStep.fluidOutputs) {
                GenericStack fs = toFluidGenericStack(fm);
                if (fs != null) assemblyOutputs.add(fs);
            }

            EncodingHelper.encodeProcessingRecipe(menu, assemblyInputs, assemblyOutputs);
            callReflectEncode(menu);
            quickMoveEncoded(menu, encodedPatternSlot);
            LOGGER.info("Assembly: encoded ({} input slot(s) → {} output(s))",
                    assemblyInputs.size(), assemblyOutputs.size());

        } catch (Exception e) {
            LOGGER.error("Layered encoding failed", e);
        }
    }

    /**
     * 把 GT recipe 解析的 inputs 转为 AE2 GenericStack 输入槽（每个独立槽，保留 NBT）
     */
    private static List<List<GenericStack>> gtInputsToGenericStacks(LayeredRecipeDetector.StepContents gt) {
        List<List<GenericStack>> inputs = new ArrayList<>();
        for (ItemStack is : gt.itemInputs) {
            inputs.add(List.of(new GenericStack(AEItemKey.of(is), is.getCount())));
        }
        for (ItemStack fm : gt.fluidInputs) {
            GenericStack fs = toFluidGenericStack(fm);
            if (fs != null) inputs.add(List.of(fs));
        }
        return inputs;
    }

    /**
     * 把 GT recipe 解析的 outputs 转为 AE2 GenericStack 输出槽
     */
    private static List<GenericStack> gtOutputsToGenericStacks(LayeredRecipeDetector.StepContents gt) {
        List<GenericStack> outputs = new ArrayList<>();
        for (ItemStack is : gt.itemOutputs) {
            outputs.add(new GenericStack(AEItemKey.of(is), is.getCount()));
        }
        for (ItemStack fm : gt.fluidOutputs) {
            GenericStack fs = toFluidGenericStack(fm);
            if (fs != null) outputs.add(fs);
        }
        return outputs;
    }

    /**
     * 为单个 EmiRecipe 编码（优先用 GT NBT 保留 NBT，回退到 EMI stacks）。
     * 安全检查：如果 GT 解析出的 inputs 为空，认为解析失败，回退到 EMI stacks
     * 避免清空编码终端的输入槽（processing mode 会清空未填充的槽）。
     */
    private void encodeSingleRecipe(T menu, EmiRecipe r) {
        var gtContents = LayeredRecipeDetector.getGTRecipeContents(r);
        List<List<GenericStack>> inputs;
        List<GenericStack> outputs;
        if (gtContents != null
                && (!gtContents.itemInputs.isEmpty() || !gtContents.fluidInputs.isEmpty())
                && (!gtContents.itemOutputs.isEmpty() || !gtContents.fluidOutputs.isEmpty())) {
            inputs = gtInputsToGenericStacks(gtContents);
            outputs = gtOutputsToGenericStacks(gtContents);
        } else {
            inputs = ofInputs(r);
            outputs = ofOutputs(r);
        }
        EncodingHelper.encodeProcessingRecipe(menu, inputs, outputs);
    }

    /**
     * QUICK_MOVE 编码样板槽，把刚编码好的样板移到玩家背包
     * 如果背包满了，QUICK_MOVE 会失败（样板仍在槽里），此时改用 PICKUP 拿出再丢到地上
     */
    private void quickMoveEncoded(PatternEncodingTermMenu menu, int encodedPatternSlot) {
        if (encodedPatternSlot < 0) return;
        Minecraft mc = Minecraft.getInstance();
        MultiPlayerGameMode gameMode = mc.gameMode;
        LocalPlayer player = mc.player;
        if (gameMode == null || player == null) return;

        // 先尝试 QUICK_MOVE
        gameMode.handleInventoryMouseClick(
                menu.containerId, encodedPatternSlot, 0, ClickType.QUICK_MOVE, player);

        // 检查 encoded pattern 槽是否还有物品（QUICK_MOVE 没成功 = 背包满）
        var slot = menu.getSlot(encodedPatternSlot);
        if (slot != null && slot.hasItem()) {
            // PICKUP 拿到光标
            gameMode.handleInventoryMouseClick(
                    menu.containerId, encodedPatternSlot, 0, ClickType.PICKUP, player);
            // 点击 -999 槽位把光标物品丢到地上
            gameMode.handleInventoryMouseClick(
                    menu.containerId, -999, 0, ClickType.PICKUP, player);
            LOGGER.info("Player inventory full, dropped encoded pattern on ground");
        }
    }

    /**
     * 统计样板终端里空白样板的总数
     */
    private int countBlankPatterns(PatternEncodingTermMenu menu) {
        int count = 0;
        for (var slot : menu.getSlots(SlotSemantics.BLANK_PATTERN)) {
            if (slot.hasItem()) {
                count += slot.getItem().getCount();
            }
        }
        return count;
    }

    /**
     * 通过反射调用 menu.encode()
     */
    private static void callReflectEncode(PatternEncodingTermMenu menu) {
        try {
            var method = menu.getClass().getMethod("encode");
            method.invoke(menu);
        } catch (NoSuchMethodException e1) {
            try {
                var method = menu.getClass().getMethod("encode", Long.class);
                method.invoke(menu, 0L);
            } catch (Exception e2) {
                LOGGER.error("reflect encode failed", e2);
            }
        } catch (Exception e) {
            LOGGER.error("reflect encode failed", e);
        }
    }

    @Nullable
    private static GenericStack toFluidGenericStack(ItemStack fluidMarker) {
        if (!fluidMarker.hasTag() || !fluidMarker.getTag().contains("fluid_id")) return null;
        String fluidId = fluidMarker.getTag().getString("fluid_id");
        int amount = fluidMarker.getTag().getInt("amount");
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse(fluidId));
        if (fluid == null) return null;
        return new GenericStack(AEFluidKey.of(fluid), Math.max(amount, 1));
    }

    public static List<List<GenericStack>> ofInputs(EmiRecipe emiRecipe) {
        return emiRecipe.getInputs().stream()
                .map(LayeredPatternHandler::intoGenericStack)
                .toList();
    }

    public static List<GenericStack> ofOutputs(EmiRecipe emiRecipe) {
        return emiRecipe.getOutputs().stream()
                .flatMap(slot -> intoGenericStack(slot).stream().limit(1))
                .toList();
    }

    private static List<GenericStack> intoGenericStack(EmiIngredient ingredient) {
        if (ingredient.isEmpty()) return new ArrayList<>();
        return ingredient.getEmiStacks().stream()
                .map(stack -> fromEmiStack(stack, ingredient.getAmount()))
                .toList();
    }

    private static GenericStack fromEmiStack(EmiStack stack, long amount) {
        // 用 getItemStack() 而不是 item.getDefaultInstance()，保留 NBT
        // 关键: 带配置的 programmed_circuit (Configuration 0~32) 必须保留 NBT 才能匹配
        ItemStack itemStack = stack.getItemStack();
        if (!itemStack.isEmpty()) {
            // 诊断: 当物品带 NBT 时记录日志（便于确认电路板等 NBT 是否被保留）
            if (itemStack.hasTag()) {
                LOGGER.info("fromEmiStack: preserved NBT for {} x{}: {}",
                        ForgeRegistries.ITEMS.getKey(itemStack.getItem()),
                        amount, itemStack.getTag());
            } else {
                LOGGER.debug("fromEmiStack: {} x{} (no NBT)",
                        ForgeRegistries.ITEMS.getKey(itemStack.getItem()), amount);
            }
            return new GenericStack(AEItemKey.of(itemStack), amount);
        }
        if (stack.getKey() instanceof Fluid fluid) {
            return new GenericStack(AEFluidKey.of(fluid), amount);
        }
        return new GenericStack(AEItemKey.of(ItemStack.EMPTY), 0);
    }
}
