package dev.antialiasing.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antialiasing.render.PostPassTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Mixin(PostChain.class)
public abstract class PostChainMixin {
    @Shadow @Final @Mutable private RenderTarget screenTarget;
    @Shadow @Final private List<PostPass> passes;
    @Shadow @Final private Map<String, RenderTarget> customRenderTargets;
    @Shadow private int screenWidth;
    @Shadow private int screenHeight;
    @Shadow public abstract void resize(int width, int height);
    @Unique private boolean taa$usesMainTarget;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void taa$rememberMain(CallbackInfo ci) {
        taa$usesMainTarget = screenTarget == Minecraft.getInstance().getMainRenderTarget();
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void taa$resizeTarget(int width, int height, CallbackInfo ci) {
        if (taa$usesMainTarget) screenTarget = Minecraft.getInstance().getMainRenderTarget();
    }

    @Inject(method = "process", at = @At("HEAD"))
    private void taa$syncScene(float tickDelta, CallbackInfo ci) {
        if (!taa$usesMainTarget) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (screenTarget != main || screenWidth != main.width || screenHeight != main.height) {
            screenTarget = main;
            resize(main.width, main.height);
        }
        for (PostPass pass : passes) {
            PostPassTargets targets = (PostPassTargets) pass;
            targets.taa$updateAuxSizes(customRenderTargets.values());
            targets.taa$updateAuxSizes(Collections.singleton(main));
        }
    }
}
