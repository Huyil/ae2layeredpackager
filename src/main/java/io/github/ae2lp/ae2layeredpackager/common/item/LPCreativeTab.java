package io.github.ae2lp.ae2layeredpackager.common.item;

import io.github.ae2lp.ae2layeredpackager.AELayeredPackager;
import io.github.ae2lp.ae2layeredpackager.common.registry.LPBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class LPCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AELayeredPackager.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN_TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2lp"))
                    .icon(() -> new ItemStack(LPBlocks.PACKAGER.get()))
                    .displayItems((params, output) -> {
                        output.accept(LPBlocks.PACKAGER.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
