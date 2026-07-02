package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import appeng.integration.modules.jeirei.EncodingHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.mojang.logging.LogUtils;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.screen.RecipeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;
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
        boolean isLayered = LayeredRecipeDetector.isLayeredRecipe(recipe);
        LOGGER.info("craft called, isLayered={}", isLayered);

        if (isLayered) {
            encodeLayeredSynchronous(menu, recipe);
        } else {
            try {
                EncodingHelper.encodeProcessingRecipe(menu, ofInputs(recipe), ofOutputs(recipe));
                LOGGER.info("Pattern encoded via EncodingHelper");
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
     * QUICK_MOVE 编码样板槽，把刚编码好的样板移到玩家背包
     */
    private void quickMoveEncoded(PatternEncodingTermMenu menu, int encodedPatternSlot) {
        if (encodedPatternSlot < 0) return;
        Minecraft mc = Minecraft.getInstance();
        MultiPlayerGameMode gameMode = mc.gameMode;
        LocalPlayer player = mc.player;
        if (gameMode == null || player == null) return;
        gameMode.handleInventoryMouseClick(
                menu.containerId, encodedPatternSlot, 0, ClickType.QUICK_MOVE, player);
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
        if (stack.getKey() instanceof Item item) {
            return new GenericStack(AEItemKey.of(item.getDefaultInstance()), amount);
        } else if (stack.getKey() instanceof Fluid fluid) {
            return new GenericStack(AEFluidKey.of(fluid), amount);
        }
        return new GenericStack(AEItemKey.of(ItemStack.EMPTY), 0);
    }
}
