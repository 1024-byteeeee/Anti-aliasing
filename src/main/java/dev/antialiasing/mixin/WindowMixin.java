package dev.antialiasing.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antialiasing.render.SupersampleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//#if MC>=12109
//$$ import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//#endif

@Mixin(Window.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class WindowMixin {
    //#if MC>=12109
    //$$ @WrapMethod(method = "getAppropriateLineWidth")
    //$$ private float taa$lineWidthDisplaySize(Operation<Float> original) {
    //$$     return SupersampleContext.withDisplaySize(() -> { return original.call(); });
    //$$ }
    //#endif
    @ModifyReturnValue(method = "getWidth", at = @At("RETURN"))
    private int taa$ssaaWidth(int original) {
        RenderTarget target = SupersampleContext.target();
        return target != null && !SupersampleContext.usesDisplaySize() ? target.width : original;
    }
    @ModifyReturnValue(method = "getHeight", at = @At("RETURN"))
    private int taa$ssaaHeight(int original) {
        RenderTarget target = SupersampleContext.target();
        return target != null && !SupersampleContext.usesDisplaySize() ? target.height : original;
    }
}
