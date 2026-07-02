package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.AEBaseScreen;
import appeng.integration.modules.jeirei.EncodingHelper;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.registry.EmiRecipeFiller;
import io.github.ae2lp.ae2layeredpackager.LPConfig;
import io.github.ae2lp.ae2layeredpackager.client.LPKeybinds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 多样板编码管理器。
 * 参考 EMI-Patternizer 的客户端异步操作链。
 * 纯客户端实现，不发送任何自定义网络包。
 */
public class PatternizeManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean operating = false;

    // 反射缓存: menu.encode() 方法
    @Nullable
    private static Method encodeMethod = null;
    @Nullable
    private static Runnable callEncode = null;

    // 已编码物品记录 (去重用)
    // TODO: 可选持久化
    private static final java.util.HashSet<String> encodedItems = new java.util.HashSet<>();

    /**
     * 按键事件处理器
     */
    public static void onKeyPressed(ScreenEvent.KeyPressed event) {
        if (operating) return;

        var keyMapping = LPKeybinds.PATTERNIZE_KEY.get();
        if (!keyMapping.isActiveAndMatches(InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) {
            return;
        }

        // 仅在 Ctrl+Shift 组合键下触发
        if (!hasModifier(event)) return;

        if (!(event.getScreen() instanceof AEBaseScreen<?> screen)) return;

        AEBaseMenu menu = screen.getMenu();

        // 获取已编码样板槽 (用于 Quick Move 取出)
        var slotsBySemantic = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        Integer encodedPatternSlot = slotsBySemantic.isEmpty() ? null : slotsBySemantic.get(0).index;

        // 初始化 encode 反射
        if (!initEncodeMethod(menu)) {
            LOGGER.error("Cannot find encode method on menu: {}", menu.getClass());
            return;
        }

        operating = true;

        try {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            MultiPlayerGameMode gameMode = minecraft.gameMode;

            if (player == null || gameMode == null) return;

            // 读取配置
            long delayPerOperation = LPConfig.DELAY_PER_OPERATION.get();
            long delayAdditionalPerPattern = LPConfig.DELAY_ADDITIONAL_PER_PATTERN.get();

            // 获取当前 EMI 配方 (从 BoM 树或当前可见配方)
            EmiRecipe recipe = getCurrentEMIRecipe();
            if (recipe == null) {
                LOGGER.debug("No EMI recipe found");
                operating = false;
                return;
            }

            LOGGER.debug("Starting pattern encoding for: {}", recipe.getId());

            AtomicLong maxDelay = new AtomicLong(0);
            int finalEncodedPatternSlot = encodedPatternSlot != null ? encodedPatternSlot : -1;

            // 检测是否为分层配方
            if (LayeredRecipeDetector.isLayeredRecipe(recipe)) {
                // 分层配方 → 多样板编码
                encodeLayeredRecipe(minecraft, menu, player, gameMode, recipe,
                        finalEncodedPatternSlot, maxDelay, delayPerOperation, delayAdditionalPerPattern);
            } else {
                // 非分层 → 单次编码
                encodeSingle(minecraft, menu, player, gameMode, recipe,
                        finalEncodedPatternSlot, maxDelay, delayPerOperation);
            }

            // 操作结束重置状态
            CompletableFuture.delayedExecutor(
                    maxDelay.get() + delayPerOperation, TimeUnit.MILLISECONDS
            ).execute(() -> {
                operating = false;
                LOGGER.debug("Pattern encoding finished");
            });

        } catch (Exception e) {
            LOGGER.error("Error during pattern encoding", e);
            operating = false;
        }
    }

    /**
     * 调试按键: 输出当前屏幕信息
     */
    public static void onDebugKeyPressed(ScreenEvent.KeyPressed event) {
        var keyMapping = LPKeybinds.DEBUG_SCREEN_KEY.get();
        if (!keyMapping.isActiveAndMatches(InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) {
            return;
        }

        var screen = event.getScreen();
        LOGGER.info("===== DEBUG: Current Screen =====");
        LOGGER.info("Screen class: {}", screen.getClass().getName());
        LOGGER.info("Screen title: {}", screen.getTitle().getString());

        if (screen instanceof AEBaseScreen<?> aeScreen) {
            LOGGER.info("This is an AE2 screen. Menu class: {}", aeScreen.getMenu().getClass().getName());
        }

        if (screen instanceof dev.emi.emi.screen.RecipeScreen) {
            LOGGER.info("This is an EMI Recipe screen");
        }

        // 打印 screen 的父类链
        Class<?> cls = screen.getClass();
        StringBuilder hierarchy = new StringBuilder();
        while (cls != null) {
            if (hierarchy.length() > 0) hierarchy.append(" → ");
            hierarchy.append(cls.getSimpleName());
            cls = cls.getSuperclass();
        }
        LOGGER.info("Screen hierarchy: {}", hierarchy);

        LOGGER.info("===== DEBUG END =====");
    }

    /**
     * 多样板编码: 分层配方 — 输入打包模型
     *
     * GT 分层配方实际是"多入单出"模型，不存在真正的中间产物。
     * 把每一层（包括最后一步）的 inputs 都打包到一个存储元件里，最终组装样板只消费这些 cell。
     *
     * Phase 1 — 为每一步的 inputs 分配存储元件
     * Phase 2 — 编码 N 张中间样板 (输入: 空 cell + 这层 inputs → 输出: 装满 inputs 的 cell)
     * Phase 3 — 编码组装样板 (输入: N 个 filled cell → 输出: 最终产物)
     */
    private static void encodeLayeredRecipe(Minecraft minecraft, AEBaseMenu menu,
                                            LocalPlayer player, MultiPlayerGameMode gameMode,
                                            EmiRecipe recipe, int encodedPatternSlot,
                                            AtomicLong maxDelay, long delayPerOp, long delayAdditional) {
        var steps = LayeredRecipeDetector.getStepContents(recipe);
        if (steps == null || steps.size() < 2) {
            LOGGER.warn("Cannot parse steps or only one step, falling back to single encode");
            encodeSingle(minecraft, menu, player, gameMode, recipe,
                    encodedPatternSlot, maxDelay, delayPerOp);
            return;
        }

        int lastStepIdx = steps.size() - 1;

        LOGGER.info("=== Phase 1: Cell allocation for all {} step(s) ===", steps.size());

        // Phase 1: 为每一步的 INPUTS 分配 cell，按签名去重
        // - stepToCell: 每个 step 索引 → 对应的 cell（重复 step 复用同一个 cell）
        // - uniqueCells / uniqueStepIndices: 仅包含每个 unique cell 的第一次出现，用于 Phase 2 编码中间样板
        java.util.Map<Integer, CellAllocator.CellAssignment> stepToCell = new java.util.HashMap<>();
        java.util.Map<String, Integer> seenSignatures = new java.util.HashMap<>();
        List<CellAllocator.CellAssignment> uniqueCells = new ArrayList<>();
        List<Integer> uniqueStepIndices = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var stepContent = new CellAllocator.StepContents(step.itemInputs, step.fluidInputs);
            String sig = stepContent.contentSignature();

            if (seenSignatures.containsKey(sig)) {
                // 重复：复用之前 step 的 cell
                int dupStepIdx = seenSignatures.get(sig);
                stepToCell.put(i, stepToCell.get(dupStepIdx));
                LOGGER.info("Step {}: cell content duplicates step {}, reusing cell (no new intermediate pattern)",
                        i + 1, dupStepIdx + 1);
                continue;
            }

            var cells = CellAllocator.allocate(stepContent);
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
            LOGGER.error("No cells allocated, falling back to single encode");
            encodeSingle(minecraft, menu, player, gameMode, recipe,
                    encodedPatternSlot, maxDelay, delayPerOp);
            return;
        }

        LOGGER.info("=== Phase 2: Encoding {} intermediate pattern(s) ({} unique, {} total steps) ===",
                uniqueCells.size(), uniqueCells.size(), steps.size());

        // Phase 2: 只对 unique cells 编码中间样板
        for (int idx = 0; idx < uniqueCells.size(); idx++) {
            int stepIdx = uniqueStepIndices.get(idx);
            var step = steps.get(stepIdx);
            var cells = uniqueCells.get(idx);
            final int fi_stepIdx = stepIdx;
            long delay = maxDelay.get();

            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                minecraft.execute(() -> {
                    try {
                        if (!(menu instanceof PatternEncodingTermMenu encodingMenu)) {
                            LOGGER.warn("Menu is not PatternEncodingTermMenu");
                            return;
                        }

                        // 输入: 每个空 cell 独立槽 + 这层每个 input 独立槽
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

                        // 输出: 装满的 cell
                        List<GenericStack> outputs = new ArrayList<>();
                        for (ItemStack fc : cells.filledCells) {
                            outputs.add(new GenericStack(AEItemKey.of(fc), fc.getCount()));
                        }

                        EncodingHelper.encodeProcessingRecipe(encodingMenu, inputs, outputs);
                        LOGGER.info("Step {}: encoded intermediate. {} input slot(s) → {} filled cell(s)",
                                fi_stepIdx + 1, inputs.size(), outputs.size());

                        if (callEncode != null) callEncode.run();

                        if (encodedPatternSlot >= 0) {
                            gameMode.handleInventoryMouseClick(
                                    menu.containerId, encodedPatternSlot, 0,
                                    ClickType.QUICK_MOVE, player);
                        }

                        if (delayAdditional > 0) {
                            minecraft.getSoundManager().play(
                                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                        }
                    } catch (Exception e) {
                        LOGGER.error("Step {} encode failed", fi_stepIdx + 1, e);
                    }
                });
            });

            maxDelay.addAndGet(3 * delayPerOp + delayAdditional);
        }

        LOGGER.info("=== Phase 3: Encoding assembly pattern ===");

        // Phase 3: 组装样板 (按原始 step 顺序消费 filled cell，重复的 cell 也占独立槽)
        long assemblyDelay = maxDelay.get();
        CompletableFuture.delayedExecutor(assemblyDelay, TimeUnit.MILLISECONDS).execute(() -> {
            minecraft.execute(() -> {
                try {
                    if (!(menu instanceof PatternEncodingTermMenu encodingMenu)) return;

                    // 输入: 按 step 顺序遍历，每个 step 对应的 cell 占独立槽（重复 cell 也分别占槽）
                    List<List<GenericStack>> assemblyInputs = new ArrayList<>();
                    for (int i = 0; i < steps.size(); i++) {
                        var cells = stepToCell.get(i);
                        if (cells == null) continue;
                        for (ItemStack fc : cells.filledCells) {
                            assemblyInputs.add(List.of(new GenericStack(AEItemKey.of(fc), fc.getCount())));
                        }
                    }

                    // 输出: 最终产物 (取自最后一步的 outputs，不装 cell)
                    List<GenericStack> assemblyOutputs = new ArrayList<>();
                    var lastStep = steps.get(lastStepIdx);
                    for (ItemStack is : lastStep.itemOutputs) {
                        assemblyOutputs.add(new GenericStack(AEItemKey.of(is), is.getCount()));
                    }
                    for (ItemStack fm : lastStep.fluidOutputs) {
                        GenericStack fs = toFluidGenericStack(fm);
                        if (fs != null) assemblyOutputs.add(fs);
                    }

                    EncodingHelper.encodeProcessingRecipe(encodingMenu, assemblyInputs, assemblyOutputs);
                    LOGGER.info("Assembly: encoded ({} input slot(s) → {} output(s))",
                            assemblyInputs.size(), assemblyOutputs.size());
                    for (int ai = 0; ai < assemblyOutputs.size(); ai++) {
                        var gs = assemblyOutputs.get(ai);
                        LOGGER.info("  assembly output[{}]: key={}, amount={}",
                                ai, gs.what(), gs.amount());
                    }

                    if (callEncode != null) callEncode.run();

                    if (encodedPatternSlot >= 0) {
                        gameMode.handleInventoryMouseClick(
                                menu.containerId, encodedPatternSlot, 0,
                                ClickType.QUICK_MOVE, player);
                    }
                } catch (Exception e) {
                    LOGGER.error("Assembly encode failed", e);
                }
            });
        });

        maxDelay.addAndGet(3 * delayPerOp + delayAdditional);
    }

    /**
     * 将流体标记 ItemStack 转为 GenericStack
     */
    @Nullable
    private static GenericStack toFluidGenericStack(ItemStack fluidMarker) {
        if (!fluidMarker.hasTag() || !fluidMarker.getTag().contains("fluid_id")) return null;
        String fluidId = fluidMarker.getTag().getString("fluid_id");
        int amount = fluidMarker.getTag().getInt("amount");
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(
                net.minecraft.resources.ResourceLocation.tryParse(fluidId));
        if (fluid == null) return null;
        return new GenericStack(AEFluidKey.of(fluid), Math.max(amount, 1));
    }

    /**
     * 单次编码 (非分层配方)
     */
    private static void encodeSingle(Minecraft minecraft, AEBaseMenu menu,
                                     LocalPlayer player, MultiPlayerGameMode gameMode,
                                     EmiRecipe recipe, int encodedPatternSlot,
                                     AtomicLong maxDelay, long delayPerOp) {
        long delay = maxDelay.get();

        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            minecraft.execute(() -> {
                try {
                    // 使用 EMI 的标准配方填充机制
                    if (recipe != null) {
                        EmiRecipeFiller.performFill(recipe,
                                (AEBaseScreen<?>) minecraft.screen,
                                EmiCraftContext.Type.FILL_BUTTON,
                                EmiCraftContext.Destination.NONE, 1);
                    }

                    if (callEncode != null) {
                        callEncode.run();
                    }

                    if (encodedPatternSlot >= 0) {
                        gameMode.handleInventoryMouseClick(
                                menu.containerId, encodedPatternSlot, 0,
                                ClickType.QUICK_MOVE, player);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to encode single pattern", e);
                }
            });
        });

        maxDelay.addAndGet(3 * delayPerOp);
    }

    /**
     * 初始化 encode 方法的反射调用
     */
    private static boolean initEncodeMethod(AEBaseMenu menu) {
        if (encodeMethod != null && callEncode != null) return true;

        try {
            // 尝试 encode(Long) - AE2 Pattern Workstation 兼容
            encodeMethod = menu.getClass().getMethod("encode", Long.class);
            callEncode = () -> {
                try {
                    encodeMethod.invoke(menu, 0L);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    LOGGER.error("Failed to invoke encode(Long)", e);
                }
            };
            return true;
        } catch (NoSuchMethodException e1) {
            try {
                // 标准 encode()
                encodeMethod = menu.getClass().getMethod("encode");
                callEncode = () -> {
                    try {
                        encodeMethod.invoke(menu);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        LOGGER.error("Failed to invoke encode()", e);
                    }
                };
                return true;
            } catch (NoSuchMethodException e2) {
                LOGGER.error("Cannot find encode method", e2);
                return false;
            }
        }
    }

    /**
     * 获取当前 EMI 配方 (从 BoM 树的目标节点)
     * 用户需要在 EMI 中 Ctrl+B 打开配方树，选中目标物品
     */
    @Nullable
    private static EmiRecipe getCurrentEMIRecipe() {
        if (!BoM.craftingMode) return null;
        if (BoM.tree == null || BoM.tree.goal == null) return null;
        return BoM.tree.goal.recipe;
    }

    /**
     * 清空样板终端的输入/输出槽
     * 将编码产物槽和非空白样板槽的物品快速移动
     */
    private static void clearTerminal(AEBaseMenu menu) {
        try {
            // 遍历所有槽位，取出空白样板以外的物品
            for (Slot slot : menu.slots) {
                if (slot.hasItem()) {
                    ItemStack stack = slot.getItem();
                    // 跳过空白样板 (不取出)
                    if (isBlankPattern(stack)) continue;
                    // 快速移动其他物品
                    if (Minecraft.getInstance().gameMode != null) {
                        Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                                menu.containerId, slot.index, 0,
                                ClickType.QUICK_MOVE, Minecraft.getInstance().player);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to clear terminal", e);
        }
    }

    /**
     * 判断是否为空白样板
     */
    private static boolean isBlankPattern(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        return "ae2:blank_pattern".equals(id);
    }

    /**
     * 检查修饰键 (Ctrl+Shift)
     */
    private static boolean hasModifier(ScreenEvent.KeyPressed event) {
        return event.getKeyCode() == LPKeybinds.PATTERNIZE_KEY.get().getKey().getValue()
                && InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(),
                InputConstants.KEY_LCONTROL)
                && InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(),
                InputConstants.KEY_LSHIFT);
    }
}
