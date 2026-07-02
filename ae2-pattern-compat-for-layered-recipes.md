# AE2 样板兼容 — 分层配方 (Layered Recipe) 接口总览

> 用于 `large_rotor_machine` 等使用 `setLayered()` 的多方块机器的 AE2 样板编码支持。

---

## 1. 整体流程

```
KubeJS 定义配方
    ↓
GTRecipeBuilder → applyLayeredRecipeModifications()  [LayeredRecipeHelper]
    ↓
配方存入 recipeType (含 layered_steps + layered_xei 数据)
    ↓
GTRecipeType.buildRepresentativeRecipes() → 将 XEI 展示配方注册到主类别
    ↓
GTRecipeEMICategory.registerDisplays() → 包装为 GTEmiRecipe 注册到 EMI
    ↓
Ae2PatternTerminalHandler.supportsRecipe() → 识别 GTEmiRecipe
    ↓
用户点击 craft() → EncodingHelper.encodeProcessingRecipe() 编码为 AE2 样板
```

---

## 2. 关键文件清单

### 2.1 分层配方核心系统

| 文件 | 作用 |
|------|------|
| `api/recipe/GTRecipeType.java` | `setLayered()` 方法启用分层模式，`isLayered()` 判断，`buildRepresentativeRecipes()` 注册展示配方 |
| `api/recipe/LayeredRecipeHelper.java` | 分层配方的序列化/反序列化，步骤计算，XEI 展示配方合成 |
| `api/recipe/ui/LayeredRecipeUIHelper.java` | 分层配方在 JEI/REI/EMI 中的 UI 渲染（每步骤独立的输入槽） |
| `api/machine/multiblock/LayeredWorkableElectricMultiblockMachine.java` | 分层多方块机器的基类，使用 `LayeredRecipeLogic` |
| `common/machine/trait/LayeredRecipeLogic.java` | 分层配方的运行时逻辑：逐步执行、超时、中断、红石输出 |
| `common/cover/detector/LayeredStepDetectorCover.java` | 分层进度检测盖板，按步骤输出红石信号 |

### 2.2 KubeJS 集成

| 文件 | 作用 |
|------|------|
| `integration/kjs/builders/GTRecipeTypeBuilder.java` | KubeJS 中 `.setLayered()` 的绑定 |
| `integration/kjs/recipe/GTRecipeSchema.java` | KubeJS 中 `.layeredRecipe()` 配方构建方法 |

### 2.3 AE2 样板编码系统

| 文件 | 作用 |
|------|------|
| `integration/emi/recipe/Ae2PatternTerminalHandler.java` | **核心** — AE2 样板编码终端的 EMI 适配器 |
| `integration/emi/recipe/GTEmiRecipe.java` | GT 配方的 EMI 包装，`getInputs()`/`getOutputs()` 提供物料清单 |
| `integration/emi/recipe/GTRecipeEMICategory.java` | 注册配方类别到 EMI，`registerWorkStations()` 关联机器和类别 |
| `integration/emi/GTEMIPlugin.java` | EMI 插件入口，注册 handler 到 `PatternEncodingTermMenu` |
| `common/data/machines/GTAEMachines.java` | AE2 相关机器定义（ME 总线、样板缓冲器等） |

### 2.4 其他

| 文件 | 作用 |
|------|------|
| `integration/emi/recipe/GTEmiRecipeHandler.java` | GT 机器 GUI 内的 EMI 配方处理器（非 AE2 相关） |
| `integration/jade/provider/LayeredRecipeProvider.java` | Jade/WTHIT 显示分层进度 |
| `data/recipe/builder/LayeredRecipeInfo.java` | 分层信息的数据类（各步骤输入分配、超时等） |

---

## 3. 关键接口与方法

### 3.1 `GTRecipeType.setLayered()` (第 132 行)

```java
public GTRecipeType setLayered() {
    enableSyntheticCategory();       // 创建合成类别用于存储完整配方
    recipeUI = LayeredRecipeUIHelper.createRecipeUI(this);  // 设置分层 UI
    layered = true;
    return this;
}
```

- 调用 `enableSyntheticCategory()` 创建一个隐藏的合成类别（`xxx_synthetic`）
- 分层配方存储在 **合成类别** 中
- `buildRepresentativeRecipes()` 将 XEI 展示配方发布到 **主类别** 供 EMI 显示

### 3.2 `LayeredRecipeHelper` 核心方法

| 方法 | 作用 |
|------|------|
| `applyLayeredRecipeModifications(builder)` | **配方构建时的入口** — 将 `LayeredRecipeInfo` 展开为多步骤，生成 XEI 展示配方 |
| `calculateRecipeSteps(recipe)` | 根据 `LayeredRecipeInfo` 计算每个步骤的独立 GTRecipe |
| `getLayeredSteps(recipe)` | 从配方 NBT 中反序列化步骤列表 |
| `getXeiLayeredRecipe(recipe)` | 获取合并后的 XEI 展示配方（含所有步骤的总输入/总输出） |
| `buildRepresentativeRecipes(recipeType)` | 将 XEI 配方注册到主类别，供 JEI/REI/EMI 显示 |

### 3.3 `LayeredRecipeHelper.applyLayeredRecipeModifications()` (第 100 行)

这个方法在 KubeJS 配方构建完成后被调用，做了三件事：

1. **计算分层步骤** — 将 `LayeredRecipeInfo` 展开为 `List<GTRecipe>`
2. **生成 XEI 展示配方** — 将所有步骤的 inputs/outputs/tickInputs/tickOutputs 合并为一个总配方
3. **重置构建器** — 将 builder 的内容设为第一步骤的输入 + 最后步骤的输出，存入 `KEY_LAYERED_STEPS` 和 `KEY_LAYERED_XEI`

```java
// XEI 展示配方的构建（第 115-130 行）
var xei = GTRecipeBuilder.of(builder.id.withPrefix("/"), builder.recipeType)
    .addData(KEY_LAYERED_STEPS, serializedSteps)
    .EUt(layers.get(0).getInputEUt().getTotalEU())
    .inputItems(layers.stream().flatMap(l -> getLayerData(ItemRecipeCapability.CAP, l.inputs))...)
    .outputItems(layers.stream().flatMap(l -> getLayerData(ItemRecipeCapability.CAP, l.outputs))...)
    // ... 流体和 tick 同理 ...
    .buildRawRecipe();
```

### 3.4 `Ae2PatternTerminalHandler` (第 28-90 行)

```java
public class Ae2PatternTerminalHandler<T extends PatternEncodingTermMenu>
        implements EmiRecipeHandler<T> {

    @Override
    public boolean supportsRecipe(EmiRecipe recipe) {
        return recipe instanceof GTEmiRecipe || recipe instanceof MultiblockInfoEmiRecipe;
    }

    @Override
    public boolean craft(EmiRecipe recipe, EmiCraftContext<T> context) {
        T menu = context.getScreenHandler();
        EncodingHelper.encodeProcessingRecipe(menu,
                ofInputs(recipe),   // → EMI 配方的 getInputs()
                ofOutputs(recipe)); // → EMI 配方的 getOutputs()
        return true;
    }
}
```

- **所有 `GTEmiRecipe` 都支持**，包括分层配方的展示配方
- `craft()` 调用 AE2 的 `EncodingHelper.encodeProcessingRecipe()` 编码
- 编码时读取的是 EMI 配方的 `getInputs()` / `getOutputs()`

### 3.5 `GTEmiRecipe` (第 32 行)

```java
public class GTEmiRecipe extends ModularEmiRecipe<WidgetGroup> {
    final GTRecipe recipe;

    public GTEmiRecipe(GTRecipe recipe, EmiRecipeCategory category) {
        super(() -> new GTRecipeWidget(recipe));
        this.recipe = recipe;
    }
}
```

- 继承 `ModularEmiRecipe`，从 `GTRecipeWidget` 读取 UI 插槽
- `getInputs()` / `getOutputs()` 由父类 `ModularEmiRecipe` 根据 `addWidgets()` 中添加的 `SlotWidget`/`TankWidget` 确定
- 分层配方的 EMI 展示使用的是 **XEI 展示配方**（合并了所有步骤的输入输出）

### 3.6 `LayeredRecipeLogic` (第 48 行起)

运行时管理分层执行：

```java
public class LayeredRecipeLogic extends RecipeLogic {
    @Persisted @DescSynced
    private final List<GTRecipe> layeredRecipe = ...;  // 当前执行的分层步骤列表
    @Persisted @DescSynced
    private int layeredRecipeLayerIndex = -1;           // 当前步骤索引
    @Nullable @Getter @Persisted
    protected GTRecipe lastOriginLayeredRecipe;         // 原始完整配方的备份
}
```

核心方法：
- `getCurrentLayer()` — 获取当前执行的步骤
- `getNextLayeredRecipe()` — 获取下一步骤（用于检查是否需要等待输入）
- `onRecipeFinish()` — 步骤完成后的推进逻辑
- `interruptRecipe()` — 中断并清空分层状态
- `getCoverRedstoneOutput()` — 为 `LayeredStepDetectorCover` 提供红石信号

---

## 4. 数据流: 从配方定义到 AE2 样板

### 4.1 KubeJS 配方定义

```js
// 在 KubeJS server_scripts 中
event.recipes.gtceu.large_rotor_machine('recipe_id')
    .layeredRecipe(layered => {
        layered.step(1, step => step.inputItems(...).outputItems(...))
        layered.step(2, step => step.inputItems(...).outputItems(...))
        layered.step(3, step => step.inputItems(...).outputItems(...))
    })
    .EUt(32768).duration(200)
```

### 4.2 内部存储结构

```
GTRecipe.data = {
    "layered_info": {           // LayeredRecipeInfo 序列化
        "input": { ... },       // 各输入属于哪一步
        "tickInput": { ... },
        "layers": [             // 每步骤的 duration/timeout
            { duration: 100, timeout: 0 },
            { duration: 60, timeout: 0 },
            { duration: 40, timeout: 0 }
        ]
    },
    "layered_steps": [          // 展开后的步骤列表 (calculateRecipeSteps 生成)
        GTRecipe(step1), GTRecipe(step2), GTRecipe(step3)
    ],
    "layered_xei": GTRecipe(    // XEI 展示配方 (所有步骤合并)
        inputs = [step1.inputs, step2.inputs, step3.inputs],
        outputs = [step1.outputs, step2.outputs, step3.outputs],
        tickInputs = [...],
        tickOutputs = [...]
    )
}
```

### 4.3 EMI / AE2 流程

```
registerDisplays() → 遍历 recipeType.getRecipesInCategory(mainCategory)
    → 对每个配方: new GTEmiRecipe(xeiRecipe, emiCategory)
    → registry.addRecipe()

用户打开 AE2 样板编码终端 + EMI
    → Ae2PatternTerminalHandler.supportsRecipe() → true (因为是 GTEmiRecipe)
    → 用户点击 "+" → craft()
    → ofInputs() → recipe.getInputs() → XEI 配方的合并输入
    → ofOutputs() → recipe.getOutputs() → XEI 配方的合并输出
    → EncodingHelper.encodeProcessingRecipe() → 写入样板
```

---

## 5. 需要修改 AE2 样板编码行为时

| 修改目的 | 需要改的文件 |
|----------|------------|
| 改变哪些配方支持样板编码 | `Ae2PatternTerminalHandler.supportsRecipe()` |
| 改变编码时的输入输出 | `Ae2PatternTerminalHandler.ofInputs()` / `ofOutputs()` |
| 改变分层配方的 EMI 展示内容 | `LayeredRecipeUIHelper` 或 `GTEmiRecipe.addWidgets()` |
| 改变 XEI 展示配方的合并逻辑 | `LayeredRecipeHelper.applyLayeredRecipeModifications()` |
| 新增机器关联到配方类别 | `GTRecipeEMICategory.registerWorkStations()` |
| 新增配方类型到分层系统 | KubeJS 脚本中 `.setLayered()` |

---

## 6. EMI 配方页面的绘制流程

### 6.1 渲染链路

```
GTRecipeEMICategory.registerDisplays()
    → 遍历 recipeType.getRecipesInCategory(category)
    → new GTEmiRecipe(recipe, emiCategory)
    → registry.addRecipe() 注册到 EMI

用户打开 EMI 配方浏览界面
    → EMI 调用 GTEmiRecipe.addWidgets(WidgetHolder)
    → GTEmiRecipe 从 GTRecipeWidget 的 LDLib 控件树中提取 IRecipeIngredientSlot
    → 转换为 EMI 的 SlotWidget / TankWidget
    → 添加到 WidgetHolder
```

### 6.2 GTRecipeWidget 的构造 (`integration/xei/widgets/GTRecipeWidget.java`)

```java
public GTRecipeWidget(GTRecipe recipe) {
    super(xOffset, 0, recipe.recipeType.getRecipeUI().getJEISize().width,
                recipe.recipeType.getRecipeUI().getJEISize().height);
    this.recipe = recipe;
    setRecipeWidget();  // 构建配方 UI
    setTierToMin();     // 设置最低电压等级
    initializeRecipeTextWidget();  // 添加时间/能耗文本
    addButtons();       // 添加 ID 复制按钮等
}
```

`setRecipeWidget()` 的工作流程：

1. **收集存储** — 调用 `collectStorage()` 遍历配方的 inputs/outputs/tickInputs/tickOutputs，按 `IO` 和 `RecipeCapability` 分类
2. **创建 UI 模板** — 调用 `recipeType.getRecipeUI().createUITemplate()` 创建进度条和插槽布局
3. **添加插槽** — 调用 `addSlots()` 将输入/输出物料添加到 UI 组中
4. **添加文本** — 添加能耗、时间等信息标签
5. **添加条件信息** — 显示维度、环境等配方条件

### 6.3 分层配方的特殊绘制 (`LayeredRecipeUIHelper`)

当配方类型是分层时，`GTRecipeType.setLayered()` 会设置自定义的 UI Builder：

```java
// GTRecipeType.java 第 134 行
recipeUI = LayeredRecipeUIHelper.createRecipeUI(this);
```

`LayeredRecipeUIHelper.UIBuilder.build()` 的工作方式：

1. **获取分层步骤** — `LayeredRecipeHelper.getLayeredSteps(rootRecipe)` 从配方数据中读取步骤列表
2. **渲染每步输入** — `buildStepInput()` 为每个步骤在水平方向绘制独立的输入槽（带罗马数字标号 I, II, III...）
3. **渲染合并输出** — `buildStepOutput()` 在底部绘制所有步骤的合并输出槽
4. **添加总进度条** — 在输出上方绘制箭头进度条

```
┌─────────────────────────────────────┐
│  I     II    III                     │  ← 每步骤的输入（水平排列）
│ [A]   [D]    [G]                    │
│ [B]   [E]    [H]                    │
│ [C]   [F]                           │
│                                     │
│             ──►                      │  ← 进度箭头
│           [X] [Y] [Z]               │  ← 合并输出
└─────────────────────────────────────┘
```

### 6.4 GTEmiRecipe.addWidgets() 的控件转换

```java
// GTEmiRecipe.java 第 51 行
public void addWidgets(WidgetHolder widgets) {
    var widget = this.widget.get();  // GTRecipeWidget
    var modular = new ModularWrapper<>(widget);
    modular.setRecipeWidget(0, 0);

    for (Widget w : getFlatWidgetCollection(widget)) {
        if (w instanceof IRecipeIngredientSlot slot) {
            // 跳过 DraggableScrollableWidgetGroup 内的子控件
            // 跳过 RENDER_ONLY 的槽位

            var ingredients = slot.getXEIIngredients();  // 获取物料
            // 根据类型创建 SlotWidget 或 TankWidget
            // 设置 IngredientIO (INPUT/OUTPUT/CATALYST/RENDER_ONLY)
            // 添加到 slots 列表
        }
    }
    // 将 EMI 控件添加到 WidgetHolder
}
```

**关键点**：
- `getInputs()` / `getOutputs()` 继承自 `ModularEmiRecipe`，在 `addWidgets()` 完成后由 LDLib 基类自动填充
- `Ae2PatternTerminalHandler.ofInputs()` / `ofOutputs()` 读取的就是这些列表
- 分层配方的 EMI 展示使用的是 **XEI 合并配方**（所有步骤的物料合并显示），所以 `getInputs()` 返回的是所有步骤的总输入

---

## 7. 实现"按快捷键输出每步骤独立样板"的功能方案

### 7.1 需求分析

当前行为：用户浏览分层配方 → 点击 EMI 的 "+" 按钮 → 编码 **一个** 合并了所有步骤的 AE2 样板

期望行为：用户浏览分层配方 → 按快捷键 → 编码 **多个** AE2 样板（每个步骤一个独立样板）

### 7.2 实现方案

#### 方案 A：在 Ae2PatternTerminalHandler 中检测 Shift+Click

修改 `Ae2PatternTerminalHandler.craft()`，在编码前检查配方是否有分层步骤：

```java
@Override
public boolean craft(EmiRecipe recipe, EmiCraftContext<T> context) {
    if (recipe instanceof GTEmiRecipe gtEmiRecipe) {
        var gtRecipe = gtEmiRecipe.recipe;
        if (LayeredRecipeHelper.hasLayeredSteps(gtRecipe)) {
            var steps = LayeredRecipeHelper.getLayeredSteps(gtRecipe);
            if (steps != null && isShiftDown()) {
                // 编码多个样板，每个步骤一个
                for (var step : steps) {
                    EncodingHelper.encodeProcessingRecipe(menu,
                        ofStepInputs(step),
                        ofStepOutputs(step));
                }
                return true;
            }
        }
    }
    // 默认行为：编码合并配方
    EncodingHelper.encodeProcessingRecipe(menu,
            ofInputs(recipe), ofOutputs(recipe));
    return true;
}
```

需要的辅助方法：
```java
// 将单个 GTRecipe 步骤转换为 AE2 GenericStack
private static List<List<GenericStack>> ofStepInputs(GTRecipe step) { ... }
private static List<GenericStack> ofStepOutputs(GTRecipe step) { ... }
```

**优点**：改动小，所有逻辑集中在 `Ae2PatternTerminalHandler`
**缺点**：`EmiCraftContext.Type` 只有 `FILL_BUTTON` 和 `CRAFTABLE`，无法区分 Shift 点击。需要检测全局按键状态

#### 方案 B：注册一个自定义按键绑定 + EMI Recipe Screen Hook

1. 在 `common/CommonProxy.java` 中注册按键绑定
2. 监听 EMI RecipeScreen 打开事件，在界面上添加自定义按钮
3. 点击按钮时遍历分层步骤并调用 `EncodingHelper.encodeProcessingRecipe()`

```java
// 检测 Shift 键的辅助方法
private static boolean isShiftDown() {
    var window = Minecraft.getInstance().getWindow();
    return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
        || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
}
```

**优点**：用户交互清晰
**缺点**：改动较大

#### 方案 C：全部在 KubeJS 中实现

通过 KubeJS 监听 EMI 事件和 AE2 容器事件，但**性能较差且不可靠**，不推荐。

### 7.3 推荐方案：方案 A（修改 Ae2PatternTerminalHandler）

**涉及修改文件**：
- `integration/emi/recipe/Ae2PatternTerminalHandler.java` — 修改 `craft()` 方法，添加 Shift+Click 检测和逐步骤编码逻辑

**核心逻辑**：

```java
@Override
public boolean craft(EmiRecipe recipe, EmiCraftContext<T> context) {
    T menu = context.getScreenHandler();

    if (recipe instanceof GTEmiRecipe gtRecipe && isShiftDown()) {
        var gt = gtRecipe.recipe;
        if (LayeredRecipeHelper.hasLayeredSteps(gt)) {
            var steps = LayeredRecipeHelper.getLayeredSteps(gt);
            if (steps != null) {
                for (var step : steps) {
                    var inputs = ofStepInputs(step);
                    var outputs = ofStepOutputs(step);
                    if (!inputs.isEmpty() || !outputs.isEmpty()) {
                        EncodingHelper.encodeProcessingRecipe(menu, inputs, outputs);
                    }
                }
                return true;
            }
        }
    }

    // 默认：编码合并配方
    EncodingHelper.encodeProcessingRecipe(menu,
            ofInputs(recipe), ofOutputs(recipe));
    return true;
}

private static List<List<GenericStack>> ofStepInputs(GTRecipe step) {
    // 从 GTRecipe 的 inputs 和 tickInputs 中提取 GenericStack
    var result = new ArrayList<List<GenericStack>>();
    for (var entry : step.inputs.entrySet()) {
        for (var content : entry.getValue()) {
            var cap = entry.getKey();
            var stack = convertContentToGenericStack(cap, content);
            if (stack != null) {
                result.add(List.of(stack));
            }
        }
    }
    // 同理处理 tickInputs...
    return result;
}

private static List<GenericStack> ofStepOutputs(GTRecipe step) {
    // 从 GTRecipe 的 outputs 中提取 GenericStack
    var result = new ArrayList<GenericStack>();
    for (var entry : step.outputs.entrySet()) {
        for (var content : entry.getValue()) {
            var cap = entry.getKey();
            var stack = convertContentToGenericStack(cap, content);
            if (stack != null) {
                result.add(stack);
            }
        }
    }
    return result;
}
```

### 7.4 注意事项

1. **`EncodingHelper.encodeProcessingRecipe()` 每次调用都会在样板终端中添加一个新样板**，所以循环调用会生成多个样板
2. **需确保物品/流体转换为 AE2 GenericStack 时数量正确** — 使用 `Content.getContent()` 和对应的 `RecipeCapability.of()` 方法转换
3. **UI 反馈**：编码完成后最好显示一个消息提示用户生成了多少个样板
4. **Shift+Click 检测**使用 `GLFW.glfwGetKey()` 是最简单的方式，无需注册按键绑定

---

## 8. 注意事项

1. **分层配方的输入分配**：每个步骤的输入通过 `LayeredRecipeInfo.input` 映射指定，未指定的输入在所有步骤中都可用
2. **输出只在最后一步产出**：`createStepRecipe()` 中仅在 `recipeStep == layers.size() - 1` 时才复制输出
3. **XEI 展示配方的局限性**：合并后的展示配方将所有步骤的物料合并显示，AE2 样板也是基于这个合并配方编码。这意味着 **AE2 样板无法区分物料属于哪个步骤** — 所有输入物料必须一次性提供
4. **运行时分层推进**：`LayeredRecipeLogic.onRecipeFinish()` 在每步完成后自动推进到下一步，直到所有步骤完成
