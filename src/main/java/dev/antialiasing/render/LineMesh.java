package dev.antialiasing.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/** Six triangle vertices per line; endpoints and colors match the 1.21.8 layout. */
public final class LineMesh implements AutoCloseable {
    public static final int STRIDE = 44;
    private static final float[] CORNERS = {0, -1, 0, 1, 1, 1, 1, 1, 1, -1, 0, -1};
    private ByteBuffer buffer;
    private int vertices;

    public int vertexCount() { return vertices; }
    public void clear() { vertices = 0; }
    public ByteBuffer bytes() {
        ByteBuffer view = buffer.duplicate().order(ByteOrder.nativeOrder());
        view.position(0).limit(vertices * STRIDE);
        return view;
    }

    public void line(float x0, float y0, float z0, float x1, float y1, float z1, int color, float width) {
        line(x0, y0, z0, x1, y1, z1, color, color, width);
    }

    public void line(ByteBuffer source, int a, int b, float width) {
        line(source.getFloat(a), source.getFloat(a + 4), source.getFloat(a + 8),
                source.getFloat(b), source.getFloat(b + 4), source.getFloat(b + 8),
                color(source, a + 12), color(source, b + 12), width);
    }

    private static int color(ByteBuffer source, int offset) {
        return (source.get(offset + 3) & 255) << 24 | (source.get(offset) & 255) << 16
                | (source.get(offset + 1) & 255) << 8 | source.get(offset + 2) & 255;
    }

    private void line(float x0, float y0, float z0, float x1, float y1, float z1,
                       int color0, int color1, float width) {
        if ((color0 >>> 24) == 0 && (color1 >>> 24) == 0) return;
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        if (dx * dx + dy * dy + dz * dz < 1.0e-12f) return;
        int required = Math.multiplyExact(vertices + 6, STRIDE);
        if (buffer == null) buffer = MemoryUtil.memAlloc(Math.max(65536, required));
        else if (buffer.capacity() < required)
            buffer = MemoryUtil.memRealloc(buffer, Math.max(required, buffer.capacity() * 2));
        long address = MemoryUtil.memAddress(buffer) + (long) vertices * STRIDE;
        for (int i = 0; i < 6; i++, address += STRIDE) {
            MemoryUtil.memPutFloat(address, x0); MemoryUtil.memPutFloat(address + 4, y0); MemoryUtil.memPutFloat(address + 8, z0);
            MemoryUtil.memPutFloat(address + 12, x1); MemoryUtil.memPutFloat(address + 16, y1); MemoryUtil.memPutFloat(address + 20, z1);
            rgba(address + 24, color0); rgba(address + 28, color1);
            MemoryUtil.memPutFloat(address + 32, CORNERS[i * 2]);
            MemoryUtil.memPutFloat(address + 36, CORNERS[i * 2 + 1]);
            MemoryUtil.memPutFloat(address + 40, width);
        }
        vertices += 6;
    }

    private static void rgba(long address, int color) {
        MemoryUtil.memPutByte(address, (byte) (color >> 16));
        MemoryUtil.memPutByte(address + 1, (byte) (color >> 8));
        MemoryUtil.memPutByte(address + 2, (byte) color);
        MemoryUtil.memPutByte(address + 3, (byte) (color >> 24));
    }

    @Override public void close() {
        if (buffer != null) { MemoryUtil.memFree(buffer); buffer = null; }
        vertices = 0;
    }
}
