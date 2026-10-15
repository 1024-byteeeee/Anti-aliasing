package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.function.Supplier;

/** Only the 3D portion of a frame observes the enlarged framebuffer. */
public final class SupersampleContext {
    private static RenderTarget target;
    private static float lineScale = 1;
    private static final ThreadLocal<Boolean> displaySize = ThreadLocal.withInitial(() -> false);
    private SupersampleContext() {}
    public static RenderTarget target() { return target; }
    public static float lineScale() { return lineScale; }
    public static boolean usesDisplaySize() { return displaySize.get(); }
    public static void withDisplaySize(Runnable action) {
        withDisplaySize(() -> { action.run(); return null; });
    }
    /** Only the synchronous line-width calculation observes native window dimensions. */
    public static <T> T withDisplaySize(Supplier<T> action) {
        if (target == null) return action.get();
        boolean previous = displaySize.get();
        displaySize.set(true);
        try { return action.get(); }
        finally { displaySize.set(previous); }
    }
    public static float lineWidthScale() {
        return target == null ? 1 : lineScale * (float) dev.antialiasing.TaaClient.CONFIG.ssaaLineWidthMultiplier;
    }
    static void begin(RenderTarget value) {
        int nativeWidth = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().width;
        target = value;
        lineScale = (float) value.width / nativeWidth;
    }
    static void end() { target = null; lineScale = 1; }
}
