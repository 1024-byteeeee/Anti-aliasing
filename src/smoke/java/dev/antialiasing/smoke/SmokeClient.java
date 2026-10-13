package dev.antialiasing.smoke;

import dev.antialiasing.TaaClient;
import dev.antialiasing.ui.TaaSettingsScreen;
import dev.antialiasing.ui.ModMenuIntegration;
import dev.antialiasing.config.TaaConfig;
import net.fabricmc.loader.api.FabricLoader;
import dev.antialiasing.smoke.mixin.CreateWorldAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.GameRules;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/** Development-only entrypoint. Never included in the distributable TAA jar. */
public final class SmokeClient implements ClientModInitializer {
    private int phase;
    private int ticks;
    private long start;
    private CompletableFuture<Void> reload;
    private boolean initialLanguageLoaded;
    private boolean modMenuVerified;
    private int ssaaFramesBefore;
    private static int observedSsaaWidth, observedSsaaHeight;
    private static boolean capturedSsaaSource;
    public static void observeSsaaWorld() {
        if (!Boolean.getBoolean("taa.smoke") || !TaaClient.SSAA.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        if (target.width != mc.getWindow().getWidth() || target.height != mc.getWindow().getHeight())
            throw new IllegalStateException("SSAA framebuffer/window dimensions disagree during 3D rendering");
        observedSsaaWidth = target.width; observedSsaaHeight = target.height;
        var outline = mc.levelRenderer.entityOutlineTarget();
        if (outline != null && (outline.width != target.width || outline.height != target.height))
            throw new IllegalStateException("Entity outline was not supersampled");
        if (!capturedSsaaSource && TaaClient.CONFIG.ssaaScale == 2) {
            capturedSsaaSource = true;
            Screenshot.grab(mc.gameDirectory, "ssaa-source-4x.png", target, 1, message -> {});
        }
    }
    private void setSsaa(int scale) {
        TaaClient.CONFIG.antialiasingMode = 1;
        TaaClient.CONFIG.ssaaScale = scale;
        TaaClient.CONFIG.enabled = true;
        TaaClient.SSAA.reset();
        ssaaFramesBefore = TaaClient.SSAA.renderedFrames();
    }
    private void verifySsaa(Minecraft mc, String name) {
        require(TaaClient.SSAA.renderedFrames() > ssaaFramesBefore && TaaClient.SSAA.statusKey().equals("taa.ssaa.ready"), "SSAA did not render: " + TaaClient.SSAA.statusKey());
        require(!TaaClient.SSAA.isActive() && dev.antialiasing.render.SupersampleContext.target() == null, "SSAA leaked into HUD");
        require(observedSsaaWidth == mc.getMainRenderTarget().width * TaaClient.CONFIG.ssaaScale
                        && observedSsaaHeight == mc.getMainRenderTarget().height * TaaClient.CONFIG.ssaaScale, "SSAA render scale is incorrect");
        require(mc.getWindow().getWidth() == mc.getMainRenderTarget().width
                        && mc.getWindow().getHeight() == mc.getMainRenderTarget().height, "Native presentation size changed");
        require(!TaaClient.RENDERER.hasValidHistory(), "SSAA retained TAA history");
        TaaClient.LOGGER.info("SSAA_SMOKE_RESOLUTION {} source={}x{} output={}x{}", name, observedSsaaWidth, observedSsaaHeight,
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
        screenshot(mc, name);
    }
    private final java.util.List<int[]> stabilityFrames = new java.util.ArrayList<>();
    private boolean capturePending;
    private boolean stabilityReported;
    private int settledTicks;
    private int glassStage;
    private int glassFrames;
    private double glassContrast;
    private final double[] glassContrasts = new double[4];
    private final double[] glassSums = new double[132 * 62 * 3];
    private final double[] glassSquares = new double[132 * 62 * 3];
    private double glassTemporalRms;

    private boolean glassQuality(Minecraft mc) throws Exception {
        if (!Boolean.getBoolean("taa.glassQuality") || glassStage == 4) return false;
        if (ticks % 100 == 0) TaaClient.LOGGER.info("TAA_GLASS_PROGRESS stage={} ticks={} captures={} pending={} chunksReady={} iconified={}",
                glassStage, ticks, glassFrames, capturePending, mc.levelRenderer.hasRenderedAllSections(),
                GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_ICONIFIED));
        if (glassStage == 0 && ticks < 100) {
            TaaClient.CONFIG.enabled = false;
            return true;
        }
        int delay = glassStage == 2 ? 400 : 100;
        boolean fixtureReady = glassStage == 3
                ? mc.level.getBlockState(new net.minecraft.core.BlockPos(0, 104, -48)).isAir()
                : mc.level.getBlockState(new net.minecraft.core.BlockPos(0, 104, -48)).is(net.minecraft.world.level.block.Blocks.GLASS);
        if (ticks < delay || !fixtureReady) return true;
        if (glassFrames < 16) {
            if (!capturePending) {
                capturePending = true;
                Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                    try (image) {
                        if (glassFrames == 0) image.writeToFile(mc.gameDirectory.toPath().resolve("glass-" + glassStage + ".png"));
                        double contrast = 0;
                        // Floating glass wall against sky; use adjacent sky on each
                        // row so sky gradients do not count as visible glass detail.
                        for (int y = 230; y < 292; y++) for (int x = 575; x < 707; x++) {
                            int pixel = image.getPixel(x, y);
                            int left = image.getPixel(560, y), right = image.getPixel(722, y);
                            for (int shift : new int[] {0, 8, 16}) {
                                double background = (((left >>> shift) & 255) + ((right >>> shift) & 255)) * .5;
                                contrast += Math.abs(((pixel >>> shift) & 255) - background);
                                if (glassStage == 2) {
                                    int index = ((y - 230) * 132 + x - 575) * 3 + shift / 8;
                                    double value = (pixel >>> shift) & 255;
                                    glassSums[index] += value;
                                    glassSquares[index] += value * value;
                                }
                            }
                        }
                        glassContrast += contrast / (132 * 62 * 3);
                        glassFrames++;
                    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    finally { capturePending = false; }
                });
            }
            return true;
        }
        glassContrasts[glassStage] = glassContrast / glassFrames;
        if (glassStage == 2) {
            double variance = 0;
            for (int i = 0; i < glassSums.length; i++)
                variance += glassSquares[i] / glassFrames - Math.pow(glassSums[i] / glassFrames, 2);
            glassTemporalRms = Math.sqrt(Math.max(0, variance / glassSums.length));
        }
        glassStage++;
        glassFrames = 0; glassContrast = 0; ticks = 0;
        if (glassStage == 1) { TaaClient.CONFIG.enabled = true; TaaClient.RENDERER.invalidate(); }
        if (glassStage == 3) {
            mc.player.connection.sendCommand("fill -8 104 -48 8 111 -48 air");
        }
        if (glassStage == 4) {
            String report = "{\"offContrast\":" + glassContrasts[0] + ",\"earlyContrast\":" + glassContrasts[1]
                    + ",\"lateContrast\":" + glassContrasts[2] + ",\"removedContrast\":" + glassContrasts[3]
                    + ",\"lateTemporalRms8bit\":" + glassTemporalRms + "}";
            Files.writeString(mc.gameDirectory.toPath().resolve("glass-quality.json"), report);
            require(glassContrasts[0] > 2, "Glass fixture did not render: " + report);
            require(glassContrasts[2] >= glassContrasts[0] * .8,
                    "Distant glass lost visibility: " + report);
            require(glassContrasts[2] <= glassContrasts[0] * 1.3,
                    "Distant glass edges became too strong: " + report);
            require(glassContrasts[2] >= glassContrasts[1] * .9,
                    "Distant glass faded during prolonged accumulation: " + report);
            require(glassContrasts[3] < .5, "Removed glass left persistent history: " + report);
            require(Double.isFinite(glassTemporalRms) && glassTemporalRms < .8,
                    "Distant glass shimmer regression: " + report);
            TaaClient.LOGGER.info("TAA_GLASS_QUALITY {}", report);
            // Restore the scene and continue the existing full client regression.
            mc.player.connection.sendCommand("fill -8 104 -48 8 111 -48 glass");
        }
        return true;
    }
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("taa.smoke")) return;
        start = System.nanoTime();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try { tick(mc); }
            catch (Throwable e) {
                TaaClient.LOGGER.error("TAA_SMOKE_FAILED", e);
                try { Files.writeString(mc.gameDirectory.toPath().resolve("smoke-failed.txt"), e.toString()); } catch (Exception ignored) {}
                mc.stop();
            }
        });
    }
    private void next() { phase++; ticks = 0; }
    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private void screenshot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1,
                message -> TaaClient.LOGGER.info("TAA_SMOKE_SCREENSHOT {} {}", name, message.getString()));
    }
    private java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets(
            net.minecraft.client.gui.components.events.GuiEventListener root) {
        var result = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        if (root instanceof net.minecraft.client.gui.components.AbstractWidget widget) result.add(widget);
        if (root instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container)
            for (var child : container.children()) result.addAll(widgets(child));
        return result;
    }
    private void verifyUi(Minecraft mc) {
        var controls = widgets(mc.screen);
        var settings = mc.screen;
        int count = controls.size();
        var toggle = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button
                        && w.getMessage().getString().equals(net.minecraft.client.resources.language.I18n.get("taa.enabled", net.minecraft.client.resources.language.I18n.get("options.on"))))
                .map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
        var reset = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button
                        && w.getMessage().getString().equals(net.minecraft.client.resources.language.I18n.get("taa.reset")))
                .map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
        for (int i = 0; i < 20; i++) {
            toggle.onPress(); reset.onPress();
            TaaClient.openSettings(mc.screen);
            require(mc.screen == settings && widgets(mc.screen).size() == count, "Repeated button/open duplicated the UI");
        }
        require(TaaSettingsScreen.forParent(settings) == settings, "Mod Menu nested the settings screen");
        var motion = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button
                        && w.getMessage().getString().equals(net.minecraft.client.resources.language.I18n.get("taa.motion", net.minecraft.client.resources.language.I18n.get("options.on"))))
                .map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
        motion.onPress();
        require(!TaaClient.CONFIG.reduceHistoryOnMotion, "Motion reduction toggle did not disable");
        TaaClient.CONFIG.historyWeight = 1.0;
        TaaClient.CONFIG.sanitize();
        require(TaaClient.CONFIG.historyWeight == 1.0 && TaaClient.CONFIG.save(), "100% history could not be saved");
        var fullHistory = TaaConfig.load();
        require(fullHistory.historyWeight == 1.0 && !fullHistory.reduceHistoryOnMotion, "100% history/motion flag did not round trip");
        reset.onPress();
        require(TaaClient.CONFIG.historyWeight == 0.97 && TaaClient.CONFIG.reduceHistoryOnMotion, "New defaults did not restore");
        motion.onPress(); // Exercise fixed history weight during the following hand/motion phases.
        TaaClient.CONFIG.historyWeight = 1.0;
        var input = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                .map(w -> (net.minecraft.client.gui.components.EditBox) w).findFirst().orElseThrow();
        var apply = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button
                        && w.getMessage().getString().equals(net.minecraft.client.resources.language.I18n.get("taa.samples.apply")))
                .map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
        for (int cycle : new int[] {1, 7, 13, 64, 256}) {
            input.setValue(Integer.toString(cycle)); apply.onPress();
            require(TaaClient.CONFIG.sampleCount == cycle, "Custom sample count did not apply");
        }
        for (String invalid : new String[] {"", "0", "257", "999"}) {
            input.setValue(invalid); apply.onPress();
            require(TaaClient.CONFIG.sampleCount == 256, "Invalid sample count was accepted");
        }
        input.setValue("17"); apply.onPress();
        var mode = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button
                        && w.getMessage().getString().contains("Unsharp"))
                .map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
        var slider = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.AbstractSliderButton
                        && w.getWidth() == 150).findFirst().orElseThrow();
        TaaClient.CONFIG.casAmount = 0.1;
        mode.onPress();
        require(TaaClient.CONFIG.sharpeningMode == 2 && slider.getMessage().getString().contains("0.10"), "CAS minimum UI mismatch");
        mode.onPress(); require(!slider.active, "Sharpening slider should be disabled when sharpening is off");
        mode.onPress(); require(slider.active && slider.getMessage().getString().contains("0.20"), "Unsharp slider did not restore its value");
        TaaClient.CONFIG.casAmount = 1;
        TaaClient.LOGGER.info("TAA_SMOKE_UI_INTERACTION_PASSED");
    }
    private void tick(Minecraft mc) throws Exception {
        if (System.nanoTime() - start > 240_000_000_000L) throw new IllegalStateException("Smoke test timed out at phase " + phase);
        ticks++;
        if (mc.getOverlay() != null) return;
        switch (phase) {
            case -2 -> {
                if (ticks > 15) {
                    screenshot(mc, "modmenu-taa-settings");
                    mc.screen.onClose();
                    require(mc.screen instanceof com.terraformersmc.modmenu.gui.ModsScreen, "TAA did not return to Mod Menu");
                    phase = -1; ticks = 0;
                }
            }
            case -1 -> {
                if (ticks > 15) {
                    screenshot(mc, "modmenu-list");
                    mc.screen.onClose();
                    require(mc.screen instanceof TitleScreen, "Mod Menu did not return to title");
                    modMenuVerified = true;
                    TaaClient.LOGGER.info("TAA_SMOKE_MODMENU_REGISTERED_OPEN_CLOSE_PASSED");
                    phase = 0; ticks = 0;
                }
            }
            case 0 -> {
                if (mc.screen instanceof TitleScreen && ticks > 20) {
                    if (!initialLanguageLoaded) {
                        initialLanguageLoaded = true;
                        mc.options.languageCode = "en_us";
                        mc.getLanguageManager().setSelected("en_us");
                        mc.reloadResourcePacks();
                        return;
                    }
                    mc.options.renderDistance().set(6);
                    mc.options.simulationDistance().set(5);
                    mc.options.enableVsync().set(false);
                    mc.options.framerateLimit().set(120);
                    mc.options.pauseOnLostFocus = false;
                    TaaClient.CONFIG.resetTemporalValues();
                    TaaClient.CONFIG.sharpeningMode = 1;
                    TaaClient.CONFIG.antialiasingMode = 0;
                    if (FabricLoader.getInstance().isModLoaded("modmenu")) {
                        if (!modMenuVerified) {
                            require(com.terraformersmc.modmenu.ModMenu.hasConfigScreen("taa"), "TAA config was not registered with Mod Menu");
                            var mods = new com.terraformersmc.modmenu.gui.ModsScreen(mc.screen);
                            mc.setScreen(mods);
                            require(mods.getModHasConfigScreen("taa"), "Mod list did not expose TAA config");
                            mods.safelyOpenConfigScreen("taa");
                            require(mc.screen instanceof TaaSettingsScreen, "Mod Menu could not open TAA settings");
                            phase = -2; ticks = 0;
                            return;
                        }
                        require(new ModMenuIntegration().getModConfigScreenFactory().create(mc.screen) instanceof TaaSettingsScreen,
                                "Mod Menu factory failed");
                        TaaClient.LOGGER.info("TAA_SMOKE_MODMENU_PASSED");
                    }
                    mc.setScreen(new VideoSettingsScreen(mc.screen, mc, mc.options));
                    next();
                }
            }
            case 1 -> { if (ticks > 10) { screenshot(mc, "video-settings"); mc.setScreen(new TaaSettingsScreen(mc.screen)); next(); } }
            case 2 -> { if (ticks > 15) {
                var settings = mc.screen;
                for (int i = 0; i < 20; i++) TaaClient.openSettings(mc.screen);
                require(mc.screen == settings, "Repeated opening nested settings");
                settings.onClose();
                require(mc.screen instanceof VideoSettingsScreen, "Settings required multiple closes");
                TaaClient.openSettings(mc.screen);
                GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 0, 0); screenshot(mc, "taa-settings");
                CreateWorldScreen.testWorld(mc, new TitleScreen()); next();
            } }
            case 3 -> {
                if (mc.screen instanceof CreateWorldScreen screen) {
                    screen.getUiState().setName("TAA Smoke " + System.currentTimeMillis());
                    screen.getUiState().setAllowCommands(true);
                    screen.getUiState().getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
                    ((CreateWorldAccessor) screen).taaSmoke$create(); next();
                }
            }
            case 4 -> {
                if (mc.level != null && mc.player != null && ticks > 40) {
                    mc.setScreen(null);
                    mc.player.connection.sendCommand("fill -16 100 -16 16 100 16 stone");
                    for (int i = -10; i <= 10; i++) mc.player.connection.sendCommand("setblock " + i + " 101 " + i + " oak_fence");
                    mc.player.connection.sendCommand("fill -8 101 -6 -4 104 -2 oak_leaves[persistent=true]");
                    mc.player.connection.sendCommand("fill 4 101 -6 8 103 -2 glass");
                    // Thin transparent texture details at roughly 65 blocks.
                    mc.player.connection.sendCommand("fill -8 104 -48 8 111 -48 glass");
                    mc.player.connection.sendCommand("fill -3 101 3 3 101 7 water");
                    mc.player.connection.sendCommand("summon cow 5 101 6");
                    mc.player.connection.sendCommand("gamemode spectator");
                    mc.player.connection.sendCommand("tp @s 0 106 18 180 12");
                    mc.player.connection.sendCommand("time set noon");
                    if (Boolean.getBoolean("taa.stability")) {
                        mc.player.connection.sendCommand("tick freeze");
                        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                        mc.options.bobView().set(false);
                    }
                    mc.options.hideGui = true;
                    next();
                }
            }
            case 5 -> {
                if (Boolean.getBoolean("taa.stability") && !stabilityReported && mc.player != null) {
                    // Keep external keyboard/mouse input and lighting commands out
                    // of the static quality measurement in this isolated client.
                    mc.options.keyUp.setDown(false);
                    mc.options.keyDown.setDown(false);
                    mc.options.keyLeft.setDown(false);
                    mc.options.keyRight.setDown(false);
                    mc.options.keyJump.setDown(false);
                    mc.options.keyShift.setDown(false);
                    mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    mc.player.setPos(0.5, 106, 18.5);
                    mc.player.xo = mc.player.xOld = 0.5;
                    mc.player.yo = mc.player.yOld = 106;
                    mc.player.zo = mc.player.zOld = 18.5;
                    mc.player.setYRot(180);
                    mc.player.yRotO = 180;
                    mc.player.setXRot(12);
                    mc.player.xRotO = 12;
                    mc.level.setTimeFromServer(mc.level.getGameTime(), 6000, false);
                }
                if (glassQuality(mc)) return;
                if (ticks > 60) {
                    if (Boolean.getBoolean("taa.stability") && !stabilityReported) {
                        if (!mc.levelRenderer.hasRenderedAllSections()) { settledTicks = 0; return; }
                        if (++settledTicks < 20) return;
                        if (stabilityFrames.size() < 32) {
                            if (!capturePending) {
                                capturePending = true;
                                Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                                    try (image) {
                                        if (stabilityFrames.isEmpty() || stabilityFrames.size() == 31) {
                                            try { image.writeToFile(mc.gameDirectory.toPath().resolve(stabilityFrames.isEmpty() ? "stability-first.png" : "stability-last.png")); }
                                            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                                            TaaClient.LOGGER.info("TAA_STABILITY_FRAME samples={} enabled={} history={} camera={}",
                                                    TaaClient.CONFIG.sampleCount, TaaClient.CONFIG.enabled, TaaClient.RENDERER.hasValidHistory(), mc.gameRenderer.getMainCamera().getPosition());
                                        }
                                        int[] roi = new int[1120 * 100];
                                        int index = 0;
                                        for (int y = 280; y < 380; y++) for (int x = 80; x < 1200; x++)
                                            roi[index++] = image.getPixel(x, y);
                                        stabilityFrames.add(roi);
                                    } finally { capturePending = false; }
                                });
                            }
                            return;
                        }
                        double variance = 0;
                        int pixels = stabilityFrames.getFirst().length;
                        for (int p = 0; p < pixels; p++) for (int shift : new int[] {0, 8, 16}) {
                            double sum = 0, square = 0;
                            for (int[] frame : stabilityFrames) {
                                int v = (frame[p] >>> shift) & 255;
                                sum += v; square += v * v;
                            }
                            variance += square / stabilityFrames.size() - Math.pow(sum / stabilityFrames.size(), 2);
                        }
                        double rms = Math.sqrt(variance / (pixels * 3));
                        String result = "{\"frames\":32,\"roi\":\"80,280,1120,100\",\"temporalRms8bit\":" + rms + "}";
                        Files.writeString(mc.gameDirectory.toPath().resolve(Boolean.getBoolean("taa.referenceShader") ? "reference-stability.json" : "stability.json"), result);
                        TaaClient.LOGGER.info("TAA_STABILITY {}", result);
                        require(Double.isFinite(rms) && (Boolean.getBoolean("taa.referenceShader") || rms < 0.8),
                                "Static-frame shimmer regression: temporal RMS " + rms + " (limit 0.8)");
                        stabilityReported = true;
                    }
                    if (Boolean.getBoolean("taa.referenceShader")) {
                        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                        Object iris = api.getMethod("getInstance").invoke(null);
                        require((boolean) api.getMethod("isShaderPackInUse").invoke(iris), "Reference shader pack did not activate");
                        screenshot(mc, "reference-on");
                        Files.writeString(mc.gameDirectory.toPath().resolve("smoke-passed.txt"), "Reference shader pack activated and captured.");
                        TaaClient.LOGGER.info("TAA_REFERENCE_CAPTURE_PASSED");
                        phase = 99; ticks = 0;
                        return;
                    }
                    require(TaaClient.RENDERER.hasValidHistory(), "TAA did not accumulate history: " + TaaClient.RENDERER.statusKey());
                    TaaClient.LOGGER.info("TAA_SMOKE_CAMERA player={} camera={} eyeBlock={}", mc.player.position(),
                            mc.gameRenderer.getMainCamera().getPosition(), mc.level.getBlockState(mc.gameRenderer.getMainCamera().getBlockPosition()));
                    require(mc.level.getBlockState(mc.gameRenderer.getMainCamera().getBlockPosition()).isAir(), "Test camera is inside a block");
                    screenshot(mc, "taa-on");
                    TaaClient.CONFIG.enabled = false; next();
                }
            }
            case 6 -> {
                if (ticks > 20) {
                    require(!TaaClient.RENDERER.hasValidHistory(), "Disabled TAA retains history");
                    screenshot(mc, "taa-off");
                    TaaClient.CONFIG.enabled = true;
                    TaaClient.RENDERER.invalidate(); next();
                }
            }
            case 7 -> {
                if (ticks > 25) {
                    require(TaaClient.RENDERER.hasValidHistory(), "Re-enabled TAA failed");
                    TaaClient.CONFIG.sharpeningMode = 2;
                    mc.player.setYRot(mc.player.getYRot() + 30);
                    next();
                }
            }
            case 8 -> {
                if (ticks > 25) {
                    require(TaaClient.RENDERER.hasValidHistory(), "Camera rotation broke history");
                    screenshot(mc, "reference-mode-cas");
                    TaaClient.CONFIG.sharpeningMode = 1;
                    TaaClient.CONFIG.debugView = 3; next();
                }
            }
            case 9 -> {
                if (ticks > 20) {
                    screenshot(mc, "history-contribution");
                    TaaClient.CONFIG.debugView = 0;
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 540);
                    next();
                }
            }
            case 10 -> {
                if (ticks > 30) {
                    require(TaaClient.RENDERER.hasValidHistory(), "Resize broke TAA");
                    screenshot(mc, "resized");
                    reload = mc.reloadResourcePacks(); next();
                }
            }
            case 11 -> {
                if (reload != null && reload.isDone() && ticks > 40) {
                    reload.join();
                    mc.options.hideGui = false;
                    require(TaaClient.RENDERER.hasValidHistory(), "Resource reload broke TAA");
                    mc.options.languageCode = "zh_cn";
                    mc.getLanguageManager().setSelected("zh_cn");
                    reload = mc.reloadResourcePacks(); next();
                }
            }
            case 12 -> {
                if (reload.isDone() && ticks > 40) {
                    reload.join();
                    mc.setScreen(new TaaSettingsScreen(null)); next();
                    GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 0, 0);
                }
            }
            case 13 -> {
                if (ticks > 20) {
                    screenshot(mc, "taa-settings-zh");
                    verifyUi(mc);
                    mc.screen.resize(mc, mc.screen.width, mc.screen.height);
                    require(widgets(mc.screen).stream().filter(w -> w instanceof net.minecraft.client.gui.components.EditBox).count() == 1,
                            "Screen resize duplicated controls");
                    mc.setScreen(null);
                    mc.player.connection.sendCommand("tick unfreeze");
                    mc.player.connection.sendCommand("gamemode creative");
                    mc.player.connection.sendCommand("item replace entity @s weapon.mainhand with diamond_sword");
                    mc.player.connection.sendCommand("item replace entity @s weapon.offhand with shield");
                    TaaClient.RENDERER.invalidate(); next();
                }
            }
            case 14 -> {
                if (ticks > 20) {
                    require(TaaClient.RENDERER.hasValidHistory(), "First-person TAA failed");
                    mc.player.getAbilities().flying = true;
                    mc.player.onUpdateAbilities();
                    screenshot(mc, "hands-taa-on");
                    TaaClient.CONFIG.enabled = false; TaaClient.RENDERER.invalidate(); next();
                }
            }
            case 15 -> {
                if (ticks > 20) {
                    require(!TaaClient.RENDERER.hasValidHistory(), "Disabled first-person TAA retained history");
                    screenshot(mc, "hands-taa-off");
                    TaaClient.CONFIG.enabled = true; TaaClient.RENDERER.invalidate();
                    mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND); next();
                }
            }
            case 16 -> {
                if (ticks > 20) {
                    require(TaaClient.RENDERER.hasValidHistory(), "First-person TAA failed after swing");
                    screenshot(mc, "hands-swing");
                    TaaClient.CONFIG.debugView = 0; TaaClient.RENDERER.invalidate(); next();
                }
            }
            case 17 -> {
                if (ticks > 20) {
                    require(TaaClient.RENDERER.hasValidHistory(), "Final TAA history failed");
                    TaaClient.CONFIG.resetTemporalValues();
                    require(TaaClient.CONFIG.save(), "Config save failed");
                    TaaConfig saved = TaaConfig.load();
                    require(saved.historyWeight == 0.97 && saved.reduceHistoryOnMotion && saved.sharpening == 0.2 && saved.enabled,
                            "Reference config round trip failed");
                    require(!Files.readString(mc.gameDirectory.toPath().resolve("config/taa.json")).contains("referenceMode"), "Legacy profile remained in config");
                    mc.player.connection.sendCommand("tp @s 0 106 18 180 12");
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1280, 720);
                    setSsaa(2);
                    phase = 30; ticks = 0;
                }
            }
            case 18 -> {
                if (ticks == 10) mc.getSingleplayerServer().execute(() ->
                        mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().setRemainingFireTicks(200));
                if (ticks > 25) {
                    require(mc.player.isOnFire() && TaaClient.RENDERER.hasValidHistory(), "Fire overlay/TAA test failed");
                    screenshot(mc, "taa-fire");
                    mc.player.connection.sendCommand("fill -2 101 12 2 105 16 water");
                    mc.player.connection.sendCommand("tp @s 0 101 14 180 12");
                    next();
                }
            }
            case 19 -> {
                if (ticks > 30) {
                    require(mc.player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && TaaClient.RENDERER.hasValidHistory(), "Underwater overlay/TAA test failed");
                    screenshot(mc, "taa-underwater");
                    mc.player.connection.sendCommand("tp @s 0 106 18 180 12");
                    mc.getSingleplayerServer().execute(() -> {
                        var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                        player.level().broadcastEntityEvent(player, (byte) 35);
                    });
                    next();
                }
            }
            case 20 -> {
                if (ticks > 12) {
                    require(TaaClient.RENDERER.hasValidHistory(), "Item activation TAA failed");
                    screenshot(mc, "taa-item-activation");
                    TaaClient.CONFIG.debugView = 1; TaaClient.RENDERER.invalidate(); next();
                }
            }
            case 21 -> {
                if (ticks > 20) {
                    require(!TaaClient.RENDERER.hasValidHistory(), "Current-frame debug retained history");
                    TaaClient.CONFIG.debugView = 2; TaaClient.RENDERER.invalidate(); next();
                }
            }
            case 22 -> {
                if (ticks > 20) {
                    require(TaaClient.RENDERER.hasValidHistory(), "Previous-history debug failed");
                    TaaClient.CONFIG.debugView = 0; TaaClient.CONFIG.save();
                    Files.writeString(mc.gameDirectory.toPath().resolve("smoke-passed.txt"),
                            "World/hand TAA, SSAA 4x/9x/16x, native HUD, SSAA disabled/resize/reload/Fabulous/mode switching, UI, Mod Menu, effects and config passed.");
                    TaaClient.LOGGER.info("TAA_SMOKE_PASSED"); next();
                }
            }
            case 23, 99 -> { if (ticks > 30) mc.stop(); }
            case 30 -> {
                if (ticks > 25) { verifySsaa(mc, "ssaa-4x-hands"); setSsaa(3); next(); }
            }
            case 31 -> {
                if (ticks > 20) { verifySsaa(mc, "ssaa-9x-hands"); setSsaa(4); next(); }
            }
            case 32 -> {
                if (ticks > 20) {
                    verifySsaa(mc, "ssaa-16x-hands");
                    TaaClient.CONFIG.enabled = false; next();
                }
            }
            case 33 -> {
                if (ticks > 15) {
                    require(!TaaClient.SSAA.isActive() && TaaClient.SSAA.renderWidth() == 0, "Disabled SSAA retained high-resolution buffers");
                    screenshot(mc, "ssaa-disabled");
                    setSsaa(2);
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 540); next();
                }
            }
            case 34 -> {
                if (ticks > 20) {
                    verifySsaa(mc, "ssaa-resized");
                    reload = mc.reloadResourcePacks(); next();
                }
            }
            case 35 -> {
                if (reload.isDone() && ticks > 30) {
                    reload.join(); verifySsaa(mc, "ssaa-reloaded");
                    mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FABULOUS); next();
                }
            }
            case 36 -> {
                if (ticks > 25) {
                    verifySsaa(mc, "ssaa-fabulous");
                    mc.setScreen(new TaaSettingsScreen(null)); next();
                }
            }
            case 37 -> {
                if (ticks > 15) {
                    screenshot(mc, "ssaa-settings-zh");
                    var controls = widgets(mc.screen);
                    var input = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.EditBox)
                            .map(w -> (net.minecraft.client.gui.components.EditBox) w).findFirst().orElseThrow();
                    require(!input.active, "TAA sample input remains active in SSAA");
                    int count = controls.size();
                    var algorithm = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button && w.getMessage().getString().contains("SSAA") && !w.getMessage().getString().contains("×")).map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
                    for (int i = 0; i < 20; i++) { algorithm.onPress(); algorithm.onPress(); }
                    require(widgets(mc.screen).size() == count && !input.active, "SSAA mode switching duplicated controls or enabled TAA input");
                    var factor = controls.stream().filter(w -> w instanceof net.minecraft.client.gui.components.Button && w.getMessage().getString().contains("×")).map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
                    factor.onPress(); require(TaaClient.CONFIG.ssaaScale == 3, "SSAA 9x control failed");
                    factor.onPress(); require(TaaClient.CONFIG.ssaaScale == 4, "SSAA 16x control failed");
                    factor.onPress(); require(TaaClient.CONFIG.ssaaScale == 2, "SSAA 4x control failed");
                    input.setValue("");
                    mc.screen.onClose(); require(mc.screen == null, "Disabled TAA input blocked closing SSAA settings");
                    var saved = TaaConfig.load();
                    require(saved.antialiasingMode == 1 && saved.ssaaScale == 2, "SSAA config did not round trip");
                    mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                    TaaClient.CONFIG.antialiasingMode = 0; TaaClient.RENDERER.invalidate(); next();
                }
            }
            case 38 -> {
                if (ticks > 25) {
                    require(TaaClient.RENDERER.hasValidHistory() && TaaClient.SSAA.renderWidth() == 0, "Switching SSAA back to TAA failed");
                    TaaClient.CONFIG.save();
                    TaaClient.LOGGER.info("SSAA_SMOKE_PASSED");
                    mc.player.connection.sendCommand("gamemode survival");
                    phase = 18; ticks = 0;
                }
            }
            default -> {}
        }
    }
}
