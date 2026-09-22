package org.sawiq.minecanvas.fabric.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

final class ConfigWidgets {
    private static final int TEXT = 0xFF4A3846;
    private static final int MUTED = 0xFF6B5563;
    private static final int ACCENT = 0xFFD89BA6;
    private static final int CARD = 0xFFE9DCE6;
    private static final int CARD_HOVER = 0xFFF2E7EE;
    private static final int CARD_BORDER = 0xFFC9AFC2;
    private static final int BOX = 0xFFF3E9F0;
    private static final int BOX_BORDER = 0xFFBFA3B6;

    enum Icon { SPEAKER, PLAY, TIMELINE, DOWNLOAD, CACHE }

    private ConfigWidgets() {
    }

    static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        roundedRect(graphics, x - 3, y + 5, width + 6, height, 0x22382432);
        roundedRect(graphics, x - 2, y + 3, width + 4, height, 0x334A3846);
        roundedRect(graphics, x - 1, y + 1, width + 2, height, 0x44382432);
        roundedRect(graphics, x, y, width, height, 0xFFC6A9BE);
        verticalGradient(graphics, x + 1, y + 1, width - 2, height - 2, 0xFFB49FB0, 0xFFA38FA6);
        graphics.fill(x + 3, y + 2, x + width - 3, y + 3, 0x88C9B2C4);
    }

    static void roundedBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                              int border, int fill) {
        roundedRect(graphics, x, y, width, height, border);
        if (width > 2 && height > 2) roundedRect(graphics, x + 1, y + 1, width - 2, height - 2, fill);
    }

    static void roundedRect(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        if (width < 4 || height < 4) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        graphics.fill(x + 2, y, x + width - 2, y + height, color);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
        graphics.fill(x, y + 2, x + width, y + height - 2, color);
    }

    private static void verticalGradient(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                         int top, int bottom) {
        if (width <= 0 || height <= 0) return;
        for (int line = 0; line < height; line++) {
            float amount = height == 1 ? 0 : (float) line / (height - 1);
            int color = interpolate(top, bottom, amount);
            int inset = Math.min(width / 2, 1 + (line == 0 || line == height - 1 ? 1 : 0));
            graphics.fill(x + inset, y + line, x + width - inset, y + line + 1, color);
        }
    }

    private static int interpolate(int a, int b, float amount) {
        int aa = (int) (((a >>> 24) & 255) * (1 - amount) + ((b >>> 24) & 255) * amount);
        int ar = (int) (((a >>> 16) & 255) * (1 - amount) + ((b >>> 16) & 255) * amount);
        int ag = (int) (((a >>> 8) & 255) * (1 - amount) + ((b >>> 8) & 255) * amount);
        int ab = (int) ((a & 255) * (1 - amount) + (b & 255) * amount);
        return aa << 24 | ar << 16 | ag << 8 | ab;
    }

    static void sparkle(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 2, y, x + 3, y + 5, color);
        graphics.fill(x, y + 2, x + 5, y + 3, color);
    }

    static void playGlyph(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int line = 0; line < 12; line++) {
            int width = line < 6 ? line / 2 + 1 : (11 - line) / 2 + 1;
            graphics.fill(x, y + line, x + width, y + line + 1, color);
        }
    }

    static void filmFrame(GuiGraphicsExtractor graphics, int x, int y) {
        roundedBorder(graphics, x, y, 30, 20, 0xFFC9AFC2, 0xFFE9DCE6);
        graphics.fill(x + 4, y + 3, x + 6, y + 6, 0xFFA38FA6);
        graphics.fill(x + 4, y + 14, x + 6, y + 17, 0xFFA38FA6);
        graphics.fill(x + 24, y + 3, x + 26, y + 6, 0xFFA38FA6);
        graphics.fill(x + 24, y + 14, x + 26, y + 17, 0xFFA38FA6);
        for (int line = 0; line < 8; line++) {
            int width = 1 + 2 * Math.min(line, 7 - line);
            graphics.fill(x + 11, y + 6 + line, x + 11 + width, y + 7 + line, ACCENT);
        }
    }

    static void mountain(GuiGraphicsExtractor graphics, int x, int bottom, int width, int top, int color) {
        int height = Math.max(1, bottom - top);
        for (int line = 0; line < height; line++) {
            int half = Math.max(1, line * width / height / 2);
            int center = x + width / 2;
            graphics.fill(center - half, top + line, center + half, top + line + 1, color);
        }
    }

    static void tree(GuiGraphicsExtractor graphics, int x, int bottom, int color) {
        graphics.fill(x + 3, bottom - 11, x + 4, bottom, color);
        graphics.fill(x + 2, bottom - 10, x + 5, bottom - 9, color);
        graphics.fill(x + 1, bottom - 7, x + 6, bottom - 6, color);
        graphics.fill(x, bottom - 4, x + 7, bottom - 3, color);
    }

    private abstract static class Row extends AbstractWidget {
        final Font font;
        final Component label;
        final Icon icon;

        Row(int x, int y, int width, int height, Font font, Component label, Icon icon) {
            super(x, y, width, height, label);
            this.font = font;
            this.label = label;
            this.icon = icon;
        }

        @Override
        protected final void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                      float partialTick) {
            roundedBorder(graphics, getX(), getY(), getWidth(), getHeight(), CARD_BORDER,
                    isHoveredOrFocused() ? CARD_HOVER : CARD);
            drawIcon(graphics, getX() + 7, getY() + (getHeight() - 14) / 2, icon);
            drawLabel(graphics, displayedLabel(), controlLeft() - 5);
            drawControl(graphics, mouseX, mouseY);
            handleCursor(graphics);
        }

        Component displayedLabel() {
            return label;
        }

        private void drawLabel(GuiGraphicsExtractor graphics, Component label, int right) {
            int x = getX() + 28;
            int available = Math.max(0, right - x);
            String text = label.getString();
            if (font.width(text) > available) {
                text = available > font.width("…") ? font.plainSubstrByWidth(text, available - font.width("…")) + "…" : "";
            }
            graphics.text(font, text, x, getY() + (getHeight() - font.lineHeight) / 2, TEXT, false);
        }

        abstract int controlLeft();

        abstract void drawControl(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

        boolean overControl(double mouseX, double mouseY) {
            return mouseX >= controlLeft() && mouseX < getRight() - 6 && mouseY >= getY() && mouseY < getBottom();
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return overControl(event.x(), event.y()) && super.mouseClicked(event, doubleClick);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    static final class Toggle extends Row {
        private final Consumer<Boolean> changed;
        private boolean value;

        Toggle(int x, int y, int width, int height, Font font, Component label, Icon icon,
               boolean value, Consumer<Boolean> changed) {
            super(x, y, width, height, font, label, icon);
            this.value = value;
            this.changed = changed;
            updateMessage();
        }

        @Override
        int controlLeft() {
            return Math.max(getRight() - 49, getX() + 28);
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int switchX = controlLeft();
            int switchY = getY() + (getHeight() - 14) / 2;
            roundedBorder(graphics, switchX, switchY, 36, 14, value ? 0xFFBF7F8C : BOX_BORDER,
                    value ? ACCENT : 0xFFD2C3CD);
            roundedRect(graphics, switchX + (value ? 23 : 2), switchY + 2, 11, 10, 0xFFFFFFFF);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            setValue(!value);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!event.isSelection()) return false;
            playDownSound(Minecraft.getInstance().getSoundManager());
            setValue(!value);
            return true;
        }

        private void setValue(boolean value) {
            this.value = value;
            changed.accept(value);
            updateMessage();
        }

        private void updateMessage() {
            setMessage(label.copy().append(": ").append(Component.translatable(
                    value ? "config.minecanvas.enabled" : "config.minecanvas.disabled")));
        }
    }

    static final class ToggleCycle<T> extends Row {
        private final Component cycleLabel;
        private final List<T> values;
        private final Function<T, String> display;
        private final Consumer<Boolean> toggleChanged;
        private final Consumer<T> cycleChanged;
        private final int fallbackIndex;
        private boolean enabled;
        private int index;
        private T value;

        ToggleCycle(int x, int y, int width, int height, Font font, Component label, Icon icon,
                    Component cycleLabel, boolean enabled, Consumer<Boolean> toggleChanged,
                    List<T> values, T value, Function<T, String> display, Consumer<T> cycleChanged) {
            this(x, y, width, height, font, label, icon, cycleLabel, enabled, toggleChanged,
                    values, value, display, cycleChanged, value);
        }

        ToggleCycle(int x, int y, int width, int height, Font font, Component label, Icon icon,
                    Component cycleLabel, boolean enabled, Consumer<Boolean> toggleChanged,
                    List<T> values, T value, Function<T, String> display, Consumer<T> cycleChanged, T fallback) {
            super(x, y, width, height, font, label, icon);
            this.cycleLabel = cycleLabel;
            this.values = values;
            this.display = display;
            this.toggleChanged = toggleChanged;
            this.cycleChanged = cycleChanged;
            this.enabled = enabled;
            this.index = values.indexOf(value);
            this.fallbackIndex = Math.max(0, values.indexOf(fallback));
            this.value = value;
            updateMessage();
        }

        private int selectorWidth() {
            return Math.min(118, Math.max(100, getWidth() / 3));
        }

        private int selectorLeft() {
            return Math.max(getRight() - selectorWidth() - 6, getX() + 28);
        }

        @Override
        int controlLeft() {
            return Math.max(selectorLeft() - 44, getX() + 28);
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int centerY = getY() + getHeight() / 2;
            roundedBorder(graphics, controlLeft(), centerY - 7, 36, 14,
                    enabled ? 0xFFBF7F8C : BOX_BORDER, enabled ? ACCENT : 0xFFD2C3CD);
            roundedRect(graphics, controlLeft() + (enabled ? 23 : 2), centerY - 5, 11, 10, 0xFFFFFFFF);

            roundedBorder(graphics, selectorLeft(), centerY - 9, selectorWidth(), 18, BOX_BORDER, BOX);
            String text = display.apply(value);
            graphics.text(font, text, selectorLeft() + 6,
                    getY() + (getHeight() - font.lineHeight) / 2, TEXT, false);
            chevron(graphics, getRight() - 16, centerY - 2, false, MUTED);
        }

        @Override
        boolean overControl(double mouseX, double mouseY) {
            boolean overToggle = mouseX >= controlLeft() && mouseX < controlLeft() + 36;
            boolean overSelector = mouseX >= selectorLeft() && mouseX < getRight() - 6;
            return (overToggle || overSelector) && mouseY >= getY() && mouseY < getBottom();
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (event.x() < selectorLeft()) {
                enabled = !enabled;
                toggleChanged.accept(enabled);
            } else {
                cycle(1);
            }
            updateMessage();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection()) {
                enabled = !enabled;
                toggleChanged.accept(enabled);
                updateMessage();
                return true;
            }
            if (event.isRight() || event.isDown()) return cycle(1);
            if (event.isLeft() || event.isUp()) return cycle(-1);
            return false;
        }

        private boolean cycle(int direction) {
            index = index < 0 ? fallbackIndex : Math.floorMod(index + direction, values.size());
            value = values.get(index);
            cycleChanged.accept(value);
            updateMessage();
            return true;
        }

        private void updateMessage() {
            setMessage(label.copy().append(": ").append(Component.translatable(
                    enabled ? "config.minecanvas.enabled" : "config.minecanvas.disabled"))
                    .append(", ").append(cycleLabel).append(": " + display.apply(value)));
        }
    }

    static final class Slider extends Row {
        private final IntConsumer changed;
        private int value;

        Slider(int x, int y, int width, int height, Font font, Component label, Icon icon,
               int value, IntConsumer changed) {
            super(x, y, width, height, font, label, icon);
            this.value = Math.max(0, Math.min(100, value));
            this.changed = changed;
            updateMessage();
        }

        @Override
        int controlLeft() {
            return Math.max(getRight() - Math.min(151, Math.max(88, getWidth() / 2)), getX() + 28);
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int boxWidth = 38;
            int boxX = getRight() - boxWidth - 6;
            int trackX = controlLeft();
            int trackWidth = Math.max(24, boxX - trackX - 8);
            int centerY = getY() + getHeight() / 2;
            roundedRect(graphics, trackX, centerY - 2, trackWidth, 4, 0xFFE3CBD5);
            roundedRect(graphics, trackX, centerY - 2, Math.max(4, trackWidth * value / 100), 4, ACCENT);
            int knobX = trackX + (trackWidth - 8) * value / 100;
            roundedRect(graphics, knobX, centerY - 5, 9, 10, 0x33000000);
            roundedRect(graphics, knobX, centerY - 6, 9, 10, 0xFFFFFFFF);
            roundedBorder(graphics, boxX, centerY - 8, boxWidth, 16, BOX_BORDER, BOX);
            String text = value + "%";
            graphics.text(font, text, boxX + (boxWidth - font.width(text)) / 2,
                    getY() + (getHeight() - font.lineHeight) / 2, TEXT, false);
        }

        @Override
        boolean overControl(double mouseX, double mouseY) {
            return mouseX >= controlLeft() && mouseX < getRight() - 50 && mouseY >= getY() && mouseY < getBottom();
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            setFromMouse(event.x());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            setFromMouse(event.x());
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isLeft()) return change(-1);
            if (event.isRight()) return change(1);
            return false;
        }

        private void setFromMouse(double mouseX) {
            int boxX = getRight() - 44;
            int trackWidth = Math.max(24, boxX - controlLeft() - 8);
            setValue((int) Math.round((mouseX - controlLeft()) * 100 / trackWidth));
        }

        private boolean change(int amount) {
            setValue(value + amount);
            return true;
        }

        private void setValue(int value) {
            this.value = Math.max(0, Math.min(100, value));
            changed.accept(this.value);
            updateMessage();
        }

        private void updateMessage() {
            setMessage(label.copy().append(": " + value + "%"));
        }
    }

    static final class CycleBox<T> extends Row {
        private final List<T> values;
        private final Function<T, String> display;
        private final Consumer<T> changed;
        private final int fallbackIndex;
        private int index;
        private T value;

        CycleBox(int x, int y, int width, int height, Font font, Component label, Icon icon,
                 List<T> values, T value, Function<T, String> display, Consumer<T> changed) {
            super(x, y, width, height, font, label, icon);
            this.values = values;
            this.display = display;
            this.changed = changed;
            this.index = Math.max(0, values.indexOf(value));
            this.fallbackIndex = this.index;
            this.value = values.get(this.index);
            updateMessage();
        }

        CycleBox(int x, int y, int width, int height, Font font, Component label, Icon icon,
                 List<T> values, T value, Function<T, String> display, Consumer<T> changed, T fallback) {
            super(x, y, width, height, font, label, icon);
            this.values = values;
            this.display = display;
            this.changed = changed;
            this.index = values.indexOf(value);
            this.fallbackIndex = values.indexOf(fallback);
            this.value = value;
            updateMessage();
        }

        @Override
        int controlLeft() {
            return Math.max(getRight() - Math.min(118, Math.max(74, getWidth() / 3)), getX() + 28);
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int y = getY() + (getHeight() - 18) / 2;
            int width = getRight() - controlLeft() - 6;
            roundedBorder(graphics, controlLeft(), y, width, 18, BOX_BORDER, BOX);
            String text = display.apply(value);
            graphics.text(font, text, controlLeft() + 6, getY() + (getHeight() - font.lineHeight) / 2, TEXT, false);
            chevron(graphics, getRight() - 16, getY() + getHeight() / 2 - 2, false, MUTED);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            cycle(1);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection() || event.isRight() || event.isDown()) return cycle(1);
            if (event.isLeft() || event.isUp()) return cycle(-1);
            return false;
        }

        private boolean cycle(int direction) {
            index = index < 0 ? fallbackIndex : Math.floorMod(index + direction, values.size());
            value = values.get(index);
            changed.accept(value);
            updateMessage();
            return true;
        }

        private void updateMessage() {
            setMessage(label.copy().append(": " + display.apply(value)));
        }
    }

    static final class Cache extends Row {
        private static final long GIB = 1024L * 1024L * 1024L;
        private final Runnable clear;
        private int fileCount;
        private long sizeBytes;
        private long clearedBytes;
        private long noteUntil;
        private boolean clearing;

        Cache(int x, int y, int width, int height, Font font, int fileCount, long sizeBytes, Runnable clear) {
            super(x, y, width, height, font, Component.translatable("config.minecanvas.cache"), Icon.CACHE);
            this.clear = clear;
            this.fileCount = fileCount;
            this.sizeBytes = sizeBytes;
            updateMessage();
        }

        void setInfo(int fileCount, long sizeBytes, long clearedBytes) {
            this.fileCount = fileCount;
            this.sizeBytes = sizeBytes;
            this.clearedBytes = clearedBytes;
            noteUntil = System.currentTimeMillis() + 3000L;
            clearing = false;
            active = true;
            updateMessage();
        }

        @Override
        Component displayedLabel() {
            if (clearing) return Component.translatable("config.minecanvas.cache_clearing");
            if (System.currentTimeMillis() < noteUntil) {
                return Component.translatable("config.minecanvas.cache_cleared", formatSize(clearedBytes));
            }
            return Component.translatable("config.minecanvas.cache_summary", fileCount, formatSize(sizeBytes));
        }

        private static Component formatSize(long bytes) {
            if (bytes < GIB) {
                return Component.translatable("config.minecanvas.cache_size_mb",
                        Math.round(bytes / (1024.0 * 1024.0)));
            }
            return Component.translatable("config.minecanvas.cache_size_gb",
                    String.format(Locale.ROOT, "%.1f", bytes / (double) GIB));
        }

        private void updateMessage() {
            setMessage(displayedLabel().copy()
                    .append("; ").append(Component.translatable("config.minecanvas.clear_cache")));
        }

        @Override
        int controlLeft() {
            return Math.max(getRight() - Math.min(92, Math.max(66, getWidth() / 4)) - 6, getX() + 28);
        }

        @Override
        void drawControl(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            int width = getRight() - controlLeft() - 6;
            int height = Math.min(20, getHeight() - 6);
            int y = getY() + (getHeight() - height) / 2;
            boolean hovered = mouseX >= controlLeft() && mouseX < getRight() - 6
                    && mouseY >= getY() && mouseY < getBottom();
            int fill = clearing ? 0xFFBFA3B6 : hovered || isFocused() ? 0xFFE4AAB4 : ACCENT;
            roundedBorder(graphics, controlLeft(), y, width, height, 0xFFB77C8A, fill);
            Component text = Component.translatable("config.minecanvas.clear_cache");
            graphics.text(font, text, controlLeft() + (width - font.width(text)) / 2,
                    getY() + (getHeight() - font.lineHeight) / 2, 0xFFFFFFFF, true);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            startClearing();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (clearing || !event.isSelection()) return false;
            playDownSound(Minecraft.getInstance().getSoundManager());
            startClearing();
            return true;
        }

        private void startClearing() {
            if (clearing) return;
            clearing = true;
            active = false;
            updateMessage();
            clear.run();
        }
    }

    static final class Action extends AbstractWidget {
        private final Font font;
        private final boolean close;
        private final Runnable pressed;

        Action(int x, int y, int width, int height, Font font, Component message, boolean close, Runnable pressed) {
            super(x, y, width, height, message);
            this.font = font;
            this.close = close;
            this.pressed = pressed;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int fill = isHoveredOrFocused() ? 0xFFE4AAB4 : ACCENT;
            roundedRect(graphics, getX() + 1, getY() + 2, getWidth(), getHeight(), 0x44382432);
            roundedBorder(graphics, getX(), getY(), getWidth(), getHeight(), 0xFFB77C8A, fill);
            graphics.text(font, getMessage(), getX() + (getWidth() - font.width(getMessage())) / 2,
                    getY() + (getHeight() - font.lineHeight) / 2, close ? TEXT : 0xFFFFFFFF, true);
            if (!close && getWidth() > 70) {
                sparkle(graphics, getX() + 12, getY() + getHeight() / 2 - 2, 0xFFF2C9D2);
                sparkle(graphics, getRight() - 17, getY() + getHeight() / 2 - 2, 0xFFF2C9D2);
            }
            handleCursor(graphics);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            pressed.run();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!event.isSelection()) return false;
            playDownSound(Minecraft.getInstance().getSoundManager());
            pressed.run();
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static void chevron(GuiGraphicsExtractor graphics, int x, int y, boolean up, int color) {
        if (up) {
            graphics.fill(x + 2, y, x + 3, y + 1, color);
            graphics.fill(x + 1, y + 1, x + 4, y + 2, color);
            graphics.fill(x, y + 2, x + 5, y + 3, color);
        } else {
            graphics.fill(x, y, x + 5, y + 1, color);
            graphics.fill(x + 1, y + 1, x + 4, y + 2, color);
            graphics.fill(x + 2, y + 2, x + 3, y + 3, color);
        }
    }

    private static void drawIcon(GuiGraphicsExtractor graphics, int x, int y, Icon icon) {
        int color = 0xFF9A7287;
        switch (icon) {
            case SPEAKER -> {
                graphics.fill(x, y + 5, x + 4, y + 10, color);
                graphics.fill(x + 4, y + 3, x + 7, y + 12, color);
                graphics.fill(x + 9, y + 5, x + 10, y + 10, color);
                graphics.fill(x + 11, y + 3, x + 12, y + 12, color);
            }
            case PLAY -> playGlyph(graphics, x + 3, y + 1, color);
            case TIMELINE -> {
                graphics.fill(x + 1, y + 9, x + 3, y + 13, color);
                graphics.fill(x + 5, y + 5, x + 7, y + 13, color);
                graphics.fill(x + 9, y + 2, x + 11, y + 13, color);
                graphics.fill(x, y + 13, x + 13, y + 14, color);
            }
            case DOWNLOAD -> {
                graphics.fill(x + 6, y + 1, x + 8, y + 9, color);
                graphics.fill(x + 3, y + 6, x + 11, y + 8, color);
                graphics.fill(x + 4, y + 8, x + 10, y + 10, color);
                graphics.fill(x + 1, y + 11, x + 3, y + 14, color);
                graphics.fill(x + 11, y + 11, x + 13, y + 14, color);
                graphics.fill(x + 1, y + 13, x + 13, y + 15, color);
            }
            case CACHE -> {
                roundedBorder(graphics, x + 1, y + 2, 12, 4, color, 0x00FFFFFF);
                roundedBorder(graphics, x + 1, y + 6, 12, 4, color, 0x00FFFFFF);
                roundedBorder(graphics, x + 1, y + 10, 12, 4, color, 0x00FFFFFF);
            }
        }
    }
}
