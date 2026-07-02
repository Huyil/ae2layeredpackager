package io.github.ae2lp.ae2layeredpackager.client.patternizer;

import appeng.menu.me.items.PatternEncodingTermMenu;
import com.mojang.logging.LogUtils;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.EmiEntrypoint;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * EMI 插件入口 — 注册 LayeredPatternHandler
 *
 * 同时注册到 AE2 原版的 PatternEncodingTermMenu 和 ae2wtlib 的 WETMenu（如果加载）。
 */
@EmiEntrypoint
public class AELPEmiPlugin implements EmiPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String WET_MOD_ID = "ae2wtlib";
    private static final String WET_MENU_CLASS = "de.mari_023.ae2wtlib.wet.WETMenu";

    @Override
    public void register(EmiRegistry registry) {
        LOGGER.info("AELPEmiPlugin register() called - registering LayeredPatternHandler");

        registry.addRecipeHandler(PatternEncodingTermMenu.TYPE, new LayeredPatternHandler<>());
        LOGGER.info("LayeredPatternHandler registered for PatternEncodingTermMenu");

        // ae2wtlib 可选支持
        registerAe2wtlibWET(registry);
    }

    /**
     * 注册到 ae2wtlib 的无限样板终端 (WET)
     * WETMenu extends PatternEncodingTermMenu，但 EMI 按 MenuType 匹配，需要单独注册。
     * 因为 Java 泛型不变性，EmiRecipeHandler<PatternEncodingTermMenu> 不能直接传给
     * 接受 MenuType<WETMenu> 的方法，所以用 raw type 绕过。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerAe2wtlibWET(EmiRegistry registry) {
        if (!ModList.get().isLoaded(WET_MOD_ID)) {
            LOGGER.info("ae2wtlib not loaded, skipping WET handler registration");
            return;
        }

        try {
            Class<?> wetMenuClass = Class.forName(WET_MENU_CLASS);
            Field typeField = wetMenuClass.getDeclaredField("TYPE");
            typeField.setAccessible(true);
            MenuType wetType = (MenuType) typeField.get(null);

            // LayeredPatternHandler<T extends PatternEncodingTermMenu>
            // WETMenu extends PatternEncodingTermMenu，所以可以共用同一个 handler 类
            dev.emi.emi.api.recipe.handler.EmiRecipeHandler rawHandler = new LayeredPatternHandler();
            registry.addRecipeHandler(wetType, rawHandler);
            LOGGER.info("LayeredPatternHandler registered for ae2wtlib WETMenu");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("ae2wtlib loaded but WETMenu class not found: {}", e.getMessage());
        } catch (NoSuchFieldException e) {
            LOGGER.warn("ae2wtlib WETMenu.TYPE field not found: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to register LayeredPatternHandler for ae2wtlib WETMenu", e);
        }
    }
}
