//#if MC>=12100 && MC<12109
package dev.antialiasing.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.MeshData;
import dev.antialiasing.render.DebugLineGeometry;
import dev.antialiasing.render.LineWidthSource;
import dev.antialiasing.render.SupersampleContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
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
    @Shadow @Final private RenderType.CompositeState state;

    @WrapMethod(method = "draw")
    private void taa$expandStrip(MeshData mesh, Operation<Void> original) {
        if (!DebugLineGeometry.supports(mesh)) {
            original.call(mesh); return;
        }
        float width = DebugLineGeometry.replaying() ? DebugLineGeometry.replayWidth() : taa$pixelWidth();
        if (DebugLineGeometry.enqueue((RenderType) (Object) this, mesh, width)) return;
        RenderPipeline saved = renderPipeline;
        try {
            taa$drawExpanded(mesh, width, original, DebugLineGeometry.currentPipeline());
            if (!DebugLineGeometry.replaying())
                taa$drawExpanded(mesh, width, original, DebugLineGeometry.DEPTH_PIPELINE);
        } finally {
            renderPipeline = saved;
            // flushBatch owns each disposable replay input; its retained snapshot
            // is never passed through RenderType.draw or other mods' wrappers.
            if (!DebugLineGeometry.replaying()) mesh.close();
        }
    }

    @Unique
    private float taa$pixelWidth() {
        float width = 1;
        for (RenderStateShard shard : ((CompositeStateAccessor) (Object) state).taa$states()) {
            if (shard instanceof LineWidthSource source) {
                width = (float) source.taa$lineWidth().orElseGet(() -> SupersampleContext.withDisplaySize(
                        () -> Math.max(2.5, Minecraft.getInstance().getWindow().getWidth() / 1920.0 * 2.5)));
                break;
            }
        }
        if (!Float.isFinite(width) || width <= 0) width = 1;
        return width * SupersampleContext.lineWidthScale();
    }

    @Unique
    private void taa$drawExpanded(MeshData mesh, float width, Operation<Void> original, RenderPipeline pipeline) {
        try (MeshData expanded = DebugLineGeometry.expand(mesh, width)) {
            if (expanded == null) return;
            renderPipeline = pipeline;
            original.call(expanded);
        }
    }
}
//#endif
