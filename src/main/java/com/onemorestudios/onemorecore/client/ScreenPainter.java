package com.onemorestudios.onemorecore.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Tickets;
import com.onemorestudios.onemorecore.OrderLayout;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.ScreenClick;
import com.onemorestudios.onemorecore.ScreenSettings;
import com.onemorestudios.onemorecore.Status;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What a restaurant screen shows, drawn on a canvas of w x h units with its top left at the origin, x to the right and y down
 * (the caller's pose maps the canvas onto one side of the screen). Both pictures have a header with the title and two tabs (Menu,
 * Orders) that switch what this side shows. The menu is like the boards over a fast food counter: the dishes by kind, each with
 * a picture, the name and a yellow price tag. The orders are like a kitchen order display: a ticket per order in columns (a
 * coloured head with the table and the patience, a line per dish, the price), and on a wide picture a summary of the dishes to
 * make; a ticket gives its paper ticket when clicked, a summary line marks the tickets with that dish. The pages change by
 * themselves; up close (Diegesis) the content scrolls instead. Screens light themselves (full brightness), as a television does.
 */
public final class ScreenPainter {
    private ScreenPainter() {}

    private static final ResourceLocation WHITE_TEXTURE = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/block/white.png");
    private static final RenderType WHITE = RenderType.entityCutout(WHITE_TEXTURE);
    /** See-through fills (shadows, highlights): a cutout would draw them solid. */
    private static final RenderType SHEER = RenderType.entityTranslucent(WHITE_TEXTURE);
    private static final String[] KINDS = {"main", "starter", "drink", "dessert", "combo"};
    private static final int[] KIND_COLORS = {0xFFE8762D, 0xFF4CAF50, 0xFF2D8CE8, 0xFFE8508A, 0xFFD32F2F};
    private static final int LIGHT = LightTexture.FULL_BRIGHT;

    /** The canvas of one frame: the pose, the buffers, the pointer (NaN: none), and what a click under the pointer runs. */
    static final class Canvas {
        final PoseStack pose;
        final MultiBufferSource buffers;
        final float w, h, hx, hy;
        /** Canvas units of one layout unit (a 200-unit-high single screen at normal size), and the side's settings. */
        final float u;
        final int settings;
        float z;
        ItemStack hovered = ItemStack.EMPTY;
        Runnable action;
        BlockPos anchor = BlockPos.ZERO;
        int side;
        ScreenRenderer.View view = new ScreenRenderer.View();
        boolean scrolling;

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
            if (rw <= 0 || rh <= 0) return;
            z += 0.02f;
            TrayRenderer.quad(pose, buffers.getBuffer(argb >>> 24 == 0xFF ? WHITE : SHEER), x, y, x + rw, y + rh, z, argb, LIGHT);
        }

        /** A rectangle with its corners cut by r (a pixel-art rounded box). */
        void box(float x, float y, float rw, float rh, float r, int argb) {
            rect(x + r, y, rw - 2 * r, rh, argb);
            rect(x, y + r, r, rh - 2 * r, argb);
            rect(x + rw - r, y + r, r, rh - 2 * r, argb);
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

        /** A region that a click runs; under the pointer it lights up. */
        boolean hit(float x, float y, float rw, float rh, Runnable run) {
            if (!over(x, y, rw, rh)) return false;
            action = run;
            return true;
        }

        /** An item with its middle at (cx, cy), size units across; lit and remembered under the pointer. */
        void item(ItemStack stack, float cx, float cy, float size) {
            if (stack.isEmpty()) return;
            boolean over = over(cx - size / 2, cy - size / 2, size, size);
            if (over) {
                rect(cx - size * 0.6f, cy - size * 0.6f, size * 1.2f, size * 1.2f, 0x55FFFFFF);
                hovered = stack;
            }
            float s = over ? size * 1.12f : size;
            pose.pushPose();
            pose.translate(cx, cy, z + 0.3f);
            pose.scale(s, -s, 0.01f);
            Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI, LIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, null, 0);
            pose.popPose();
        }

        /** Content drawn only between two heights (the scrolled area): true when a band of height bh at y shows. */
        boolean shows(float y, float bh, float top, float bottom) {
            return y + bh > top && y < bottom;
        }
    }

    /** Page dots centred at the bottom of the canvas. */
    static void dots(Canvas c, int page, int pages, int on, int off) {
        if (pages <= 1) return;
        float u = c.u, d = 2.4f * u, step = 4.5f * u, x = (c.w - (pages - 1) * step - d) / 2;
        for (int i = 0; i < pages; i++) c.rect(x + i * step, c.h - 3.6f * u, d, d, i == page ? on : off);
    }

    /** A thin scroll bar at the right of the area, when the content is higher than it. */
    static void scrollBar(Canvas c, float top, float areaH, float content, int color) {
        c.view.maxScroll = Math.max(0, content - areaH);
        if (!c.scrolling || content <= areaH) return;
        float u = c.u, barH = Math.max(8 * u, areaH * areaH / content), y = top + (areaH - barH) * c.view.scroll / (content - areaH);
        c.rect(c.w - 2.2f * u, top, 1.2f * u, areaH, 0x22FFFFFF);
        c.rect(c.w - 2.2f * u, y, 1.2f * u, barH, color);
    }

    /**
     * The header: the title at the left, at the right the tabs Menu and Orders (the shown one lit; a click shows the other on this
     * side) and, with a service running, the time left.
     */
    static void header(Canvas c, String title, int count, int bg, int accent, int titleColor) {
        float u = c.u, hh = OrderLayout.HEADER * u;
        c.rect(0, 0, c.w, hh, bg);
        c.rect(0, hh - 1.2f * u, c.w, 1.2f * u, accent);
        float ts = Math.min(1.1f * u, (hh - 6 * u) / 7);
        float ty = (hh - 1.2f * u - 7 * ts) / 2;
        float x = 6 * u;
        c.text(c.fit(title, c.w * 0.4f, ts), x, ty, ts, titleColor);
        x += Math.min(c.width(title, ts), c.w * 0.4f) + 4 * u;
        if (count >= 0) {
            String n = String.valueOf(count);
            float cw = c.width(n, ts) + 6 * u;
            c.box(x, ty - 2.5f * u, cw, 7 * ts + 4 * u, 1.2f * u, count == 0 ? 0xFF3A3D44 : accent);
            c.text(n, x + 3 * u, ty, ts, 0xFFFFFFFF);
        }
        // The tabs.
        int mode = ScreenSettings.mode(c.settings);
        float tabS = 0.7f * u, right = c.w - 4 * u;
        Status s = Status.client;
        if (s != null && s.serviceLeft() > 0) {
            int sec = s.serviceLeft() / 20;
            String clock = String.format("%02d:%02d", sec / 60, sec % 60);
            right -= c.width(clock, ts);
            c.text(clock, right, ty, ts, sec < 30 ? 0xFFFF6B5E : 0xFFFFC94D);
            right -= 5 * u;
        }
        String[] tabs = {Component.translatable("onemorecore.screen.tab.orders").getString(), Component.translatable("onemorecore.screen.tab.menu").getString()};
        int[] modes = {Restaurant.SCREEN_ORDERS, Restaurant.SCREEN_MENU};
        for (int i = 0; i < 2; i++) {
            float tw = c.width(tabs[i], tabS) + 8 * u, tx = right - tw, th = 12 * u, tyTab = (hh - 1.2f * u - th) / 2;
            if (tx < c.w * 0.45f) break;
            boolean on = mode == modes[i];
            int target = modes[i];
            boolean over = !on && c.hit(tx, tyTab, tw, th, () -> setMode(c.anchor, c.side, target));
            c.box(tx, tyTab, tw, th, 1.5f * u, on ? accent : over ? 0x55FFFFFF : 0x22FFFFFF);
            c.text(tabs[i], tx + 4 * u, tyTab + (th - 7 * tabS) / 2, tabS, on ? 0xFFFFFFFF : 0xFFB8C0CC);
            right = tx - 2.5f * u;
        }
    }

    /** Asks the server to show the menu or the orders on one side of a screen. */
    static void setMode(BlockPos anchor, int side, int mode) {
        PacketDistributor.sendToServer(new ScreenSettings.Save(anchor, -1, -1, side, -1, mode));
    }

    private static final int MENU_BG = 0xFF17191E, ROW = 0xFF22252C, ICON_BG = 0xFF2C3038, YELLOW = 0xFFFFC72C;

    /** One block of the menu's flow: a kind's heading (line null) or a dish line. */
    private record Cell(String kind, MenuBoardBlockEntity.Line line) {}

    /**
     * The menu: a red header, then the dishes by kind as rows (a big picture, the name, a yellow price tag) flowing down as many
     * columns as fit; the pages change every 6 seconds when they do not fit, or up close the kinds go into the shortest column
     * and the menu scrolls.
     */
    static ItemStack menu(Canvas c, List<MenuBoardBlockEntity.Line> lines) {
        float u = c.u;
        c.rect(0, 0, c.w, c.h, MENU_BG);
        float headH = OrderLayout.HEADER * u;
        List<Cell> flow = new ArrayList<>();
        for (String k : KINDS) {
            if (!ScreenSettings.showsKind(c.settings, k)) continue;
            List<MenuBoardBlockEntity.Line> of = lines.stream().filter(l -> l.kind().equals(k)).toList();
            if (of.isEmpty()) continue;
            flow.add(new Cell(k, null));
            for (var l : of) flow.add(new Cell(k, l));
        }
        float gap = 5 * u, top = headH + 5 * u, bottom = c.h - 6 * u, headingH = 13 * u, rowH = 24 * u, space = 2.5f * u;
        int cols = Math.max(1, (int) ((c.w - gap) / (110 * u)));
        float colW = (c.w - gap * (cols + 1)) / cols;
        // Each placed cell: its column and its y (on its page; before scrolling).
        List<float[]> placed = new ArrayList<>();
        int pages = 1;
        if (c.scrolling) {
            // Whole kinds into the shortest column.
            float[] colY = new float[cols];
            java.util.Arrays.fill(colY, top);
            int i = 0;
            while (i < flow.size()) {
                int col = 0;
                for (int k = 1; k < cols; k++) if (colY[k] < colY[col]) col = k;
                do {
                    Cell cell = flow.get(i);
                    placed.add(new float[] {col, colY[col], 0});
                    colY[col] += (cell.line() == null ? headingH : rowH) + space;
                    i++;
                } while (i < flow.size() && flow.get(i).line() != null);
            }
            float content = 0;
            for (float y : colY) content = Math.max(content, y - top);
            scrollBar(c, top, bottom - top, content, YELLOW);
        } else {
            // Pages of columns; a column never ends with a heading, and one that starts in a kind repeats its heading.
            int col = 0, page = 0;
            float y = top;
            List<Cell> withRepeats = new ArrayList<>();
            for (int i = 0; i < flow.size(); i++) {
                Cell cell = flow.get(i);
                float need = cell.line() == null ? headingH + rowH + space : rowH;
                if (y + need > bottom && y > top) {
                    col++;
                    y = top;
                    if (col == cols) { col = 0; page++; }
                    if (cell.line() != null) {
                        withRepeats.add(new Cell(cell.kind(), null));
                        placed.add(new float[] {col, y, page});
                        y += headingH + space;
                    }
                }
                withRepeats.add(cell);
                placed.add(new float[] {col, y, page});
                y += (cell.line() == null ? headingH : rowH) + space;
            }
            flow = withRepeats;
            pages = page + 1;
            c.view.maxScroll = 0;
        }
        int shown = !c.scrolling && ScreenSettings.autoPages(c.settings) ? (int) (System.currentTimeMillis() / 6000 % pages) : 0;
        float scroll = c.scrolling ? Math.min(c.view.scroll, c.view.maxScroll) : 0;
        for (int i = 0; i < flow.size(); i++) {
            float[] at = placed.get(i);
            if ((int) at[2] != shown) continue;
            Cell cell = flow.get(i);
            float x = gap + at[0] * (colW + gap), y = at[1] - scroll;
            float bh = cell.line() == null ? headingH : rowH;
            if (!c.shows(y, bh, top, bottom) || y < top - 1) continue;
            int color = KIND_COLORS[java.util.Arrays.asList(KINDS).indexOf(cell.kind())];
            if (cell.line() == null) {
                String head = Component.translatable("onemorecore.board." + cell.kind()).getString().toUpperCase(java.util.Locale.ROOT);
                float hs = 0.85f * u;
                c.rect(x, y + 1 * u, 2.4f * u, headingH - 2 * u, color);
                c.text(c.fit(head, colW - 6 * u, hs), x + 5 * u, y + (headingH - 7 * hs) / 2, hs, color);
                float after = x + 5 * u + Math.min(c.width(head, hs), colW - 6 * u) + 3 * u;
                if (after < x + colW) c.rect(after, y + headingH / 2, x + colW - after, 0.6f * u, 0x33FFFFFF);
                continue;
            }
            MenuBoardBlockEntity.Line l = cell.line();
            c.box(x, y, colW, rowH, 1.2f * u, ROW);
            c.rect(x, y + 3 * u, 1.2f * u, rowH - 6 * u, color);
            float icon = rowH - 6 * u, iconBox = rowH - 3 * u;
            int n = Math.min(3, l.items().size());
            float picW = iconBox + (n - 1) * icon * 0.55f;
            c.box(x + 2.5f * u, y + 1.5f * u, picW, iconBox, 1.2f * u, ICON_BG);
            for (int j = 0; j < n; j++) c.item(l.items().get(j), x + 2.5f * u + iconBox / 2 + j * icon * 0.55f, y + rowH / 2, icon);
            String price = String.valueOf(l.price());
            float ps = 0.85f * u, pw = c.width(price, ps) + 6 * u, ph = 11 * u;
            c.box(x + colW - pw - 3 * u, y + (rowH - ph) / 2, pw, ph, 1.2f * u, YELLOW);
            c.text(price, x + colW - pw, y + (rowH - 7 * ps) / 2, ps, 0xFF1A1A1A);
            String name = l.items().size() == 1 ? l.items().get(0).getHoverName().getString()
                    : String.join(" + ", l.items().stream().map(st -> st.getHoverName().getString()).toList());
            float nx = x + 2.5f * u + picW + 3.5f * u, ns = 0.8f * u;
            c.text(c.fit(name, x + colW - pw - 6 * u - nx, ns), nx, y + (rowH - 7 * ns) / 2, ns, 0xFFF2F2F2);
        }
        if (flow.isEmpty()) {
            String none = Component.translatable("onemorecore.board.empty").getString();
            float ns = 0.9f * u;
            c.text(c.fit(none, c.w - 8 * u, ns), Math.max(4 * u, (c.w - c.width(none, ns)) / 2), (headH + c.h) / 2 - 4 * u, ns, 0xFF8A919C);
        }
        c.rect(0, bottom, c.w, c.h - bottom, MENU_BG);
        // The header goes over the scrolled rows.
        header(c, Component.translatable("onemorecore.screen.menu").getString(), -1, 0xFFB71C1C, YELLOW, YELLOW);
        dots(c, shown, pages, YELLOW, 0x44FFFFFF);
        return c.hovered;
    }

    private static final int KDS_BG = 0xFF2B3542, KDS_HEAD = 0xFF1C232D, PAPER = 0xFFF6F7F9, INK = 0xFF1F2933, RULE = 0xFFE1E5EA,
            SUMMARY_BG = 0xFFEEF1F5, GREEN = 0xFF2E9E4F, AMBER = 0xFFE39A1B, RED = 0xFFD93A35, BLUE = 0xFF2F80ED;

    /**
     * The orders, like a kitchen order display: tickets in columns, oldest first (OrderLayout). A ticket: a head coloured by the
     * patience left (green, amber, red; red tickets blink) with the table and the ticket number, a patience bar, a line per dish
     * (picture and name) with a rule between guests, and the price with a Ticket button. A click on a ticket gives its paper
     * ticket. On a wide picture, a summary of the dishes to make: a click on a dish marks the tickets that have it.
     */
    static ItemStack orders(Canvas c, List<Tickets.Ticket> tickets) {
        float u = c.u;
        c.rect(0, 0, c.w, c.h, KDS_BG);
        OrderLayout.Layout layout = OrderLayout.layout(c.w, c.h, u, OrderLayout.guests(tickets), c.scrolling);
        scrollBar(c, layout.areaTop(), layout.areaHeight(), layout.contentHeight(), 0xFFB8C0CC);
        int page = c.scrolling ? 0 : OrderLayout.page(layout.pages(), ScreenSettings.autoPages(c.settings) && Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime() : 0);
        float scroll = c.scrolling ? Math.min(c.view.scroll, layout.maxScroll()) : 0;
        float top = layout.areaTop(), bottom = top + layout.areaHeight();
        boolean blink = System.currentTimeMillis() / 400 % 2 == 0;
        Item marked = c.view.marked;
        for (OrderLayout.Card card : layout.cards()) {
            if (card.page() != page) continue;
            float x = card.x(), y = card.y() - scroll, cw = card.w(), ch = card.h();
            if (!c.shows(y, ch, top, bottom)) continue;
            Tickets.Ticket t = tickets.get(card.index());
            float p = Math.max(0, Math.min(1, t.patience()));
            int head = p > 0.5f ? GREEN : p > 0.25f ? AMBER : RED;
            boolean has = marked == null || t.items().stream().anyMatch(s -> s.is(marked));
            final int index = card.index();
            boolean over = t.orderId() != null && c.hit(x, y, cw, ch, () -> PacketDistributor.sendToServer(
                    new ScreenClick(c.anchor, tickets.get(index).orderId(), c.hx, c.hy, c.side, c.scrolling ? scroll : -1, page)));
            if (p <= 0.25f && blink) c.rect(x - 1.2f * u, y - 1.2f * u, cw + 2.4f * u, ch + 2.4f * u, RED);
            if (marked != null && has) c.rect(x - 1.2f * u, y - 1.2f * u, cw + 2.4f * u, ch + 2.4f * u, 0xFFFFD54F);
            c.rect(x + 1.2f * u, y + 1.5f * u, cw, ch, 0x55000000);
            c.rect(x, y, cw, ch, PAPER);
            float hh = OrderLayout.HEAD * u;
            c.rect(x, y, cw, hh, head);
            String table = Component.translatable("onemorecore.screen.table", t.table().isEmpty() ? "?" : t.table()).getString();
            String number = "#" + (card.index() + 1);
            float ls = 0.82f * u, nsz = 0.62f * u;
            c.text(number, x + cw - c.width(number, nsz) - 3 * u, y + (hh - 7 * nsz) / 2, nsz, 0xDDFFFFFF);
            c.text(c.fit(table, cw - c.width(number, nsz) - 9 * u, ls), x + 3.5f * u, y + (hh - 7 * ls) / 2, ls, 0xFFFFFFFF);
            float by = y + hh;
            c.rect(x, by, cw, OrderLayout.BAR * u, 0xFFD5D9DF);
            c.rect(x, by, cw * p, OrderLayout.BAR * u, head);
            // A line per dish; a rule between guests.
            float ly = by + OrderLayout.BAR * u + OrderLayout.PAD * u, line = OrderLayout.LINE * u, icon = line - 2.5f * u, text = 0.66f * u;
            List<List<ItemStack>> rows = t.rows();
            for (int r = 0; r < rows.size(); r++) {
                if (r > 0) {
                    c.rect(x + 3 * u, ly + OrderLayout.GUEST_GAP * u / 2 - 0.3f * u, cw - 6 * u, 0.6f * u, RULE);
                    ly += OrderLayout.GUEST_GAP * u;
                }
                List<ItemStack> row = rows.get(r);
                if (row.isEmpty()) { ly += line; continue; }
                for (ItemStack dish : row) {
                    boolean hit = marked != null && dish.is(marked);
                    if (hit) c.rect(x + 1.5f * u, ly, cw - 3 * u, line, 0xFFFFF3C4);
                    c.item(dish, x + 3 * u + icon / 2, ly + line / 2, icon);
                    c.text(c.fit(dish.getHoverName().getString(), cw - icon - 9 * u, text), x + 5.5f * u + icon, ly + (line - 7 * text) / 2, text, INK);
                    ly += line;
                }
            }
            // The foot: the price, and the button that gives the paper ticket.
            float fy = y + ch - OrderLayout.FOOT * u;
            c.rect(x + 2 * u, fy, cw - 4 * u, 0.6f * u, RULE);
            c.item(StatusCurrency.get(), x + 7 * u, fy + OrderLayout.FOOT * u / 2, 7 * u);
            c.text(String.valueOf(t.price()), x + 12.5f * u, fy + (OrderLayout.FOOT * u - 7 * 0.72f * u) / 2, 0.72f * u, 0xFF2E7D32);
            String take = Component.translatable("onemorecore.screen.ticket").getString();
            float bs = 0.6f * u, bw = c.width(take, bs) + 6 * u, bh = 8 * u;
            if (bw < cw * 0.55f) {
                c.box(x + cw - bw - 3 * u, fy + (OrderLayout.FOOT * u - bh) / 2, bw, bh, 1.2f * u, over ? 0xFF1565C0 : BLUE);
                c.text(take, x + cw - bw, fy + (OrderLayout.FOOT * u - 7 * bs) / 2, bs, 0xFFFFFFFF);
            }
            if (!has) c.rect(x, y, cw, ch, 0x88202832);
        }
        if (tickets.isEmpty()) {
            String none = Component.translatable("onemorecore.screen.no_orders").getString();
            float ns = 0.95f * u;
            c.text(c.fit(none, c.w - 8 * u, ns), Math.max(4 * u, (c.w - c.width(none, ns)) / 2), (top + bottom) / 2 - 4 * u, ns, 0xFF8A96A6);
        }
        // Cards scrolled past the bottom of the area end at its edge.
        c.rect(0, bottom, c.w, c.h - bottom, KDS_BG);
        if (layout.summaryW() > 0) summary(c, tickets, layout);
        header(c, Component.translatable("onemorecore.screen.orders").getString(), tickets.size(), KDS_HEAD, BLUE, 0xFFFFFFFF);
        if (!c.scrolling) dots(c, page, layout.pages(), 0xFFFFFFFF, 0x44FFFFFF);
        return c.hovered;
    }

    /** The dishes of all tickets with their counts; a click on one marks the tickets with it (again: all). */
    private static void summary(Canvas c, List<Tickets.Ticket> tickets, OrderLayout.Layout layout) {
        float u = c.u, x = layout.summaryX(), y = layout.areaTop(), w = layout.summaryW(), h = layout.areaHeight();
        Map<Item, int[]> counts = new LinkedHashMap<>();
        Map<Item, ItemStack> stacks = new LinkedHashMap<>();
        for (var t : tickets) for (ItemStack s : t.items()) {
            counts.computeIfAbsent(s.getItem(), k -> new int[1])[0] += s.getCount();
            stacks.putIfAbsent(s.getItem(), s);
        }
        c.rect(x, y, w, h, SUMMARY_BG);
        c.rect(x, y, w, 13 * u, 0xFFDDE2E8);
        float ts = 0.72f * u;
        c.text(Component.translatable("onemorecore.screen.summary").getString(), x + 4 * u, y + (13 * u - 7 * ts) / 2, ts, INK);
        float ly = y + 15 * u, line = 12 * u, icon = 9 * u, text = 0.64f * u;
        for (var e : counts.entrySet()) {
            if (ly + line > y + h) break;
            Item item = e.getKey();
            boolean on = item == c.view.marked;
            boolean over = c.hit(x, ly, w, line, () -> c.view.marked = c.view.marked == item ? null : item);
            if (on || over) c.rect(x + 1 * u, ly, w - 2 * u, line, on ? 0xFFFFE082 : 0xFFE3E8EE);
            c.item(stacks.get(item), x + 3 * u + icon / 2, ly + line / 2, icon);
            String n = String.valueOf(e.getValue()[0]);
            c.text(n, x + w - c.width(n, text) - 4 * u, ly + (line - 7 * text) / 2, text, 0xFF1565C0);
            c.text(c.fit(stacks.get(item).getHoverName().getString(), w - icon - c.width(n, text) - 12 * u, text), x + 5 * u + icon,
                    ly + (line - 7 * text) / 2, text, INK);
            ly += line;
        }
    }

    /** The currency item of the restaurant, for the price marks. */
    static final class StatusCurrency {
        private StatusCurrency() {}

        static ItemStack get() {
            Status s = Status.client;
            return s == null ? new ItemStack(OneMoreCore.COIN.get()) : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(s.currency()));
        }
    }
}
