package dev.antialiasing.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.antialiasing.TaaClient;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
//#if MC>=12109
//$$ import com.mojang.blaze3d.textures.GpuSampler;
//#endif

@Mixin(PictureInPictureRenderer.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class GuiEntityMixin {
    @Unique private int taa$scale = 1;
    @Shadow private GpuTexture texture;
    @Shadow private GpuTextureView textureView;
    @Shadow private GpuTexture depthTexture;
    @Shadow private GpuTextureView depthTextureView;

    @WrapMethod(method = "prepare")
    private void taa$prepare(PictureInPictureRenderState state, GuiRenderState gui, int guiScale, Operation<Void> original) {
        taa$scale = state instanceof GuiEntityRenderState
                ? TaaClient.GUI_SSAA.prepare((state.x1() - state.x0()) * guiScale, (state.y1() - state.y0()) * guiScale) : 1;
        GpuTextureView colorOverride = RenderSystem.outputColorTextureOverride;
        GpuTextureView depthOverride = RenderSystem.outputDepthTextureOverride;
        try {
            try { original.call(state, gui, guiScale * taa$scale); }
            catch (RuntimeException | OutOfMemoryError error) {
                if (taa$scale == 1) throw error;
                TaaClient.GUI_SSAA.fail(error);
                // A failed vanilla texture allocation can leave only some fields initialized.
                // Clear those fields before retrying at the original resolution.
                if (textureView != null) { textureView.close(); textureView = null; }
                if (depthTextureView != null) { depthTextureView.close(); depthTextureView = null; }
                if (texture != null) { texture.close(); texture = null; }
                if (depthTexture != null) { depthTexture.close(); depthTexture = null; }
                taa$scale = 1;
                original.call(state, gui, guiScale);
            }
        } finally {
            RenderSystem.outputColorTextureOverride = colorOverride;
            RenderSystem.outputDepthTextureOverride = depthOverride;
            taa$scale = 1;
        }
    }

    //#if MC>=12109
    //$$ @WrapOperation(method = "blitTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/TextureSetup;singleTexture(Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)Lnet/minecraft/client/gui/render/TextureSetup;"))
    //$$ private TextureSetup taa$resolve(GpuTextureView texture, GpuSampler sampler, Operation<TextureSetup> original) {
    //$$     return original.call(TaaClient.GUI_SSAA.resolve(texture, taa$scale), sampler);
    //$$ }
    //#else
    @WrapOperation(method = "blitTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/TextureSetup;singleTexture(Lcom/mojang/blaze3d/textures/GpuTextureView;)Lnet/minecraft/client/gui/render/TextureSetup;"))
    private TextureSetup taa$resolve(GpuTextureView texture, Operation<TextureSetup> original) {
        return original.call(TaaClient.GUI_SSAA.resolve(texture, taa$scale));
    }
    //#endif
}
