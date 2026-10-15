//#if MC>=12100
package dev.antialiasing.mixin;

import dev.antialiasing.TaaClient;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void taa$beginGui(CallbackInfo ci) { TaaClient.GUI_SSAA.beginFrame(); }
}
//#endif
