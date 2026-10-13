package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Only the 3D portion of a frame observes the enlarged framebuffer. */
public final class SupersampleContext {
    private static RenderTarget target;
    private SupersampleContext() {}
    public static RenderTarget target() { return target; }
    static void begin(RenderTarget value) { target = value; }
    static void end() { target = null; }
}
