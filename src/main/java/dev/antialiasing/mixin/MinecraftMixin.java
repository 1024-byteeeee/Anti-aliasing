package dev.antialiasing.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antialiasing.render.SupersampleContext;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class MinecraftMixin {
    @ModifyReturnValue(method = "getMainRenderTarget", at = @At("RETURN"))
    private RenderTarget taa$ssaaTarget(RenderTarget original) {
        RenderTarget target = SupersampleContext.target();
        return target != null ? target : original;
    }
}
