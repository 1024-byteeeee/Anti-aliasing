package dev.antialiasing.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antialiasing.render.PostPassTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.function.IntSupplier;

@Mixin(PostPass.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class PostPassMixin implements PostPassTargets {
    @Shadow @Final @Mutable public RenderTarget inTarget;
    @Shadow @Final @Mutable public RenderTarget outTarget;
    @Shadow @Final private List<IntSupplier> auxAssets;
    @Shadow @Final private List<Integer> auxWidths;
    @Shadow @Final private List<Integer> auxHeights;
    @Unique private boolean taa$mainInput;
    @Unique private boolean taa$mainOutput;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void taa$rememberMain(CallbackInfo ci) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        taa$mainInput = inTarget == main;
        taa$mainOutput = outTarget == main;
    }

    // Vanilla captures target::getTextureId while parsing a chain. Resolve main
    // textures at draw time so changing SSAA scale cannot leave a stale target.
    @WrapMethod(method = "addAuxAsset")
    private void taa$dynamicMainSampler(String name, IntSupplier supplier, int width, int height, Operation<Void> original) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int texture = supplier.getAsInt();
        if (texture >= 0 && texture == main.getColorTextureId())
            supplier = () -> Minecraft.getInstance().getMainRenderTarget().getColorTextureId();
        else if (texture >= 0 && texture == main.getDepthTextureId())
            supplier = () -> Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();
        original.call(name, supplier, width, height);
    }

    @Inject(method = "process", at = @At("HEAD"))
    private void taa$syncMain(float tickDelta, CallbackInfo ci) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (taa$mainInput) inTarget = main;
        if (taa$mainOutput) outTarget = main;
    }

    @Override public void taa$updateAuxSizes(Iterable<RenderTarget> targets) {
        for (int index = 0; index < auxAssets.size(); index++) {
            int texture = auxAssets.get(index).getAsInt();
            if (texture < 0) continue;
            for (RenderTarget target : targets) {
                if (texture == target.getColorTextureId() || texture == target.getDepthTextureId()) {
                    auxWidths.set(index, target.width);
                    auxHeights.set(index, target.height);
                    break;
                }
            }
        }
    }
}
