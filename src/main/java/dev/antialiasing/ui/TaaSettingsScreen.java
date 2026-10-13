package dev.antialiasing.ui;

import dev.antialiasing.TaaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import java.util.Locale;

public final class TaaSettingsScreen extends OptionsSubScreen {
    private StrengthSlider strength;
    private HistorySlider history;
    private EditBox samples;
    private Button enabled, mode, debug, done, motion, algorithm, ssaaScale, apply, clear;
    private String lastStatus;
    private StringWidget note, heading, status;
    private boolean dirty;
    public TaaSettingsScreen(Screen parent) {
        super(rootParent(parent), Minecraft.getInstance().options, Component.translatable("taa.title"));
    }
    private static Screen rootParent(Screen parent) {
        while (parent instanceof TaaSettingsScreen settings) parent = settings.lastScreen;
        return parent;
    }
    public static TaaSettingsScreen forParent(Screen parent) {
        return parent instanceof TaaSettingsScreen settings ? settings : new TaaSettingsScreen(parent);
    }
    @Override protected void init() {
        String pending = samples == null ? Integer.toString(TaaClient.CONFIG.sampleCount) : samples.getValue();
        // Do not add new children to OptionsSubScreen's persistent layout on every init.
        list = addRenderableWidget(new OptionsList(minecraft, width, this));
        addOptions();
        samples.setValue(pending);
        heading = addRenderableWidget(new StringWidget(310, 20, title, font));
        done = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
        repositionElements();
    }
    @Override protected void repositionElements() {
        if (list != null) list.updateSize(width, layout);
        if (heading != null) heading.setPosition(width / 2 - 155, 7);
        if (done != null) done.setPosition(width / 2 - 100, height - 27);
    }
    @Override protected void addOptions() {
        var config = TaaClient.CONFIG;
        lastStatus = TaaClient.statusKey();
        status = new StringWidget(310, 20, Component.translatable(lastStatus), font);
        list.addSmall(status, null);
        enabled = Button.builder(enabledLabel(), b -> {
            config.enabled = !config.enabled;
            b.setMessage(enabledLabel()); TaaClient.RENDERER.invalidate(); TaaClient.SSAA.reset(); changed();
            status.setMessage(Component.translatable(TaaClient.statusKey()));
        }).build();
        list.addSmall(enabled, Button.builder(Component.translatable("taa.reset"), b -> {
            config.defaults(); TaaClient.RENDERER.invalidate(); TaaClient.SSAA.reset(); changed();
            enabled.setMessage(enabledLabel()); mode.setMessage(sharpenLabel()); debug.setMessage(debugLabel());
            motion.setMessage(motionLabel());
            status.setMessage(Component.translatable(TaaClient.statusKey()));
            history.refresh(); strength.refresh(); samples.setValue(Integer.toString(config.sampleCount));
            refreshAlgorithm();
        }).build());
        algorithm = Button.builder(algorithmLabel(), b -> {
            config.antialiasingMode = (config.antialiasingMode + 1) % 2;
            TaaClient.RENDERER.invalidate(); TaaClient.SSAA.reset(); changed(); refreshAlgorithm();
        }).build();
        ssaaScale = Button.builder(ssaaLabel(), b -> {
            config.ssaaScale = config.ssaaScale == 4 ? 2 : config.ssaaScale + 1;
            TaaClient.SSAA.reset(); changed(); refreshAlgorithm();
        }).tooltip(Tooltip.create(Component.translatable("taa.ssaa.tooltip"))).build();
        list.addSmall(algorithm, ssaaScale);
        history = new HistorySlider();
        list.addSmall(history, null);
        motion = Button.builder(motionLabel(), b -> {
            config.reduceHistoryOnMotion = !config.reduceHistoryOnMotion;
            b.setMessage(motionLabel()); changed();
        }).width(310).tooltip(Tooltip.create(Component.translatable("taa.motion.tooltip"))).build();
        list.addSmall(motion, null);
        samples = new EditBox(font, 0, 0, 150, 20, Component.translatable("taa.samples"));
        samples.setMaxLength(3);
        samples.setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'));
        samples.setHint(Component.translatable("taa.samples.hint"));
        samples.setTooltip(Tooltip.create(Component.translatable("taa.samples.tooltip")));
        apply = Button.builder(Component.translatable("taa.samples.apply"), b -> applySamples()).build();
        list.addSmall(samples, apply);
        strength = new StrengthSlider();
        mode = Button.builder(sharpenLabel(), b -> {
            config.sharpeningMode = (config.sharpeningMode + 1) % 3;
            b.setMessage(sharpenLabel()); strength.refresh(); changed();
        }).build();
        list.addSmall(mode, strength);
        debug = Button.builder(debugLabel(), b -> {
            config.debugView = (config.debugView + 1) % 4;
            b.setMessage(debugLabel()); TaaClient.RENDERER.invalidate(); changed();
        }).width(310).tooltip(Tooltip.create(Component.translatable("taa.debug.tooltip"))).build();
        list.addSmall(debug, null);
        clear = Button.builder(Component.translatable("taa.clear.history"), b -> TaaClient.RENDERER.invalidate()).width(310).build();
        list.addSmall(clear, null);
        list.addSmall(new StringWidget(310, 20, Component.translatable("taa.coverage"), font), null);
        note = new StringWidget(310, 20, Component.translatable("taa.note"), font);
        list.addSmall(note, null);
        refreshAlgorithm();
    }
    private Component algorithmLabel() {
        return Component.translatable("taa.algorithm", TaaClient.CONFIG.antialiasingMode == 0 ? "TAA" : "SSAA");
    }
    private Component ssaaLabel() {
        int scale = TaaClient.CONFIG.ssaaScale;
        return Component.translatable("taa.ssaa.scale", scale * scale, scale, scale);
    }
    private void refreshAlgorithm() {
        boolean temporal = TaaClient.CONFIG.antialiasingMode == 0;
        algorithm.setMessage(algorithmLabel()); ssaaScale.setMessage(ssaaLabel());
        ssaaScale.active = !temporal;
        history.active = temporal; motion.active = temporal; samples.active = temporal;
        samples.setEditable(temporal);
        apply.active = temporal; debug.active = temporal; clear.active = temporal;
        status.setMessage(Component.translatable(TaaClient.statusKey()));
    }
    @Override public void tick() {
        super.tick();
        String key = TaaClient.statusKey();
        if (!key.equals(lastStatus)) { lastStatus = key; status.setMessage(Component.translatable(key)); }
    }
    private boolean applySamples() {
        try {
            int count = Integer.parseInt(samples.getValue());
            if (count < 1 || count > 256) throw new NumberFormatException();
            if (count != TaaClient.CONFIG.sampleCount) {
                TaaClient.CONFIG.sampleCount = count; TaaClient.RENDERER.invalidate(); changed();
            }
            note.setMessage(Component.translatable("taa.note"));
            samples.setValue(Integer.toString(count));
            return true;
        } catch (NumberFormatException e) {
            note.setMessage(Component.translatable("taa.samples.invalid"));
            samples.setFocused(true);
            return false;
        }
    }
    private Component enabledLabel() {
        return Component.translatable("taa.enabled", Component.translatable(TaaClient.CONFIG.enabled ? "options.on" : "options.off"));
    }
    private Component sharpenLabel() {
        return Component.translatable("taa.sharpen.mode", Component.translatable("taa.sharpen.mode." + TaaClient.CONFIG.sharpeningMode));
    }
    private Component motionLabel() {
        return Component.translatable("taa.motion", Component.translatable(TaaClient.CONFIG.reduceHistoryOnMotion ? "options.on" : "options.off"));
    }
    private Component debugLabel() {
        return Component.translatable("taa.debug", Component.translatable("taa.debug." + TaaClient.CONFIG.debugView));
    }
    private static String format(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private void changed() { dirty = true; }
    private void save() {
        if (dirty) {
            boolean success = TaaClient.CONFIG.save(); dirty = !success;
            if (note != null) note.setMessage(Component.translatable(success ? "taa.note" : "taa.save.failed"));
        }
    }
    @Override public void onClose() { if (TaaClient.CONFIG.antialiasingMode == 1 || applySamples()) { save(); if (!dirty) super.onClose(); } }
    @Override public void removed() { save(); super.removed(); }
    private final class HistorySlider extends AbstractSliderButton {
        HistorySlider() { super(0, 0, 310, 20, Component.empty(), 0); refresh(); setTooltip(Tooltip.create(Component.translatable("taa.history.tooltip"))); }
        void refresh() { value = TaaClient.CONFIG.historyWeight; updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.translatable("taa.history", format(value))); }
        @Override protected void applyValue() { TaaClient.CONFIG.historyWeight = value; changed(); }
    }
    private final class StrengthSlider extends AbstractSliderButton {
        StrengthSlider() { super(0, 0, 150, 20, Component.empty(), 0); refresh(); }
        void refresh() {
            var config = TaaClient.CONFIG;
            active = config.sharpeningMode != 0;
            value = config.sharpeningMode == 2 ? (config.casAmount - 0.1) / 0.9 : config.sharpening;
            setTooltip(Tooltip.create(Component.translatable(config.sharpeningMode == 2 ? "taa.cas.tooltip" : "taa.sharpen.tooltip")));
            updateMessage();
        }
        @Override protected void updateMessage() {
            int type = TaaClient.CONFIG.sharpeningMode;
            setMessage(Component.translatable(type == 2 ? "taa.cas" : "taa.sharpen", format(type == 2 ? 0.1 + value * 0.9 : value)));
        }
        @Override protected void applyValue() {
            if (TaaClient.CONFIG.sharpeningMode == 2) TaaClient.CONFIG.casAmount = 0.1 + value * 0.9;
            else TaaClient.CONFIG.sharpening = value;
            changed();
        }
    }
}
