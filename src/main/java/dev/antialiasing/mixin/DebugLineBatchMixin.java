//#if MC<12109
package dev.antialiasing.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.antialiasing.render.DebugLineGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
//#if MC>=11900 && MC<12100
//$$ import net.minecraft.client.renderer.RenderType;
//#endif
//#if MC>=12100
import net.minecraft.client.renderer.culling.Frustum;
//#endif
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DebugRenderer.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class DebugLineBatchMixin {
    @WrapMethod(method = "render")
    //#if MC>=12100
    private void taa$drawDebugLines(PoseStack pose, Frustum frustum, MultiBufferSource.BufferSource buffers,
                                    double x, double y, double z, Operation<Void> original) {
    //#else
    //$$ private void taa$drawDebugLines(PoseStack pose, MultiBufferSource.BufferSource buffers,
    //$$                                 double x, double y, double z, Operation<Void> original) {
    //#endif
        if (!DebugLineGeometry.beginBatch()) {
            //#if MC>=12100
            original.call(pose, frustum, buffers, x, y, z);
            //#else
            //$$ original.call(pose, buffers, x, y, z);
            //#endif
            return;
        }
        try {
            //#if MC>=12100
            original.call(pose, frustum, buffers, x, y, z);
            // The final strip is still buffered when DebugRenderer.render returns.
            buffers.endLastBatch();
            //#else
            //$$ original.call(pose, buffers, x, y, z);
            //#if MC>=11900
            // Flush only chunk strips. Older worlds still have entity/outline
            // buffers waiting for the caller to restore its model-view matrix.
            //$$ buffers.endBatch(RenderType.debugLineStrip(1.0));
            //$$ buffers.endBatch(RenderType.debugLineStrip(2.0));
            //#endif
            //#endif
            DebugLineGeometry.flushBatch();
            //#if MC>=12100
            dev.antialiasing.render.ModernChunkBorders.render();
            //#endif
        } finally {
            DebugLineGeometry.discardBatch();
            //#if MC>=12100
            dev.antialiasing.render.ModernChunkBorders.discard();
            //#endif
        }
    }
}
//#endif
