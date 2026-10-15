//#if MC>=12109
//$$ package dev.antialiasing.render;
//$$
//$$ import com.mojang.blaze3d.buffers.GpuBuffer;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import com.mojang.blaze3d.vertex.VertexFormatElement;
//$$ import net.minecraft.client.renderer.RenderPipelines;
//$$ import net.minecraft.resources.Identifier;
//$$ import org.joml.Matrix4f;
//$$ import org.joml.Vector3f;
//$$ import org.joml.Vector4f;
//$$ import java.util.OptionalInt;
//$$ import java.util.OptionalDouble;
//$$
//$$ /** Draw the 1.21.8 chunk grid at the main Gizmo pass, before translucency/clouds. */
//$$ public final class ModernChunkBorders {
//$$     private static final VertexFormat FORMAT = VertexFormat.builder()
//$$             .add("Position", VertexFormatElement.POSITION)
//$$             .add("LineEnd", element(VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 3))
//$$             .add("Color", VertexFormatElement.COLOR)
//$$             .add("LineEndColor", element(VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4))
//$$             .add("LineCorner", element(VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 2))
//$$             .add("PixelWidth", element(VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 1)).build();
//$$     private static final RenderPipeline COLOR = pipeline("chunk_lines", false), DEPTH = pipeline("chunk_lines_depth", true);
//$$     private static final LineMesh mesh = new LineMesh();
//$$     private static final Matrix4f identity = new Matrix4f();
//$$     private static boolean pending;
//$$     private static int minY, maxY, chunkX, chunkZ;
//$$     private static double cameraX, cameraY, cameraZ;
//$$     private ModernChunkBorders() {}
//$$
//$$     private static VertexFormatElement element(VertexFormatElement.Type type, VertexFormatElement.Usage usage, int count) {
//$$         for (int id = 0; id < VertexFormatElement.MAX_COUNT; id++)
//$$             if (VertexFormatElement.byId(id) == null) return VertexFormatElement.register(id, 0, type, usage, count);
//$$         throw new IllegalStateException("No free vertex format element for SSAA chunk lines");
//$$     }
//$$     private static RenderPipeline pipeline(String name, boolean depth) {
//$$         return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
//$$                 .withLocation(Identifier.fromNamespaceAndPath("taa", "pipeline/" + name))
//$$                 .withVertexShader(Identifier.fromNamespaceAndPath("taa", "core/debug_lines_vertex_width"))
//$$                 .withFragmentShader(Identifier.fromNamespaceAndPath("taa", "core/debug_lines"))
//$$                 .withVertexFormat(FORMAT, VertexFormat.Mode.TRIANGLES)
//$$                 .withCull(false).withColorWrite(!depth).withDepthWrite(depth)
//$$                 .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).build());
//$$     }
//$$     public static void request(int bottom, int top, int x, int z, double cx, double cy, double cz) {
//$$         minY = bottom; maxY = top; chunkX = x; chunkZ = z;
//$$         cameraX = cx; cameraY = cy; cameraZ = cz; pending = true;
//$$     }
//$$     public static void render() {
//$$         if (!pending) return;
//$$         pending = false;
//$$         var target = SupersampleContext.target();
//$$         if (target == null) return;
//$$         mesh.clear();
//$$         float scale = SupersampleContext.lineWidthScale();
//$$         ChunkBorderLines.emit(minY, maxY, chunkX, chunkZ, cameraX, cameraY, cameraZ,
//$$                 (x0, y0, z0, x1, y1, z1, color, width) -> mesh.line(x0, y0, z0, x1, y1, z1, color, width * scale));
//$$         if (mesh.vertexCount() == 0) return;
//$$         GpuBuffer buffer = FORMAT.uploadImmediateVertexBuffer(mesh.bytes());
//$$         var transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(),
//$$                 new Vector4f(1), new Vector3f(), identity);
//$$         for (RenderPipeline pipeline : new RenderPipeline[]{COLOR, DEPTH}) {
//$$             var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
//$$             var depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
//$$             try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
//$$                     () -> "SSAA chunk borders", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
//$$                 pass.setPipeline(pipeline);
//$$                 RenderSystem.bindDefaultUniforms(pass);
//$$                 pass.setUniform("DynamicTransforms", transforms);
//$$                 pass.setVertexBuffer(0, buffer);
//$$                 pass.draw(0, mesh.vertexCount());
//$$             }
//$$         }
//$$     }
//$$     public static void close() { pending = false; mesh.close(); }
//$$ }
//#endif
