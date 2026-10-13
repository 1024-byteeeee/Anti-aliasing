package dev.antialiasing.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemporalMathTest {
    @Test void customCyclesAreCenteredBoundedAndRepeatAtTheirConfiguredLength() {
        for (int count : new int[] {1, 2, 3, 7, 13, 64, 128, 255, 256}) {
            for (int axis = 0; axis < 2; axis++) {
                double sum = 0;
                for (int frame = 0; frame < count; frame++) {
                    float offset = TemporalMath.pixelOffset(frame, count, axis);
                    assertTrue(Float.isFinite(offset) && Math.abs(offset) <= 0.500001);
                    assertEquals(offset, TemporalMath.pixelOffset(frame + count, count, axis));
                    sum += offset;
                }
                assertEquals(0, sum / count, 1e-6);
            }
        }
    }
    @Test void handCameraRotationCancelsWhileWorldRotationRemains() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 100f);
        Vector4f hand = new Vector4f(0.3f, -0.2f, -0.7f, 1);
        Vector4f expected = projection.transform(new Vector4f(hand));
        for (float angle : new float[] {0, 0.4f, -1.2f}) {
            Matrix4f cameraView = new Matrix4f().rotateX(angle * 0.3f).rotateY(angle);
            // Vanilla renderItemInHand applies inverse view to its pose stack.
            Matrix4f combined = new Matrix4f(projection).mul(cameraView).mul(cameraView.invert(new Matrix4f()));
            Vector4f actual = combined.transform(new Vector4f(hand));
            assertEquals(expected.x / expected.w, actual.x / actual.w, 1e-6);
            assertEquals(expected.y / expected.w, actual.y / actual.w, 1e-6);
        }
    }
    @Test void allJitterPhasesResolveToTheSameOutputPosition() {
        Matrix4f stable = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 2048f)
                .rotateZ(0.05f).translate(0.01f, 0.02f, 0);
        Vector4f point = new Vector4f(1.2f, 0.7f, -10, 1);
        Vector4f expected = stable.transform(new Vector4f(point));
        expected.div(expected.w);
        for (int phase = 0; phase < 8; phase++) {
            float u = TemporalMath.pixelOffset(phase, 8, 0) / 1920;
            float v = TemporalMath.pixelOffset(phase, 8, 1) / 1080;
            Matrix4f raster = new Matrix4f().translation(u * 2, v * 2, 0).mul(stable);
            Matrix4f history = new Matrix4f().translation(-u * 2, -v * 2, 0).mul(raster);
            Vector4f rasterClip = raster.transform(new Vector4f(point));
            rasterClip.div(rasterClip.w);
            assertEquals(expected.x, rasterClip.x - 2 * u, 1e-6);
            assertEquals(expected.y, rasterClip.y - 2 * v, 1e-6);
            Vector4f historyClip = history.transform(new Vector4f(point));
            historyClip.div(historyClip.w);
            assertEquals(expected.x, historyClip.x, 1e-6);
            assertEquals(expected.y, historyClip.y, 1e-6);
        }
    }
    @Test void samplesRemainSubpixelAndDistinct() {
        java.util.Set<String> samples = new java.util.HashSet<>();
        for (int i = 0; i < 8; i++) {
            double x = TemporalMath.pixelOffset(i, 8, 0);
            double y = TemporalMath.pixelOffset(i, 8, 1);
            assertTrue(x >= -0.5 && x < 0.5);
            assertTrue(y >= -0.5 && y < 0.5);
            assertTrue(samples.add(x + ":" + y));
        }
    }
    @Test void clipTranslationProducesSamePixelShiftAtDifferentDepths() {
        Matrix4f base = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f)
                .rotateZ(0.05f).translate(0.01f, 0.02f, 0);
        float offsetX = 0.75f / 1920;
        float offsetY = -0.5f / 1080;
        Matrix4f jittered = new Matrix4f().translation(offsetX, offsetY, 0).mul(base);
        for (float depth : new float[] {-1, -10, -500}) {
            Vector4f a = base.transform(new Vector4f(0.2f, 0.3f, depth, 1));
            Vector4f b = jittered.transform(new Vector4f(0.2f, 0.3f, depth, 1));
            assertEquals(offsetX, b.x / b.w - a.x / a.w, 1e-7);
            assertEquals(offsetY, b.y / b.w - a.y / a.w, 1e-7);
        }
    }
    @Test void relativeCameraReprojectionMatchesAbsoluteWorldAtLargeCoordinates() {
        double oldX = 29_000_000.125;
        double newX = oldX + 0.25;
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f);
        Matrix4f oldView = new Matrix4f().rotateY(0.2f);
        Matrix4f newView = new Matrix4f().rotateY(0.23f);
        Matrix4f currentVP = new Matrix4f(projection).mul(newView);
        Vector4f currentRelative = new Vector4f(2, 1, -12, 1);
        Vector4f clip = currentVP.transform(new Vector4f(currentRelative));
        clip.div(clip.w);
        Vector4f reconstructed = currentVP.invert(new Matrix4f()).transform(clip);
        reconstructed.div(reconstructed.w);
        Vector4f actual = new Matrix4f(projection).mul(oldView).translate((float) (newX - oldX), 0, 0).transform(reconstructed);
        Vector4f expected = new Matrix4f(projection).mul(oldView).transform(new Vector4f(2.25f, 1, -12, 1));
        assertEquals(expected.x / expected.w, actual.x / actual.w, 1e-5);
        assertEquals(expected.y / expected.w, actual.y / actual.w, 1e-5);
        assertEquals(expected.z / expected.w, actual.z / actual.w, 1e-5);
    }
}
