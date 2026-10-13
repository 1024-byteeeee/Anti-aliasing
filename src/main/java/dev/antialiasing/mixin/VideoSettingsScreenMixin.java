package dev.antialiasing.mixin;

import dev.antialiasing.ui.TaaSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    protected VideoSettingsScreenMixin(Screen parent, Options options, Component title) { super(parent, options, title); }
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void taa$settingsButton(CallbackInfo ci) {
        list.addSmall(Button.builder(Component.translatable("taa.title"), button ->
                dev.antialiasing.TaaClient.openSettings((Screen) (Object) this)).build(), null);
    }
}
