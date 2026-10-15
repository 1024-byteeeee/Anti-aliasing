package dev.antialiasing.mixin;

import dev.antialiasing.TaaClient;
import dev.antialiasing.ui.TaaSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class VideoSettingsScreenMixin extends Screen {
    protected VideoSettingsScreenMixin(Component title) { super(title); }
    @Inject(method = "init", at = @At("TAIL"))
    private void taa$button(CallbackInfo ci) {
        //#if MC>=11903
        addRenderableWidget(Button.builder(TaaSettingsScreen.label("taa.video.button"), b -> TaaClient.openSettings(this))
                .bounds(width - 101, 6, 96, 20).build());
        //#elseif MC>=11700
        //$$ addRenderableWidget(new Button(width - 101, 6, 96, 20, TaaSettingsScreen.label("taa.video.button"), b -> TaaClient.openSettings(this)));
        //#else
        //$$ addButton(new Button(width - 101, 6, 96, 20, TaaSettingsScreen.label("taa.video.button"), b -> TaaClient.openSettings(this)));
        //#endif
    }
}
