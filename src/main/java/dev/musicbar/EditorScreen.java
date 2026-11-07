package dev.musicbar;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

 
public final class EditorScreen extends Screen {

    private enum ColorSlot {
        ACCENT("Акцент / рамка", c -> c.accent, (c, v) -> c.accent = v),
        ACCENT2("Акцент 2 (градиент)", c -> c.accent2, (c, v) -> c.accent2 = v),
        GLOW("Свечение", c -> c.glow, (c, v) -> c.glow = v),
        BACKGROUND("Фон", c -> c.background, (c, v) -> c.background = v),
        TEXT("Текст", c -> c.text, (c, v) -> c.text = v),
        SUBTEXT("Подтекст", c -> c.subtext, (c, v) -> c.subtext = v);

        final String label;
        final ToIntFunction<MusicConfig> getter;
        final BiConsumer<MusicConfig, Integer> setter;

        ColorSlot(String label, ToIntFunction<MusicConfig> getter, BiConsumer<MusicConfig, Integer> setter) {
            this.label = label;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");

    private final Screen parent;
    private final MusicConfig c = MusicConfig.get();
    private ColorSlot slot = ColorSlot.ACCENT;
    private final List<Button> slotButtons = new ArrayList<>();
    private ValueSlider sr, sg, sb;
    private EditBox hex;
    private boolean updating;
    private int hx1, hx2, hx3;    

    public EditorScreen(Screen parent) {
        super(Component.literal("Music Bar"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        c.save();
        LyricsService.refresh();
    }

     

    @Override
    protected void init() {
        slotButtons.clear();
        int colW = Math.min(150, (width - 24) / 3);
        int gap = 6;
        int total = colW * 3 + gap * 2;
        int x1 = (width - total) / 2;
        int x2 = x1 + colW + gap;
        int x3 = x2 + colW + gap;
        hx1 = x1;
        hx2 = x2;
        hx3 = x3;
        final int top = 74, step = 18, bh = 16;

         
        int y = top;
        for (ColorSlot s : ColorSlot.values()) {
            Button b = addRenderableWidget(Button.builder(Component.literal(s.label), btn -> {
                slot = s;
                refreshSlotButtons();
                syncColorWidgets();
            }).bounds(x1, y, colW, bh).build());
            slotButtons.add(b);
            y += step;
        }
        y += 4;
        toggle("Плеер", () -> c.enabled, v -> c.enabled = v, x1, y, colW, bh);
        y += step;
        toggle("Радужный режим", () -> c.rainbow, v -> c.rainbow = v, x1, y, colW, bh);
        y += step;
        addRenderableWidget(Button.builder(Component.literal("Пульс: " + c.pulseMode.label), btn -> {
            c.pulseMode = c.pulseMode.next();
            btn.setMessage(Component.literal("Пульс: " + c.pulseMode.label));
        }).bounds(x1, y, colW, bh).build());

         
        y = top;
        int rgb = slot.getter.applyAsInt(c);
        sr = addRenderableWidget(new ValueSlider(x2, y, colW, bh, "R", 0, 255, (rgb >> 16) & 0xFF, "", v -> onRgbSlider()));
        y += step;
        sg = addRenderableWidget(new ValueSlider(x2, y, colW, bh, "G", 0, 255, (rgb >> 8) & 0xFF, "", v -> onRgbSlider()));
        y += step;
        sb = addRenderableWidget(new ValueSlider(x2, y, colW, bh, "B", 0, 255, rgb & 0xFF, "", v -> onRgbSlider()));
        y += step;
        hex = addRenderableWidget(new EditBox(font, x2, y, colW, bh, Component.literal("HEX")));
        hex.setMaxLength(7);
        hex.setValue(String.format("#%06X", rgb));
        hex.setResponder(this::onHexChanged);
        y += step + 4;
        toggle("Артист", () -> c.showArtist, v -> c.showArtist = v, x2, y, colW, bh);
        y += step;
        toggle("Прогресс", () -> c.showProgress, v -> c.showProgress = v, x2, y, colW, bh);
        y += step;
        toggle("Визуализатор", () -> c.showVisualizer, v -> c.showVisualizer = v, x2, y, colW, bh);
        y += step;
        toggle("Скрыть, если тихо", () -> c.hideWhenIdle, v -> c.hideWhenIdle = v, x2, y, colW, bh);

         
        y = top;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Фон", 0, 255, c.backgroundAlpha, "", v -> c.backgroundAlpha = (int) v));
        y += step;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Размер свечения", 0, 16, c.glowSize, "", v -> c.glowSize = (int) v));
        y += step;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Яркость свечения", 0, 100, c.glowIntensity, "%", v -> c.glowIntensity = (int) v));
        y += step;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Сила пульса", 0, 100, c.pulseStrength, "%", v -> c.pulseStrength = (int) v));
        y += step;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Чувств. бита", 0, 100, c.sensitivity, "%", v -> c.sensitivity = (int) v));
        y += step;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Ширина", 140, 360, c.width, "", v -> c.width = (int) v));
        y += step;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Сдвиг Y", 0, 200, c.offsetY, "", v -> c.offsetY = (int) v));
        y += step;
        addRenderableWidget(new ValueSlider(x3, y, colW, bh, "Сдвиг X", -400, 400, c.offsetX, "", v -> c.offsetX = (int) v));

         
        int by = height - 22;
        addRenderableWidget(Button.builder(Component.literal("Готово"), btn -> onClose())
                .bounds(width / 2 - 105, by, 100, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Сброс"), btn -> {
            c.resetToDefaults();
            rebuildWidgets();
        }).bounds(width / 2 + 5, by, 100, 18).build());

        refreshSlotButtons();
    }

    private Button toggle(String label, BooleanSupplier get, Consumer<Boolean> set, int x, int y, int w, int h) {
        return addRenderableWidget(Button.builder(toggleText(label, get.getAsBoolean()), btn -> {
            set.accept(!get.getAsBoolean());
            btn.setMessage(toggleText(label, get.getAsBoolean()));
        }).bounds(x, y, w, h).build());
    }

    private static Component toggleText(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aВКЛ" : "§cВЫКЛ"));
    }

    private void refreshSlotButtons() {
        ColorSlot[] all = ColorSlot.values();
        for (int i = 0; i < slotButtons.size(); i++) {
            String prefix = all[i] == slot ? "§e> §r" : "";
            slotButtons.get(i).setMessage(Component.literal(prefix + all[i].label));
        }
    }

     

    private void syncColorWidgets() {
        updating = true;
        int rgb = slot.getter.applyAsInt(c);
        sr.set((rgb >> 16) & 0xFF);
        sg.set((rgb >> 8) & 0xFF);
        sb.set(rgb & 0xFF);
        hex.setValue(String.format("#%06X", rgb));
        updating = false;
    }

    private void onRgbSlider() {
        if (updating) return;
        int rgb = ((int) Math.round(sr.current()) << 16) | ((int) Math.round(sg.current()) << 8) | (int) Math.round(sb.current());
        slot.setter.accept(c, rgb);
        updating = true;
        hex.setValue(String.format("#%06X", rgb));
        updating = false;
    }

    private void onHexChanged(String text) {
        if (updating || !HEX.matcher(text).matches()) return;
        int rgb = Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16);
        slot.setter.accept(c, rgb);
        updating = true;
        sr.set((rgb >> 16) & 0xFF);
        sg.set((rgb >> 8) & 0xFF);
        sb.set(rgb & 0xFF);
        updating = false;
    }

     

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xB0000000);

        MediaMonitor.Info real = MusicBarClient.monitor() != null ? MusicBarClient.monitor().info() : MediaMonitor.Info.NONE;
        MusicHud.draw(g, font, width, real.isEmpty() ? MusicHud.demo() : real, null);

        int hy = 62;
        g.drawString(font, "Цвета", hx1, hy, 0xFFFFFFFF, true);
        g.drawString(font, "Цвет: " + slot.label, hx2, hy, 0xFFFFFFFF, true);
        int rgb = slot.getter.applyAsInt(c);
        int sw = hx2 + font.width("Цвет: " + slot.label) + 4;
        g.fill(sw, hy - 1, sw + 10, hy + 9, 0xFF000000 | rgb);
        g.drawString(font, "Параметры", hx3, hy, 0xFFFFFFFF, true);

        super.render(g, mouseX, mouseY, partialTick);
    }
}
