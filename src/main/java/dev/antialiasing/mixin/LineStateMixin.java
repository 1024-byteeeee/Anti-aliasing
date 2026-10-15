//#if MC<12109
package dev.antialiasing.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.antialiasing.render.SupersampleContext;
import dev.antialiasing.render.LineWidthSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import java.util.OptionalDouble;

@Mixin(targets = "net.minecraft.client.renderer.RenderStateShard$LineStateShard")
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class LineStateMixin implements LineWidthSource {
    @Shadow @Final private OptionalDouble width;

    @Override
    public OptionalDouble taa$lineWidth() { return width; }

    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderStateShard;<init>(Ljava/lang/String;Ljava/lang/Runnable;Ljava/lang/Runnable;)V"))
    private static void taa$setupWidth(Args args, OptionalDouble width) {
        if (width.isPresent() && width.getAsDouble() != 1.0) return;
        Runnable original = args.get(1);
        args.set(1, (Runnable) () -> {
            if (!width.isPresent()) {
                // Keep the original callback and any other wrappers. Its automatic
                // width calculation must see display dimensions, not SSAA dimensions.
                SupersampleContext.withDisplaySize(original);
                return;
            }
            // Vanilla omits this assignment for its default-width render type.
            // Make it explicit while SSAA is active, including for native debug strips.
            if (SupersampleContext.target() != null) RenderSystem.lineWidth(1.0f);
            original.run();
        });
    }
}
//#endif
