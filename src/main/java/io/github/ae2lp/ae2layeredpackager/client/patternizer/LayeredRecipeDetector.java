package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        if (!isGTLoaded()) {
            LOGGER.info("isLayeredRecipe: GT not loaded");
            return false;
        }
        if (recipe == null) {
            LOGGER.info("isLayeredRecipe: recipe is null");
            return false;
        }
        LOGGER.info("isLayeredRecipe: checking recipe class={}", recipe.getClass().getName());
        CompoundTag recipeData = getGTRecipeData(recipe);
        boolean result = recipeData != null && recipeData.contains(KEY_LAYERED_STEPS);
        LOGGER.info("isLayeredRecipe: result={}, data={}, has_layered_steps={}",
                result, recipeData != null,
                recipeData != null && recipeData.contains(KEY_LAYERED_STEPS));
        return result;
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
        if (!isGTLoaded()) return List.of();
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
     * 获取分层配方的每步骤独立输入输出，包含物品和流体
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
                    List<ItemStack> inputFluids = extractInputFluids(stepTag);
                    List<ItemStack> outputFluids = extractOutputFluids(stepTag);
                    steps.add(new StepContents(inputs, outputs, inputFluids, outputFluids));
                }
                LOGGER.info("getStepContents: parsed {} steps from layered recipe", steps.size());
                return steps;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse layered recipe steps", e);
        }
        return null;
    }

    /**
     * 获取任意 GT 配方（分层或非分层）的输入输出。
     *
     * 对分层配方：从 GTRecipe.data.layered_steps 解析（每步的 NBT 含 inputs/outputs）。
     * 对非分层配方：分层步骤不存在，但 inputs/outputs 是 GTRecipe 的 Java 字段，
     * 不在 data CompoundTag 里。需要通过 LayeredRecipeHelper.RECIPE_WITH_ID_CODEC
     * 反射调用，把整个 GTRecipe 序列化成 NBT，再用 extractInputItems 等解析。
     *
     * 这样可以保留 EMI stack 丢失的 NBT（如 programmed_circuit 的 Configuration）。
     */
    @Nullable
    public static StepContents getGTRecipeContents(EmiRecipe recipe) {
        if (!isGTLoaded()) return null;
        initReflection();
        if (gtEmiRecipeRecipeField == null) return null;

        try {
            Object gtRecipe = gtEmiRecipeRecipeField.get(recipe);
            if (gtRecipe == null) return null;

            // 通过 RECIPE_WITH_ID_CODEC 把 GTRecipe 序列化为 NBT
            CompoundTag recipeTag = serializeGTRecipe(gtRecipe);
            if (recipeTag == null) {
                LOGGER.warn("getGTRecipeContents: failed to serialize GTRecipe");
                return null;
            }

            // 诊断: 输出顶层 keys 和 inputs/outputs 子 keys，确认 NBT 结构
            LOGGER.info("getGTRecipeContents: top-level keys = {}", recipeTag.getAllKeys());
            if (recipeTag.contains("inputs", Tag.TAG_COMPOUND)) {
                CompoundTag inputsTag = recipeTag.getCompound("inputs");
                LOGGER.info("getGTRecipeContents: inputs.keys = {}", inputsTag.getAllKeys());
                for (String key : inputsTag.getAllKeys()) {
                    if (inputsTag.get(key) instanceof ListTag lt) {
                        LOGGER.info("getGTRecipeContents: inputs[{}] = {} entries, first = {}",
                                key, lt.size(), lt.isEmpty() ? "<empty>" : lt.get(0));
                    } else {
                        LOGGER.info("getGTRecipeContents: inputs[{}] = {}", key, inputsTag.get(key));
                    }
                }
            } else {
                LOGGER.warn("getGTRecipeContents: no 'inputs' CompoundTag in serialized recipe");
            }
            if (recipeTag.contains("outputs", Tag.TAG_COMPOUND)) {
                CompoundTag outputsTag = recipeTag.getCompound("outputs");
                LOGGER.info("getGTRecipeContents: outputs.keys = {}", outputsTag.getAllKeys());
            }

            List<ItemStack> itemInputs = extractInputItems(recipeTag);
            List<ItemStack> itemOutputs = extractOutputItems(recipeTag);
            List<ItemStack> fluidInputs = extractInputFluids(recipeTag);
            List<ItemStack> fluidOutputs = extractOutputFluids(recipeTag);
            LOGGER.info("getGTRecipeContents: {} item inputs, {} item outputs, {} fluid inputs, {} fluid outputs",
                    itemInputs.size(), itemOutputs.size(), fluidInputs.size(), fluidOutputs.size());
            for (ItemStack is : itemInputs) {
                if (is.hasTag()) {
                    LOGGER.info("  input {} x{} nbt={}",
                            ForgeRegistries.ITEMS.getKey(is.getItem()), is.getCount(), is.getTag());
                }
            }
            return new StepContents(itemInputs, itemOutputs, fluidInputs, fluidOutputs);
        } catch (Exception e) {
            LOGGER.error("getGTRecipeContents failed", e);
            return null;
        }
    }

    /**
     * 通过反射调用 LayeredRecipeHelper.RECIPE_WITH_ID_CODEC 把 GTRecipe 序列化为 CompoundTag。
     * 注意：NbtOps 和 DynamicOps 是 MC/DFU 自带的，直接 import 用，避免 Class.forName
     * 在 Forge ModuleClassLoader 下抛 ClassNotFoundException。
     */
    @Nullable
    private static CompoundTag serializeGTRecipe(Object gtRecipe) {
        try {
            Class<?> layeredRecipeHelper = Class.forName(
                    "com.gregtechceu.gtceu.api.recipe.LayeredRecipeHelper");
            Field codecField = layeredRecipeHelper.getDeclaredField("RECIPE_WITH_ID_CODEC");
            codecField.setAccessible(true);
            Object codec = codecField.get(null);

            // 直接用 NbtOps.INSTANCE（编译时引用，避免 ClassLoader 隔离问题）
            Method encodeStart = codec.getClass().getMethod("encodeStart",
                    DynamicOps.class, Object.class);
            Object dataResult = encodeStart.invoke(codec, NbtOps.INSTANCE, gtRecipe);

            Method resultMethod = dataResult.getClass().getMethod("result");
            Object opt = resultMethod.invoke(dataResult);
            if (!(opt instanceof Optional<?> optResult) || optResult.isEmpty()) return null;
            Object tag = optResult.get();
            if (!(tag instanceof CompoundTag recipeTag)) return null;
            return recipeTag;
        } catch (Exception e) {
            LOGGER.error("serializeGTRecipe failed", e);
            return null;
        }
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
            LOGGER.info("initReflection: attempting to find GTEmiRecipe class...");
            Class<?> gtEmiRecipeClass = Class.forName("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe");
            gtEmiRecipeRecipeField = gtEmiRecipeClass.getDeclaredField("recipe");
            gtEmiRecipeRecipeField.setAccessible(true);
            LOGGER.info("initReflection: GTEmiRecipe.recipe field found and accessible");

            Class<?> gtRecipeClass = Class.forName("com.gregtechceu.gtceu.api.recipe.GTRecipe");
            gtRecipeDataField = gtRecipeClass.getField("data");
            LOGGER.info("initReflection: GTRecipe.data field found");

            LOGGER.info("GT reflection fields initialized successfully");
        } catch (ClassNotFoundException e) {
            LOGGER.info("initReflection: GT classes not found - {}", e.getMessage());
        } catch (NoSuchFieldException e) {
            LOGGER.info("initReflection: Field not found - {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.info("initReflection: Unexpected error - {}", e.getMessage());
        }
    }

    /**
     * 从 CompoundTag 中提取输入物品 (GTRecipe NBT 格式)
     * 格式: inputs.item[{content: {item:"..."}, chance, maxChance, tierChanceBoost}]
     */
    private static List<ItemStack> extractInputItems(CompoundTag recipeTag) {
        List<ItemStack> items = new ArrayList<>();
        try {
            if (recipeTag.contains("inputs", Tag.TAG_COMPOUND)) {
                CompoundTag inputs = recipeTag.getCompound("inputs");
                extractItemsFromCapability(items, inputs, "item");
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
            if (recipeTag.contains("outputs", Tag.TAG_COMPOUND)) {
                CompoundTag outputs = recipeTag.getCompound("outputs");
                extractItemsFromCapability(items, outputs, "item");
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract output items", e);
        }
        return items;
    }

    /**
     * 从 CompoundTag 中提取输入流体 (以 ItemStack 包装)
     */
    private static List<ItemStack> extractInputFluids(CompoundTag recipeTag) {
        List<ItemStack> fluids = new ArrayList<>();
        try {
            if (recipeTag.contains("inputs", Tag.TAG_COMPOUND)) {
                CompoundTag inputs = recipeTag.getCompound("inputs");
                extractFluidsFromCapability(fluids, inputs, "fluid");
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract input fluids", e);
        }
        return fluids;
    }

    /**
     * 从 CompoundTag 中提取输出流体
     */
    private static List<ItemStack> extractOutputFluids(CompoundTag recipeTag) {
        List<ItemStack> fluids = new ArrayList<>();
        try {
            if (recipeTag.contains("outputs", Tag.TAG_COMPOUND)) {
                CompoundTag outputs = recipeTag.getCompound("outputs");
                extractFluidsFromCapability(fluids, outputs, "fluid");
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract output fluids", e);
        }
        return fluids;
    }

    /**
     * 从能力映射中提取物品 (解析 GT RecipeCapability NBT 格式)
     * 每个 Content 条目格式: {content: <Ingredient NBT>, chance, maxChance, tierChanceBoost}
     */
    private static void extractItemsFromCapability(List<ItemStack> result, CompoundTag capabilityMap, String key) {
        if (!capabilityMap.contains(key, Tag.TAG_LIST)) return;
        ListTag contentList = capabilityMap.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < contentList.size(); i++) {
            CompoundTag entry = contentList.getCompound(i);
            if (!entry.contains("content")) continue;
            Tag contentTag = entry.get("content");
            if (contentTag instanceof CompoundTag ingredientNbt) {
                int beforeSize = result.size();
                var parsed = parseIngredientFromNbt(ingredientNbt);
                result.addAll(parsed);
                // 诊断: 如果解析失败（empty），记录原因
                if (parsed.isEmpty()) {
                    LOGGER.warn("extractItemsFromCapability[{}][{}/{}] parsed 0 items from content: {}",
                            key, i + 1, contentList.size(), ingredientNbt);
                }
            }
        }
    }

    /**
     * 从能力映射中提取流体
     * FluidIngredient NBT 格式: {amount: 1000, value: [{fluid: "minecraft:water"}]}
     */
    private static void extractFluidsFromCapability(List<ItemStack> result, CompoundTag capabilityMap, String key) {
        if (!capabilityMap.contains(key, Tag.TAG_LIST)) return;
        ListTag contentList = capabilityMap.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < contentList.size(); i++) {
            CompoundTag entry = contentList.getCompound(i);
            if (!entry.contains("content")) continue;
            Tag contentTag = entry.get("content");
            if (contentTag instanceof CompoundTag fluidNbt) {
                parseFluidIngredientFromNbt(result, fluidNbt);
            }
        }
    }

    /**
     * 解析 Ingredient NBT 为 ItemStack 列表
     *
     * Ingredient NBT 格式 (通过 Ingredient.toJson() -> NbtOps):
     *   简单物品: {item: "minecraft:iron_ingot"}
     *   标签:     {tag: "forge:ingots/iron"}
     *   Sized:    {type: "gtceu:sized", count: 4, ingredient: {item: "..."}}
     */
    private static List<ItemStack> parseIngredientFromNbt(CompoundTag nbt) {
        List<ItemStack> result = new ArrayList<>();

        // SizedIngredient: {type: "gtceu:sized", count: N, ingredient: {...} OR [...]}
        // ingredient 可以是单个 CompoundTag，也可以是 ListTag (alternatives 列表)
        if (nbt.contains("type", Tag.TAG_STRING) && "gtceu:sized".equals(nbt.getString("type"))) {
            int count = nbt.getInt("count");
            for (ItemStack stack : parseIngredientOrAlternatives(nbt, count)) {
                result.add(stack);
            }
            return result;
        }

        // IntProviderIngredient: {type: "gtceu:int_provider", count_provider: {...}, ingredient: {...} OR [...]}
        if (nbt.contains("type", Tag.TAG_STRING) && "gtceu:int_provider".equals(nbt.getString("type"))) {
            int count = 1;
            if (nbt.contains("count_provider", Tag.TAG_COMPOUND)) {
                count = nbt.getCompound("count_provider").getInt("max");
            }
            for (ItemStack stack : parseIngredientOrAlternatives(nbt, count)) {
                result.add(stack);
            }
            return result;
        }

        // GT IntCircuitIngredient: {configuration:N, type:"gtceu:circuit"}
        // 表示 gtceu:programmed_circuit + {Configuration:N} NBT
        if (nbt.contains("type", Tag.TAG_STRING) && "gtceu:circuit".equals(nbt.getString("type"))) {
            byte configuration = nbt.getByte("configuration");
            Item circuit = ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.tryParse("gtceu:programmed_circuit"));
            if (circuit != null) {
                ItemStack stack = new ItemStack(circuit, 1);
                CompoundTag stackTag = new CompoundTag();
                stackTag.putByte("Configuration", configuration);
                stack.setTag(stackTag);
                result.add(stack);
            }
            return result;
        }

        // 简单物品: {item: "minecraft:iron_ingot"}
        if (nbt.contains("item", Tag.TAG_STRING)) {
            String itemId = nbt.getString("item");
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
            if (item != null) {
                ItemStack stack = new ItemStack(item, 1);
                // 如果有 tag，附加 NBT
                if (nbt.contains("tag", Tag.TAG_COMPOUND)) {
                    stack.setTag(nbt.getCompound("tag").copy());
                }
                result.add(stack);
            }
            return result;
        }

        // 标签: {tag: "forge:ingots/iron"} — 取第一个匹配物品
        if (nbt.contains("tag", Tag.TAG_STRING)) {
            String tagName = nbt.getString("tag");
            var tagKey = net.minecraft.tags.ItemTags.create(ResourceLocation.tryParse(tagName));
            var tagItems = ForgeRegistries.ITEMS.tags().getTag(tagKey).stream().toList();
            LOGGER.info("parseIngredientFromNbt: tag='{}' resolved to {} items", tagName, tagItems.size());
            if (!tagItems.isEmpty()) {
                result.add(new ItemStack(tagItems.get(0), 1));
            }
            return result;
        }

        // 未识别的 ingredient 类型 — 记录警告方便排查
        if (nbt.contains("type", Tag.TAG_STRING)) {
            LOGGER.warn("parseIngredientFromNbt: unrecognized ingredient type '{}', full nbt={}",
                    nbt.getString("type"), nbt);
        } else {
            LOGGER.warn("parseIngredientFromNbt: unrecognized ingredient format, full nbt={}", nbt);
        }

        return result;
    }

    /**
     * 解析 ingredient 字段，可能是单个 CompoundTag 或 ListTag (alternatives)
     * 对于 alternatives 列表，取第一个能成功解析的（避免把多种选择编码成多个槽）
     */
    private static List<ItemStack> parseIngredientOrAlternatives(CompoundTag nbt, int count) {
        List<ItemStack> result = new ArrayList<>();
        if (nbt.contains("ingredient", Tag.TAG_COMPOUND)) {
            for (ItemStack stack : parseIngredientFromNbt(nbt.getCompound("ingredient"))) {
                stack.setCount(Math.max(count, 1));
                result.add(stack);
            }
        } else if (nbt.contains("ingredient", Tag.TAG_LIST)) {
            ListTag altList = nbt.getList("ingredient", Tag.TAG_COMPOUND);
            for (int i = 0; i < altList.size(); i++) {
                var sub = parseIngredientFromNbt(altList.getCompound(i));
                if (!sub.isEmpty()) {
                    for (ItemStack stack : sub) {
                        stack.setCount(Math.max(count, 1));
                        result.add(stack);
                    }
                    break; // 只取第一个能解析的 alternative
                }
            }
            if (result.isEmpty()) {
                LOGGER.warn("parseIngredientOrAlternatives: all {} alternatives failed to parse, full nbt={}",
                        altList.size(), nbt);
            }
        }
        return result;
    }

    /**
     * 解析 FluidIngredient NBT
     * 格式: {amount: 1000, value: [{fluid: "minecraft:water"}, ...]}
     * 返回包装为 ItemStack 的流体 (使用空 ItemStack 携带流体信息)
     */
    private static void parseFluidIngredientFromNbt(List<ItemStack> result, CompoundTag nbt) {
        int amount = nbt.contains("amount", Tag.TAG_ANY_NUMERIC) ? nbt.getInt("amount") : 1000;

        if (nbt.contains("value")) {
            Tag valueTag = nbt.get("value");
            if (valueTag instanceof ListTag valueList) {
                for (int i = 0; i < valueList.size(); i++) {
                    Tag entry = valueList.get(i);
                    if (entry instanceof CompoundTag fluidEntry) {
                        if (fluidEntry.contains("fluid", Tag.TAG_STRING)) {
                            String fluidId = fluidEntry.getString("fluid");
                            Fluid fluid = ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse(fluidId));
                            if (fluid != null && fluid != Fluids.EMPTY) {
                                // 使用 ItemStack 包装流体信息，用 FluidCellItem 或自定义方式
                                // 这里使用空 ItemStack 但通过 NBT 传递流体信息
                                // 实际 CellAllocator 会通过 List<ItemStack> 来统计流体
                                ItemStack fluidMarker = new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET, 1);
                                CompoundTag fluidData = new CompoundTag();
                                fluidData.putString("fluid_id", fluidId);
                                fluidData.putInt("amount", amount);
                                fluidMarker.setTag(fluidData);
                                result.add(fluidMarker);
                            }
                        }
                    }
                }
            } else if (valueTag instanceof StringTag valueStr) {
                // 字符串简写: value: "minecraft:water"
                String fluidId = valueStr.getAsString();
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse(fluidId));
                if (fluid != null && fluid != Fluids.EMPTY) {
                    ItemStack fluidMarker = new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET, 1);
                    CompoundTag fluidData = new CompoundTag();
                    fluidData.putString("fluid_id", fluidId);
                    fluidData.putInt("amount", amount);
                    fluidMarker.setTag(fluidData);
                    result.add(fluidMarker);
                }
            }
        }
    }

    /**
     * 从 EMI 配料列表中提取 ItemStack
     */
    private static List<ItemStack> extractItemStacks(List<?> emiStacks) {
        List<ItemStack> result = new ArrayList<>();
        try {
            for (Object obj : emiStacks) {
                if (obj instanceof dev.emi.emi.api.stack.EmiIngredient ingredient) {
                    var stacks = ingredient.getEmiStacks();
                    for (var emiStack : stacks) {
                        var es = (dev.emi.emi.api.stack.EmiStack) emiStack;
                        ItemStack stack = es.getItemStack();
                        if (!stack.isEmpty()) {
                            stack.setCount((int) Math.max(1, ingredient.getAmount()));
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
     * 步骤物料容器 — 包含物品和流体的输入输出
     */
    public static class StepContents {
        public final List<ItemStack> itemInputs;
        public final List<ItemStack> itemOutputs;
        public final List<ItemStack> fluidInputs;
        public final List<ItemStack> fluidOutputs;

        public StepContents(List<ItemStack> itemInputs, List<ItemStack> itemOutputs,
                            List<ItemStack> fluidInputs, List<ItemStack> fluidOutputs) {
            this.itemInputs = itemInputs;
            this.itemOutputs = itemOutputs;
            this.fluidInputs = fluidInputs;
            this.fluidOutputs = fluidOutputs;
        }
    }
}
