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

1. 安装本模组及依赖（AE2 + EMI）
2. 打开 AE2 样板编码终端
3. 在 EMI 中选中一个配方
4. 按 `Ctrl+Shift+P` 自动编码
   - 普通配方 → 单次编码
   - 分层 GT 配方 → 多样板编码

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
