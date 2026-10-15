//#if MC>=12100
package dev.antialiasing.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.OptionalInt;
import java.util.OptionalDouble;

/** Own chunk submission: batching mods cannot bypass pixel-width expansion. */
public final class ModernChunkBorders {
    //#if MC>=12109
    //$$ private static final VertexFormat FORMAT = VertexFormat.builder()
    //$$         .add("Position", VertexFormatElement.POSITION)
    //$$         .add("LineEnd", element(VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 3))
    //$$         .add("Color", VertexFormatElement.COLOR)
    //$$         .add("LineEndColor", element(VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4))
    //$$         .add("LineCorner", element(VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 2))
    //$$         .add("PixelWidth", element(VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 1)).build();
    //#else
    private static final VertexFormat FORMAT = DebugLineGeometry.format();
    //#endif
    private static final RenderPipeline COLOR = pipeline("chunk_lines", false), DEPTH = pipeline("chunk_lines_depth", true);
    private static final RenderPipeline LAYER = pipeline("chunk_lines_layer", false);
    private static final RenderPipeline COMPOSITE = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(ResourceLocation.fromNamespaceAndPath("taa", "pipeline/chunk_line_composite"))
            .withVertexShader(ResourceLocation.fromNamespaceAndPath("taa", "core/fullscreen"))
            .withFragmentShader(ResourceLocation.fromNamespaceAndPath("taa", "core/debug_line_composite"))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withSampler("LineColor").withSampler("LineDepth").withSampler("SceneDepth")
            .withBlend(BlendFunction.TRANSLUCENT).withCull(false).withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).build());
    private static final LineMesh mesh = new LineMesh();
    private static final Matrix4f identity = new Matrix4f(), pose = new Matrix4f(), modelView = new Matrix4f();
    private static boolean pending, repaint;
    private static GpuBufferSlice projection;
    private static GpuBuffer globals;
    private static TextureTarget layer;
    private static int minY, maxY, chunkX, chunkZ;
    private static double cameraX, cameraY, cameraZ;
    private ModernChunkBorders() {}

    public static void beginFrame() { discard(); repaint = false; projection = null; globals = null; }

    //#if MC>=12109
    //$$ private static VertexFormatElement element(VertexFormatElement.Type type, VertexFormatElement.Usage usage, int count) {
    //$$     for (int id = 0; id < VertexFormatElement.MAX_COUNT; id++)
    //$$         if (VertexFormatElement.byId(id) == null) return VertexFormatElement.register(id, 0, type, usage, count);
    //$$     throw new IllegalStateException("No free vertex format element for SSAA chunk lines");
    //$$ }
    //#endif
    private static RenderPipeline pipeline(String name, boolean depth) {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(ResourceLocation.fromNamespaceAndPath("taa", "pipeline/" + name))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath("taa", "core/debug_lines_vertex_width"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath("taa", name.equals("chunk_lines_layer")
                        ? "core/debug_lines_layer" : "core/debug_lines"))
                .withVertexFormat(FORMAT, VertexFormat.Mode.TRIANGLES)
                .withCull(false).withColorWrite(!depth).withDepthWrite(depth)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).build());
    }
    //#if MC>=12109
    //$$ public static void request(int bottom, int top, int x, int z, double cx, double cy, double cz) {
    //#else
    public static void request(int bottom, int top, int x, int z, double cx, double cy, double cz, Matrix4f transform) {
        pose.set(transform);
    //#endif
        minY = bottom; maxY = top; chunkX = x; chunkZ = z;
        cameraX = cx; cameraY = cy; cameraZ = cz; pending = true;
    }
    public static void render() {
        if (!pending) return;
        pending = false;
        var target = SupersampleContext.target();
        if (target == null) return;
        mesh.clear();
        float scale = SupersampleContext.lineWidthScale();
        ChunkBorderLines.emit(minY, maxY, chunkX, chunkZ, cameraX, cameraY, cameraZ,
                (x0, y0, z0, x1, y1, z1, color, width) -> mesh.line(x0, y0, z0, x1, y1, z1, color, width * scale));
        if (mesh.vertexCount() == 0) return;
        GpuBuffer buffer = FORMAT.uploadImmediateVertexBuffer(mesh.bytes());
        modelView.set(RenderSystem.getModelViewMatrix());
        //#if MC<12109
        modelView.mul(pose);
        //#endif
        projection = RenderSystem.getProjectionMatrixBuffer();
        globals = RenderSystem.getGlobalSettingsUniform();
        if (layer == null || layer.width != target.width || layer.height != target.height) {
            releaseLayer();
            layer = new TextureTarget("SSAA stable chunk line colors", target.width, target.height, true);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(layer.getColorTexture(), 0);
        var sceneDepth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        // Capture all colors before any line writes depth. Blue is the last
        // color in this layer and cannot subsequently fight with coplanar red.
        draw(LAYER, buffer, layer.getColorTextureView(), sceneDepth);
        for (RenderPipeline pipeline : new RenderPipeline[]{COLOR, DEPTH}) {
            var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            var depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
            draw(pipeline, buffer, color, depth);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(sceneDepth.texture(), layer.getDepthTexture(),
                0, 0, 0, 0, 0, target.width, target.height);
        repaint = true;
    }

    /** Iris final/composite replaces main color after the debug stage. */
    public static void repaintAfterWorld() {
        if (!repaint) return;
        repaint = false;
        try {
            var target = SupersampleContext.target();
            if (target == null) return;
            if (layer == null) return;
            // Reuse finalized colors rather than depth-testing each line again.
            // No depth attachment: sampling SceneDepth cannot create feedback.
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "SSAA stable chunk line composite", target.getColorTextureView(), OptionalInt.empty())) {
                pass.setPipeline(COMPOSITE);
                //#if MC<12109
                pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                //#endif
                TaaRenderer.bind(pass, "LineColor", layer.getColorTextureView());
                TaaRenderer.bind(pass, "LineDepth", layer.getDepthTextureView());
                TaaRenderer.bind(pass, "SceneDepth", target.getDepthTextureView());
                pass.draw(0, 3);
            }
        } finally {
            projection = null; globals = null;
        }
    }

    private static void draw(RenderPipeline pipeline, GpuBuffer buffer, GpuTextureView color, GpuTextureView depth) {
        //#if MC>=12109
        //$$ var transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1), new Vector3f(), identity);
        //#else
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1), new Vector3f(), identity, 1);
        //#endif
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "SSAA chunk borders", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            if (projection != null) pass.setUniform("Projection", projection);
            if (globals != null) pass.setUniform("Globals", globals);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, buffer);
            pass.draw(0, mesh.vertexCount());
        }
    }
    public static void discard() { pending = false; }
    public static void releaseLayer() {
        repaint = false;
        if (layer != null) { layer.destroyBuffers(); layer = null; }
    }
    public static void close() { beginFrame(); releaseLayer(); mesh.close(); }
}
//#endif
