package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.ScreenSettings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The settings of a screen (sneak-click with an empty hand or a screwdriver): for each side what it shows (off, menu, orders),
 * the menu's kinds, the text size and the paging; for a Screen also how it stands (upright or wide) and how far it is turned
 * down. Done sends the changes; Escape drops them.
 */
final class ScreenSettingsScreen extends Screen {
    private static final int W = 330, H = 176;
    private final ScreenSettings.Open screen;
    private final int[] faces = new int[2];
    private final boolean[] changed = new boolean[2];
    private int side, tilt, landscape;
    private final Ui ui = new Ui();

    ScreenSettingsScreen(ScreenSettings.Open screen) {
        super(Component.translatable("onemorecore.settings.title"));
        this.screen = screen;
        faces[0] = ScreenSettings.face(screen.settings(), 0);
        faces[1] = ScreenSettings.face(screen.settings(), 1);
        side = Math.min(screen.side(), screen.sides() - 1);
        tilt = screen.tilt();
        landscape = screen.landscape();
    }

    private static Component t(String key) {
        return Component.translatable("onemorecore.settings." + key);
    }

    private void set(int value) {
        faces[side] = ScreenSettings.valid(value);
        changed[side] = true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui.backdrop(g, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        ui.clear();
        // Two columns: what the side shows (left), how it looks and stands (right).
        int x = (width - W) / 2, y = (height - H) / 2, gap = 10, colW = (W - 24 - gap) / 2, left = x + 12, right = left + colW + gap;
        Ui.panel(g, font, x, y, W, H, title);
        int v = faces[side], cy = y + 28;
        if (screen.sides() > 1) {
            ui.segments(g, font, left, cy, W - 24, 14, new Component[] {t("front"), t("back")}, side, mx, my, k -> side = k);
            cy += 20;
        }
        int top = cy;
        Ui.label(g, font, left, cy, t("shows"));
        cy += 10;
        ui.segments(g, font, left, cy, colW, 14, new Component[] {t("off"), t("menu"), t("orders")}, ScreenSettings.mode(v), mx, my,
                k -> set(ScreenSettings.pack(k, ScreenSettings.kinds(faces[side]), ScreenSettings.size(faces[side]), ScreenSettings.autoPages(faces[side]))));
        cy += 20;
        Ui.label(g, font, left, cy, t("kinds"));
        cy += 10;
        int chipW = (colW - 3) / 2;
        for (int i = 0; i < ScreenSettings.KINDS.length; i++) {
            int bit = 1 << i;
            String kind = ScreenSettings.KINDS[i];
            boolean on = ScreenSettings.showsKind(v, kind);
            int col = i % 2, row = i / 2;
            ui.pill(g, font, left + col * (chipW + 3), cy + row * 17, chipW, 14, Component.translatable("onemorecore.board." + kind), on, 0xFF2F80ED, mx, my, () -> {
                int kinds = ScreenSettings.kinds(faces[side]);
                set(ScreenSettings.pack(ScreenSettings.mode(faces[side]), (kinds == 0 ? 31 : kinds) ^ bit, ScreenSettings.size(faces[side]),
                        ScreenSettings.autoPages(faces[side])));
            });
        }
        cy = top;
        Ui.label(g, font, right, cy, t("size"));
        cy += 10;
        int size = ScreenSettings.size(v);
        // Small, normal, large in order (the packed values are normal 0, small 1, large 2).
        int[] order = {1, 0, 2};
        int shownSize = size == 1 ? 0 : size == 0 ? 1 : 2;
        ui.segments(g, font, right, cy, colW, 14, new Component[] {t("small"), t("normal"), t("large")}, shownSize, mx, my,
                k -> set(ScreenSettings.pack(ScreenSettings.mode(faces[side]), ScreenSettings.kinds(faces[side]), order[k], ScreenSettings.autoPages(faces[side]))));
        cy += 20;
        Ui.label(g, font, right, cy, t("pages"));
        cy += 10;
        boolean auto = ScreenSettings.autoPages(v);
        ui.pill(g, font, right, cy, colW, 14, t(auto ? "pages_auto" : "pages_first"), auto, 0xFF2E9E4F, mx, my,
                () -> set(ScreenSettings.pack(ScreenSettings.mode(faces[side]), ScreenSettings.kinds(faces[side]), ScreenSettings.size(faces[side]), !auto)));
        cy += 20;
        if (landscape >= 0 || tilt >= 0) {
            Ui.label(g, font, right, cy, t("placement"));
            cy += 10;
            if (landscape >= 0) {
                ui.segments(g, font, right, cy, colW, 14, new Component[] {t("upright"), t("wide")}, landscape, mx, my, k -> landscape = k);
                cy += 17;
            }
            if (tilt >= 0)
                ui.segments(g, font, right, cy, colW, 14, new Component[] {Component.literal("0°"), Component.literal("22.5°"), Component.literal("45°")},
                        tilt, mx, my, k -> tilt = k);
        }
        int by = y + H - 22;
        ui.pill(g, font, left, by, colW, 14, t("cancel"), false, 0, mx, my, this::onClose);
        ui.pill(g, font, right, by, colW, 14, t("done"), true, Ui.ACCENT, mx, my, this::save);
    }

    private void save() {
        boolean placement = tilt != screen.tilt() || landscape != screen.landscape();
        for (int s = 0; s < screen.sides(); s++) {
            if (!changed[s] && !(placement && s == side)) continue;
            PacketDistributor.sendToServer(new ScreenSettings.Save(screen.pos(), changed[s] ? faces[s] : -1, s == side && tilt != screen.tilt() ? tilt : -1, s,
                    s == side && landscape != screen.landscape() ? landscape : -1, -1));
        }
        onClose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return button == 0 && ui.click(mx, my) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
