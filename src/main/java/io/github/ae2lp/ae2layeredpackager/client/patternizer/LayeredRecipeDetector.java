package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 分层配方检测器。
 * 通过纯反射检测 GTEmiRecipe 是否为分层配方，不引用任何 GT 类的编译时依赖。
 */
public class LayeredRecipeDetector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GT_MOD_ID = "gtceu";

    // GTRecipe 中的 NBT 键
    private static final String KEY_LAYERED_STEPS = "layered_steps";
    private static final String KEY_LAYERED_INFO = "layered_info";
    private static final String KEY_LAYERED_XEI = "layered_xei";

    // 缓存反射字段
    @Nullable
    private static Field gtEmiRecipeRecipeField = null;
    @Nullable
    private static Field gtRecipeDataField = null;
    private static boolean reflectionAttempted = false;

    /**
     * 检测 GT 模组是否加载
     */
    public static boolean isGTLoaded() {
        return ModList.get().isLoaded(GT_MOD_ID);
    }

    /**
     * 检测 EMI 配方是否为 GT 分层配方
     */
    public static boolean isLayeredRecipe(EmiRecipe recipe) {
        if (!isGTLoaded()) return false;
        CompoundTag recipeData = getGTRecipeData(recipe);
        return recipeData != null && recipeData.contains(KEY_LAYERED_STEPS);
    }

    /**
     * 获取分层配方的步骤数
     */
    public static int getStepCount(EmiRecipe recipe) {
        if (!isGTLoaded()) return 0;
        CompoundTag recipeData = getGTRecipeData(recipe);
        if (recipeData == null || !recipeData.contains(KEY_LAYERED_STEPS)) return 0;

        try {
            ListTag steps = recipeData.getList(KEY_LAYERED_STEPS, Tag.TAG_COMPOUND);
            return steps.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取分层配方在 EMI 中的输入 (所有步骤合并)
     */
    public static List<ItemStack> getLayeredInputs(EmiRecipe recipe) {
        // 如果支持分层检测，从 EMI 配方获取输入
        if (!isGTLoaded()) return List.of();

        // 直接从 EMI recipe 获取 inputs - 这已经返回合并后的 XEI 展示配方的输入
        return extractItemStacks(recipe.getInputs());
    }

    /**
     * 获取分层配方的输出
     */
    public static List<ItemStack> getLayeredOutputs(EmiRecipe recipe) {
        if (!isGTLoaded()) return List.of();
        return extractItemStacks(recipe.getOutputs());
    }

    /**
     * 尝试获取分层配方的每步骤独立输入 (需要更深入的反射)
     * TODO: 如果反射不够稳定，可以后续通过 GT API 扩展
     */
    @Nullable
    public static List<StepContents> getStepContents(EmiRecipe recipe) {
        if (!isGTLoaded()) return null;
        CompoundTag recipeData = getGTRecipeData(recipe);
        if (recipeData == null) return null;

        try {
            if (recipeData.contains(KEY_LAYERED_STEPS)) {
                ListTag stepsTag = recipeData.getList(KEY_LAYERED_STEPS, Tag.TAG_COMPOUND);
                List<StepContents> steps = new ArrayList<>();

                for (int i = 0; i < stepsTag.size(); i++) {
                    CompoundTag stepTag = stepsTag.getCompound(i);
                    List<ItemStack> inputs = extractInputItems(stepTag);
                    List<ItemStack> outputs = extractOutputItems(stepTag);
                    steps.add(new StepContents(inputs, outputs));
                }
                return steps;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse layered recipe steps", e);
        }
        return null;
    }

    /**
     * 通过反射获取 GTRecipe 的 data CompoundTag
     */
    @Nullable
    private static CompoundTag getGTRecipeData(EmiRecipe recipe) {
        if (!isGTLoaded()) return null;
        initReflection();

        try {
            // GTEmiRecipe 有一个 public final GTRecipe recipe 字段
            if (gtEmiRecipeRecipeField == null) return null;
            Object gtRecipe = gtEmiRecipeRecipeField.get(recipe);
            if (gtRecipe == null) return null;

            // GTRecipe 有一个 public CompoundTag data 字段
            if (gtRecipeDataField == null) return null;
            return (CompoundTag) gtRecipeDataField.get(gtRecipe);
        } catch (Exception e) {
            LOGGER.debug("Failed to access GT recipe data via reflection", e);
            return null;
        }
    }

    /**
     * 初始化反射字段
     */
    private static void initReflection() {
        if (reflectionAttempted) return;
        reflectionAttempted = true;

        try {
            // GTEmiRecipe 类名
            Class<?> gtEmiRecipeClass = Class.forName("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe");
            gtEmiRecipeRecipeField = gtEmiRecipeClass.getField("recipe");

            // GTRecipe 类名
            Class<?> gtRecipeClass = Class.forName("com.gregtechceu.gtceu.api.recipe.GTRecipe");
            gtRecipeDataField = gtRecipeClass.getField("data");

            LOGGER.debug("GT reflection fields initialized successfully");
        } catch (Exception e) {
            LOGGER.debug("GT not found or incompatible version, layered recipe support disabled");
        }
    }

    /**
     * 从 CompoundTag 中提取输入物品 (GTRecipe 格式)
     */
    private static List<ItemStack> extractInputItems(CompoundTag recipeTag) {
        List<ItemStack> items = new ArrayList<>();
        try {
            // GTRecipe 的 inputs 使用 ItemRecipeCapability 序列化
            // 简化处理：读取 "inputs" 复合标签
            if (recipeTag.contains("inputs")) {
                // TODO: 解析 GTRecipe 的 inputs 格式
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract input items", e);
        }
        return items;
    }

    /**
     * 从 CompoundTag 中提取输出物品
     */
    private static List<ItemStack> extractOutputItems(CompoundTag recipeTag) {
        List<ItemStack> items = new ArrayList<>();
        try {
            if (recipeTag.contains("outputs")) {
                // TODO: 解析 GTRecipe 的 outputs 格式
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract output items", e);
        }
        return items;
    }

    /**
     * 从 EMI 配料列表中提取 ItemStack
     */
    private static List<ItemStack> extractItemStacks(List<?> emiStacks) {
        List<ItemStack> result = new ArrayList<>();
        try {
            for (Object obj : emiStacks) {
                // EmiIngredient -> ItemStack 转换
                if (obj instanceof dev.emi.emi.api.stack.EmiIngredient ingredient) {
                    var stacks = ingredient.getEmiStacks();
                    for (var emiStack : stacks) {
                        // EmiIngredient.getEmiStacks() returns List<EmiStack>, direct cast is safe
                        var es = (dev.emi.emi.api.stack.EmiStack) emiStack;
                        ItemStack stack = es.getItemStack();
                        if (!stack.isEmpty()) {
                            stack.setCount((int) ingredient.getAmount());
                            result.add(stack);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract item stacks from EMI recipe", e);
        }
        return result;
    }

    /**
     * 步骤物料容器
     */
    public static class StepContents {
        public final List<ItemStack> inputs;
        public final List<ItemStack> outputs;

        public StepContents(List<ItemStack> inputs, List<ItemStack> outputs) {
            this.inputs = inputs;
            this.outputs = outputs;
        }
    }
}
