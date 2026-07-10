# AE2 Layered Packager (ae2lp)

[![ License](https://img.shields.io/badge/license-LGPL--3.0-blue.svg)](LICENSE.txt)
[![MC Version](https://img.shields.io/badge/MC-1.20.1-success)](https://minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-47.1.47-orange)](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)

**AE2 Layered Packager** 是一个客户端模组，为 **GregTech 分层配方 (Layered Recipe)** 提供自动多样板编码功能。

## 功能

### 分层配方多样板编码

当你在 AE2 样板编码终端中按下 `Ctrl+Shift+P` 时，本模组会自动：

1. **检测分层配方** — 通过反射识别 GregTech 的分层配方（`layered_steps` NBT 数据）
2. **分配存储元件** — 为每步计算所需容量，自动选择最合适的存储元件
3. **生成中间样板** — 每步一个：`[空存储元件 + 原料] → [装好物品的存储元件]`
4. **生成组装样板** — 将所有中间产物装配为最终输出

### 存储元件优先级配置

可在客户端配置中选择：

| 模式 | 行为 |
|------|------|
| **DEFAULT** | 优先 `dual_storage_cell` (expandedae)，不存在则回退 AE2 原版 |
| **PREFER_VANILLA** | 始终使用 AE2 原版 `item_storage_cell` + `fluid_storage_cell` |
| **PREFER_DUAL** | 强制使用 `dual_storage_cell` |

### 参考模组

本项目参考了以下优秀模组的实现：

| 模组 | 参考内容 |
|------|---------|
| **[EMI-Patternizer](https://github.com/link-fgfgui/EMI-Patternizer)** | 客户端的多样板编码循环机制（`EmiRecipeFiller` + `menu.encode()` 反射 + `QUICK_MOVE` 异步链） |
| **[ExpandedAE](https://github.com/KoJol2711/expandedae)** | `dual_storage_cell` 双存储元件（运行时可选兼容，通过 Item ID 反射检测） |
| **[ExtendedAE](https://github.com/GlodBlock/ExtendedAE)** | AE2 附属模组的架构参考 |
| **[GTM-StarT-Fork](https://github.com/Star-Technology-Mod-Pack/GTM-StarT-Fork)** | `GTEmiRecipe` 和 `LayeredRecipeHelper` 分层配方系统（运行时可选兼容） |

### 模组依赖

| 模组 | 必需 | 说明 |
|------|------|------|
| **Applied Energistics 2** | ✅ 是 | AE2 样板终端 |
| **EMI** | ✅ 是 | 配方查看和填充 API |
| **GTM-StarT / GregTech CEu Modern** | ❌ 可选 | 分层配方检测（通过反射，无硬依赖） |
| **ExpandedAE** | ❌ 可选 | `dual_storage_cell` 双存储元件（通过 Item ID 检测） |

## 使用方式

### 安装

1. 安装本模组及依赖（AE2 + EMI 必需；GTCEu/GTM-StarT、expandedae、ae2wtlib 可选）
2. 把 jar 放进 `mods/` 目录

### 单配方编码

**对单个 GT 配方编码（含 programmed_circuit 等 NBT 物品）：**

1. 打开 AE2 样板编码终端（或 ae2wtlib 无限样板终端 WET）
2. 在终端打开的状态下，按 `U` 在 EMI 中查看某个物品/机器的配方
3. 在 EMI 配方页面，点击某个配方旁的 `+` 按钮
4. 该配方会自动填入样板终端的输入/输出槽（保留 NBT，例如 `gtceu:programmed_circuit` 的 `Configuration: 0~32`）
5. 点击终端的编码按钮完成

**对分层 GT 配方自动多样板编码：**

1. 打开 AE2 样板编码终端
2. 在 EMI 中浏览到分层配方（GT 多方块机器的多步骤配方）
3. 点击配方旁的 `+` 按钮，本模组会自动：
   - 为每一步分配存储元件（按容量自动选 1k~256k）
   - 生成 N 张中间样板：`[空 cell + 该层 inputs] → [装满的 cell]`
   - 生成 1 张组装样板：`[所有 filled cell] → [最终产物]`
4. 相同内容的中间样板只生成一次（去重）

### 批量导出当前机器的所有配方

**`Ctrl + 点击 + 按钮** = 一键导出当前 EMI 页面机器类别下的所有配方：

1. 打开 AE2 样板编码终端
2. 在 EMI 中浏览某个机器的配方页面（如 GT 化学反应釜、组装机等）
3. **按住 Ctrl**（左或右），然后点击任一配方旁的 `+` 按钮
4. 该机器类别下的所有配方会按顺序自动编码，每个样板 QUICK_MOVE 到玩家背包
5. 玩家背包满时多余样板会自动**掉落到地上**

**两种查询模式：**

| 触发场景 | 行为 |
|---------|------|
| 直接查看机器类别（如点 EMI 侧栏的机器图标） | 导出该机器 tab 下所有配方 |
| 在某物品上按 `U` 查询用途，再 Ctrl+点击 + | 只导出该物品参与合成的配方 |

### 配置项 (`config/ae2lp-client.toml`)

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `cellPriority` | `DEFAULT` | 存储元件优先级：`DEFAULT` / `PREFER_VANILLA` / `PREFER_DUAL` |
| `bulkExportMaxRecipes` | `32` | 批量导出（Ctrl+点击+）的最大配方数上限，超过此值批量导出会被禁用并降级为单配方编码 |
| `delayPerOperation` | `60` | 异步编码每次操作间隔（毫秒） |
| `delayAdditionalPerPattern` | `20` | 每张样板编码完成后的额外延迟（毫秒） |

### 注意事项

- 批量导出会跳过 **vanilla 工作台** 类别（`minecraft:crafting`），其他机器类别（含原版熔炉、GT 机器等）都允许
- 批量导出会跳过 **分层配方**（避免复杂的多样板逻辑），只编码普通处理配方
- 编码前会检查样板终端的空白样板槽，**数量不足时禁止启动**并提示
- 所有逻辑都是**纯客户端**实现，不发送自定义网络包，不要求服务端安装

## 构建

```bash
./gradlew build -x runClient -x runServer
```

构建产物在 `build/libs/`。

## 后续计划

- **打包机方块** (`ae2lp:packager`) — 将相邻容器的物品/流体打包成包裹，支持 AE 子网
- **拆包模式** — 将包裹拆到容器，支持阻挡模式
- **包裹样板** — 与样板供应器联动（类似分子装配室）

## 已知问题

### 启动时 ResourcePackInfo 加载警告

游戏启动时可能弹出 `未能加载有效的 ResourcePackInfo` 警告窗口，需要手动点击关闭。模组功能本身不受影响，关闭窗口后正常使用。

已尝试的修复（均未彻底解决）：
- 删除 jar 中的 `pack.mcmeta`
- 添加 `pack.mcmeta` 并设置 `pack_format: 8`（与 AE2 一致）

可能与 Forge 1.20.1 某些版本或与 FancyMenu 等模组的资源包加载流程冲突，待社区反馈或后续 Forge 更新解决。

## 许可证

LGPL-3.0
