package dev.antialiasing.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antialiasing.render.SupersampleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void taa$ssaaWidth(CallbackInfoReturnable<Integer> cir) {
        RenderTarget target = SupersampleContext.target();
        if (target != null) cir.setReturnValue(target.width);
    }
    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void taa$ssaaHeight(CallbackInfoReturnable<Integer> cir) {
        RenderTarget target = SupersampleContext.target();
        if (target != null) cir.setReturnValue(target.height);
    }
}
