package dev.antialiasing.render;

public final class TemporalMath {
    private static final float[] EIGHT_SAMPLES = {
        0.125f, -0.375f, -0.125f, 0.375f, 0.625f, 0.125f, 0.375f, -0.625f,
        -0.625f, 0.625f, -0.875f, -0.125f, 0.375f, -0.875f, 0.875f, 0.875f
    };
    private TemporalMath() {}
    private static int cachedCount;
    private static float[][] cachedSamples;
    /** Screen-pixel offsets. Center each cycle to avoid a persistent camera offset. */
    public static float pixelOffset(int frame, int count, int axis) {
        count = Math.max(1, Math.min(256, count));
        if (count == 8) return EIGHT_SAMPLES[Math.floorMod(frame, 8) * 2 + axis] * 0.5f;
        if (cachedCount != count) {
            float[][] samples = new float[count][2];
            for (int a = 0; a < 2; a++) {
                double mean = 0;
                for (int i = 0; i < count; i++) {
                    samples[i][a] = (float) halton(i + 1, a == 0 ? 2 : 3);
                    mean += samples[i][a];
                }
                mean /= count;
                double extent = 0;
                for (int i = 0; i < count; i++) extent = Math.max(extent, Math.abs(samples[i][a] - mean));
                double scale = extent > 0.5 ? 0.5 / extent : 1;
                for (int i = 0; i < count; i++) samples[i][a] = (float) ((samples[i][a] - mean) * scale);
            }
            cachedSamples = samples;
            cachedCount = count;
        }
        return cachedSamples[Math.floorMod(frame, count)][axis];
    }
    private static double halton(int index, int base) {
        double result = 0, fraction = 1;
        while (index > 0) { fraction /= base; result += fraction * (index % base); index /= base; }
        return result;
    }
}
