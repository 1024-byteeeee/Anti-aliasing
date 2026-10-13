package dev.antialiasing.mixin;

import dev.antialiasing.TaaClient;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow private GlobalSettingsUniform globalSettingsUniform;
    //#if MC>=12109
    //$$ private static final String WORLD_RENDER = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V";
    //#else
    private static final String WORLD_RENDER = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V";
    //#endif
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void taa$begin(DeltaTracker delta, CallbackInfo ci) {
        TaaClient.SSAA.beginFrame();
        if (TaaClient.SSAA.isActive()) taa$updateGlobals(delta);
        TaaClient.RENDERER.beginFrame();
    }
    private void taa$updateGlobals(DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        //#if MC>=12109
        //$$ globalSettingsUniform.update(mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.glintStrength().get(),
        //$$         mc.level == null ? 0L : mc.level.getGameTime(), delta, mc.options.getMenuBackgroundBlurriness(),
        //$$         mc.gameRenderer.getMainCamera(), mc.options.textureFiltering().get() == net.minecraft.client.TextureFilteringMethod.RGSS);
        //#else
        globalSettingsUniform.update(mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.glintStrength().get(),
                mc.level == null ? 0L : mc.level.getGameTime(), delta, mc.options.getMenuBackgroundBlurriness());
        //#endif
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PerspectiveProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f taa$jitter(Matrix4f matrix) { return TaaClient.RENDERER.jitter(matrix); }

    @ModifyArgs(method = "renderLevel", at = @At(value = "INVOKE", target = WORLD_RENDER))
    private void taa$capture(Args args) {
        TaaClient.RENDERER.capture((Camera) args.get(3), (Matrix4f) args.get(4), (Matrix4f) args.get(5));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = WORLD_RENDER, shift = At.Shift.AFTER))
    private void taa$saveWorldDepth(DeltaTracker delta, CallbackInfo ci) { TaaClient.RENDERER.saveWorldDepth(); }

    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CachedPerspectiveProjectionMatrixBuffer;getBuffer(IIF)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice taa$handProjection(CachedPerspectiveProjectionMatrixBuffer original, int width, int height, float fov) {
        return TaaClient.RENDERER.handProjection(original, width, height, fov);
    }

    // Includes hands, screen effects, debug geometry, entity outlines and vanilla post effects.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void taa$resolve(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        TaaClient.RENDERER.resolve();
        boolean ssaa = TaaClient.SSAA.isActive();
        TaaClient.SSAA.finishFrame();
        if (ssaa) taa$updateGlobals(delta);
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void taa$resize(int width, int height, CallbackInfo ci) { TaaClient.RENDERER.invalidate(); TaaClient.SSAA.reset(); }

    @Inject(method = "resetData", at = @At("HEAD"))
    private void taa$reset(CallbackInfo ci) { TaaClient.RENDERER.invalidate(); TaaClient.SSAA.reset(); }
}
