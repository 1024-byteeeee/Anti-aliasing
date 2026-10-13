package dev.antialiasing.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antialiasing.render.SupersampleContext;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void taa$ssaaTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = SupersampleContext.target();
        if (target != null) cir.setReturnValue(target);
    }
}
