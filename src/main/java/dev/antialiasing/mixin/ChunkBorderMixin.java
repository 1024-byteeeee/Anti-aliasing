package dev.antialiasing.mixin;

import dev.antialiasing.render.SupersampleContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.debug.ChunkBorderRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//#if MC>=12100
import dev.antialiasing.render.ModernChunkBorders;
//#else
//$$ import dev.antialiasing.render.DebugLineGeometry;
//#endif
//#if MC>=12109
//$$ import net.minecraft.client.renderer.culling.Frustum;
//$$ import net.minecraft.util.debug.DebugValueAccess;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
//#endif

@Mixin(ChunkBorderRenderer.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class ChunkBorderMixin {
    //#if MC>=12109
    //$$ @WrapMethod(method = "emitGizmos")
    //$$ private void taa$chunk(double x, double y, double z, DebugValueAccess values, Frustum frustum, float partialTick, Operation<Void> original) {
    //$$     if (SupersampleContext.target() == null) {
    //$$         original.call(x, y, z, values, frustum, partialTick);
    //$$         return;
    //$$     }
    //$$     Minecraft mc = Minecraft.getInstance();
    //$$     Entity entity = mc.gameRenderer.getMainCamera().entity();
    //$$     ModernChunkBorders.request(mc.level.getMinY(), mc.level.getMaxY() + 1,
    //$$             entity.chunkPosition().x, entity.chunkPosition().z, x, y, z);
    //$$ }
    //#else
    @WrapMethod(method = "render")
    private void taa$chunk(PoseStack pose, MultiBufferSource buffers, double x, double y, double z, Operation<Void> original) {
        //#if MC>=12100
        if (SupersampleContext.target() == null) {
        //#else
        //$$ if (!DebugLineGeometry.prepare()) {
        //#endif
            original.call(pose, buffers, x, y, z);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.gameRenderer.getMainCamera().getEntity();
        //#if MC>=12100
        ModernChunkBorders.request(mc.level.getMinY(), mc.level.getMaxY() + 1,
                entity.chunkPosition().x, entity.chunkPosition().z, x, y, z, pose.last().pose());
        //#elseif MC>=11700
        //$$ DebugLineGeometry.chunk(pose, mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight(),
        //$$         entity.chunkPosition().x, entity.chunkPosition().z, x, y, z);
        //#else
        //$$ DebugLineGeometry.chunk(pose, 0, 256, entity.xChunk, entity.zChunk, x, y, z);
        //#endif
    }
    //#endif
}
