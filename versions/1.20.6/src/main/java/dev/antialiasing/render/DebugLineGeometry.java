package dev.antialiasing.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.antialiasing.TaaClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Legacy API adapter for the same clipped pixel-space geometry used by 1.21.8. */
public final class DebugLineGeometry {
    private static final LineMesh mesh = new LineMesh();
    private static final LineMesh chunkReplay = new LineMesh();
    private static Draw chunkDraw;
    private static final LegacyGraphics overlayGraphics = new LegacyGraphics();
    private static RenderTarget layer;
    private static boolean layerReady;
    private static final List<Draw> pending = new ArrayList<>();
    private static int program, layerProgram, layerFramebuffer, vao, vertices, uniforms;
    private static boolean collecting, failed;
    private DebugLineGeometry() {}

    public static void beginFrame() { discardBatch(); chunkDraw = null; chunkReplay.clear(); layerReady = false; }

    public static boolean beginBatch() {
        if (SupersampleContext.target() == null || collecting || failed) return false;
        collecting = true;
        return true;
    }

    public static boolean prepare() {
        if (SupersampleContext.target() == null || failed) return false;
        if (program != 0) return true;
        try (LegacyGraphics.State state = new LegacyGraphics.State()) {
            init();
            return true;
        } catch (RuntimeException | OutOfMemoryError error) {
            close(); failed = true;
            TaaClient.LOGGER.error("SSAA debug line shader failed; using vanilla lines until reload", error);
            return false;
        }
    }

    public static boolean accept(ByteBuffer source, int count, int stride, int mode) {
        if (count < 2 || (mode != GL11.GL_LINES && mode != GL11.GL_LINE_STRIP && mode != GL11.GL_LINE_LOOP)
                || !prepare()) return false;
        ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
        input.clear();
        if ((long) count * stride > input.capacity()) throw new IllegalArgumentException("Invalid line vertex range");
        int first = mesh.vertexCount();
        float width = NativeLineAntialiasing.requestedWidth() * SupersampleContext.lineWidthScale();
        int step = mode == GL11.GL_LINES ? 2 : 1;
        for (int i = 1; i < count; i += step) mesh.line(input, (i - 1) * stride, i * stride, width);
        if (mode == GL11.GL_LINE_LOOP) mesh.line(input, (count - 1) * stride, 0, width);
        finish(first, false);
        return true;
    }

    public static void chunk(PoseStack pose, int minY, int maxY, int chunkX, int chunkZ, double x, double y, double z) {
        int first = mesh.vertexCount();
        float scale = SupersampleContext.lineWidthScale();
        //#if MC>=11900
        org.joml.Matrix4f matrix = pose.last().pose();
        org.joml.Vector3f start = new org.joml.Vector3f(), end = new org.joml.Vector3f();
        //#endif
        ChunkBorderLines.emit(minY, maxY, chunkX, chunkZ, x, y, z,
                (x0, y0, z0, x1, y1, z1, color, width) -> {
                    //#if MC>=11900
                    // Match vanilla's CPU pose transform before the captured
                    // RenderSystem model-view transform is applied by our shader.
                    matrix.transformPosition(x0, y0, z0, start);
                    matrix.transformPosition(x1, y1, z1, end);
                    mesh.line(start.x, start.y, start.z, end.x, end.y, end.z, color, width * scale);
                    //#else
                    //$$ mesh.line(x0, y0, z0, x1, y1, z1, color, width * scale);
                    //#endif
                });
        chunkReplay.copyFrom(mesh, first, mesh.vertexCount() - first);
        finish(first, true);
    }

    private static void finish(int first, boolean chunk) {
        if (mesh.vertexCount() == first) return;
        float[] data = snapshot();
        if (chunk) for (int i = 32; i < 36; i++) data[i] = 1;
        Draw draw = new Draw(first, mesh.vertexCount() - first, data, chunk);
        if (chunk) chunkDraw = draw;
        pending.add(draw);
        if (!collecting) flushBatch();
    }

    private static float[] snapshot() {
        float[] result = new float[40];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            //#if MC>=11903
            RenderSystem.getModelViewMatrix().get(matrix);
            //#elseif MC>=11700
            //$$ RenderSystem.getModelViewMatrix().store(matrix);
            //#else
            //$$ GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, matrix);
            //#endif
            for (int i = 0; i < 16; i++) result[i] = matrix.get(i);
            //#if MC>=11903
            RenderSystem.getProjectionMatrix().get(matrix);
            //#elseif MC>=11700
            //$$ RenderSystem.getProjectionMatrix().store(matrix);
            //#else
            //$$ GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, matrix);
            //#endif
            for (int i = 0; i < 16; i++) result[16 + i] = matrix.get(i);
        }
        //#if MC>=11700
        System.arraycopy(RenderSystem.getShaderColor(), 0, result, 32, 4);
        //#else
        //$$ for (int i = 32; i < 36; i++) result[i] = 1;
        //#endif
        result[36] = SupersampleContext.target().width;
        result[37] = SupersampleContext.target().height;
        return result;
    }

    public static void flushBatch() {
        collecting = false;
        if (pending.isEmpty()) { mesh.clear(); return; }
        Draw capturedChunk = chunkDraw;
        chunkDraw = null;
        try {
            RenderTarget target = SupersampleContext.target();
            if (capturedChunk != null) {
                try (LegacyGraphics.State state = new LegacyGraphics.State()) {
                    if (layer == null || layer.width != target.width || layer.height != target.height) {
                        releaseLayer();
                        layer = LegacyGraphics.target(target.width, target.height, true);
                    }
                    layer.clear(false);
                    draw(chunkReplay, Collections.singletonList(capturedChunk), true);
                }
            }
            draw(mesh, pending, false);
            if (capturedChunk != null) {
                try (LegacyGraphics.State state = new LegacyGraphics.State()) {
                    LegacyGraphics.copy(target, layer, GL11.GL_DEPTH_BUFFER_BIT);
                }
                layerReady = true;
            }
        }
        finally { discardBatch(); }
    }

    /** Reapply only chunk color after shader-pack final composition. */
    public static void repaintAfterWorld() {
        chunkDraw = null;
        try {
            if (layerReady && layer != null && SupersampleContext.target() != null) {
                try (LegacyGraphics.State state = new LegacyGraphics.State()) {
                    overlayGraphics.compositeLines(SupersampleContext.target(), layer);
                }
            }
        } finally { layerReady = false; chunkReplay.clear(); }
    }

    private static void draw(LineMesh geometry, List<Draw> draws, boolean captureLayer) {
        try (LegacyGraphics.State state = new LegacyGraphics.State(); MemoryStack stack = MemoryStack.stackPush()) {
            RenderTarget target = SupersampleContext.target();
            target.bindWrite(true);
            if (captureLayer) {
                if (layerFramebuffer == 0) layerFramebuffer = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, layerFramebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, layer.getColorTextureId(), 0);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL11.GL_TEXTURE_2D, target.getDepthTextureId(), 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
            GL20.glUseProgram(captureLayer ? layerProgram : program); GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, geometry.bytes(), GL15.GL_STREAM_DRAW);
            GL11.glDisable(GL11.GL_CULL_FACE);
            //#if MC<11700
            //$$ GL11.glDisable(GL11.GL_ALPHA_TEST);
            //#endif
            FloatBuffer data = stack.mallocFloat(40);
            for (int pass = 0; pass < (captureLayer ? 1 : 2); pass++) {
                GL11.glColorMask(pass == 0, pass == 0, pass == 0, pass == 0);
                for (Draw draw : draws) {
                    GL11.glDepthMask(pass == 1 && draw.writeDepth);
                    if (draw.depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                    if (pass == 0 && draw.blend) {
                        GL11.glEnable(GL11.GL_BLEND);
                        GL14.glBlendFuncSeparate(draw.srcRgb, draw.dstRgb, draw.srcAlpha, draw.dstAlpha);
                        GL20.glBlendEquationSeparate(draw.equationRgb, draw.equationAlpha);
                    } else GL11.glDisable(GL11.GL_BLEND);
                    data.clear(); data.put(draw.data).flip();
                    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniforms);
                    GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, data, GL15.GL_STREAM_DRAW);
                    GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, uniforms);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, captureLayer ? 0 : draw.first, draw.count);
                }
            }
        }
    }

    public static void discardBatch() { collecting = false; pending.clear(); mesh.clear(); }

    private static void init() {
        int vertex = 0, fragment = 0, layerFragment = 0;
        program = GL20.glCreateProgram();
        layerProgram = GL20.glCreateProgram();
        try {
            String source = LegacyGraphics.source("debug_lines_portable.vsh").replace(
                    "#moj_import <taa:line_position.glsl>", LegacyGraphics.source("/assets/taa/shaders/include/line_position.glsl"));
            vertex = LegacyGraphics.shader(GL20.GL_VERTEX_SHADER, source);
            fragment = LegacyGraphics.shader(GL20.GL_FRAGMENT_SHADER, LegacyGraphics.source("debug_lines.fsh"));
            layerFragment = LegacyGraphics.shader(GL20.GL_FRAGMENT_SHADER, LegacyGraphics.source("debug_lines_layer.fsh"));
            String[] names = {"Position", "LineEnd", "Color", "LineEndColor", "LineCorner", "PixelWidth"};
            for (int shader : new int[]{program, layerProgram}) {
                GL20.glAttachShader(shader, vertex);
                GL20.glAttachShader(shader, shader == program ? fragment : layerFragment);
                for (int i = 0; i < names.length; i++) GL20.glBindAttribLocation(shader, i, names[i]);
                GL30.glBindFragDataLocation(shader, 0, "fragColor");
                GL20.glLinkProgram(shader);
                if (GL20.glGetProgrami(shader, GL20.GL_LINK_STATUS) == 0)
                    throw new IllegalStateException(GL20.glGetProgramInfoLog(shader));
                GL31.glUniformBlockBinding(shader, GL31.glGetUniformBlockIndex(shader, "LineData"), 0);
            }
            vao = GL30.glGenVertexArrays(); vertices = GL15.glGenBuffers(); uniforms = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
            int[] counts = {3, 3, 4, 4, 2, 1}, offsets = {0, 12, 24, 28, 32, 40};
            for (int i = 0; i < counts.length; i++) {
                GL20.glEnableVertexAttribArray(i);
                boolean color = i == 2 || i == 3;
                GL20.glVertexAttribPointer(i, counts[i], color ? GL11.GL_UNSIGNED_BYTE : GL11.GL_FLOAT,
                        color, LineMesh.STRIDE, offsets[i]);
            }
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
            if (layerFragment != 0) GL20.glDeleteShader(layerFragment);
        }
    }

    public static void close() {
        beginFrame(); releaseLayer(); mesh.close(); chunkReplay.close(); overlayGraphics.close();
        if (program != 0) GL20.glDeleteProgram(program);
        if (layerProgram != 0) GL20.glDeleteProgram(layerProgram);
        if (layerFramebuffer != 0) GL30.glDeleteFramebuffers(layerFramebuffer);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vertices != 0) GL15.glDeleteBuffers(vertices);
        if (uniforms != 0) GL15.glDeleteBuffers(uniforms);
        program = layerProgram = layerFramebuffer = vao = vertices = uniforms = 0; failed = false;
    }

    public static void releaseLayer() {
        layerReady = false;
        // Drop attachment references before destroying their textures.
        if (layerFramebuffer != 0) { GL30.glDeleteFramebuffers(layerFramebuffer); layerFramebuffer = 0; }
        overlayGraphics.releaseLineOutput();
        if (layer != null) { layer.destroyBuffers(); layer = null; }
    }

    private static final class Draw {
        final int first, count;
        final float[] data;
        final boolean depthTest, writeDepth, blend;
        final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        Draw(int first, int count, float[] data, boolean chunk) {
            this.first = first; this.count = count; this.data = data;
            depthTest = chunk || GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            writeDepth = chunk || GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            blend = !chunk && GL11.glIsEnabled(GL11.GL_BLEND);
        }
    }
}
