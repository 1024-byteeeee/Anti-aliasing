package dev.antialiasing.mixin;

import dev.antialiasing.TaaClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
//#if MC<11700
//$$ import dev.antialiasing.render.Matrix4fLoader;
//#endif
//#if MC>=11903
import org.joml.Matrix4f;
//#else
//$$ import com.mojang.math.Matrix4f;
//$$ import org.lwjgl.system.MemoryStack;
//$$ import java.nio.FloatBuffer;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    //#if MC>=12005
    private static final String WORLD = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";
    //#elseif MC>=11903
    //$$ private static final String WORLD = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V";
    //#else
    //$$ private static final String WORLD = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lcom/mojang/math/Matrix4f;)V";
    //#endif
    //#if MC>=11903
    private static final String UPLOAD = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V";
    //#else
    //$$ private static final String UPLOAD = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lcom/mojang/math/Matrix4f;)V";
    //#endif
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void taa$begin(CallbackInfo ci) { TaaClient.SSAA.beginFrame(); TaaClient.RENDERER.beginFrame(); }
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = UPLOAD), index = 0)
    private Matrix4f taa$jitter(Matrix4f matrix) { return taa$projection(matrix, false); }
    @ModifyArg(method = "renderItemInHand", at = @At(value = "INVOKE", target = UPLOAD), index = 0)
    private Matrix4f taa$hand(Matrix4f matrix) { return taa$projection(matrix, true); }
    private static Matrix4f taa$projection(Matrix4f matrix, boolean hand) {
        //#if MC>=11903
        return hand ? TaaClient.RENDERER.jitterHand(matrix) : TaaClient.RENDERER.jitter(matrix);
        //#else
        //$$ try (MemoryStack stack = MemoryStack.stackPush()) {
        //$$     FloatBuffer bytes = stack.mallocFloat(16); matrix.store(bytes);
        //$$     org.joml.Matrix4f converted = new org.joml.Matrix4f(bytes);
        //$$     if (hand) TaaClient.RENDERER.jitterHand(converted); else TaaClient.RENDERER.jitter(converted);
        //$$     converted.get(bytes);
        //#if MC>=11700
        //$$     matrix.load(bytes);
        //#else
        //$$     Matrix4fLoader.load(matrix, bytes);
        //#endif
        //$$     return matrix;
        //$$ }
        //#endif
    }
    @ModifyArgs(method = "renderLevel", at = @At(value = "INVOKE", target = WORLD))
    private void taa$capture(Args args) {
        //#if MC>=12005
        TaaClient.RENDERER.capture((Camera) args.get(3), (Matrix4f) args.get(6), (Matrix4f) args.get(7));
        //#elseif MC>=11903
        //$$ TaaClient.RENDERER.capture((Camera) args.get(4), ((PoseStack) args.get(0)).last().pose(), (Matrix4f) args.get(7));
        //#else
        //$$ try (MemoryStack stack = MemoryStack.stackPush()) {
        //$$     FloatBuffer view = stack.mallocFloat(16), projection = stack.mallocFloat(16);
        //$$     ((PoseStack) args.get(0)).last().pose().store(view); ((Matrix4f) args.get(7)).store(projection);
        //$$     TaaClient.RENDERER.capture((Camera) args.get(4), new org.joml.Matrix4f(view), new org.joml.Matrix4f(projection));
        //$$ }
        //#endif
    }
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = WORLD, shift = At.Shift.AFTER))
    private void taa$worldDepth(CallbackInfo ci) { TaaClient.RENDERER.saveWorldDepth(); }
    // Vanilla clears depth here immediately before switching to the HUD projection.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", ordinal = 0))
    private void taa$resolve(CallbackInfo ci) { TaaClient.RENDERER.resolve(); TaaClient.SSAA.finishFrame(); }
    @Inject(method = {"resize", "resetData"}, at = @At("HEAD"))
    private void taa$reset(CallbackInfo ci) { TaaClient.RENDERER.invalidate(); TaaClient.SSAA.reset(); }
}
