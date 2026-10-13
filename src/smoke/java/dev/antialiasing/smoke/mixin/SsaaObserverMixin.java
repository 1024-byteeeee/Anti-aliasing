package dev.antialiasing.smoke.mixin;

import dev.antialiasing.smoke.SmokeClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SsaaObserverMixin {
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void taaSmoke$ssaaResolution(CallbackInfo ci) { SmokeClient.observeSsaaWorld(); }
}
