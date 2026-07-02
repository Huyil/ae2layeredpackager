package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import appeng.client.gui.AEBaseScreen;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
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
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
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
     * 多样板编码: 分层配方
     */
    private static void encodeLayeredRecipe(Minecraft minecraft, AEBaseMenu menu,
                                            LocalPlayer player, MultiPlayerGameMode gameMode,
                                            EmiRecipe recipe, int encodedPatternSlot,
                                            AtomicLong maxDelay, long delayPerOp, long delayAdditional) {
        // 获取每步骤的输入输出
        var stepContents = LayeredRecipeDetector.getStepContents(recipe);
        if (stepContents == null || stepContents.isEmpty()) {
            // 无法解析步骤，回退到单次编码
            encodeSingle(minecraft, menu, player, gameMode, recipe,
                    encodedPatternSlot, maxDelay, delayPerOp);
            return;
        }

        LOGGER.debug("Encoding layered recipe with {} steps", stepContents.size());

        // 阶段1: 为每个步骤创建中间样板 (空cell + 原料 → 装满的cell)
        List<CellAllocator.CellAssignment> cellAssignments = new ArrayList<>();
        List<EmiRecipe> intermediateRecipes = new ArrayList<>();

        for (int i = 0; i < stepContents.size(); i++) {
            var step = stepContents.get(i);

            // 分配存储元件
            var contents = new CellAllocator.StepContents(step.inputs, step.outputs);
            var cells = CellAllocator.allocate(contents);
            if (cells == null) {
                LOGGER.warn("Cannot allocate storage cell for step {}", i + 1);
                continue;
            }
            cellAssignments.add(cells);

            // 为 EMI RecipeFiller 构造中间配方
            // 注意：这里需要构造一个 EmiRecipe 对象供 EmiRecipeFiller 填充
            // 由于 EmiRecipeFiller 需要真实的 EmiRecipe，我们使用一个包装
            // 实际编码时直接填充终端槽位
        }

        // 阶段2: 逐个编码中间样板
        for (int idx = 0; idx < cellAssignments.size(); idx++) {
            final int stepIndex = idx;
            var cells = cellAssignments.get(idx);
            long delay = maxDelay.get();

            // 编码: 空cell + 原料 → 装满的cell
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                minecraft.execute(() -> {
                    try {
                        // Step 1: 清空终端
                        clearTerminal(menu);

                        // Step 2: 放入输入 (空存储元件 + 原料)
                        // 通过模拟 EMI 配方填充来实现
                        // TODO: 使用 EmiRecipeFiller.performFill() 或直接设置槽位

                        // Step 3: 放入输出 (装满的存储元件)
                        // TODO: 设置输出槽

                        // Step 4: 编码
                        if (callEncode != null) {
                            callEncode.run();
                        }

                        // Step 5: 取出样板
                        if (encodedPatternSlot >= 0) {
                            gameMode.handleInventoryMouseClick(
                                    menu.containerId, encodedPatternSlot, 0,
                                    ClickType.QUICK_MOVE, player);
                        }

                        if (LPConfig.DELAY_ADDITIONAL_PER_PATTERN.get() > 0) {
                            minecraft.getSoundManager().play(
                                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                        }

                    } catch (Exception e) {
                        LOGGER.error("Failed to encode intermediate pattern {}", stepIndex + 1, e);
                    }
                });
            });

            maxDelay.addAndGet(3 * delayPerOp + delayAdditional);
        }

        // 阶段3: 编码最终组装样板
        long finalDelay = maxDelay.get();
        CompletableFuture.delayedExecutor(finalDelay, TimeUnit.MILLISECONDS).execute(() -> {
            minecraft.execute(() -> {
                try {
                    clearTerminal(menu);

                    // 输入: 各步骤的装满的cell
                    for (var cells : cellAssignments) {
                        for (ItemStack filled : cells.filledCells) {
                            // TODO: 设置输入槽
                        }
                    }

                    // 输出: 最终产物
                    var outputs = LayeredRecipeDetector.getLayeredOutputs(recipe);
                    // TODO: 设置输出槽

                    if (callEncode != null) {
                        callEncode.run();
                    }

                    if (encodedPatternSlot >= 0) {
                        gameMode.handleInventoryMouseClick(
                                menu.containerId, encodedPatternSlot, 0,
                                ClickType.QUICK_MOVE, player);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to encode final assembly pattern", e);
                }
            });
        });

        maxDelay.addAndGet(3 * delayPerOp + delayAdditional);
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
     * 获取当前 EMI 配方
     */
    @Nullable
    private static EmiRecipe getCurrentEMIRecipe() {
        // TODO: 从 EMI 的 BoM 树或当前显示的配方中获取
        // 参考 EMI-Patternizer: 从 BoM.tree 遍历
        // 暂时返回 null，后续实现
        return null;
    }

    /**
     * 清空样板终端的输入/输出槽
     */
    private static void clearTerminal(AEBaseMenu menu) {
        // TODO: 清空样板终端中的输入输出槽
        // 可以通过遍历槽位并取出物品实现
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
