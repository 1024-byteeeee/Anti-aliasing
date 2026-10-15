//#if MC>=12100 && MC<12109
package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/** Each quad carries the same full-precision endpoint pair so its screen normal cannot twist. */
public final class DebugLineGeometry {
    private static final VertexFormatElement ENDPOINT = element(3);
    private static final VertexFormatElement CORNER = element(2);
    private static final VertexFormatElement END_COLOR = endColor();
    private static final VertexFormatElement WIDTH = element(1);
    private static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("LineEnd", ENDPOINT)
            .add("Color", VertexFormatElement.COLOR)
            .add("LineEndColor", END_COLOR)
            .add("LineCorner", CORNER).add("PixelWidth", WIDTH).build();
    private static final RenderPipeline COLOR_PIPELINE = pipeline("debug_lines", false);
    public static final RenderPipeline DEPTH_PIPELINE = pipeline("debug_lines_depth", true);
    private static final List<PendingStrip> pending = new ArrayList<>();
    private static boolean collecting, replaying, depthOnly;
    private static float replayWidth;
    private static ByteBufferBuilder scratch, snapshots, replay;
    private DebugLineGeometry() {}
    public static VertexFormat format() { return FORMAT; }

    private static RenderPipeline pipeline(String name, boolean depth) {
        return RenderPipelines.register(RenderPipeline.builder(
                    RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(ResourceLocation.fromNamespaceAndPath("taa", "pipeline/" + name))
            .withVertexShader(ResourceLocation.fromNamespaceAndPath("taa", "core/debug_lines_vertex_width"))
            .withFragmentShader(ResourceLocation.fromNamespaceAndPath("taa", "core/debug_lines"))
            .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS)
            .withCull(false)
            .withDepthTestFunction(RenderPipelines.DEBUG_LINE_STRIP.getDepthTestFunction())
            .withColorWrite(!depth).withDepthWrite(depth).build());
    }

    public static boolean beginBatch() {
        if (SupersampleContext.target() == null || collecting || replaying) return false;
        collecting = true;
        return true;
    }

    /** Copy before returning to callers that may recycle their shared buffer. */
    public static boolean enqueue(RenderType type, MeshData mesh, float width) {
        if (!collecting) return false;
        try (mesh) {
            if (snapshots == null) snapshots = new ByteBufferBuilder(65536);
            MeshData snapshot = copy(mesh, snapshots);
            try {
                pending.add(new PendingStrip(type, snapshot, width));
            } catch (Throwable error) {
                snapshot.close();
                throw error;
            }
        }
        return true;
    }

    private static MeshData copy(MeshData mesh, ByteBufferBuilder destination) {
        ByteBuffer source = mesh.vertexBuffer();
        long output = destination.reserve(source.remaining());
        MemoryUtil.memCopy(MemoryUtil.memAddress(source), output, source.remaining());
        return new MeshData(destination.build(), mesh.drawState());
    }

    public static boolean replaying() { return replaying; }
    public static float replayWidth() { return replayWidth; }
    public static RenderPipeline currentPipeline() { return depthOnly ? DEPTH_PIPELINE : COLOR_PIPELINE; }

    public static void flushBatch() {
        collecting = false;
        replaying = true;
        try {
            // Color first: coplanar red/blue borders cannot occlude each other.
            // Commit depth only after every color is final, before clouds and
            // other translucent passes can blend over lines in front of them.
            for (int pass = 0; pass < 2; pass++) {
                depthOnly = pass == 1;
                for (PendingStrip strip : pending) {
                    replayWidth = strip.width();
                    if (replay == null) replay = new ByteBufferBuilder(65536);
                    // Each draw owns a disposable input. Neither the original
                    // caller nor a wrapper closing it can invalidate our snapshot.
                    try (MeshData input = copy(strip.mesh(), replay)) {
                        strip.type().draw(input);
                    }
                }
            }
        } finally {
            discardBatch();
        }
    }

    public static void discardBatch() {
        collecting = replaying = depthOnly = false;
        replayWidth = 0;
        for (PendingStrip strip : pending) strip.mesh().close();
        pending.clear();
    }

    private record PendingStrip(RenderType type, MeshData mesh, float width) {}

    private static VertexFormatElement element(int count) {
        return element(VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, count);
    }
    private static VertexFormatElement endColor() {
        return element(VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4);
    }
    private static VertexFormatElement element(VertexFormatElement.Type type, VertexFormatElement.Usage usage, int count) {
        for (int id = 0; id < VertexFormatElement.MAX_COUNT; id++) {
            if (VertexFormatElement.byId(id) == null)
                return VertexFormatElement.register(id, 0, type, usage, count);
        }
        throw new IllegalStateException("No free vertex format element for SSAA debug lines");
    }

    public static boolean supports(MeshData mesh) {
        return SupersampleContext.target() != null && mesh.indexBuffer() == null
                && mesh.drawState().mode() == VertexFormat.Mode.DEBUG_LINE_STRIP
                && mesh.drawState().format() == DefaultVertexFormat.POSITION_COLOR;
    }

    public static MeshData expand(MeshData mesh, float width) {
        if (scratch == null) scratch = new ByteBufferBuilder(65536);
        scratch.clear();
        ByteBuffer source = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int stride = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
        int start = source.position();
        int vertices = 0;
        for (int i = 1; i < mesh.drawState().vertexCount(); i++) {
            int a = start + (i - 1) * stride, b = start + i * stride;
            if ((source.get(a + 15) & 255) == 0 && (source.get(b + 15) & 255) == 0) continue;
            float dx = source.getFloat(b) - source.getFloat(a);
            float dy = source.getFloat(b + 4) - source.getFloat(a + 4);
            float dz = source.getFloat(b + 8) - source.getFloat(a + 8);
            if (dx * dx + dy * dy + dz * dz < 1.0e-12f) continue;
            // QUADS supplies six triangle indices for these four explicit corners.
            long quad = scratch.reserve(4 * FORMAT.getVertexSize());
            vertex(quad, source, a, b, 0, -1, width);
            vertex(quad + FORMAT.getVertexSize(), source, a, b, 0, 1, width);
            vertex(quad + 2L * FORMAT.getVertexSize(), source, a, b, 1, 1, width);
            vertex(quad + 3L * FORMAT.getVertexSize(), source, a, b, 1, -1, width);
            vertices += 4;
        }
        if (vertices == 0) return null;
        return new MeshData(scratch.build(), new MeshData.DrawState(FORMAT, vertices, vertices / 4 * 6,
                VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(vertices)));
    }
    private static void vertex(long output, ByteBuffer input, int a, int b, float endpoint, float side, float width) {
        for (int axis = 0; axis < 3; axis++) {
            MemoryUtil.memPutFloat(output + axis * 4L, input.getFloat(a + axis * 4));
            MemoryUtil.memPutFloat(output + 12 + axis * 4L, input.getFloat(b + axis * 4));
        }
        for (int channel = 0; channel < 4; channel++) {
            MemoryUtil.memPutByte(output + 24 + channel, input.get(a + 12 + channel));
            MemoryUtil.memPutByte(output + 28 + channel, input.get(b + 12 + channel));
        }
        MemoryUtil.memPutFloat(output + 32, endpoint);
        MemoryUtil.memPutFloat(output + 36, side);
        MemoryUtil.memPutFloat(output + 40, width);
    }
    public static void close() {
        discardBatch();
        if (scratch != null) { scratch.close(); scratch = null; }
        if (snapshots != null) { snapshots.close(); snapshots = null; }
        if (replay != null) { replay.close(); replay = null; }
    }
}
//#endif
