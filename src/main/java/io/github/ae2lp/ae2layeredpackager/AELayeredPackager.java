package io.github.ae2lp.ae2layeredpackager;

import com.mojang.logging.LogUtils;
import io.github.ae2lp.ae2layeredpackager.client.LPKeybinds;
import io.github.ae2lp.ae2layeredpackager.client.patternizer.PatternizeManager;
import io.github.ae2lp.ae2layeredpackager.common.registry.LPBlocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(AELayeredPackager.MOD_ID)
public class AELayeredPackager {

    public static final String MOD_ID = "ae2lp";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AELayeredPackager() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeEventBus = MinecraftForge.EVENT_BUS;

        // 注册方块和物品
        LPBlocks.register(modEventBus);

        // 注册配置
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, LPConfig.SPEC);

        // 客户端专用注册
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            modEventBus.addListener(this::clientSetup);
            LPKeybinds.register();
            forgeEventBus.addListener(PatternizeManager::onKeyPressed);
        });

        LOGGER.info("AE2 Layered Packager initialized");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.debug("AE2 Layered Packager client setup complete");
    }
}
