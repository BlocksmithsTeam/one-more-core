package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the order tickets stand on a screen's picture, like a kitchen order display: a header, tickets in columns (each ticket
 * goes to the shortest column), and on a wide picture a summary column at the right. The client draws by it and the server checks
 * clicks by it, so it uses no text widths. Sizes are canvas units; u is the layout unit of the picture (ScreenRect.unit).
 * Without scrolling the tickets fill pages; with scrolling (a player at the screen with Diegesis) they make one long page.
 */
public final class OrderLayout {
    private OrderLayout() {}

    public static final float HEADER = 22, GAP = 4, COLUMN = 92, SUMMARY = 92, HEAD = 15, BAR = 2.5f, LINE = 12, GUEST_GAP = 3, FOOT = 12,
            PAD = 3;

    /** A ticket's place: its index in the tickets, the page, and the rectangle (y on its page, before scrolling). */
    public record Card(int index, int page, float x, float y, float w, float h) {
        public boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** The layout: the cards, the number of pages, the content height (for scrolling), the summary column (width 0: none). */
    public record Layout(List<Card> cards, int pages, float contentHeight, float areaTop, float areaHeight, float summaryX, float summaryW) {
        /** The card under a point of the shown page (scroll: canvas units scrolled down), or null. */
        public Card at(int page, float scroll, float x, float y) {
            for (Card c : cards) if (c.page == page && c.contains(x, y + scroll)) return c;
            return null;
        }

        public float maxScroll() {
            return Math.max(0, contentHeight - areaHeight);
        }
    }

    /** The height of a ticket with these guests (each the number of its dishes). */
    public static float height(List<Integer> guests, float u) {
        int lines = 0;
        for (int g : guests) lines += Math.max(1, g);
        return (HEAD + BAR + PAD * 2 + FOOT) * u + lines * LINE * u + Math.max(0, guests.size() - 1) * GUEST_GAP * u;
    }

    public static Layout layout(float w, float h, float u, List<List<Integer>> tickets, boolean scrolling) {
        float top = (HEADER + GAP) * u, bottom = h - GAP * u, areaH = bottom - top;
        float summaryW = w >= 3.2f * (COLUMN + GAP) * u ? SUMMARY * u : 0;
        float contentW = w - summaryW - (summaryW > 0 ? GAP * u : 0);
        int cols = Math.max(1, (int) ((contentW - GAP * u) / ((COLUMN + GAP) * u)));
        float colW = (contentW - GAP * u * (cols + 1)) / cols;
        List<Card> cards = new ArrayList<>();
        float[] colY = new float[cols];
        int page = 0;
        java.util.Arrays.fill(colY, top);
        boolean pageEmpty = true;
        float content = 0;
        for (int i = 0; i < tickets.size(); i++) {
            float ch = height(tickets.get(i), u);
            int c = 0;
            for (int k = 1; k < cols; k++) if (colY[k] < colY[c] - 0.01f) c = k;
            if (!scrolling && !pageEmpty && colY[c] + ch > bottom) {
                page++;
                java.util.Arrays.fill(colY, top);
                c = 0;
            }
            float x = GAP * u + c * (colW + GAP * u);
            cards.add(new Card(i, page, x, colY[c], colW, ch));
            colY[c] += ch + GAP * u;
            content = Math.max(content, colY[c] - top);
            pageEmpty = false;
        }
        return new Layout(cards, page + 1, content, top, areaH, w - summaryW - GAP * u, summaryW);
    }

    /** The guests of each ticket, each the number of its dishes. */
    public static List<List<Integer>> guests(List<Tickets.Ticket> tickets) {
        List<List<Integer>> out = new ArrayList<>();
        for (var t : tickets) out.add(t.rows().stream().map(List::size).toList());
        return out;
    }

    /** The page shown at a game time: a page every 6 seconds. */
    public static int page(int pages, long gameTime) {
        return pages <= 1 ? 0 : (int) (gameTime / 120 % pages);
    }
}
