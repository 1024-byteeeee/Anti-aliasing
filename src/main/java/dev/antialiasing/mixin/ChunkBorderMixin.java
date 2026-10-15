//#if MC<11900 || MC>=12109
//$$ package dev.antialiasing.mixin;

//$$ import dev.antialiasing.render.SupersampleContext;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.renderer.debug.ChunkBorderRenderer;
//$$ import net.minecraft.world.entity.Entity;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC>=12109
//$$ import dev.antialiasing.render.ModernChunkBorders;
//$$ import net.minecraft.client.renderer.culling.Frustum;
//$$ import net.minecraft.util.debug.DebugValueAccess;
//#else
//$$ import dev.antialiasing.render.DebugLineGeometry;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//#endif

//$$ @Mixin(ChunkBorderRenderer.class)
//$$ @SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
//$$ public abstract class ChunkBorderMixin {
    //#if MC>=12109
    //$$ @Inject(method = "emitGizmos", at = @At("HEAD"), cancellable = true)
    //$$ private void taa$chunk(double x, double y, double z, DebugValueAccess values, Frustum frustum, float partialTick, CallbackInfo ci) {
    //$$     if (SupersampleContext.target() == null) return;
    //$$     Minecraft mc = Minecraft.getInstance();
    //$$     Entity entity = mc.gameRenderer.getMainCamera().entity();
    //$$     ModernChunkBorders.request(mc.level.getMinY(), mc.level.getMaxY() + 1,
    //$$             entity.chunkPosition().x, entity.chunkPosition().z, x, y, z);
    //$$     ci.cancel();
    //$$ }
    //#else
    //$$ @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    //$$ private void taa$chunk(PoseStack pose, MultiBufferSource buffers, double x, double y, double z, CallbackInfo ci) {
    //$$     if (!DebugLineGeometry.prepare()) return;
    //$$     Minecraft mc = Minecraft.getInstance();
    //$$     Entity entity = mc.gameRenderer.getMainCamera().getEntity();
    //#if MC>=11700
    //$$     DebugLineGeometry.chunk(mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight(),
    //$$             entity.chunkPosition().x, entity.chunkPosition().z, x, y, z);
    //#else
    //$$     DebugLineGeometry.chunk(0, 256, entity.xChunk, entity.zChunk, x, y, z);
    //#endif
    //$$     ci.cancel();
    //$$ }
    //#endif
//$$ }
//#endif
