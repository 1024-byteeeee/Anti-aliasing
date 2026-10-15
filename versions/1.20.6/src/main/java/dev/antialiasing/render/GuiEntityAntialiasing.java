package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antialiasing.TaaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Crops the existing GUI projection using a shifted viewport; GUI coordinates stay unchanged. */
public final class GuiEntityAntialiasing implements AutoCloseable {
    private final LegacyGraphics graphics = new LegacyGraphics();
    private RenderTarget high, horizontal, resolved;
    private boolean failed, capturing;

    public void render(float x, float y, float size, LivingEntity entity, Runnable draw) {
        if (TaaClient.CONFIG == null || !TaaClient.CONFIG.enabled || TaaClient.CONFIG.antialiasingMode != 1
                || !TaaClient.CONFIG.guiEntityAntialiasing || failed || capturing) { draw.run(); return; }
        Minecraft mc = Minecraft.getInstance();
        double guiScale = mc.getWindow().getGuiScale();
        int factor = TaaClient.CONFIG.ssaaScale;
        int width = mc.getMainRenderTarget().width, height = mc.getMainRenderTarget().height;
        int[] viewport = new int[4], scissor = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        // Leave room for rotated equipment when an older caller supplies no clipping rectangle.
        double radius = size * Math.max(2, entity.getBbWidth()) * 1.5 + 8;
        double verticalRadius = size * Math.max(2, entity.getBbHeight()) * 1.5 + 8;
        int left = Math.max(0, (int) Math.floor((x - radius) * guiScale));
        int right = Math.min(width, (int) Math.ceil((x + radius) * guiScale));
        int bottom = Math.max(0, height - (int) Math.ceil((y + verticalRadius) * guiScale));
        int top = Math.min(height, height - (int) Math.floor((y - verticalRadius) * guiScale));
        if (clipped) {
            left = Math.max(left, scissor[0]); right = Math.min(right, scissor[0] + scissor[2]);
            bottom = Math.max(bottom, scissor[1]); top = Math.min(top, scissor[1] + scissor[3]);
        }
        int w = right - left, h = top - bottom;
        int max = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (w <= 0 || h <= 0 || (long) w * factor > max || (long) h * factor > max) { draw.run(); return; }
        try (LegacyGraphics.State state = new LegacyGraphics.State()) {
            graphics.initGui();
            if (high == null || resolved == null || high.width != w * factor || high.height != h * factor
                    || resolved.width != w || resolved.height != h) {
                releaseTargets();
                high = LegacyGraphics.target(w * factor, h * factor, true);
                horizontal = LegacyGraphics.target(w, h * factor, false);
                resolved = LegacyGraphics.target(w, h, false);
            }
            // Clear the full texture, even when the caller has an active GUI scissor.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            high.clear(false);
        } catch (RuntimeException | OutOfMemoryError error) {
            fail(error); draw.run(); return;
        }
        capturing = true;
        try {
            high.bindWrite(false);
            GL11.glViewport(-left * factor, -bottom * factor, width * factor, height * factor);
            if (clipped) GL11.glScissor((scissor[0] - left) * factor, (scissor[1] - bottom) * factor, scissor[2] * factor, scissor[3] * factor);
            draw.run();
            try (LegacyGraphics.State state = new LegacyGraphics.State()) {
                graphics.filter(high, horizontal, factor, false, true);
                graphics.filter(horizontal, resolved, factor, true, true);
                graphics.compositeGui(drawFramebuffer, resolved, left, bottom);
            } catch (RuntimeException | OutOfMemoryError error) {
                fail(error);
                restore(drawFramebuffer, readFramebuffer, viewport, scissor);
                draw.run();
            }
        } finally {
            restore(drawFramebuffer, readFramebuffer, viewport, scissor);
            capturing = false;
        }
    }
    private static void restore(int draw, int read, int[] viewport, int[] scissor) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
    }
    private void fail(Throwable error) {
        if (!failed) TaaClient.LOGGER.error("GUI entity SSAA failed; using vanilla previews until reset", error);
        failed = true;
    }
    public void reset() { close(); failed = false; }
    private void releaseTargets() {
        if (high != null) { high.destroyBuffers(); high = null; }
        if (horizontal != null) { horizontal.destroyBuffers(); horizontal = null; }
        if (resolved != null) { resolved.destroyBuffers(); resolved = null; }
    }
    @Override public void close() { releaseTargets(); graphics.close(); }
}
