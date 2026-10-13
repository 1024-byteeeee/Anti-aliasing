package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antialiasing.TaaClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;

public final class SsaaRenderer implements AutoCloseable {
    private final LegacyGraphics graphics = new LegacyGraphics();
    private RenderTarget high, horizontal, resolved, nativeTarget;
    private boolean active, failed, limitExceeded;
    private int scale, renderedFrames;
    public boolean isActive() { return active; }
    public int renderedFrames() { return renderedFrames; }
    public int renderWidth() { return high == null ? 0 : high.width; }
    public int renderHeight() { return high == null ? 0 : high.height; }
    public RenderTarget sourceTarget() { return high; }
    public String statusKey() {
        if (!TaaClient.CONFIG.enabled) return "taa.status.off";
        return limitExceeded ? "taa.ssaa.limit" : failed ? "taa.ssaa.failed" : "taa.ssaa.ready";
    }
    public void beginFrame() {
        SupersampleContext.end(); active = false;
        Minecraft mc = Minecraft.getInstance();
        if (!TaaClient.CONFIG.enabled || TaaClient.CONFIG.antialiasingMode != 1 || mc.level == null) { releaseTargets(); return; }
        //#if MC>=11700
        if (mc.gameRenderer.isPanoramicMode()) { releaseTargets(); return; }
        //#endif
        if (failed || limitExceeded) return;
        try (LegacyGraphics.State state = new LegacyGraphics.State()) {
            nativeTarget = mc.getMainRenderTarget();
            int width = nativeTarget.width, height = nativeTarget.height, requested = TaaClient.CONFIG.ssaaScale;
            int maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if (width < 1 || height < 1) return;
            if ((long) width * requested > maximum || (long) height * requested > maximum) {
                limitExceeded = true; releaseTargets(); return;
            }
            graphics.init();
            if (high == null || resolved.width != width || resolved.height != height || requested != scale) {
                releaseTargets(); scale = requested;
                high = LegacyGraphics.target(width * scale, height * scale, true);
                horizontal = LegacyGraphics.target(width, height * scale, false);
                resolved = LegacyGraphics.target(width, height, false);
            }
            high.clear(false);
            active = true;
        } catch (RuntimeException | OutOfMemoryError e) { fail(e); }
        if (active) { SupersampleContext.begin(high); high.bindWrite(true); }
    }
    public void finishFrame() {
        boolean render = active; active = false; SupersampleContext.end();
        if (!render) return;
        try (LegacyGraphics.State state = new LegacyGraphics.State(); MemoryStack stack = MemoryStack.stackPush()) {
            graphics.filter(high, horizontal, scale, false);
            graphics.filter(horizontal, resolved, scale, true);
            ByteBuffer data = stack.calloc(320);
            data.putFloat(128, 1f / nativeTarget.width).putFloat(132, 1f / nativeTarget.height);
            data.putFloat(148, (float) TaaClient.CONFIG.sharpening).putFloat(156, (float) TaaClient.CONFIG.casAmount);
            data.putFloat(188, TaaClient.CONFIG.sharpeningMode);
            graphics.upload(data); graphics.present(nativeTarget, resolved, resolved); renderedFrames++;
        } catch (RuntimeException | OutOfMemoryError e) { fail(e); }
        finally { nativeTarget.bindWrite(true); }
    }
    public void reset() { active = failed = limitExceeded = false; SupersampleContext.end(); releaseTargets(); }
    private void fail(Throwable e) {
        TaaClient.LOGGER.error("SSAA rendering failed; reduce scale or reload resources to retry", e);
        failed = true; active = false; SupersampleContext.end(); releaseTargets();
    }
    private void releaseTargets() {
        if (high != null) { high.destroyBuffers(); high = null; }
        if (horizontal != null) { horizontal.destroyBuffers(); horizontal = null; }
        if (resolved != null) { resolved.destroyBuffers(); resolved = null; }
    }
    @Override public void close() { reset(); graphics.close(); }
}
