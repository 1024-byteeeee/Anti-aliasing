package dev.antialiasing.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.antialiasing.TaaClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.OptionalDouble;

public final class TaaRenderer implements AutoCloseable {
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("taa", path); }
    private static RenderPipeline.Builder fullscreen(String name) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader(id("core/fullscreen"))
                .withFragmentShader(id("core/" + name))
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withCull(false).withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withUniform("TaaData", UniformType.UNIFORM_BUFFER);
    }
    private static final RenderPipeline RESOLVE = RenderPipelines.register(fullscreen("resolve")
            .withDepthWrite(true).withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withSampler("CurrentColor").withSampler("CurrentDepth")
            .withSampler("HistoryColor").withSampler("HistoryResidual").withSampler("HistoryDepth").withSampler("WorldDepth").build());
    private static final RenderPipeline RESIDUAL = RenderPipelines.register(fullscreen("resolve_residual")
            .withFragmentShader(id("core/resolve")).withShaderDefine("RESIDUAL_PASS")
            .withSampler("CurrentColor").withSampler("CurrentDepth")
            .withSampler("HistoryColor").withSampler("HistoryResidual").withSampler("HistoryDepth").withSampler("WorldDepth").build());
    static final RenderPipeline PRESENT = RenderPipelines.register(fullscreen("present")
            .withSampler("ResolvedColor").withSampler("HistoryColor").build());
    private final TextureTarget[] history = new TextureTarget[2];
    private final TextureTarget[] residual = new TextureTarget[2];
    private GpuBuffer uniforms;
    private GpuTexture worldDepth;
    private GpuTextureView worldDepthView;
    private PerspectiveProjectionMatrixBuffer handProjectionBuffer;
    private final Matrix4f handVP = new Matrix4f();
    private final Matrix4f previousHandVP = new Matrix4f();
    private final Matrix4f inverseHandVP = new Matrix4f();
    private final Matrix4f handRaster = new Matrix4f();
    private boolean worldDepthSaved;
    private boolean handCaptured;
    private final Matrix4f currentVP = new Matrix4f();
    private final Matrix4f previousVP = new Matrix4f();
    private final Matrix4f inverseVP = new Matrix4f();
    private final Matrix4f previousFromCurrent = new Matrix4f();
    private Vec3 currentCamera = Vec3.ZERO;
    private Vec3 previousCamera = Vec3.ZERO;
    private Object levelIdentity;
    private int frameIndex;
    private int readIndex;
    private boolean valid;
    private boolean activeFrame;
    private boolean captured;
    private boolean pipelinesReady;
    private boolean failed;
    private long lastFrameTime;
    private float jitterU;
    private float jitterV;

    public String statusKey() {
        if (failed) return "taa.status.failed";
        return TaaClient.CONFIG.enabled ? "taa.status.ready" : "taa.status.off";
    }

    public boolean hasValidHistory() { return valid; }

    public void beginFrame() {
        activeFrame = false;
        captured = false;
        worldDepthSaved = false;
        handCaptured = false;
        jitterU = 0;
        jitterV = 0;
        Minecraft mc = Minecraft.getInstance();
        if (TaaClient.CONFIG != null && TaaClient.CONFIG.antialiasingMode == 1) {
            releaseTargets();
            invalidate();
            return;
        }
        if (TaaClient.CONFIG == null || !TaaClient.CONFIG.enabled || failed
                || mc.level == null || mc.gameRenderer.isPanoramicMode()) {
            invalidate();
            return;
        }
        try {
            RenderTarget target = mc.getMainRenderTarget();
            if (target.width < 1 || target.height < 1 || target.getDepthTextureView() == null) return;
            if (!pipelinesReady) {
                if (!RenderSystem.getDevice().precompilePipeline(RESOLVE).isValid()
                        || !RenderSystem.getDevice().precompilePipeline(RESIDUAL).isValid()
                        || !RenderSystem.getDevice().precompilePipeline(PRESENT).isValid()) {
                    throw new IllegalStateException("TAA shader compilation failed");
                }
                pipelinesReady = true;
            }
            if (history[0] == null || history[0].width != target.width || history[0].height != target.height) {
                releaseTargets();
                for (int i = 0; i < 2; i++) {
                    history[i] = new TextureTarget("TAA history " + i, target.width, target.height, true);
                    residual[i] = new TextureTarget("TAA rounding residual " + i, target.width, target.height, false);
                    //#if MC<12109
                    residual[i].setFilterMode(FilterMode.LINEAR);
                    //#endif
                    RenderSystem.getDevice().createCommandEncoder().clearColorTexture(residual[i].getColorTexture(), 0xff808080);
                    //#if MC<12109
                    history[i].setFilterMode(FilterMode.LINEAR);
                    // Depth stores the foreground mask; do not interpolate across silhouettes.
                    history[i].getDepthTexture().setTextureFilter(FilterMode.NEAREST, false);
                    //#endif
                    RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                            history[i].getColorTexture(), 0, history[i].getDepthTexture(), 1.0);
                }
                worldDepth = RenderSystem.getDevice().createTexture(() -> "TAA world depth", 15,
                        TextureFormat.DEPTH32, target.width, target.height, 1, 1);
                //#if MC<12109
                worldDepth.setTextureFilter(FilterMode.NEAREST, false);
                worldDepth.setAddressMode(AddressMode.CLAMP_TO_EDGE);
                //#endif
                worldDepthView = RenderSystem.getDevice().createTextureView(worldDepth);
                invalidate();
            }
            if (uniforms == null) uniforms = RenderSystem.getDevice().createBuffer(() -> "TAA uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 320);
            long now = System.nanoTime();
            if (levelIdentity != mc.level || now - lastFrameTime > 500_000_000L) invalidate();
            levelIdentity = mc.level;
            lastFrameTime = now;
            activeFrame = true;
        } catch (RuntimeException e) { fail(e); }
    }

    /** Called on the final world projection, after bobbing and portal effects, before its GPU upload. */
    public Matrix4f jitter(Matrix4f projection) {
        if (!activeFrame) return projection;
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        jitterU = TemporalMath.pixelOffset(frameIndex, TaaClient.CONFIG.sampleCount, 0) / target.width;
        jitterV = TemporalMath.pixelOffset(frameIndex, TaaClient.CONFIG.sampleCount, 1) / target.height;
        // Left multiplication shifts clip coordinates by offset * w, including bobbed projections.
        new Matrix4f().translation(jitterU * 2, jitterV * 2, 0).mul(projection, projection);
        return projection;
    }

    public void capture(Camera camera, Matrix4f view, Matrix4f projection) {
        if (!activeFrame) return;
        //#if MC>=12109
        //$$ currentCamera = camera.position();
        //#else
        currentCamera = camera.getPosition();
        //#endif
        // Reprojection uses the stable camera while rasterization samples vary per frame.
        currentVP.translation(-jitterU * 2, -jitterV * 2, 0).mul(projection).mul(view);
        if (!currentVP.isFinite() || Math.abs(currentVP.determinant()) < 1e-10f) {
            invalidate();
            activeFrame = false;
            return;
        }
        if (currentCamera.distanceToSqr(previousCamera) > 64.0) invalidate();
        // At zero camera displacement, a large view/projection discontinuity is a camera cut.
        if (valid && matrixDifference(currentVP, previousVP) > 1.0f) invalidate();
        currentVP.invert(inverseVP);
        Vec3 delta = currentCamera.subtract(previousCamera);
        previousFromCurrent.set(previousVP).translate((float) delta.x, (float) delta.y, (float) delta.z);
        captured = true;
    }

    private static float matrixDifference(Matrix4f a, Matrix4f b) {
        float result = 0;
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++)
            result = Math.max(result, Math.abs(a.get(c, r) - b.get(c, r)));
        return result;
    }

    public void saveWorldDepth() {
        if (!activeFrame || !captured) return;
        try {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(target.getDepthTexture(), worldDepth,
                    0, 0, 0, 0, 0, target.width, target.height);
            worldDepthSaved = true;
        } catch (RuntimeException e) { fail(e); }
    }

    public GpuBufferSlice handProjection(GpuBufferSlice original, int width, int height, float fov) {
        if (!activeFrame) return original;
        // Matches vanilla's HUD 3D near/far planes. Camera rotation cancels in the hand pose.
        handVP.setPerspective((float) Math.toRadians(fov), (float) width / height, 0.05f, 100.0f);
        handCaptured = true;
        if (handProjectionBuffer == null) handProjectionBuffer = new PerspectiveProjectionMatrixBuffer("TAA hands");
        handRaster.translation(jitterU * 2, jitterV * 2, 0).mul(handVP);
        return handProjectionBuffer.getBuffer(handRaster);
    }

    public void resolve() {
        if (!activeFrame || !captured || !worldDepthSaved || !handCaptured) return;
        try {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            TextureTarget read = history[readIndex];
            TextureTarget write = history[1 - readIndex];
            var config = TaaClient.CONFIG;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.malloc(320);
                inverseVP.get(0, data);
                previousFromCurrent.get(64, data);
                data.position(128);
                data.putFloat(1.0f / target.width).putFloat(1.0f / target.height)
                        .putFloat((float) config.historyWeight).putFloat(config.reduceHistoryOnMotion ? 1 : 0);
                data.putFloat(valid ? 1 : 0).putFloat((float) config.sharpening)
                        .putFloat(config.debugView).putFloat((float) config.casAmount);
                boolean worldStill = valid && currentCamera.distanceToSqr(previousCamera) < 1e-16
                        && matrixDifference(currentVP, previousVP) < 1e-6f;
                boolean handStill = valid && matrixDifference(handVP, previousHandVP) < 1e-6f;
                data.putFloat(target.width).putFloat(target.height).putFloat(worldStill ? 1 : 0).putFloat(handStill ? 1 : 0);
                data.putFloat(jitterU).putFloat(jitterV).putFloat(1).putFloat(config.sharpeningMode);
                handVP.invert(inverseHandVP).get(192, data);
                previousHandVP.get(256, data);
                data.position(320);
                data.flip();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uniforms.slice(), data);
            }
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            // Compute both parts from the same inputs before presenting to main color.
            try (var pass = encoder.createRenderPass(() -> "TAA precision residual", residual[1 - readIndex].getColorTextureView(), OptionalInt.empty())) {
                pass.setPipeline(RESIDUAL);
                //#if MC<12109
                pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                //#endif
                pass.setUniform("TaaData", uniforms);
                TaaRenderer.bind(pass, "CurrentColor", target.getColorTextureView());
                TaaRenderer.bind(pass, "CurrentDepth", target.getDepthTextureView());
                TaaRenderer.bind(pass, "HistoryColor", read.getColorTextureView());
                TaaRenderer.bind(pass, "HistoryResidual", residual[readIndex].getColorTextureView());
                TaaRenderer.bind(pass, "HistoryDepth", read.getDepthTextureView());
                TaaRenderer.bind(pass, "WorldDepth", worldDepthView);
                pass.draw(0, 3);
            }
            try (var pass = encoder.createRenderPass(() -> "TAA temporal resolve", write.getColorTextureView(),
                    OptionalInt.empty(), write.getDepthTextureView(), OptionalDouble.of(1.0))) {
                pass.setPipeline(RESOLVE);
                // The 1.21.8 OpenGL VAO cache requires a buffer even for an empty vertex format.
                //#if MC<12109
                pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                //#endif
                pass.setUniform("TaaData", uniforms);
                TaaRenderer.bind(pass, "CurrentColor", target.getColorTextureView());
                TaaRenderer.bind(pass, "CurrentDepth", target.getDepthTextureView());
                TaaRenderer.bind(pass, "HistoryColor", read.getColorTextureView());
                TaaRenderer.bind(pass, "HistoryResidual", residual[readIndex].getColorTextureView());
                TaaRenderer.bind(pass, "HistoryDepth", read.getDepthTextureView());
                TaaRenderer.bind(pass, "WorldDepth", worldDepthView);
                pass.draw(0, 3);
            }
            // Resolve stores foreground coverage alongside the scene color history.
            // Preserve a separate current-color source for debug mode; never sample the output attachment.
            if (config.debugView == 1) {
                encoder.copyTextureToTexture(target.getColorTexture(), write.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
                valid = false;
            } else {
                present(target, write.getColorTextureView(), read.getColorTextureView(), uniforms);
                valid = true;
            }
            previousVP.set(currentVP);
            previousHandVP.set(handVP);
            previousCamera = currentCamera;
            readIndex = 1 - readIndex;
            frameIndex = (frameIndex + 1) % config.sampleCount;
        } catch (RuntimeException e) { fail(e); }
        finally { activeFrame = false; }
    }

    public void invalidate() {
        valid = false;
        frameIndex = 0;
    }
    static void bind(RenderPass pass, String name, GpuTextureView view) {
        //#if MC>=12109
        //$$ boolean linear = name.equals("HistoryColor") || name.equals("HistoryResidual");
        //$$ pass.bindTexture(name, view, RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST));
        //#else
        pass.bindSampler(name, view);
        //#endif
    }
    static void present(RenderTarget target, GpuTextureView resolved, GpuTextureView previous, GpuBuffer data) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Anti-aliasing sharpen and present", target.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PRESENT);
            //#if MC<12109
            pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            //#endif
            pass.setUniform("TaaData", data);
            TaaRenderer.bind(pass, "ResolvedColor", resolved);
            TaaRenderer.bind(pass, "HistoryColor", previous);
            pass.draw(0, 3);
        }
    }
    public void reload() {
        invalidate();
        pipelinesReady = false;
        failed = false;
    }
    private void fail(RuntimeException e) {
        TaaClient.LOGGER.error("TAA disabled after rendering failure; reload resources to retry", e);
        failed = true;
        activeFrame = false;
        invalidate();
    }
    private void releaseTargets() {
        if (worldDepthView != null) { worldDepthView.close(); worldDepthView = null; }
        if (worldDepth != null) { worldDepth.close(); worldDepth = null; }
        for (int i = 0; i < history.length; i++) {
            if (history[i] != null) { history[i].destroyBuffers(); history[i] = null; }
            if (residual[i] != null) { residual[i].destroyBuffers(); residual[i] = null; }
        }
    }
    @Override public void close() {
        releaseTargets();
        if (uniforms != null) { uniforms.close(); uniforms = null; }
        if (handProjectionBuffer != null) { handProjectionBuffer.close(); handProjectionBuffer = null; }
        invalidate();
        pipelinesReady = false;
        levelIdentity = null;
    }
}
