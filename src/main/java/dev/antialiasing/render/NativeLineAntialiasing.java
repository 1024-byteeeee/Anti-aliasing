package dev.antialiasing.render;

/** Keep the requested display-pixel width independently of the legacy GL state. */
public final class NativeLineAntialiasing {
    private static float legacyWidth = 1;
    private NativeLineAntialiasing() {}

    public static void requestedWidth(float width) { legacyWidth = width; }
    public static float requestedWidth() { return legacyWidth; }

    public static float legacyWidth(float width) {
        legacyWidth = width;
        // A forward-compatible context forbids wide lines even when the driver
        // reports a wider ALIASED_LINE_WIDTH_RANGE. Never request one during SSAA.
        return SupersampleContext.lineScale() > 1 ? 1.0f : width;
    }
}
