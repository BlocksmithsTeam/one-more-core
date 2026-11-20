package com.onemorestudios.onemoreclient.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.MenuBoardBlockEntity;
import com.onemorestudios.onemoreclient.OneMoreClient;
import com.onemorestudios.onemoreclient.OrderBoardBlockEntity;
import com.onemorestudios.onemoreclient.Status;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * What a restaurant television shows, drawn on a canvas of w x h units with its top left at the origin, x to the right
 * and y down (the caller's pose maps the canvas onto the screen of the television).
 * The menu looks like the menu screens over a fast food counter: a dark screen with one column per kind, a coloured
 * heading, and a tile for each dish with a big picture, the name and the price. The orders look like the order screen
 * of a restaurant game: a red title bar, and a card per order with a coloured head (green, then amber, then red as the
 * patience runs out), the table, the dishes and the price. The item under the pointer is lit and its tooltip shows.
 * The screens are lit by themselves (full brightness), as a television is.
 */
public final class ScreenPainter {
    private ScreenPainter() {}

    private static final RenderType WHITE = RenderType.entityCutout(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/block/white.png"));
    private static final String[] KINDS = {"main", "starter", "drink", "dessert", "combo"};
    private static final int[] KIND_COLORS = {0xFFE8762D, 0xFF4CAF50, 0xFF2D8CE8, 0xFFE8508A, 0xFFD32F2F};
    private static final int LIGHT = LightTexture.FULL_BRIGHT;

    /** An item drawn on the canvas, for the pointer. */
    record Spot(float x, float y, float w, float h, ItemStack stack) {}

    /** The canvas of one frame: the pose, the buffers, the hover point (NaN: none) and the item spots drawn. */
    static final class Canvas {
        final PoseStack pose;
        final MultiBufferSource buffers;
        final float w, h, hx, hy;
        final List<Spot> spots = new ArrayList<>();
        float z;
        ItemStack hovered = ItemStack.EMPTY;

        Canvas(PoseStack pose, MultiBufferSource buffers, float w, float h, float hx, float hy) {
            this.pose = pose;
            this.buffers = buffers;
            this.w = w;
            this.h = h;
            this.hx = hx;
            this.hy = hy;
        }

        /** A filled rectangle, a little in front of what was drawn before. */
        void rect(float x, float y, float rw, float rh, int argb) {
            z += 0.02f;
            TrayRenderer.quad(pose, buffers.getBuffer(WHITE), x, y, x + rw, y + rh, z, argb, LIGHT);
        }

        void text(String s, float x, float y, float scale, int color) {
            Font font = Minecraft.getInstance().font;
            pose.pushPose();
            pose.translate(x, y, z + 0.05f);
            pose.scale(scale, scale, scale);
            font.drawInBatch(s, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, LIGHT);
            pose.popPose();
        }

        float width(String s, float scale) {
            return Minecraft.getInstance().font.width(s) * scale;
        }

        /** Text cut to a width, with an ellipsis. */
        String fit(String s, float maxW, float scale) {
            Font font = Minecraft.getInstance().font;
            int max = (int) (maxW / scale);
            if (font.width(s) <= max) return s;
            return font.plainSubstrByWidth(s, Math.max(0, max - font.width("…"))) + "…";
        }

        boolean over(float x, float y, float rw, float rh) {
            return hx >= x && hx < x + rw && hy >= y && hy < y + rh;
        }

        /** An item with its middle at (cx, cy), size units across; lit and remembered under the pointer. */
        void item(ItemStack stack, float cx, float cy, float size) {
            if (stack.isEmpty()) return;
            boolean over = over(cx - size / 2, cy - size / 2, size, size);
            if (over) {
                rect(cx - size * 0.6f, cy - size * 0.6f, size * 1.2f, size * 1.2f, 0x55FFFFFF);
                hovered = stack;
            }
            spots.add(new Spot(cx - size / 2, cy - size / 2, size, size, stack));
            float s = over ? size * 1.12f : size;
            pose.pushPose();
            pose.translate(cx, cy, z + 0.3f);
            pose.scale(s, -s, 0.01f);
            Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI, LIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, null, 0);
            pose.popPose();
        }
    }

    /** The menu: columns by kind; when the dishes do not fit, the pages change every 6 seconds. */
    static ItemStack menu(Canvas c, List<MenuBoardBlockEntity.Line> lines) {
        float u = c.h / 200;
        c.rect(0, 0, c.w, c.h, 0xFF15171C);
        // The title strip.
        c.rect(0, 0, c.w, 22 * u, 0xFF0F1014);
        String title = Component.translatable("onemoreclient.screen.menu").getString();
        c.text(title, (c.w - c.width(title, 1.6f * u)) / 2, 5 * u, 1.6f * u, 0xFFFFD866);
        List<String> kinds = new ArrayList<>();
        for (String k : KINDS) if (lines.stream().anyMatch(l -> l.kind().equals(k))) kinds.add(k);
        if (kinds.isEmpty()) {
            String none = Component.translatable("onemoreclient.board.empty").getString();
            c.text(none, (c.w - c.width(none, 1.1f * u)) / 2, c.h / 2, 1.1f * u, 0xFF8A919C);
            return c.hovered;
        }
        float gap = 5 * u, top = 28 * u;
        float colW = (c.w - gap * (kinds.size() + 1)) / kinds.size();
        for (int k = 0; k < kinds.size(); k++) {
            String kind = kinds.get(k);
            int color = KIND_COLORS[java.util.Arrays.asList(KINDS).indexOf(kind)];
            float x = gap + k * (colW + gap);
            c.rect(x, top, colW, 14 * u, color);
            String head = Component.translatable("onemoreclient.board." + kind).getString().toUpperCase(java.util.Locale.ROOT);
            float hs = 0.9f * u;
            c.text(c.fit(head, colW - 6 * u, hs), x + 4 * u, top + 3.5f * u, hs, 0xFFFFFFFF);
            List<MenuBoardBlockEntity.Line> of = lines.stream().filter(l -> l.kind().equals(kind)).toList();
            // Tiles in rows of one or two; the tile height fits the column, at most 4 rows a page.
            float areaTop = top + 17 * u, areaH = c.h - areaTop - gap;
            int perRow = colW > 120 * u ? 2 : 1;
            int rows = Math.min(4, (of.size() + perRow - 1) / perRow);
            int perPage = rows * perRow, pages = (of.size() + perPage - 1) / perPage;
            int page = (int) (System.currentTimeMillis() / 6000 % pages);
            float tileH = (areaH - (rows - 1) * 3 * u) / rows, tileW = (colW - (perRow - 1) * 3 * u) / perRow;
            for (int i = 0; i < perPage && page * perPage + i < of.size(); i++) {
                MenuBoardBlockEntity.Line l = of.get(page * perPage + i);
                float tx = x + (i % perRow) * (tileW + 3 * u), ty = areaTop + (i / perRow) * (tileH + 3 * u);
                c.rect(tx, ty, tileW, tileH, 0xFF22252C);
                // The picture: one big icon, or the dishes of a combo side by side.
                float icon = Math.min(tileH * 0.55f, tileW / Math.max(1, l.items().size()) * 0.8f);
                float iy = ty + Math.max(3 * u + icon / 2, (tileH - 12 * u) / 2);
                for (int j = 0; j < l.items().size(); j++)
                    c.item(l.items().get(j), tx + tileW / 2 + (j - (l.items().size() - 1) / 2f) * icon * 1.05f, iy, icon);
                String name = l.items().size() == 1 ? l.items().get(0).getHoverName().getString() : head;
                float ns = Math.min(0.75f * u, tileH / 60);
                String price = String.valueOf(l.price());
                float ps = ns * 1.15f, pw = c.width(price, ps) + 6 * u;
                float by = ty + tileH - 11 * u * (ns / (0.75f * u));
                c.text(c.fit(name, tileW - pw - 6 * u, ns), tx + 3 * u, by + 1.5f * u, ns, 0xFFF2F2F2);
                c.rect(tx + tileW - pw - 2 * u, by, pw, 9 * u * (ns / (0.75f * u)), 0xFFFFC72C);
                c.text(price, tx + tileW - pw + 1 * u, by + 1.2f * u, ps, 0xFF1A1A1A);
            }
            if (pages > 1) {
                String p = (page + 1) + "/" + pages;
                c.text(p, x + colW - c.width(p, 0.6f * u) - 2 * u, c.h - 7 * u, 0.6f * u, 0xFF8A919C);
            }
        }
        return c.hovered;
    }

    /** The orders: a red title bar with the time left, and a card per order, oldest first. */
    static ItemStack orders(Canvas c, List<OrderBoardBlockEntity.Ticket> tickets) {
        float u = c.h / 200;
        c.rect(0, 0, c.w, c.h, 0xFFC9CDD3);
        // A faint pattern of squares, like the screen of the game this copies.
        for (float y = 30 * u; y < c.h - 4 * u; y += 24 * u)
            for (float x = ((int) (y / (24 * u)) % 2) * 12 * u + 6 * u; x < c.w - 6 * u; x += 24 * u) c.rect(x, y, 5 * u, 5 * u, 0xFFBFC3CA);
        c.rect(0, 0, c.w, 22 * u, 0xFFC62828);
        String title = Component.translatable("onemoreclient.screen.orders").getString();
        c.text(title, 6 * u, 6 * u, 1.2f * u, 0xFFFFFFFF);
        Status s = Status.client;
        if (s != null && s.serviceLeft() > 0) {
            int sec = s.serviceLeft() / 20;
            String clock = String.format("%02d:%02d", sec / 60, sec % 60);
            c.text(clock, c.w - c.width(clock, 1.2f * u) - 6 * u, 6 * u, 1.2f * u, 0xFFFFFFFF);
        }
        if (tickets.isEmpty()) {
            String none = Component.translatable("onemoreclient.screen.no_orders").getString();
            c.text(none, (c.w - c.width(none, 1.1f * u)) / 2, c.h / 2, 1.1f * u, 0xFF6B7280);
            return c.hovered;
        }
        float gap = 6 * u, top = 30 * u;
        int perPage = com.onemorestudios.onemoreclient.ScreenRect.ordersPerPage(c.w, c.h);
        int pages = (tickets.size() + perPage - 1) / perPage;
        int page = com.onemorestudios.onemoreclient.ScreenRect.page(pages, Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime());
        float cardW = (c.w - gap * (perPage + 1)) / perPage, cardH = c.h - top - gap;
        for (int i = 0; i < perPage && page * perPage + i < tickets.size(); i++) {
            OrderBoardBlockEntity.Ticket t = tickets.get(page * perPage + i);
            float x = gap + i * (cardW + gap), y = top;
            int head = t.patience() > 0.5f ? 0xFF3FA34D : t.patience() > 0.25f ? 0xFFE0A020 : 0xFFC62828;
            c.rect(x + 1.5f * u, y + 1.5f * u, cardW, cardH, 0x40000000);
            c.rect(x, y, cardW, cardH, 0xFF2B2F36);
            c.rect(x, y, cardW, 26 * u, head);
            String table = Component.translatable("onemoreclient.screen.table", t.table()).getString();
            c.text(c.fit(table, cardW - 8 * u, 1.05f * u), x + 4 * u, y + 4 * u, 1.05f * u, 0xFFFFFFFF);
            // Patience as three dots under the table.
            for (int d = 0; d < 3; d++)
                c.rect(x + 4 * u + d * 7 * u, y + 17 * u, 5 * u, 5 * u, t.patience() > d / 3f ? 0xFFFFFFFF : 0x55FFFFFF);
            c.text(Component.translatable("onemoreclient.screen.items").getString(), x + 4 * u, y + 31 * u, 0.6f * u, 0xFF9AA3AD);
            int cols = 3;
            float icon = Math.min(20 * u, (cardW - 8 * u) / cols * 0.85f), cell = (cardW - 8 * u) / cols;
            for (int k = 0; k < t.items().size() && k < 9; k++)
                c.item(t.items().get(k), x + 4 * u + (k % cols + 0.5f) * cell, y + 40 * u + (k / cols + 0.5f) * (icon + 4 * u), icon);
            c.rect(x, y + cardH - 18 * u, cardW, 18 * u, 0xFF23262C);
            String price = String.valueOf(t.price());
            c.item(StatusCurrency.get(), x + 9 * u, y + cardH - 9 * u, 10 * u);
            c.text(price, x + 16 * u, y + cardH - 13 * u, 0.95f * u, 0xFF7AE07A);
            if (t.items().size() > 9) c.text("+" + (t.items().size() - 9), x + cardW - 18 * u, y + cardH - 13 * u, 0.8f * u, 0xFFFFFFFF);
        }
        if (pages > 1) {
            String p = (page + 1) + "/" + pages;
            c.text(p, c.w - c.width(p, 0.7f * u) - 6 * u, c.h - 7 * u, 0.7f * u, 0xFF6B7280);
        }
        return c.hovered;
    }

    /** The currency item of the restaurant, for the price marks. */
    static final class StatusCurrency {
        private StatusCurrency() {}

        static ItemStack get() {
            Status s = Status.client;
            return s == null ? new ItemStack(OneMoreClient.COIN.get()) : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(s.currency()));
        }
    }

    /** Puts the canvas of a screen rectangle on the pose: top left at the rectangle's top left, 10 units per model pixel. */
    static void place(PoseStack pose, com.onemorestudios.onemoreclient.ScreenRect r, float turn) {
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-turn));
        pose.translate(-0.5, 0, -0.5);
        pose.translate(r.x0() / 16, r.y1() / 16, (r.z() + 0.05f) / 16);
        pose.scale(1 / 160f, -1 / 160f, 1 / 160f);
    }
}
