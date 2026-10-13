package dev.antialiasing.mixin;

import com.mojang.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.nio.FloatBuffer;

@Mixin(Matrix4f.class)
public interface Matrix4fAccessor {
    @Accessor("m00") void taa$m00(float value);
    @Accessor("m01") void taa$m01(float value);
    @Accessor("m02") void taa$m02(float value);
    @Accessor("m03") void taa$m03(float value);
    @Accessor("m10") void taa$m10(float value);
    @Accessor("m11") void taa$m11(float value);
    @Accessor("m12") void taa$m12(float value);
    @Accessor("m13") void taa$m13(float value);
    @Accessor("m20") void taa$m20(float value);
    @Accessor("m21") void taa$m21(float value);
    @Accessor("m22") void taa$m22(float value);
    @Accessor("m23") void taa$m23(float value);
    @Accessor("m30") void taa$m30(float value);
    @Accessor("m31") void taa$m31(float value);
    @Accessor("m32") void taa$m32(float value);
    @Accessor("m33") void taa$m33(float value);
}
