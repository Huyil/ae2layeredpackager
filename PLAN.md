# ae2layeredpackager 项目计划

> 更新于 2026-07-03

---

## 一、项目目标

为 **AE2 样板编码终端**（`PatternEncodingTermScreen`）添加 **GT 分层配方多样板自动编码** 功能。用户在 EMI 中浏览 GT 分层配方时，可以通过点击 `+` 按钮或按快捷键，自动为分层配方生成一组协作样板。

---

## 二、关键发现：GT 分层配方的真实模型

通过阅读 GT 源码（[LayeredRecipeHelper.java:208-210](d:/code/GTM-StarT-Fork/src/main/java/com/gregtechceu/gtceu/api/recipe/LayeredRecipeHelper.java#L208-L210) 和 [LayeredRecipeInfo.java:74-79](d:/code/GTM-StarT-Fork/src/main/java/com/gregtechceu/gtceu/data/recipe/builder/LayeredRecipeInfo.java#L74-L79)），确认：

**GT 分层配方是"多入单出"模型，不存在真正的中间产物。**

- 一个多方块有多条输入总线，每条总线对应一个 layer
- `layered_info.input` 是 `RecipeCapability → {contentIndex → layerIndex}` 的映射表
- `LayeredRecipeInfo.Layer` 只有 `duration` 和 `timeout` 两个字段——没有任何中间物料信息
- `createStepRecipe()` 中 `if (recipeStep == layeredInfo.layers().size() - 1) copy.outputs.putAll(fullRecipe.outputs);`——**只有最后一步才有 outputs**

所以 PLAN 早期版本设想的"步骤1→B、步骤2→D、组装样板用 B+D 造 F"模型在 GT 里根本不存在。

---

## 三、当前编码模型：输入打包方案

由于 GT 不暴露中间产物，本模组改用**输入打包**模型——把每一层的输入物料打包到一个存储元件里，最终组装样板消费这些 cell。

### 编码结果（以 3 层配方为例）

| 样板 | 输入 | 输出 |
|------|------|------|
| 中间样板 1 | `[空 cell] + step1 的 inputs` | `[装着 step1 inputs 的 cell]` |
| 中间样板 2 | `[空 cell] + step2 的 inputs` | `[装着 step2 inputs 的 cell]` |
| 中间样板 3 | `[空 cell] + step3 的 inputs` | `[装着 step3 inputs 的 cell]` |
| 组装样板 | `[3 个 filled cell]` | `最终产物` |

### 关键约定

- **每一步都生成中间样板**——包括最后一步，所有层的 inputs 都打包到 cell
- **组装样板只消费 cell**——不再直接消费任何原料，输入槽里只有 N 个 filled cell
- **流体处理**：优先 `expandedae:dual_storage_cell`（同时装物品+流体），无 expandedae 时回退到 AE2 原版 `item_storage_cell` + `fluid_storage_cell` 两张分开
- **存储元件类型**：通过 `LPConfig.CELL_PRIORITY` 配置（`DEFAULT` / `PREFER_VANILLA` / `PREFER_DUAL`）

### 已知语义代价

组装样板消费 cell 后，cell 里的物料需要外部 AE2 子网"拆包"送进 GT 多方块对应总线。本模组不负责拆包——这是方案 B 固有的限制。

---

## 四、两条编码路径

### 路径 A：EMI `+` 按钮触发（`LayeredPatternHandler.craft()`）

```
用户操作: 打开 AE2 样板终端 → 打开 EMI 查找配方 → 点击 "+" 按钮
处理器:   LayeredPatternHandler.craft()
检测:     LayeredRecipeDetector.isLayeredRecipe() → 反射读取 GTRecipe.data.layered_steps
```

- **✅ 已完成**：分层/非分层分流、输入打包模型、每次 encode 后 QUICK_MOVE 取出样板避免覆盖
- **⚠️ 已知问题**：`supportsRecipe()` 匹配所有 GT 配方，会替换 GTM 自身的 handler。可能需要更精确匹配（只匹配分层配方？）

### 路径 B：快捷键触发（`PatternizeManager.onKeyPressed()`）

```
用户操作: 在样板终端打开 → EMI 按 Ctrl+B 打开 BoM 配方树 → 选中目标
           → 按 Ctrl+Shift+P
处理器:   PatternizeManager.onKeyPressed()
配方来源: BoM.tree.goal.recipe
检测:     LayeredRecipeDetector.isLayeredRecipe()
编码:     encodeLayeredRecipe()
```

- **✅ 已完成**：按键检测、`initEncodeMethod()` 反射、分层/非分层分流、`encodeSingle()`、异步延时链、输入打包模型
- **⚠️ 注意**：必须保持在 `BoM.craftingMode`（配方树模式）下才能获取配方

---

## 五、需要实现 / 待优化的功能模块

### 1. ✅ `LayeredRecipeDetector` — 步骤物料解析
- 通过反射读取 `layered_steps` NBT
- `extractInputItems` / `extractInputFluids` 正确提取每步的物品/流体输入
- `extractOutputItems` / `extractOutputFluids` 只对最后一步有非空结果（这是 GT 设计，不是 bug）

### 2. ✅ `CellAllocator` — 存储元件分配
- 支持 `dual_storage_cell`（expandedae）和 AE2 原生 cells
- 读取 `LPConfig.CELL_PRIORITY` 配置
- `createFilledCell()` 构造存储元件的 NBT
- **已移除** `allocatePlaceholder()`（输入打包模型下不再需要占位）

### 3. ✅ `LayeredPatternHandler.craft()` — EMI 按钮编码（同步）
- 输入打包模型实现完成
- 每次 encode 后 `QUICK_MOVE` 编码样板槽，避免被下一次覆盖
- 已修复每个 input 独立成槽（之前所有 input 塞进一个槽当 alternatives 的 bug）

### 4. ✅ `PatternizeManager.encodeLayeredRecipe()` — 快捷键编码（异步）
- 输入打包模型实现完成
- 异步延时链（`CompletableFuture.delayedExecutor`）保留
- 同样修复了 input 槽结构

### 5. 🔲 验证 `createFilledCell` 生成的 NBT 是否与 AE2 内部格式完全一致

参考真实 NBT（用户提供）：
```
// 装好物品的 dual_storage_cell
{Count:1b, id:"expandedae:dual_storage_cell_1k",
 tag:{amts:[L;2L,1L], ic:3L,
       keys:[{"#c":"ae2:i", id:"gtceu:prismalium_ring"},
             {"#c":"ae2:i", id:"gtceu:long_pure_netherite_rod"}]}}

// 装好物品+流体的 16k 物品 cell + 流体 cell
{Count:1b, id:"ae2:item_storage_cell_16k",
 tag:{amts:[L;8L,1L], ic:9L,
       keys:[{"#c":"ae2:i", id:"minecraft:stick"},
             {"#c":"ae2:i", id:"minecraft:diamond_pickaxe", tag:{Damage:639}}]}}
{Count:1b, id:"ae2:fluid_storage_cell_16k",
 tag:{amts:[L;62500L,62500L], ic:125000L,
       keys:[{"#c":"ae2:f", id:"gtceu:oxygen"},
             {"#c":"ae2:f", id:"minecraft:water"}]}}
```

当前 `createFilledCell` 生成的格式与此一致，但需要实际运行验证。

### 6. 🔲 测试整个编码链路

测试用例：
- 3 层 GT 配方（用户的 `csg_stargate_rod_base` 案例）
- 含流体的配方（验证 dual cell 和回退两种模式）
- 配置切换 `PREFER_VANILLA` / `PREFER_DUAL` 的行为

---

## 六、依赖关系

```
LayeredRecipeDetector (NBT 解析)
        ↓
  CellAllocator (存储元件分配, 读 LPConfig)
        ↓
   ┌────┴────┐
   ↓         ↓
LayeredPatternHandler   PatternizeManager
(EMI + 按钮, 同步)      (快捷键 Ctrl+Shift+P, 异步)
```

两条路径共享 `CellAllocator` 和 `LayeredRecipeDetector`。

---

## 七、未涵盖的问题

### 已知 Bug

- **ResourcePackInfo 加载警告**：启动时可能弹出"未能加载有效的 ResourcePackInfo"窗口，需手动关闭。已尝试两种 `pack.mcmeta` 方案（删除、`pack_format: 8`）都未解决，疑似 Forge 1.20.1 / FancyMenu 资源包加载流程冲突。功能不受影响。

### 设计限制

- **拆包机制**：组装样板消费 cell 后，需要外部子网把 cell 里的物料取出来送进 GT 多方块对应总线。本模组不提供此机制。
- **去重逻辑**：中间样板按内容签名去重（同一种 cell 只编码一次）；组装样板按原始 step 顺序排槽，重复 cell 也占独立槽位。如果 GT 机器实际需要重复 step 的全部输入，本设计正确；否则会过度消耗。
- **`supportsRecipe` 冲突**：会替换 GTM 自身的 handler，可能影响普通 GT 配方的处理

### 待优化

- **错误恢复**：如果某步编码失败（如存储元件不足），当前无回滚机制
- **UI 反馈**：编码过程中无进度条或状态指示
- **网络包**：纯客户端实现，无需自定义网络包，但也不支持服务端验证
