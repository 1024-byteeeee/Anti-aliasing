package dev.antialiasing.render;

/** The 1.21.8 chunk grid, independent of the version's rendering API. */
public final class ChunkBorderLines {
    private static final int RED = 0x7fff0000, YELLOW = 0xffffff00;
    private static final int CYAN = 0xff009b9b, BLUE = 0xff3f3fff;
    private ChunkBorderLines() {}

    @FunctionalInterface
    public interface Sink {
        void line(float x0, float y0, float z0, float x1, float y1, float z1, int color, float width);
    }

    public static void emit(int minY, int maxY, int chunkX, int chunkZ,
                            double cameraX, double cameraY, double cameraZ, Sink sink) {
        float x = (float) ((chunkX << 4) - cameraX), z = (float) ((chunkZ << 4) - cameraZ);
        float bottom = (float) (minY - cameraY), top = (float) (maxY - cameraY);
        for (int dx = -16; dx <= 32; dx += 16) for (int dz = -16; dz <= 32; dz += 16)
            sink.line(x + dx, bottom, z + dz, x + dx, top, z + dz, RED, 1);
        for (int offset = 2; offset < 16; offset += 2) {
            int color = offset % 4 == 0 ? CYAN : YELLOW;
            sink.line(x + offset, bottom, z, x + offset, top, z, color, 1);
            sink.line(x + offset, bottom, z + 16, x + offset, top, z + 16, color, 1);
        }
        for (int offset = 2; offset < 16; offset += 2) {
            int color = offset % 4 == 0 ? CYAN : YELLOW;
            sink.line(x, bottom, z + offset, x, top, z + offset, color, 1);
            sink.line(x + 16, bottom, z + offset, x + 16, top, z + offset, color, 1);
        }
        for (int y = minY; y <= maxY; y += 2)
            box(x, (float) (y - cameraY), z, y % 8 == 0 ? CYAN : YELLOW, 1, sink);
        for (int dx = 0; dx <= 16; dx += 16) for (int dz = 0; dz <= 16; dz += 16)
            sink.line(x + dx, bottom, z + dz, x + dx, top, z + dz, BLUE, 2);
        for (int y = minY; y <= maxY; y += 16)
            box(x, (float) (y - cameraY), z, BLUE, 2, sink);
    }

    private static void box(float x, float y, float z, int color, float width, Sink sink) {
        sink.line(x, y, z, x, y, z + 16, color, width);
        sink.line(x, y, z + 16, x + 16, y, z + 16, color, width);
        sink.line(x + 16, y, z + 16, x + 16, y, z, color, width);
        sink.line(x + 16, y, z, x, y, z, color, width);
    }
}
