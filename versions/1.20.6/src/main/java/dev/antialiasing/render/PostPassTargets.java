package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Keeps auxiliary sampler sizes consistent with resized scene targets. */
public interface PostPassTargets {
    void taa$updateAuxSizes(Iterable<RenderTarget> targets);
}
