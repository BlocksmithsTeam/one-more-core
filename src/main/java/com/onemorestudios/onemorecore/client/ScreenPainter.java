package com.onemorestudios.onemorecore.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OrderBoardBlockEntity;
import com.onemorestudios.onemorecore.Status;

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
 * The menu looks like the menu boards over a fast food counter, the orders like a kitchen order display (see menu and
 * orders). The item under the pointer is lit and its tooltip shows.
 * The screens are lit by themselves (full brightness), as a television is.
 */
public final class ScreenPainter {
    private ScreenPainter() {}

    private static final RenderType WHITE = RenderType.entityCutout(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/block/white.png"));
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
        /** Canvas units of one layout unit (a 200-unit-high single screen at normal size), and the screen's settings. */
        final float u;
        final int settings;
        final List<Spot> spots = new ArrayList<>();
        float z;
        ItemStack hovered = ItemStack.EMPTY;

        Canvas(PoseStack pose, MultiBufferSource buffers, float w, float h, float hx, float hy, float u, int settings) {
            this.u = u;
            this.settings = settings;
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

    /** A rectangle with its corners cut by r (a pixel-art rounded box). */
    static void box(Canvas c, float x, float y, float w, float h, float r, int argb) {
        c.rect(x + r, y, w - 2 * r, h, argb);
        c.rect(x, y + r, r, h - 2 * r, argb);
        c.rect(x + w - r, y + r, r, h - 2 * r, argb);
    }

    /** Page dots centred at the bottom of the canvas. */
    static void dots(Canvas c, int page, int pages, int on, int off) {
        if (pages <= 1) return;
        float u = c.u, d = 2.4f * u, step = 4.5f * u, x = (c.w - (pages - 1) * step - d) / 2;
        for (int i = 0; i < pages; i++) c.rect(x + i * step, c.h - 4.2f * u, d, d, i == page ? on : off);
    }

    private static final int MENU_BG = 0xFF17191E, ROW = 0xFF22252C, ICON_BG = 0xFF2C3038, YELLOW = 0xFFFFC72C;

    /** One block of the menu's flow: a kind's heading (line null) or a dish line. */
    private record Cell(String kind, MenuBoardBlockEntity.Line line) {}

    /**
     * The menu, like the boards over a fast food counter: a red header, then the dishes by kind as rows (a big picture, the
     * name, a yellow price tag) flowing down as many columns as fit; the pages change every 6 seconds when they do not fit.
     */
    static ItemStack menu(Canvas c, List<MenuBoardBlockEntity.Line> lines) {
        float u = c.u;
        c.rect(0, 0, c.w, c.h, MENU_BG);
        float headH = 24 * u;
        c.rect(0, 0, c.w, headH, 0xFFC62828);
        c.rect(0, headH, c.w, 1.6f * u, YELLOW);
        String title = Component.translatable("onemorecore.screen.menu").getString();
        float ts = 1.7f * u;
        c.text(title, (c.w - c.width(title, ts)) / 2, (headH - 7 * ts) / 2, ts, YELLOW);
        List<Cell> flow = new ArrayList<>();
        for (String k : KINDS) {
            if (!com.onemorestudios.onemorecore.ScreenSettings.showsKind(c.settings, k)) continue;
            List<MenuBoardBlockEntity.Line> of = lines.stream().filter(l -> l.kind().equals(k)).toList();
            if (of.isEmpty()) continue;
            flow.add(new Cell(k, null));
            for (var l : of) flow.add(new Cell(k, l));
        }
        if (flow.isEmpty()) {
            String none = Component.translatable("onemorecore.board.empty").getString();
            float ns = 0.9f * u;
            c.text(c.fit(none, c.w - 8 * u, ns), Math.max(4 * u, (c.w - c.width(none, ns)) / 2), (headH + c.h) / 2 - 4 * u, ns, 0xFF8A919C);
            return c.hovered;
        }
        float gap = 5 * u, top = headH + 1.6f * u + 5 * u, bottom = c.h - 7 * u, headingH = 13 * u, rowH = 24 * u, space = 2.5f * u;
        int cols = Math.max(1, (int) ((c.w - gap) / (110 * u)));
        float colW = (c.w - gap * (cols + 1)) / cols;
        // Lay the flow out into pages of columns; a column never ends with a heading, and one that starts in the middle of a
        // kind repeats its heading.
        List<List<List<Cell>>> pages = new ArrayList<>();
        List<List<Cell>> page = new ArrayList<>();
        List<Cell> col = new ArrayList<>();
        float y = top;
        for (int i = 0; i < flow.size(); i++) {
            Cell cell = flow.get(i);
            float need = cell.line() == null ? headingH + rowH + space : rowH;
            if (y + need > bottom && !col.isEmpty()) {
                page.add(col);
                col = new ArrayList<>();
                y = top;
                if (page.size() == cols) {
                    pages.add(page);
                    page = new ArrayList<>();
                }
                if (cell.line() != null) {
                    col.add(new Cell(cell.kind(), null));
                    y += headingH + space;
                }
            }
            col.add(cell);
            y += (cell.line() == null ? headingH : rowH) + space;
        }
        page.add(col);
        pages.add(page);
        int shown = com.onemorestudios.onemorecore.ScreenSettings.autoPages(c.settings) ? (int) (System.currentTimeMillis() / 6000 % pages.size()) : 0;
        List<List<Cell>> current = pages.get(shown);
        for (int k = 0; k < current.size(); k++) {
            float x = gap + k * (colW + gap);
            y = top;
            for (Cell cell : current.get(k)) {
                int color = KIND_COLORS[java.util.Arrays.asList(KINDS).indexOf(cell.kind())];
                if (cell.line() == null) {
                    String head = Component.translatable("onemorecore.board." + cell.kind()).getString().toUpperCase(java.util.Locale.ROOT);
                    float hs = 0.85f * u;
                    c.rect(x, y + 1 * u, 2.4f * u, headingH - 2 * u, color);
                    c.text(c.fit(head, colW - 6 * u, hs), x + 5 * u, y + (headingH - 7 * hs) / 2, hs, color);
                    float after = x + 5 * u + Math.min(c.width(head, hs), colW - 6 * u) + 3 * u;
                    if (after < x + colW) c.rect(after, y + headingH / 2, x + colW - after, 0.6f * u, 0x33FFFFFF);
                    y += headingH + space;
                    continue;
                }
                MenuBoardBlockEntity.Line l = cell.line();
                box(c, x, y, colW, rowH, 1.2f * u, ROW);
                c.rect(x, y + 3 * u, 1.2f * u, rowH - 6 * u, color);
                // The picture: one big icon, or the dishes of a combo overlapping.
                float icon = rowH - 6 * u, iconBox = rowH - 3 * u;
                int n = Math.min(3, l.items().size());
                float picW = iconBox + (n - 1) * icon * 0.55f;
                box(c, x + 2.5f * u, y + 1.5f * u, picW, iconBox, 1.2f * u, ICON_BG);
                for (int j = 0; j < n; j++) c.item(l.items().get(j), x + 2.5f * u + iconBox / 2 + j * icon * 0.55f, y + rowH / 2, icon);
                String price = String.valueOf(l.price());
                float ps = 0.85f * u, pw = c.width(price, ps) + 6 * u, ph = 11 * u;
                box(c, x + colW - pw - 3 * u, y + (rowH - ph) / 2, pw, ph, 1.2f * u, YELLOW);
                c.text(price, x + colW - pw, y + (rowH - 7 * ps) / 2, ps, 0xFF1A1A1A);
                String name = l.items().size() == 1 ? l.items().get(0).getHoverName().getString()
                        : String.join(" + ", l.items().stream().map(st -> st.getHoverName().getString()).toList());
                float nx = x + 2.5f * u + picW + 3.5f * u, ns = 0.8f * u;
                c.text(c.fit(name, x + colW - pw - 6 * u - nx, ns), nx, y + (rowH - 7 * ns) / 2, ns, 0xFFF2F2F2);
                y += rowH + space;
            }
        }
        dots(c, shown, pages.size(), YELLOW, 0x44FFFFFF);
        return c.hovered;
    }

    private static final int PAPER = 0xFFF4F1EA, PAPER_ROW = 0xFFEAE5DA, INK = 0xFF2A2A2A;

    /**
     * The orders, like a kitchen order display: a dark header with the count and the time left, and a paper ticket per order,
     * oldest first: a coloured head with the table (green, then amber, then red as the patience runs out, blinking at the
     * end), a patience bar, a row of dishes for each guest, and the price. The card places match ScreenRect.cardSlot.
     */
    static ItemStack orders(Canvas c, List<OrderBoardBlockEntity.Ticket> tickets) {
        float u = c.u;
        c.rect(0, 0, c.w, c.h, 0xFF16181D);
        float headH = 22 * u;
        c.rect(0, 0, c.w, headH, 0xFF0E0F12);
        c.rect(0, headH - 1.2f * u, c.w, 1.2f * u, 0xFFE53935);
        String title = Component.translatable("onemorecore.screen.orders").getString();
        float ts = 1.1f * u, ty = (headH - 1.2f * u - 7 * ts) / 2;
        c.text(title, 7 * u, ty, ts, 0xFFFFFFFF);
        String count = String.valueOf(tickets.size());
        float cx = 7 * u + c.width(title, ts) + 4 * u, cw = c.width(count, ts) + 6 * u;
        box(c, cx, ty - 2.5f * u, cw, 7 * ts + 4 * u, 1.2f * u, tickets.isEmpty() ? 0xFF3A3D44 : 0xFFE53935);
        c.text(count, cx + 3 * u, ty, ts, 0xFFFFFFFF);
        Status s = Status.client;
        if (s != null && s.serviceLeft() > 0) {
            int sec = s.serviceLeft() / 20;
            String clock = String.format("%02d:%02d", sec / 60, sec % 60);
            c.text(clock, c.w - c.width(clock, ts) - 7 * u, ty, ts, sec < 30 ? 0xFFFF6B5E : 0xFFFFC94D);
        }
        if (tickets.isEmpty()) {
            String none = Component.translatable("onemorecore.screen.no_orders").getString();
            float ns = 1f * u;
            c.text(none, (c.w - c.width(none, ns)) / 2, (headH + c.h) / 2 - 4 * u, ns, 0xFF6B7280);
            return c.hovered;
        }
        float gap = 6 * u, top = 30 * u;
        int perPage = com.onemorestudios.onemorecore.ScreenRect.ordersPerPage(c.w, u);
        int pages = (tickets.size() + perPage - 1) / perPage;
        int page = com.onemorestudios.onemorecore.ScreenRect.page(pages, gameTime(c.settings));
        float cardW = (c.w - gap * (perPage + 1)) / perPage, cardH = c.h - top - gap;
        boolean blink = System.currentTimeMillis() / 400 % 2 == 0;
        for (int i = 0; i < perPage && page * perPage + i < tickets.size(); i++) {
            OrderBoardBlockEntity.Ticket t = tickets.get(page * perPage + i);
            float x = gap + i * (cardW + gap), y = top;
            float p = Math.max(0, Math.min(1, t.patience()));
            int head = p > 0.5f ? 0xFF3FA34D : p > 0.25f ? 0xFFE0A020 : 0xFFD32F2F;
            if (p <= 0.25f && blink) c.rect(x - 1.2f * u, y - 1.2f * u, cardW + 2.4f * u, cardH + 2.4f * u, 0xFFD32F2F);
            c.rect(x + 1.5f * u, y + 1.5f * u, cardW, cardH, 0x50000000);
            c.rect(x, y, cardW, cardH, PAPER);
            float headCard = 18 * u;
            c.rect(x, y, cardW, headCard, head);
            String table = Component.translatable("onemorecore.screen.table", t.table().isEmpty() ? "?" : t.table()).getString();
            String number = "#" + (page * perPage + i + 1);
            float ls = 1f * u, nsz = 0.7f * u;
            c.text(number, x + cardW - c.width(number, nsz) - 3 * u, y + (headCard - 7 * nsz) / 2, nsz, 0xCCFFFFFF);
            c.text(c.fit(table, cardW - c.width(number, nsz) - 9 * u, ls), x + 4 * u, y + (headCard - 7 * ls) / 2, ls, 0xFFFFFFFF);
            // The patience bar.
            c.rect(x, y + headCard, cardW, 2.4f * u, 0xFFD9D3C7);
            c.rect(x, y + headCard, cardW * p, 2.4f * u, head);
            // A row for each guest.
            float footH = 15 * u, rowsTop = y + headCard + 4 * u, bottom = y + cardH - footH - 1 * u;
            float icon = Math.min(19 * u, (cardW - 8 * u) / 3 * 0.8f), rowH = icon + 4 * u;
            int perRow = Math.max(1, (int) ((cardW - 6 * u) / (icon + 3 * u)));
            int shownRows = Math.max(1, (int) ((bottom - rowsTop) / rowH));
            List<List<ItemStack>> rows = t.rows();
            for (int r = 0; r < rows.size() && r < shownRows; r++) {
                float ry = rowsTop + r * rowH;
                if (r % 2 == 1) c.rect(x + 2 * u, ry, cardW - 4 * u, rowH, PAPER_ROW);
                List<ItemStack> row = rows.get(r);
                for (int k = 0; k < row.size() && k < perRow; k++)
                    c.item(row.get(k), x + 4 * u + icon / 2 + k * (icon + 3 * u), ry + rowH / 2, icon);
                if (row.size() > perRow) c.text("+" + (row.size() - perRow), x + cardW - 11 * u, ry + (rowH - 4.5f * u) / 2, 0.65f * u, INK);
            }
            if (rows.size() > shownRows) c.text("+" + (rows.size() - shownRows), x + cardW - 13 * u, bottom - 6 * u, 0.7f * u, INK);
            // The tear line and the price.
            float fy = y + cardH - footH;
            for (float d = x + 2 * u; d < x + cardW - 2 * u; d += 4 * u) c.rect(d, fy, 2 * u, 0.6f * u, 0xFFB5AEA0);
            c.item(StatusCurrency.get(), x + 9 * u, fy + footH / 2, 9 * u);
            c.text(String.valueOf(t.price()), x + 16 * u, fy + (footH - 7 * 0.9f * u) / 2, 0.9f * u, 0xFF2E7D32);
        }
        dots(c, page, pages, 0xFFFFFFFF, 0x44FFFFFF);
        return c.hovered;
    }

    /** The game time that picks the page of the orders: 0 (the first page) when the pages do not change by themselves. */
    static long gameTime(int settings) {
        var level = Minecraft.getInstance().level;
        return level == null || !com.onemorestudios.onemorecore.ScreenSettings.autoPages(settings) ? 0 : level.getGameTime();
    }

    /** The currency item of the restaurant, for the price marks. */
    static final class StatusCurrency {
        private StatusCurrency() {}

        static ItemStack get() {
            Status s = Status.client;
            return s == null ? new ItemStack(OneMoreCore.COIN.get()) : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(s.currency()));
        }
    }

    /** Puts the canvas of a screen rectangle on the pose: top left at the rectangle's top left, 10 units per model pixel. */
    static void place(PoseStack pose, com.onemorestudios.onemorecore.ScreenRect r, float turn, float tilt) {
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-turn));
        pose.translate(-0.5, 0, -0.5);
        if (tilt != 0) {
            // Turned down about the bottom back edge (y 0, z 0), as the model: the top comes out.
            pose.mulPose(Axis.XP.rotationDegrees(tilt));
        }
        pose.translate(r.x0() / 16, r.y1() / 16, (r.z() + 0.05f) / 16);
        pose.scale(1 / 160f, -1 / 160f, 1 / 160f);
    }
}
