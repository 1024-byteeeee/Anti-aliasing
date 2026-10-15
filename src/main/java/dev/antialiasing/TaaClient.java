package dev.antialiasing;

import dev.antialiasing.config.TaaConfig;
import dev.antialiasing.render.TaaRenderer;
import dev.antialiasing.render.SsaaRenderer;
import dev.antialiasing.render.GuiEntityAntialiasing;
import dev.antialiasing.ui.TaaSettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
//#if MC>=11800
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//#else
//$$ import org.apache.logging.log4j.Logger;
//$$ import org.apache.logging.log4j.LogManager;
//#endif

public final class TaaClient implements ClientModInitializer {
    //#if MC>=11800
    public static final Logger LOGGER = LoggerFactory.getLogger("anti-aliasing");
    //#else
    //$$ public static final Logger LOGGER = LogManager.getLogger("anti-aliasing");
    //#endif
    public static TaaConfig CONFIG;
    public static final TaaRenderer RENDERER = new TaaRenderer();
    public static final SsaaRenderer SSAA = new SsaaRenderer();
    public static final GuiEntityAntialiasing GUI_SSAA = new GuiEntityAntialiasing();
    @Override public void onInitializeClient() {
        CONFIG = TaaConfig.load();
        //#if MC>=12109
        //$$ KeyMapping settings = KeyBindingHelper.registerKeyBinding(new KeyMapping(
        //$$         "key.taa.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8,
        //$$         KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("taa", "settings"))));
        //#else
        KeyMapping settings = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.taa.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.taa"));
        //#endif
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settings.consumeClick()) openSettings(client.screen);
            if (client.level == null) { RENDERER.invalidate(); SSAA.reset(); }
            if (client.screen == null || !CONFIG.enabled || CONFIG.antialiasingMode != 1 || !CONFIG.guiEntityAntialiasing) GUI_SSAA.close();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { RENDERER.close(); SSAA.close(); GUI_SSAA.close(); closeDebugLines(); });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() {
                //#if MC>=12100
                return ResourceLocation.fromNamespaceAndPath("taa", "reload");
                //#else
                //$$ return new ResourceLocation("taa", "reload");
                //#endif
            }
            @Override public void onResourceManagerReload(ResourceManager manager) { RENDERER.reload(); SSAA.reset(); GUI_SSAA.reset(); closeDebugLines(); }
        });
        LOGGER.info("Anti-aliasing initialized (Mojmap)");
    }
    public static void openSettings(net.minecraft.client.gui.screens.Screen parent) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof TaaSettingsScreen)) client.setScreen(TaaSettingsScreen.forParent(parent));
    }
    public static String statusKey() { return CONFIG.antialiasingMode == 1 ? SSAA.statusKey() : RENDERER.statusKey(); }
    private static void closeDebugLines() {
        //#if MC<12109
        dev.antialiasing.render.DebugLineGeometry.close();
        //#else
        //$$ dev.antialiasing.render.ModernChunkBorders.close();
        //#endif
    }
}
