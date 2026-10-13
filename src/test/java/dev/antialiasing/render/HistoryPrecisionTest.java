package dev.antialiasing.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryPrecisionTest {
    @Test void subpixelDetailVisibleInOnlyOneOfEightPhasesKeepsItsAverageCoverage() {
        double color = 128.0 / 255.0;
        for (int frame = 0; frame < 512; frame++) {
            double sample = (frame % 8 == 0 ? 132.0 : 128.0) / 255.0;
            color = store(color * .97 + sample * .03);
        }
        assertEquals(128.5 / 255.0, color, 0.1 / 255.0);
    }

    @Test void weakDetailsConvergeRatherThanStickingAtEightBitRoundingThreshold() {
        // A weak glass line contributes four byte levels over a flat background.
        double target = 132.0 / 255.0;
        double coarse = 128.0 / 255.0;
        double corrected = coarse;
        for (int frame = 0; frame < 256; frame++) {
            coarse = Math.round((coarse * .97 + target * .03) * 255) / 255.0;
            corrected = store(corrected * .97 + target * .03);
        }
        assertEquals(128.0 / 255.0, coarse, 1e-12);
        assertEquals(target, corrected, 0.2 / 255.0);
    }

    @Test void fullHistoryWeightRetainsAnExactlyRepresentableColorWithoutResidualDrift() {
        for (int value : new int[] {0, 1, 128, 254, 255}) {
            double color = value / 255.0;
            for (int frame = 0; frame < 10000; frame++) color = store(color);
            assertEquals(value / 255.0, color, 1e-12);
        }
    }

    private double store(double value) {
        double rounded = Math.round(value * 255) / 255.0;
        double residual = Math.round((value - rounded) * 254 * 255 + 128);
        return rounded + (residual - 128) / (254 * 255);
    }
}
