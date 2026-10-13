package dev.antialiasing.validation;

import dev.antialiasing.TaaClient;
import dev.antialiasing.validation.mixin.CreateWorldAccessor;
import dev.antialiasing.render.SupersampleContext;
import dev.antialiasing.ui.ModMenuIntegration;
import dev.antialiasing.ui.TaaSettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.opengl.GL11;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** Test mod loaded only by runValidationClient, never packaged in release jars. */
public final class ValidationClient implements ClientModInitializer {
    private int phase, ticks, frames;
    private long started;
    private CompletableFuture<Void> reload;
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("taa.validation")) return;
        started = System.nanoTime();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    private void tick(Minecraft mc) {
        try {
            ticks++;
            if (System.nanoTime() - started > 240_000_000_000L) throw new IllegalStateException("Client validation timeout at phase " + phase);
            if (phase == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null && ticks > 20) {
                mc.options.pauseOnLostFocus = false;
                //#if MC>=11900
                mc.options.bobView().set(false);
                //#else
                //$$ mc.options.bobView = false;
                //#endif
                TaaClient.CONFIG.defaults();
                TaaClient.CONFIG.antialiasingMode = 0;
                validateMenu(mc);
                //#if MC>=12109
                //$$ CreateWorldScreen.openFresh(mc, () -> mc.setScreen(new TitleScreen()));
                //#elseif MC>=12108
                CreateWorldScreen.testWorld(mc, new TitleScreen());
                //#elseif MC>=11903
                //$$ CreateWorldScreen.openFresh(mc, new TitleScreen());
                //#elseif MC>=11800
                //$$ mc.setScreen(CreateWorldScreen.createFresh(new TitleScreen()));
                //#else
                //$$ mc.setScreen(CreateWorldScreen.create(new TitleScreen()));
                //#endif
                next();
            } else if (phase == 1 && mc.screen instanceof CreateWorldScreen) {
                CreateWorldScreen screen = (CreateWorldScreen) mc.screen;
                //#if MC>=11903
                screen.getUiState().setName("TAA Validation " + System.currentTimeMillis());
                screen.getUiState().setAllowCommands(true);
                //#endif
                ((CreateWorldAccessor) screen).taaValidation$create(); next();
            } else if (phase == 2 && mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && ticks > 40) {
                mc.setScreen(null);
                mc.getSingleplayerServer().execute(() -> {
                    String[] commands = {"gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule randomTickSpeed 0",
                            "time set noon", "weather clear", "fill -20 241 -50 20 255 0 air", "fill -20 241 1 20 255 24 air",
                            "fill -16 246 -16 16 246 24 stone", "fill -8 245 -48 8 252 -48 glass", "fill 4 247 -6 8 249 -2 glass",
                            "fill -8 247 -6 -4 250 -2 oak_leaves[persistent=true]", "gamemode creative @a",
                            "give @a diamond_sword", "give @a shield", "tp @a 0 247 18 180 12"};
                    for (String command : commands) command(mc, command);
                    for (int i = -10; i <= 10; i++) command(mc, "setblock " + i + " 247 " + i + " oak_fence");
                    mc.getSingleplayerServer().getPlayerList().getPlayers().forEach(player -> {
                        //#if MC>=11700
                        player.getAbilities().flying = true;
                        //#else
                        //$$ player.abilities.flying = true;
                        //#endif
                        player.onUpdateAbilities();
                    });
                });
                next();
            } else if (phase == 3 && ticks > 100) {
                TaaClient.LOGGER.info("TAA_VALIDATION_POSITION {}", mc.player.position());
                require(TaaClient.RENDERER.hasValidHistory() && TaaClient.statusKey().equals("taa.status.ready"), "TAA did not accumulate history");
                require(Math.abs(mc.player.getY() - 247) < 0.1, "Scene fixture teleport failed");
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after TAA");
                screenshot(mc, "taa.png");
                ssaa(2); next();
            } else if (phase >= 4 && phase <= 6 && ticks > 35) {
                int scale = phase - 2;
                require(TaaClient.SSAA.renderedFrames() > frames, "SSAA did not render");
                require(TaaClient.SSAA.renderWidth() == mc.getMainRenderTarget().width * scale, "Wrong SSAA width");
                require(TaaClient.SSAA.renderHeight() == mc.getMainRenderTarget().height * scale, "Wrong SSAA height");
                require(SupersampleContext.target() == null && !TaaClient.SSAA.isActive(), "SSAA leaked into HUD");
                require(!TaaClient.RENDERER.hasValidHistory(), "TAA history retained in SSAA");
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after SSAA");
                screenshot(mc, "ssaa-" + scale * scale + "x.png");
                TaaClient.LOGGER.info("TAA_VALIDATION_SSAA {}x: {}x{}", scale * scale, TaaClient.SSAA.renderWidth(), TaaClient.SSAA.renderHeight());
                if (phase < 6) ssaa(scale + 1);
                else { TaaClient.CONFIG.enabled = false; }
                next();
            } else if (phase == 7 && ticks > 20) {
                require(TaaClient.SSAA.sourceTarget() == null, "Disabled SSAA retained target");
                TaaClient.CONFIG.defaults(); TaaClient.CONFIG.antialiasingMode = 0;
                TaaClient.SSAA.reset(); TaaClient.RENDERER.invalidate(); next();
            } else if (phase == 8 && ticks > 40) {
                require(TaaClient.RENDERER.hasValidHistory(), "Switching back to TAA failed");
                reload = mc.reloadResourcePacks(); next();
            } else if (phase == 9 && reload.isDone() && ticks > 50) {
                reload.join(); require(TaaClient.RENDERER.hasValidHistory(), "TAA failed after resource reload");
                validateMenu(mc); mc.setScreen(TaaSettingsScreen.forParent(null)); next();
            } else if (phase == 10 && ticks > 20) {
                screenshot(mc, "settings.png"); mc.setScreen(null);
                Files.write(mc.gameDirectory.toPath().resolve("validation-passed.txt"), "TAA, SSAA 4/9/16x, settings, ModMenu factory, resource reload passed".getBytes(StandardCharsets.UTF_8));
                TaaClient.LOGGER.info("TAA_VALIDATION_PASSED"); mc.stop(); phase = 99;
            }
        } catch (Throwable error) {
            TaaClient.LOGGER.error("TAA_VALIDATION_FAILED phase=" + phase, error);
            try { Files.write(mc.gameDirectory.toPath().resolve("validation-failed.txt"), error.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) { }
            phase = 99; mc.stop();
        }
    }
    private static void command(Minecraft mc, String command) {
        CommandSourceStack source = mc.getSingleplayerServer().createCommandSourceStack().withSuppressedOutput();
        //#if MC>=12003
        mc.getSingleplayerServer().getCommands().performPrefixedCommand(source, command);
        //#elseif MC>=11900
        //$$ int result = mc.getSingleplayerServer().getCommands().performPrefixedCommand(source, command);
        //#else
        //$$ int result = mc.getSingleplayerServer().getCommands().performCommand(source, command);
        //#endif
        //#if MC<12003
        //$$ TaaClient.LOGGER.info("TAA_VALIDATION_COMMAND {} result={}", command, result);
        //#endif
    }
    private void validateMenu(Minecraft mc) throws ReflectiveOperationException {
        Screen parent = mc.screen;
        Screen config = new ModMenuIntegration().getModConfigScreenFactory().create(parent);
        require(config instanceof TaaSettingsScreen, "ModMenu factory returned wrong screen");
        mc.setScreen(config);
        int count = config.children().size();
        for (int i = 0; i < 30; i++) {
            TaaClient.openSettings(config);
            require(mc.screen == config && config.children().size() == count, "Repeated settings open added screens/widgets");
        }
        Button algorithm = (Button) field(config, "algorithm");
        for (int i = 0; i < 30; i++) {
            press(algorithm);
            require(config.children().size() == count, "Algorithm button added widgets");
        }
        press(algorithm);
        EditBox samples = (EditBox) field(config, "samples");
        require(!samples.active, "TAA sample input remained active in SSAA");
        //#if MC>=12105
        Button scale = (Button) field(config, "ssaaScale");
        //#else
        //$$ Button scale = (Button) field(config, "scale");
        //#endif
        for (int i = 0; i < 3; i++) press(scale);
        require(TaaClient.CONFIG.ssaaScale == 2 && config.children().size() == count, "SSAA scale cycling failed");
        press(algorithm);
        int previous = TaaClient.CONFIG.sampleCount;
        samples.setValue("17"); press((Button) field(config, "apply"));
        require(TaaClient.CONFIG.sampleCount == 17, "Custom sample period failed");
        samples.setValue(Integer.toString(previous)); press((Button) field(config, "apply"));
        require(config.children().size() == count, "Sample apply added widgets");
        mc.setScreen(parent);
    }
    private static Object field(Object screen, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = TaaSettingsScreen.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(screen);
    }
    private static void press(Button button) throws ReflectiveOperationException {
        java.lang.reflect.Field field = Button.class.getDeclaredField("onPress");
        field.setAccessible(true); ((Button.OnPress) field.get(button)).onPress(button);
    }
    private void ssaa(int scale) {
        TaaClient.CONFIG.antialiasingMode = 1; TaaClient.CONFIG.ssaaScale = scale;
        TaaClient.SSAA.reset(); TaaClient.RENDERER.invalidate(); frames = TaaClient.SSAA.renderedFrames();
    }
    private static void screenshot(Minecraft mc, String name) {
        //#if MC>=12105
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1, message -> {});
        //#elseif MC>=11700
        //$$ Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {});
        //#else
        //$$ Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, mc.getMainRenderTarget(), message -> {});
        //#endif
    }
    private void next() { phase++; ticks = 0; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
