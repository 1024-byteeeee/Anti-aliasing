package dev.antialiasing.render;

import dev.antialiasing.mixin.Matrix4fAccessor;

import com.mojang.math.Matrix4f;
import java.nio.FloatBuffer;

public final class Matrix4fLoader {
    private Matrix4fLoader() { }
    public static void load(Matrix4f matrix, FloatBuffer data) {
        Matrix4fAccessor access = (Matrix4fAccessor) (Object) matrix;
        access.taa$m00(data.get(0)); access.taa$m01(data.get(4)); access.taa$m02(data.get(8)); access.taa$m03(data.get(12));
        access.taa$m10(data.get(1)); access.taa$m11(data.get(5)); access.taa$m12(data.get(9)); access.taa$m13(data.get(13));
        access.taa$m20(data.get(2)); access.taa$m21(data.get(6)); access.taa$m22(data.get(10)); access.taa$m23(data.get(14));
        access.taa$m30(data.get(3)); access.taa$m31(data.get(7)); access.taa$m32(data.get(11)); access.taa$m33(data.get(15));
    }
}
