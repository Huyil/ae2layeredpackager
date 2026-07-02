package io.github.ae2lp.ae2layeredpackager.common.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * 打包机方块 (占位实现)
 *
 * 后续计划:
 * - 扳手切换模式: 打包 / 拆包
 * - 打包模式: 将相邻容器内物品+流体打包成包裹 Item，支持读取 AE 子网
 * - 拆包模式: 将包裹物品拆到容器，支持样板供应器同款阻挡模式
 * - 包裹样板: 利用 AE2 合成样板与样板供应器联动
 */
public class PackagerBlock extends Block {

    public static final String ID = "packager";

    public PackagerBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(5.0f, 6.0f)
                .requiresCorrectToolForDrops());
    }
}
