package dev.musicbar;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

 
final class ValueSlider extends AbstractSliderButton {
    private final String label;
    private final String suffix;
    private final double min;
    private final double max;
    private final DoubleConsumer onChange;

    ValueSlider(int x, int y, int w, int h, String label, double min, double max, double cur,
                String suffix, DoubleConsumer onChange) {
        super(x, y, w, h, Component.empty(), (cur - min) / (max - min));
        this.label = label;
        this.suffix = suffix;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    double current() {
        return min + (max - min) * value;
    }

     
    void set(double v) {
        value = Math.max(0, Math.min(1, (v - min) / (max - min)));
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        if (label == null) return;
        setMessage(Component.literal(label + ": " + Math.round(current()) + suffix));
    }

    @Override
    protected void applyValue() {
        onChange.accept(Math.round(current()));
    }
}
