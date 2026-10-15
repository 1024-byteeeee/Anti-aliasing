package dev.antialiasing.mixin;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.antialiasing.render.DebugLineGeometry;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferUploader.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class LegacyDebugLineMixin {
    //#if MC>=11900
    @Inject(method = "_drawWithShader", at = @At("HEAD"), cancellable = true)
    private static void taa$lines(BufferBuilder.RenderedBuffer buffer, CallbackInfo ci) {
        BufferBuilder.DrawState state = buffer.drawState();
        if (state.format() == DefaultVertexFormat.POSITION_COLOR
                && DebugLineGeometry.accept(buffer.vertexBuffer(), state.vertexCount(), state.format().getVertexSize(), state.mode().asGLMode)) {
            buffer.release();
            ci.cancel();
        }
    }
    //#elseif MC>=11700
    //$$ @Inject(method = "_end", at = @At("HEAD"), cancellable = true)
    //$$ private static void taa$lines(ByteBuffer buffer, VertexFormat.Mode mode, VertexFormat format, int count,
    //$$                               VertexFormat.IndexType type, int indices, boolean sequential, CallbackInfo ci) {
    //$$     if (format == DefaultVertexFormat.POSITION_COLOR
    //$$             && DebugLineGeometry.accept(buffer, count, format.getVertexSize(), mode.asGLMode)) ci.cancel();
    //$$ }
    //#else
    //$$ @Inject(method = "_end", at = @At("HEAD"), cancellable = true)
    //$$ private static void taa$lines(ByteBuffer buffer, int mode, VertexFormat format, int count, CallbackInfo ci) {
    //$$     if (format == DefaultVertexFormat.POSITION_COLOR
    //$$             && DebugLineGeometry.accept(buffer, count, format.getVertexSize(), mode)) ci.cancel();
    //$$ }
    //#endif
}
