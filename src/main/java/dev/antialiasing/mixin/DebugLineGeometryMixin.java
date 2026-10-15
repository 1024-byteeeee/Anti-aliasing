//#if MC>=12100 && MC<12109
package dev.antialiasing.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.MeshData;
import dev.antialiasing.render.DebugLineGeometry;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class DebugLineGeometryMixin {
    @Shadow @Final @Mutable private RenderPipeline renderPipeline;

    @WrapMethod(method = "draw")
    private void taa$expandStrip(MeshData mesh, Operation<Void> original) {
        if (renderPipeline != RenderPipelines.DEBUG_LINE_STRIP || !DebugLineGeometry.supports(mesh)) {
            original.call(mesh); return;
        }
        if (DebugLineGeometry.enqueue((RenderType) (Object) this, mesh)) return;
        RenderPipeline saved = renderPipeline;
        try {
            taa$drawExpanded(mesh, original, DebugLineGeometry.currentPipeline());
            if (!DebugLineGeometry.replaying())
                taa$drawExpanded(mesh, original, DebugLineGeometry.DEPTH_PIPELINE);
        } finally {
            renderPipeline = saved;
            // A batched strip remains valid for both the color and depth passes.
            if (!DebugLineGeometry.replaying()) mesh.close();
        }
    }

    @Unique
    private void taa$drawExpanded(MeshData mesh, Operation<Void> original, RenderPipeline pipeline) {
        try (MeshData expanded = DebugLineGeometry.expand(mesh)) {
            if (expanded == null) return;
            renderPipeline = pipeline;
            original.call(expanded);
        }
    }
}
//#endif
