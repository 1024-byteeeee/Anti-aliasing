//#if MC>=12100 && MC<12109
package dev.antialiasing.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.CompositeState.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public interface CompositeStateAccessor {
    @Accessor("states")
    ImmutableList<RenderStateShard> taa$states();
}
//#endif
