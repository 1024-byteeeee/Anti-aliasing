package dev.antialiasing.ui;

import dev.antialiasing.TaaClient;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//#if MC>=12000
import net.minecraft.client.gui.GuiGraphics;
//#else
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//#endif
//#if MC<11900
//$$ import net.minecraft.network.chat.TranslatableComponent;
//$$ import net.minecraft.network.chat.TextComponent;
//#endif
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Scrollable settings screen for the older widget APIs. */
public final class TaaSettingsScreen extends Screen {
    private final Screen lastScreen;
    private final List<AbstractWidget> controls = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>(), columns = new ArrayList<>();
    private EditBox samples;
    private Button enabled, algorithm, scale, motion, mode, debug, apply, clear;
    private HistorySlider history;
    private StrengthSlider strength;
    private int scroll;
    private boolean dirty;
    private String note = "taa.note";
    public TaaSettingsScreen(Screen parent) { super(label("taa.title")); lastScreen = rootParent(parent); }
    private static Screen rootParent(Screen parent) {
        while (parent instanceof TaaSettingsScreen) parent = ((TaaSettingsScreen) parent).lastScreen;
        return parent;
    }
    public static TaaSettingsScreen forParent(Screen parent) {
        return parent instanceof TaaSettingsScreen ? (TaaSettingsScreen) parent : new TaaSettingsScreen(parent);
    }
    public static Component label(String key, Object... values) {
        //#if MC>=11900
        return Component.translatable(key, values);
        //#else
        //$$ return new TranslatableComponent(key, values);
        //#endif
    }
    private static Component empty() {
        //#if MC>=11900
        return Component.empty();
        //#else
        //$$ return TextComponent.EMPTY;
        //#endif
    }
    private <T extends AbstractWidget> T add(T widget) {
        //#if MC>=11700
        return addRenderableWidget(widget);
        //#else
        //$$ return addButton(widget);
        //#endif
    }
    private <T extends AbstractWidget> T row(T widget, int row, int column) {
        controls.add(widget); rows.add(row); columns.add(column); return add(widget);
    }
    private Button button(Component text, Button.OnPress action, int width) {
        //#if MC>=11903
        return Button.builder(text, action).width(width).build();
        //#else
        //$$ return new Button(0, 0, width, 20, text, action);
        //#endif
    }
    @Override protected void init() {
        String pending = samples == null ? Integer.toString(TaaClient.CONFIG.sampleCount) : samples.getValue();
        controls.clear(); rows.clear(); columns.clear();
        enabled = row(button(enabledLabel(), b -> {
            TaaClient.CONFIG.enabled = !TaaClient.CONFIG.enabled; resetRenderers(); changed(); refresh();
        }, 150), 0, 0);
        row(button(label("taa.reset"), b -> {
            TaaClient.CONFIG.defaults(); resetRenderers(); changed();
            samples.setValue(Integer.toString(TaaClient.CONFIG.sampleCount)); history.refresh(); strength.refresh(); refresh();
        }, 150), 0, 1);
        algorithm = row(button(empty(), b -> {
            TaaClient.CONFIG.antialiasingMode ^= 1; resetRenderers(); changed(); refresh();
        }, 150), 1, 0);
        scale = row(button(empty(), b -> {
            TaaClient.CONFIG.ssaaScale = TaaClient.CONFIG.ssaaScale == 4 ? 2 : TaaClient.CONFIG.ssaaScale + 1;
            TaaClient.SSAA.reset(); changed(); refresh();
        }, 150), 1, 1);
        history = row(new HistorySlider(), 2, 0);
        motion = row(button(empty(), b -> {
            TaaClient.CONFIG.reduceHistoryOnMotion = !TaaClient.CONFIG.reduceHistoryOnMotion; changed(); refresh();
        }, 310), 3, 0);
        samples = row(new EditBox(font, 0, 0, 150, 20, label("taa.samples")), 4, 0);
        samples.setMaxLength(3); samples.setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9')); samples.setValue(pending);
        apply = row(button(label("taa.samples.apply"), b -> applySamples(), 150), 4, 1);
        mode = row(button(empty(), b -> {
            TaaClient.CONFIG.sharpeningMode = (TaaClient.CONFIG.sharpeningMode + 1) % 3;
            changed(); strength.refresh(); refresh();
        }, 150), 5, 0);
        strength = row(new StrengthSlider(), 5, 1);
        debug = row(button(empty(), b -> {
            TaaClient.CONFIG.debugView = (TaaClient.CONFIG.debugView + 1) % 4;
            TaaClient.RENDERER.invalidate(); changed(); refresh();
        }, 310), 6, 0);
        clear = row(button(label("taa.clear.history"), b -> TaaClient.RENDERER.invalidate(), 310), 7, 0);
        Button done = button(label("gui.done"), b -> onClose(), 200); position(done, width / 2 - 100, height - 27); add(done);
        refresh(); positionRows();
    }
    private static void position(AbstractWidget widget, int x, int y) {
        //#if MC>=11903
        widget.setX(x); widget.setY(y);
        //#else
        //$$ widget.x = x; widget.y = y;
        //#endif
    }
    private void positionRows() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, 10 * 24 - (height - 95))));
        for (int i = 0; i < controls.size(); i++) {
            int y = 55 + rows.get(i) * 24 - scroll;
            AbstractWidget control = controls.get(i);
            position(control, width / 2 - 155 + columns.get(i) * 160, y);
            control.visible = y >= 55 && y + 20 <= height - 39;
        }
    }
    private void refresh() {
        boolean temporal = TaaClient.CONFIG.antialiasingMode == 0;
        int factor = TaaClient.CONFIG.ssaaScale;
        enabled.setMessage(enabledLabel());
        algorithm.setMessage(label("taa.algorithm", temporal ? "TAA" : "SSAA"));
        scale.setMessage(label("taa.ssaa.scale", factor * factor, factor, factor)); scale.active = !temporal;
        motion.setMessage(label("taa.motion", label(TaaClient.CONFIG.reduceHistoryOnMotion ? "options.on" : "options.off")));
        mode.setMessage(label("taa.sharpen.mode", label("taa.sharpen.mode." + TaaClient.CONFIG.sharpeningMode)));
        debug.setMessage(label("taa.debug", label("taa.debug." + TaaClient.CONFIG.debugView)));
        history.active = motion.active = samples.active = apply.active = debug.active = clear.active = temporal;
        samples.setEditable(temporal);
    }
    private Component enabledLabel() { return label("taa.enabled", label(TaaClient.CONFIG.enabled ? "options.on" : "options.off")); }
    private boolean errorNote() { return note.equals("taa.samples.invalid") || note.equals("taa.save.failed"); }
    private static void resetRenderers() { TaaClient.RENDERER.invalidate(); TaaClient.SSAA.reset(); }
    private void changed() { dirty = true; }
    private boolean applySamples() {
        try {
            int count = Integer.parseInt(samples.getValue());
            if (count < 1 || count > 256) throw new NumberFormatException();
            if (count != TaaClient.CONFIG.sampleCount) { TaaClient.CONFIG.sampleCount = count; TaaClient.RENDERER.invalidate(); changed(); }
            samples.setValue(Integer.toString(count)); note = "taa.note"; return true;
        } catch (NumberFormatException e) {
            note = "taa.samples.invalid";
            //#if MC>=11903
            samples.setFocused(true);
            //#else
            //$$ samples.setFocus(true);
            //#endif
            setFocused(samples); return false;
        }
    }
    private void save() {
        if (dirty) { dirty = !TaaClient.CONFIG.save(); if (dirty) note = "taa.save.failed"; }
    }
    @Override public void onClose() {
        if (TaaClient.CONFIG.antialiasingMode == 1 || applySamples()) { save(); if (!dirty) minecraft.setScreen(lastScreen); }
    }
    @Override public void removed() { save(); super.removed(); }
    @Override public void tick() {
        //#if MC<12000
        //$$ samples.tick();
        //#endif
        super.tick();
    }
    //#if MC>=12000
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        graphics.drawCenteredString(font, label(errorNote() ? note : TaaClient.statusKey()), width / 2, 34, errorNote() ? 0xff5555 : 0xaaaaaa);
        for (int i = 8; i < 10; i++) {
            int y = 55 + i * 24 - scroll;
            if (y >= 55 && y + 20 <= height - 39)
                graphics.drawCenteredString(font, label(i == 8 ? "taa.coverage" : note), width / 2, y + 5, 0xaaaaaa);
        }
        super.render(graphics, mouseX, mouseY, delta);
    }
    //#else
    //$$ @Override public void render(PoseStack graphics, int mouseX, int mouseY, float delta) {
    //$$     renderBackground(graphics);
    //$$     drawCenteredString(graphics, font, title, width / 2, 12, 0xffffff);
    //$$     drawCenteredString(graphics, font, label(errorNote() ? note : TaaClient.statusKey()), width / 2, 34, errorNote() ? 0xff5555 : 0xaaaaaa);
    //$$     for (int i = 8; i < 10; i++) {
    //$$         int y = 55 + i * 24 - scroll;
    //$$         if (y >= 55 && y + 20 <= height - 39) drawCenteredString(graphics, font, label(i == 8 ? "taa.coverage" : note), width / 2, y + 5, 0xaaaaaa);
    //$$     }
    //$$     super.render(graphics, mouseX, mouseY, delta);
    //$$ }
    //#endif
    //#if MC>=12002
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= 55 && y < height - 39) { scroll -= (int) (vertical * 24); positionRows(); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    //#else
    //$$ @Override public boolean mouseScrolled(double x, double y, double vertical) {
    //$$     if (y >= 55 && y < height - 39) { scroll -= (int) (vertical * 24); positionRows(); return true; }
    //$$     return super.mouseScrolled(x, y, vertical);
    //$$ }
    //#endif
    private final class HistorySlider extends AbstractSliderButton {
        HistorySlider() { super(0, 0, 310, 20, empty(), 0); refresh(); }
        void refresh() { value = TaaClient.CONFIG.historyWeight; updateMessage(); }
        @Override protected void updateMessage() { setMessage(label("taa.history", String.format(Locale.ROOT, "%.2f", value))); }
        @Override protected void applyValue() { TaaClient.CONFIG.historyWeight = value; changed(); }
    }
    private final class StrengthSlider extends AbstractSliderButton {
        StrengthSlider() { super(0, 0, 150, 20, empty(), 0); refresh(); }
        void refresh() {
            active = TaaClient.CONFIG.sharpeningMode != 0;
            value = TaaClient.CONFIG.sharpeningMode == 2 ? (TaaClient.CONFIG.casAmount - 0.1) / 0.9 : TaaClient.CONFIG.sharpening;
            updateMessage();
        }
        @Override protected void updateMessage() {
            boolean cas = TaaClient.CONFIG.sharpeningMode == 2;
            setMessage(label(cas ? "taa.cas" : "taa.sharpen", String.format(Locale.ROOT, "%.2f", cas ? 0.1 + value * 0.9 : value)));
        }
        @Override protected void applyValue() {
            if (TaaClient.CONFIG.sharpeningMode == 2) TaaClient.CONFIG.casAmount = 0.1 + value * 0.9;
            else TaaClient.CONFIG.sharpening = value;
            changed();
        }
    }
}
