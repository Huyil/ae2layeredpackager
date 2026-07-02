package io.github.ae2lp.ae2layeredpackager.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class LPKeybinds {

    public static final Lazy<KeyMapping> PATTERNIZE_KEY = Lazy.of(() -> new KeyMapping(
            "key.ae2lp.patternize",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,   // P 键 (配合 Ctrl+Shift)
            "key.categories.ae2lp"
    ));

    public static void register() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(LPKeybinds::registerBindings);
    }

    private static void registerBindings(RegisterKeyMappingsEvent event) {
        event.register(PATTERNIZE_KEY.get());
    }
}
