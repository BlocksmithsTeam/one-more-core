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
 * a picture, the name and a price tag. The orders are like a kitchen order display: a ticket per order in columns (a
 * coloured head with the table and the patience, a line per dish, the price), and on a wide picture a summary of the dishes to
 * make; a ticket gives its paper ticket when clicked, a summary line marks the tickets with that dish. What does not fit scrolls
 * by itself, smoothly, down or sideways (ScreenSettings); up close (Diegesis) the wheel scrolls it. Screens light themselves (full
 * brightness), as a television does.
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
        /** What is drawn outside this box is cut off (rectangles) or left out (text and items that do not fit in whole). */
        float clipX0 = -Float.MAX_VALUE, clipY0 = -Float.MAX_VALUE, clipX1 = Float.MAX_VALUE, clipY1 = Float.MAX_VALUE;

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

        void clip(float x0, float y0, float x1, float y1) {
            clipX0 = x0;
            clipY0 = y0;
            clipX1 = x1;
            clipY1 = y1;
        }

        void unclip() {
            clip(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        }

        /** Whether a box lies in the clip box in whole. */
        boolean inside(float x, float y, float rw, float rh) {
            return x >= clipX0 - 0.01f && y >= clipY0 - 0.01f && x + rw <= clipX1 + 0.01f && y + rh <= clipY1 + 0.01f;
        }

        /** A filled rectangle, a little in front of what was drawn before, cut to the clip box. */
        void rect(float x, float y, float rw, float rh, int argb) {
            float x0 = Math.max(x, clipX0), y0 = Math.max(y, clipY0), x1 = Math.min(x + rw, clipX1), y1 = Math.min(y + rh, clipY1);
            if (x1 <= x0 || y1 <= y0) return;
            z += 0.02f;
            TrayRenderer.quad(pose, buffers.getBuffer(argb >>> 24 == 0xFF ? WHITE : SHEER), x0, y0, x1, y1, z, argb, LIGHT);
        }

        /** A rectangle with its corners cut by r (a pixel-art rounded box). */
        void box(float x, float y, float rw, float rh, float r, int argb) {
            rect(x + r, y, rw - 2 * r, rh, argb);
            rect(x, y + r, r, rh - 2 * r, argb);
            rect(x + rw - r, y + r, r, rh - 2 * r, argb);
        }

        void text(String s, float x, float y, float scale, int color) {
            Font font = Minecraft.getInstance().font;
            if (!inside(x, y, font.width(s) * scale, 8 * scale)) return;
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
            return hx >= x && hx < x + rw && hy >= y && hy < y + rh && hx >= clipX0 && hx < clipX1 && hy >= clipY0 && hy < clipY1;
        }

        /** A region that a click runs; under the pointer it lights up. */
        boolean hit(float x, float y, float rw, float rh, Runnable run) {
            if (!over(x, y, rw, rh)) return false;
            action = run;
            return true;
        }

        /** An item with its middle at (cx, cy), size units across; lit and remembered under the pointer. */
        void item(ItemStack stack, float cx, float cy, float size) {
            if (stack.isEmpty() || !inside(cx - size / 2, cy - size / 2, size, size)) return;
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
    }

    /** How fast a screen scrolls by itself, layout units a second, and the gap before its start comes round again. */
    private static final float SPEED = 9, LOOP_GAP = 16;

    /**
     * Where the content is drawn along its flow (ScreenSettings.SCROLL_*), length long where shown of it shows: up close (Diegesis)
     * once, scrolled by the wheel; else once at its start when it fits or does not scroll, or twice, moving on smoothly, the start
     * coming round again after the end (as a ticker).
     */
    static float[] offsets(Canvas c, float length, float shown, int flow) {
        c.view.maxScroll = c.scrolling ? Math.max(0, length - shown) : 0;
        if (c.scrolling) return new float[] {Math.min(c.view.scroll, c.view.maxScroll)};
        if (flow == ScreenSettings.SCROLL_OFF || length <= shown) return new float[] {0};
        float period = length + LOOP_GAP * c.u;
        float off = (float) (net.minecraft.Util.getMillis() / 1000.0 * SPEED * c.u % period);
        return new float[] {off, off - period};
    }

    /** The flow the content is laid out in: the side's scroll setting; up close it scrolls by hand, down unless set sideways. */
    static int flow(Canvas c) {
        int set = ScreenSettings.scroll(c.settings);
        return c.scrolling ? (set == ScreenSettings.SCROLL_SIDEWAYS ? set : ScreenSettings.SCROLL_DOWN) : set;
    }

    /** A thin scroll bar up close, when the content is longer than its area: at the right (down) or at the bottom (sideways). */
    static void scrollBar(Canvas c, int flow, float start, float area, float content, int color) {
        if (!c.scrolling || content <= area) return;
        float u = c.u, bar = Math.max(8 * u, area * area / content), at = start + (area - bar) * c.view.scroll / (content - area);
        if (flow == ScreenSettings.SCROLL_SIDEWAYS) {
            c.rect(start, c.h - 2.2f * u, area, 1.2f * u, 0x22FFFFFF);
            c.rect(at, c.h - 2.2f * u, bar, 1.2f * u, color);
        } else {
            c.rect(c.w - 2.2f * u, start, 1.2f * u, area, 0x22FFFFFF);
            c.rect(c.w - 2.2f * u, at, 1.2f * u, bar, color);
        }
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
        // From the right: the time left, then the tabs; the title and the count get what is left, never under them.
        int mode = ScreenSettings.mode(c.settings);
        float tabS = 0.7f * u, right = c.w - 4 * u;
        Status s = Status.client;
        if (s != null && s.serviceLeft() > 0) {
            int sec = s.serviceLeft() / 20;
            String clock = String.format("%02d:%02d", sec / 60, sec % 60);
            if (c.width(clock, ts) < c.w * 0.3f) {
                right -= c.width(clock, ts);
                c.text(clock, right, ty, ts, sec < 30 ? 0xFFFF6B5E : 0xFFFFC94D);
                right -= 5 * u;
            }
        }
        String[] tabs = {Component.translatable("onemorecore.screen.tab.orders").getString(), Component.translatable("onemorecore.screen.tab.menu").getString()};
        int[] modes = {Restaurant.SCREEN_ORDERS, Restaurant.SCREEN_MENU};
        float tabsW = 0;
        for (String t : tabs) tabsW += c.width(t, tabS) + 8 * u + 2.5f * u;
        // The tabs only when the title keeps at least a third of the width.
        if (right - tabsW > c.w / 3) {
            for (int i = 0; i < 2; i++) {
                float tw = c.width(tabs[i], tabS) + 8 * u, tx = right - tw, th = 12 * u, tyTab = (hh - 1.2f * u - th) / 2;
                boolean on = mode == modes[i];
                int target = modes[i];
                boolean over = !on && c.hit(tx, tyTab, tw, th, () -> setMode(c.anchor, c.side, target));
                c.box(tx, tyTab, tw, th, 1.5f * u, on ? accent : over ? 0x55FFFFFF : 0x22FFFFFF);
                c.text(tabs[i], tx + 4 * u, tyTab + (th - 7 * tabS) / 2, tabS, on ? 0xFFFFFFFF : 0xFFB8C0CC);
                right = tx - 2.5f * u;
            }
        }
        float x = 6 * u, room = right - x - 4 * u;
        String n = count >= 0 ? String.valueOf(count) : "";
        float pill = count >= 0 ? c.width(n, ts) + 6 * u + 4 * u : 0;
        boolean withPill = count >= 0 && c.width(title, ts) + pill <= room;
        String shown = c.fit(title, withPill ? room - pill : room, ts);
        c.text(shown, x, ty, ts, titleColor);
        if (withPill) {
            float px = x + c.width(shown, ts) + 4 * u, cw = c.width(n, ts) + 6 * u;
            c.box(px, ty - 2.5f * u, cw, 7 * ts + 4 * u, 1.2f * u, count == 0 ? 0xFF3A3D44 : accent);
            c.text(n, px + 3 * u, ty, ts, 0xFFFFFFFF);
        }
    }

    /** Asks the server to show the menu or the orders on one side of a screen. */
    static void setMode(BlockPos anchor, int side, int mode) {
        PacketDistributor.sendToServer(new ScreenSettings.Save(anchor, -1, -1, side, -1, mode));
    }

    private static final int ROW_OVER = 0xFFE3E8EE, ICON_BG = 0xFFE6EAF0;

    /** One block of the menu's flow: a kind's heading (line null) or a dish line. */
    private record Cell(String kind, MenuBoardBlockEntity.Line line) {}

    /**
     * The menu, in the colours of the orders: a header, then the dishes by kind as rows (a big picture, the name, a price tag)
     * in as many columns as fit; a column never ends with a heading, and one that starts in a kind repeats its heading. Scrolled
     * down, the columns share the menu evenly; else they make pages, side by side when scrolled sideways.
     */
    static ItemStack menu(Canvas c, List<MenuBoardBlockEntity.Line> lines) {
        float u = c.u;
        c.rect(0, 0, c.w, c.h, KDS_BG);
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
        int dir = flow(c);
        // Each placed cell: its x, its y and its page (before scrolling).
        List<float[]> placed = new ArrayList<>();
        // Down, the columns share the menu evenly and run on below the picture when it does not fit; else the columns make
        // pages (side by side when sideways).
        boolean down = dir == ScreenSettings.SCROLL_DOWN, sideways = dir == ScreenSettings.SCROLL_SIDEWAYS;
        float limit = bottom;
        if (down) {
            float total = 0;
            for (Cell cell : flow) total += (cell.line() == null ? headingH : rowH) + space;
            limit = top + Math.max(bottom - top, total / cols + headingH + rowH + 2 * space);
        }
        int col = 0, page = 0;
        float fy = top, end = top;
        List<Cell> withRepeats = new ArrayList<>();
        for (int i = 0; i < flow.size(); i++) {
            Cell cell = flow.get(i);
            float need = cell.line() == null ? headingH + rowH + space : rowH;
            if (fy + need > limit && fy > top && !(down && col == cols - 1)) {
                col++;
                fy = top;
                if (col == cols) { col = 0; page++; }
                if (cell.line() != null) {
                    withRepeats.add(new Cell(cell.kind(), null));
                    placed.add(new float[] {gap + col * (colW + gap) + (sideways ? page * (c.w - gap) : 0), fy, sideways ? 0 : page});
                    fy += headingH + space;
                }
            }
            withRepeats.add(cell);
            placed.add(new float[] {gap + col * (colW + gap) + (sideways ? page * (c.w - gap) : 0), fy, sideways ? 0 : page});
            fy += (cell.line() == null ? headingH : rowH) + space;
            end = Math.max(end, fy);
        }
        flow = withRepeats;
        float length = down ? end - top : sideways ? (page + 1) * (c.w - gap) + gap : 0, shown = down ? bottom - top : c.w;
        float[] offsets = offsets(c, length, shown, dir);
        c.clip(0, top, c.w, bottom);
        for (float off : offsets) {
            float dx = dir == ScreenSettings.SCROLL_SIDEWAYS ? off : 0, dy = dir == ScreenSettings.SCROLL_DOWN ? off : 0;
            for (int i = 0; i < flow.size(); i++) {
                float[] at = placed.get(i);
                if ((int) at[2] != 0) continue;
                Cell cell = flow.get(i);
                float x = at[0] - dx, y = at[1] - dy;
                float bh = cell.line() == null ? headingH : rowH;
                if (y + bh <= top || y >= bottom || x + colW <= 0 || x >= c.w) continue;
                int color = KIND_COLORS[java.util.Arrays.asList(KINDS).indexOf(cell.kind())];
                if (cell.line() == null) {
                    String head = Component.translatable("onemorecore.board." + cell.kind()).getString().toUpperCase(java.util.Locale.ROOT);
                    float hs = 0.85f * u;
                    c.rect(x, y + 1 * u, 2.4f * u, headingH - 2 * u, color);
                    c.text(c.fit(head, colW - 6 * u, hs), x + 5 * u, y + (headingH - 7 * hs) / 2, hs, 0xFFFFFFFF);
                    float after = x + 5 * u + Math.min(c.width(head, hs), colW - 6 * u) + 3 * u;
                    if (after < x + colW) c.rect(after, y + headingH / 2, x + colW - after, 0.6f * u, 0x33FFFFFF);
                    continue;
                }
                MenuBoardBlockEntity.Line l = cell.line();
                // The whole row is the pointer's target (the icon alone is small up close): its first dish's tooltip.
                boolean overRow = c.over(x, y, colW, rowH) && !l.items().isEmpty();
                c.rect(x + 1.2f * u, y + 1.5f * u, colW, rowH, 0x55000000);
                c.box(x, y, colW, rowH, 1.2f * u, overRow ? ROW_OVER : PAPER);
                if (overRow && c.hovered.isEmpty()) c.hovered = l.items().get(0);
                c.rect(x, y + 3 * u, 1.2f * u, rowH - 6 * u, color);
                float icon = rowH - 6 * u, iconBox = rowH - 3 * u;
                int n = Math.min(3, l.items().size());
                float picW = iconBox + (n - 1) * icon * 0.55f;
                c.box(x + 2.5f * u, y + 1.5f * u, picW, iconBox, 1.2f * u, ICON_BG);
                for (int j = 0; j < n; j++) c.item(l.items().get(j), x + 2.5f * u + iconBox / 2 + j * icon * 0.55f, y + rowH / 2, icon);
                String price = String.valueOf(l.price());
                float ps = 0.85f * u, pw = c.width(price, ps) + 6 * u, ph = 11 * u;
                c.box(x + colW - pw - 3 * u, y + (rowH - ph) / 2, pw, ph, 1.2f * u, BLUE);
                c.text(price, x + colW - pw, y + (rowH - 7 * ps) / 2, ps, 0xFFFFFFFF);
                String name = l.items().size() == 1 ? l.items().get(0).getHoverName().getString()
                        : String.join(" + ", l.items().stream().map(st -> st.getHoverName().getString()).toList());
                float nx = x + 2.5f * u + picW + 3.5f * u, ns = 0.8f * u;
                c.text(c.fit(name, x + colW - pw - 6 * u - nx, ns), nx, y + (rowH - 7 * ns) / 2, ns, INK);
            }
        }
        c.unclip();
        if (flow.isEmpty()) {
            String none = Component.translatable("onemorecore.board.empty").getString();
            float ns = 0.9f * u;
            c.text(c.fit(none, c.w - 8 * u, ns), Math.max(4 * u, (c.w - c.width(none, ns)) / 2), (headH + c.h) / 2 - 4 * u, ns, 0xFF8A96A6);
        }
        scrollBar(c, dir, dir == ScreenSettings.SCROLL_SIDEWAYS ? 0 : top, shown, length, 0xFFB8C0CC);
        // The header goes over the scrolled rows.
        header(c, Component.translatable("onemorecore.screen.menu").getString(), -1, KDS_HEAD, BLUE, 0xFFFFFFFF);
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
        int dir = flow(c);
        OrderLayout.Layout layout = OrderLayout.layout(c.w, c.h, u, OrderLayout.guests(tickets), dir);
        float[] offsets = offsets(c, layout.length(), layout.shown(), dir);
        float top = layout.areaTop(), bottom = top + layout.areaHeight();
        boolean blink = System.currentTimeMillis() / 400 % 2 == 0;
        Item marked = c.view.marked;
        c.clip(0, top, layout.areaWidth(), bottom);
        for (float off : offsets) {
            float dx = dir == ScreenSettings.SCROLL_SIDEWAYS ? off : 0, dy = dir == ScreenSettings.SCROLL_DOWN ? off : 0;
            for (OrderLayout.Card card : layout.cards()) {
                if (card.page() != 0) continue;
                float x = card.x() - dx, y = card.y() - dy, cw = card.w(), ch = card.h();
                if (y + ch <= top || y >= bottom || x + cw <= 0 || x >= layout.areaWidth()) continue;
                Tickets.Ticket t = tickets.get(card.index());
                float p = Math.max(0, Math.min(1, t.patience()));
                int head = p > 0.5f ? GREEN : p > 0.25f ? AMBER : RED;
                boolean has = marked == null || t.items().stream().anyMatch(s -> s.is(marked));
                final int index = card.index();
                boolean over = t.orderId() != null && c.hit(x, y, cw, ch, () -> PacketDistributor.sendToServer(
                        new ScreenClick(c.anchor, tickets.get(index).orderId(), c.hx, c.hy, c.side, off, dir)));
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
        }
        c.unclip();
        if (tickets.isEmpty()) {
            String none = Component.translatable("onemorecore.screen.no_orders").getString();
            float ns = 0.95f * u;
            c.text(c.fit(none, c.w - 8 * u, ns), Math.max(4 * u, (c.w - c.width(none, ns)) / 2), (top + bottom) / 2 - 4 * u, ns, 0xFF8A96A6);
        }
        // Cards scrolled past the bottom of the area end at its edge.
        c.rect(0, bottom, c.w, c.h - bottom, KDS_BG);
        if (layout.summaryW() > 0) summary(c, tickets, layout);
        scrollBar(c, dir, dir == ScreenSettings.SCROLL_SIDEWAYS ? 0 : top, layout.shown(), layout.length(), 0xFFB8C0CC);
        header(c, Component.translatable("onemorecore.screen.orders").getString(), tickets.size(), KDS_HEAD, BLUE, 0xFFFFFFFF);
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
