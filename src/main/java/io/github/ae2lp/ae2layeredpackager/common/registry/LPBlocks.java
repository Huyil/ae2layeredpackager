package io.github.ae2lp.ae2layeredpackager.common.registry;

import io.github.ae2lp.ae2layeredpackager.AELayeredPackager;
import io.github.ae2lp.ae2layeredpackager.common.block.PackagerBlock;
import io.github.ae2lp.ae2layeredpackager.common.item.LPCreativeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public class LPBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AELayeredPackager.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AELayeredPackager.MOD_ID);

    // 打包机方块
    public static final RegistryObject<Block> PACKAGER = register("packager",
            PackagerBlock::new);

    private static <T extends Block> RegistryObject<T> register(String name, Supplier<T> block) {
        RegistryObject<T> registered = BLOCKS.register(name, block);
        ITEMS.register(name, () -> new BlockItem(registered.get(), new Item.Properties()));
        return registered;
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        LPCreativeTab.register(modEventBus);
    }
}
