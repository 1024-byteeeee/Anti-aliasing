//#if MC>=12109
//$$ package dev.antialiasing.mixin;
//$$
//$$ import dev.antialiasing.render.ModernChunkBorders;
//$$ import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$
//$$ @Mixin(DrawableGizmoPrimitives.class)
//$$ @SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
//$$ public abstract class GizmoChunkRenderMixin {
//$$     @Inject(method = "render", at = @At("HEAD"))
//$$     private void taa$chunks(CallbackInfo ci) { ModernChunkBorders.render(); }
//$$ }
//#endif
