package dev.antialiasing.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow private RenderTarget entityTarget;
    @Shadow private PostChain entityEffect;
    @Shadow private PostChain transparencyChain;
    @Shadow private RenderTarget translucentTarget;
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void taa$targets(CallbackInfo ci) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (entityTarget != null && (entityTarget.width != main.width || entityTarget.height != main.height)) {
            if (entityEffect != null) entityEffect.resize(main.width, main.height);
            else entityTarget.resize(main.width, main.height, false);
        }
        if (transparencyChain != null && translucentTarget != null
                && (translucentTarget.width != main.width || translucentTarget.height != main.height))
            transparencyChain.resize(main.width, main.height);
        main.bindWrite(true);
    }
}
