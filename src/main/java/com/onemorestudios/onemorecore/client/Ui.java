package com.onemorestudios.onemorecore.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The look of the mod's windows, after the Hands On Delight screens: a see-through black panel over the dimmed world, like a
 * film's title card (thin light rules, letterbox bars at the top and bottom of the screen, small caps titles), with flat
 * pill buttons and chips. A window collects its clickable regions each frame (hits) and runs the one under a click.
 */
final class Ui {
    static final int TEXT = 0xFFF2F2F2, MUTED = 0xFF9AA3AD, ACCENT = 0xFFFFC72C, PANEL = 0xD20C0E12, RULE = 0x33FFFFFF, CHIP = 0x26FFFFFF,
            CHIP_HOVER = 0x40FFFFFF, BAR = 0xE6000000;

    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable run) {}

    void clear() {
        hits.clear();
    }

    boolean click(double mx, double my) {
        for (Hit h : List.copyOf(hits))
            if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                h.run.run();
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.25f));
                return true;
            }
        return false;
    }

    /** The world dimmed, with black letterbox bars at the top and the bottom of the screen. */
    static void backdrop(GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, 0x66000000);
        int bar = Math.max(8, height / 14);
        g.fill(0, 0, width, bar, BAR);
        g.fill(0, height - bar, width, height, BAR);
    }

    /** The panel: see-through black, corners cut by one pixel, a light rule under the title. */
    static void panel(GuiGraphics g, Font font, int x, int y, int w, int h, Component title) {
        g.fill(x + 1, y, x + w - 1, y + h, PANEL);
        g.fill(x, y + 1, x + 1, y + h - 1, PANEL);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, PANEL);
        String t = title.getString().toUpperCase(java.util.Locale.ROOT);
        g.drawString(font, t, x + (w - font.width(t)) / 2, y + 8, ACCENT, false);
        g.fill(x + 10, y + 21, x + w - 10, y + 22, RULE);
    }

    /** A small heading of a section. */
    static void label(GuiGraphics g, Font font, int x, int y, Component text) {
        g.drawString(font, text.getString().toUpperCase(java.util.Locale.ROOT), x, y, MUTED, false);
    }

    /** A pill: lit when on, lighter under the pointer; a click runs the action. */
    void pill(GuiGraphics g, Font font, int x, int y, int w, int h, Component text, boolean on, int onColor, int mx, int my, Runnable run) {
        boolean over = mx >= x && mx < x + w && my >= y && my < y + h;
        int color = on ? onColor : over ? CHIP_HOVER : CHIP;
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
        String s = font.plainSubstrByWidth(text.getString(), w - 4);
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, on && onColor == ACCENT ? 0xFF16181C : TEXT, false);
        if (run != null) hits.add(new Hit(x, y, w, h, run));
    }

    /** A row of pills of which one is chosen. */
    void segments(GuiGraphics g, Font font, int x, int y, int w, int h, Component[] texts, int chosen, int mx, int my, java.util.function.IntConsumer pick) {
        int gap = 3, each = (w - gap * (texts.length - 1)) / texts.length;
        for (int i = 0; i < texts.length; i++) {
            int k = i;
            pill(g, font, x + i * (each + gap), y, each, h, texts[i], i == chosen, ACCENT, mx, my, () -> pick.accept(k));
        }
    }
}
