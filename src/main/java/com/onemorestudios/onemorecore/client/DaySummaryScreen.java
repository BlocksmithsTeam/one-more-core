package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.DaySummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class DaySummaryScreen extends Screen {
    private final DaySummary summary;
    private final Ui ui = new Ui();

    DaySummaryScreen(DaySummary summary) {
        super(text("title", summary.day()));
        this.summary = summary;
    }

    @Override protected void init() {
        int w = Math.min(300, width - 20), x = (width - w) / 2, y = (height - 238) / 2;
        addRenderableWidget(new Button(x + 12, y + 210, w - 24, 18, text("close"), b -> onClose(), narration -> narration.get()) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float tick) {
                ui.pill(g, font, getX(), getY(), getWidth(), getHeight(), getMessage(),
                        isHoveredOrFocused(), Ui.CHIP_HOVER, mx, my, null);
            }
        });
    }

    static Component text(String key, Object... args) {
        return Component.translatable("onemorecore.summary." + key, args);
    }

    static void draw(GuiGraphics g, Font font, DaySummary s, int x, int y, int w) {
        String[] keys = {"day", "income", "tips", "penalties", "net", "served", "lost", "register"};
        long[] values = {s.day(), s.income(), s.tips(), s.penalties(), s.net(), s.served(), s.lost(), s.register()};
        for (int i = 0; i < keys.length; i++) {
            g.drawString(font, text(keys[i]), x, y + i * 12, Ui.MUTED, false);
            String value = Long.toString(values[i]);
            g.drawString(font, value, x + w - font.width(value), y + i * 12, Ui.TEXT, false);
        }
        y += keys.length * 12 + 4;
        if (s.rating() > 0) {
            g.drawString(font, text("reviews", s.reviewAverage() == 0 ? text("none").getString()
                    : String.format(java.util.Locale.ROOT, "%.1f", s.reviewAverage())), x, y, Ui.TEXT, false);
            g.drawString(font, text("rating", String.format(java.util.Locale.ROOT, "%.1f", s.rating())), x, y + 12, Ui.TEXT, false);
            y += 28;
        }
        g.drawString(font, text("best"), x, y, Ui.MUTED, false);
        ResourceLocation id = ResourceLocation.tryParse(s.bestDish());
        ItemStack dish = id == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(id));
        if (dish.isEmpty()) g.drawString(font, text("none"), x, y + 14, Ui.TEXT, false);
        else {
            g.renderItem(dish, x, y + 12);
            String label = s.bestCount() + " × " + dish.getHoverName().getString();
            g.drawString(font, font.plainSubstrByWidth(label, Math.max(0, w - 22)), x + 22, y + 16, Ui.TEXT, false);
        }
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float tick) {
        Ui.backdrop(g, width, height);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float tick) {
        ui.clear();
        int w = Math.min(300, width - 20), h = 238, x = (width - w) / 2, y = (height - h) / 2;
        Ui.panel(g, font, x, y, w, h, title);
        draw(g, font, summary, x + 12, y + 30, w - 24);
        super.render(g, mx, my, tick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
