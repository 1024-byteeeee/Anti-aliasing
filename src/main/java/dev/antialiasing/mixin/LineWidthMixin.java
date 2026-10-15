package dev.antialiasing.mixin;

import dev.antialiasing.render.SupersampleContext;
//#if MC>=12109
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.VertexConsumer;
//#else
import com.mojang.blaze3d.systems.RenderSystem;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//#if MC>=12109 || MC<11700
//$$ import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//#else
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#endif

//#if MC>=12109
//$$ @Mixin(BufferBuilder.class)
//#else
@Mixin(RenderSystem.class)
//#endif
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class LineWidthMixin {
    //#if MC>=12109
    //$$ @WrapMethod(method = "setLineWidth")
    //$$ private VertexConsumer taa$vertexLineWidth(float width, Operation<VertexConsumer> original) {
    //$$     return original.call(width * SupersampleContext.lineWidthScale());
    //$$ }
    //#elseif MC>=11700
    @Inject(method = "lineWidth", at = @At("HEAD"))
    private static void taa$requestedWidth(float width, CallbackInfo ci) {
        dev.antialiasing.render.NativeLineAntialiasing.requestedWidth(width);
    }

    // Compensate at read time: the cached vanilla width is never scaled or accumulated.
    @ModifyReturnValue(method = "getShaderLineWidth", at = @At("RETURN"))
    private static float taa$shaderLineWidth(float original) {
        return original * SupersampleContext.lineWidthScale();
    }
    //#else
    //$$ @WrapMethod(method = "lineWidth")
    //$$ private static void taa$nativeLineWidth(float width, Operation<Void> original) {
    //$$     original.call(dev.antialiasing.render.NativeLineAntialiasing.legacyWidth(width));
    //$$ }
    //#endif
}
