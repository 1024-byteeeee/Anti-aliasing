package dev.antialiasing.render;

import java.util.OptionalDouble;

/** Declared display-pixel width, independent of mutable RenderSystem state. */
public interface LineWidthSource {
    OptionalDouble taa$lineWidth();
}
