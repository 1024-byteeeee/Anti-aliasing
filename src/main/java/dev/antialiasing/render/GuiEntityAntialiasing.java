package dev.antialiasing.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.antialiasing.TaaClient;
import java.util.ArrayList;
import java.util.List;

/** Resolves vanilla's entity-only picture-in-picture textures before GUI composition. */
public final class GuiEntityAntialiasing implements AutoCloseable {
    private final List<Targets> pool = new ArrayList<>();
    private GpuBuffer uniforms;
    private int next;
    private boolean failed;

    public int scale() {
        var config = TaaClient.CONFIG;
        return config != null && config.enabled && config.antialiasingMode == 1
                && config.guiEntityAntialiasing && !failed ? config.ssaaScale : 1;
    }
    public void beginFrame() { next = 0; }

    /** Allocate the resolve targets before asking vanilla to enlarge its texture. */
    public int prepare(int width, int height) {
        int scale = scale();
        int max = RenderSystem.getDevice().getMaxTextureSize();
        if (scale == 1 || width < 1 || height < 1 || (long) width * scale > max || (long) height * scale > max) return 1;
        try {
            if (uniforms == null) {
                if (!RenderSystem.getDevice().precompilePipeline(SsaaRenderer.DOWNSAMPLE).isValid())
                    throw new IllegalStateException("GUI SSAA shader compilation failed");
                uniforms = RenderSystem.getDevice().createBuffer(() -> "GUI SSAA parameters",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
            }
            if (next == pool.size()) pool.add(new Targets());
            pool.get(next).resize(width, height, scale);
            return scale;
        } catch (RuntimeException | OutOfMemoryError e) {
            fail(e);
            return 1;
        }
    }

    public GpuTextureView resolve(GpuTextureView source, int scale) {
        if (scale == 1) return source;
        try {
            Targets targets = pool.get(next++);
            SsaaRenderer.filter(source, targets.horizontal, false, scale, uniforms, true);
            SsaaRenderer.filter(targets.horizontal.getColorTextureView(), targets.resolved, true, scale, uniforms, true);
            return targets.resolved.getColorTextureView();
        } catch (RuntimeException | OutOfMemoryError e) {
            // Do not destroy textures already queued for this GUI frame.
            fail(e);
            return source;
        }
    }
    public void fail(Throwable error) {
        if (!failed) TaaClient.LOGGER.error("GUI entity SSAA failed; using vanilla previews until reset", error);
        failed = true;
    }
    public void reset() { close(); failed = false; }
    @Override public void close() {
        for (Targets targets : pool) targets.close();
        pool.clear(); next = 0;
        if (uniforms != null) { uniforms.close(); uniforms = null; }
    }
    private static final class Targets implements AutoCloseable {
        TextureTarget horizontal, resolved;
        void resize(int width, int height, int scale) {
            if (horizontal != null && resolved != null && horizontal.width == width
                    && horizontal.height == height * scale && resolved.height == height) return;
            close();
            horizontal = new TextureTarget("GUI entity SSAA horizontal", width, height * scale, false);
            resolved = new TextureTarget("GUI entity SSAA resolved", width, height, false);
        }
        @Override public void close() {
            if (horizontal != null) { horizontal.destroyBuffers(); horizontal = null; }
            if (resolved != null) { resolved.destroyBuffers(); resolved = null; }
        }
    }
}
