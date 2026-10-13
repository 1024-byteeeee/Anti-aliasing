package dev.antialiasing.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow private RenderTarget entityOutlineTarget;
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void taa$outlineResolution(CallbackInfo ci) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (entityOutlineTarget != null && (entityOutlineTarget.width != main.width || entityOutlineTarget.height != main.height))
            entityOutlineTarget.resize(main.width, main.height);
    }
}
