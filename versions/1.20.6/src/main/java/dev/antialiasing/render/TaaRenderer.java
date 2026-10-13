package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antialiasing.TaaClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;

public final class TaaRenderer implements AutoCloseable {
    private final LegacyGraphics graphics = new LegacyGraphics();
    private final RenderTarget[] history = new RenderTarget[2], residual = new RenderTarget[2];
    private RenderTarget worldDepth;
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


    public String statusKey() { return failed ? "taa.status.failed" : TaaClient.CONFIG.enabled ? "taa.status.ready" : "taa.status.off"; }
    public boolean hasValidHistory() { return valid; }
    public void beginFrame() {
        activeFrame = captured = worldDepthSaved = handCaptured = false;
        jitterU = jitterV = 0;
        Minecraft mc = Minecraft.getInstance();
        if (TaaClient.CONFIG.antialiasingMode == 1 || !TaaClient.CONFIG.enabled || failed || mc.level == null) {
            releaseTargets(); invalidate(); return;
        }
        //#if MC>=11700
        if (mc.gameRenderer.isPanoramicMode()) { invalidate(); return; }
        //#endif
        try (LegacyGraphics.State state = new LegacyGraphics.State()) {
            graphics.init();
            RenderTarget target = mc.getMainRenderTarget();
            if (target.width < 1 || target.height < 1) return;
            if (history[0] == null || history[0].width != target.width || history[0].height != target.height) {
                releaseTargets();
                for (int i = 0; i < 2; i++) {
                    history[i] = LegacyGraphics.target(target.width, target.height, true, true);
                    residual[i] = LegacyGraphics.target(target.width, target.height, false);
                    residual[i].setClearColor(128f / 255f, 128f / 255f, 128f / 255f, 1);
                    residual[i].clear(false);
                }
                worldDepth = LegacyGraphics.target(target.width, target.height, true);
                invalidate();
            }
            long now = System.nanoTime();
            if (levelIdentity != mc.level || now - lastFrameTime > 500_000_000L) invalidate();
            levelIdentity = mc.level; lastFrameTime = now; activeFrame = true;
            handVP.identity();
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
        currentCamera = camera.getPosition();
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
        try (LegacyGraphics.State state = new LegacyGraphics.State()) {
            LegacyGraphics.copy(Minecraft.getInstance().getMainRenderTarget(), worldDepth, GL11.GL_DEPTH_BUFFER_BIT);
            worldDepthSaved = true;
        } catch (RuntimeException e) { fail(e); }
    }
    public Matrix4f jitterHand(Matrix4f matrix) {
        if (activeFrame) {
            handVP.set(matrix); handCaptured = true;
            new Matrix4f().translation(jitterU * 2, jitterV * 2, 0).mul(matrix, matrix);
        }
        return matrix;
    }
    public void resolve() {
        if (!activeFrame || !captured || !worldDepthSaved) return;
        try (LegacyGraphics.State state = new LegacyGraphics.State(); MemoryStack stack = MemoryStack.stackPush()) {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            RenderTarget read = history[readIndex], write = history[1 - readIndex];
            ByteBuffer data = stack.calloc(320);
            inverseVP.get(0, data); previousFromCurrent.get(64, data);
            data.putFloat(128, 1f / target.width).putFloat(132, 1f / target.height);
            data.putFloat(136, (float) TaaClient.CONFIG.historyWeight).putFloat(140, TaaClient.CONFIG.reduceHistoryOnMotion ? 1 : 0);
            data.putFloat(144, valid ? 1 : 0).putFloat(148, (float) TaaClient.CONFIG.sharpening);
            data.putFloat(152, TaaClient.CONFIG.debugView).putFloat(156, (float) TaaClient.CONFIG.casAmount);
            boolean still = valid && currentCamera.distanceToSqr(previousCamera) < 1e-16 && matrixDifference(currentVP, previousVP) < 1e-6f;
            data.putFloat(160, target.width).putFloat(164, target.height);
            data.putFloat(168, still ? 1 : 0).putFloat(172, valid && matrixDifference(handVP, previousHandVP) < 1e-6f ? 1 : 0);
            data.putFloat(176, jitterU).putFloat(180, jitterV).putFloat(184, 1).putFloat(188, TaaClient.CONFIG.sharpeningMode);
            handVP.invert(inverseHandVP).get(192, data); previousHandVP.get(256, data);
            graphics.upload(data);
            graphics.resolve(target, worldDepth, read, residual[readIndex], residual[1 - readIndex], true);
            graphics.resolve(target, worldDepth, read, residual[readIndex], write, false);
            if (TaaClient.CONFIG.debugView == 1) {
                LegacyGraphics.copy(target, write, GL11.GL_COLOR_BUFFER_BIT); valid = false;
            } else { graphics.present(target, write, read); valid = true; }
            previousVP.set(currentVP); previousHandVP.set(handVP); previousCamera = currentCamera;
            readIndex = 1 - readIndex; frameIndex = (frameIndex + 1) % TaaClient.CONFIG.sampleCount;
        } catch (RuntimeException e) { fail(e); }
        finally { activeFrame = false; }
    }
    public void invalidate() { valid = false; frameIndex = 0; }
    public void reload() { close(); failed = false; }
    private void fail(RuntimeException e) {
        TaaClient.LOGGER.error("TAA rendering failed; reload resources to retry", e);
        failed = true; activeFrame = false; invalidate();
    }
    private void releaseTargets() {
        if (worldDepth != null) { worldDepth.destroyBuffers(); worldDepth = null; }
        for (int i = 0; i < 2; i++) {
            if (history[i] != null) { history[i].destroyBuffers(); history[i] = null; }
            if (residual[i] != null) { residual[i].destroyBuffers(); residual[i] = null; }
        }
    }
    @Override public void close() { releaseTargets(); graphics.close(); invalidate(); levelIdentity = null; }
}
