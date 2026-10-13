package dev.antialiasing.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.antialiasing.TaaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.util.OptionalInt;

public final class SsaaRenderer implements AutoCloseable {
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("taa", path); }
    private static final RenderPipeline DOWNSAMPLE = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(id("pipeline/ssaa_downsample"))
            .withVertexShader(id("core/fullscreen")).withFragmentShader(id("core/ssaa_downsample"))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withCull(false).withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withSampler("SourceColor").withUniform("SsaaData", UniformType.UNIFORM_BUFFER).build());
    private TextureTarget high, horizontal, resolved;
    private RenderTarget nativeTarget;
    private GpuBuffer downsampleData, presentationData;
    private boolean active, ready, failed, limitExceeded;
    private int scale, renderedFrames;

    public boolean isActive() { return active; }
    public int renderedFrames() { return renderedFrames; }
    public int renderWidth() { return high == null ? 0 : high.width; }
    public int renderHeight() { return high == null ? 0 : high.height; }
    public RenderTarget sourceTarget() { return high; }
    public String statusKey() {
        if (!TaaClient.CONFIG.enabled) return "taa.status.off";
        if (limitExceeded) return "taa.ssaa.limit";
        return failed ? "taa.ssaa.failed" : "taa.ssaa.ready";
    }

    public void beginFrame() {
        SupersampleContext.end();
        active = false;
        var mc = Minecraft.getInstance();
        var config = TaaClient.CONFIG;
        if (!config.enabled || config.antialiasingMode != 1 || mc.level == null || mc.gameRenderer.isPanoramicMode()) {
            releaseTargets();
            return;
        }
        if (failed || limitExceeded) return;
        try {
            nativeTarget = mc.getMainRenderTarget();
            int width = nativeTarget.width, height = nativeTarget.height;
            int requestedScale = config.ssaaScale;
            int maximum = RenderSystem.getDevice().getMaxTextureSize();
            if (width < 1 || height < 1) return;
            if ((long) width * requestedScale > maximum || (long) height * requestedScale > maximum) {
                limitExceeded = true;
                releaseTargets();
                return;
            }
            if (!ready) {
                if (!RenderSystem.getDevice().precompilePipeline(DOWNSAMPLE).isValid()
                        || !RenderSystem.getDevice().precompilePipeline(TaaRenderer.PRESENT).isValid())
                    throw new IllegalStateException("SSAA shader compilation failed");
                ready = true;
            }
            if (high == null || resolved.width != width || resolved.height != height || scale != requestedScale) {
                releaseTargets();
                scale = requestedScale;
                high = new TextureTarget("SSAA world and hands", width * scale, height * scale, true);
                horizontal = new TextureTarget("SSAA horizontal reconstruction", width, height * scale, false);
                resolved = new TextureTarget("SSAA resolved color", width, height, false);
            }
            if (downsampleData == null) {
                downsampleData = RenderSystem.getDevice().createBuffer(() -> "SSAA filter parameters",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
                presentationData = RenderSystem.getDevice().createBuffer(() -> "SSAA presentation parameters",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 320);
            }
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(high.getColorTexture(), 0, high.getDepthTexture(), 1.0);
            SupersampleContext.begin(high);
            active = true;
        } catch (RuntimeException | OutOfMemoryError e) { fail(e); }
    }

    public void finishFrame() {
        boolean render = active;
        active = false;
        // The native target and window dimensions must be restored before HUD.
        SupersampleContext.end();
        if (!render) return;
        try {
            filter(high.getColorTextureView(), horizontal, false);
            filter(horizontal.getColorTextureView(), resolved, true);
            var config = TaaClient.CONFIG;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.calloc(320);
                data.putFloat(128, 1.0f / nativeTarget.width).putFloat(132, 1.0f / nativeTarget.height);
                data.putFloat(148, (float) config.sharpening).putFloat(156, (float) config.casAmount);
                data.putFloat(188, config.sharpeningMode);
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(presentationData.slice(), data);
            }
            TaaRenderer.present(nativeTarget, resolved.getColorTextureView(), resolved.getColorTextureView(), presentationData);
            renderedFrames++;
        } catch (RuntimeException | OutOfMemoryError e) { fail(e); }
    }

    private void filter(GpuTextureView source, RenderTarget output, boolean vertical) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(16);
            data.putFloat(0, scale).putFloat(4, vertical ? 1 : 0);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(downsampleData.slice(), data);
        }
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "SSAA Lanczos reconstruction", output.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(DOWNSAMPLE);
            //#if MC<12109
            pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            //#endif
            pass.setUniform("SsaaData", downsampleData);
            TaaRenderer.bind(pass, "SourceColor", source);
            pass.draw(0, 3);
        }
    }

    public void reset() {
        active = false;
        SupersampleContext.end();
        failed = false;
        limitExceeded = false;
        ready = false;
        releaseTargets();
    }
    private void fail(Throwable e) {
        TaaClient.LOGGER.error("SSAA disabled after rendering failure; reduce scale or reload resources to retry", e);
        failed = true;
        active = false;
        SupersampleContext.end();
        releaseTargets();
    }
    private void releaseTargets() {
        if (high != null) { high.destroyBuffers(); high = null; }
        if (horizontal != null) { horizontal.destroyBuffers(); horizontal = null; }
        if (resolved != null) { resolved.destroyBuffers(); resolved = null; }
    }
    @Override public void close() {
        reset();
        if (downsampleData != null) { downsampleData.close(); downsampleData = null; }
        if (presentationData != null) { presentationData.close(); presentationData = null; }
    }
}
