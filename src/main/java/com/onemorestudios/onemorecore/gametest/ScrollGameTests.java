package com.onemorestudios.onemorecore.gametest;

import java.util.ArrayList;
import java.util.List;

import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OrderLayout;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.ScreenSettings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Screens that scroll by themselves: the setting of each side, and the tickets side by side. */
@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class ScrollGameTests {
    /** Each side keeps its own scroll; saves from before (pages that turned, or the first page only) read as down and off. */
    @GameTest(template = "empty")
    public static void scrollSettingPerSide(GameTestHelper helper) {
        int front = ScreenSettings.pack(Restaurant.SCREEN_MENU, 0, 2, ScreenSettings.SCROLL_SIDEWAYS);
        int back = ScreenSettings.pack(Restaurant.SCREEN_ORDERS, 3, 1, ScreenSettings.SCROLL_OFF);
        int both = ScreenSettings.withFace(ScreenSettings.withFace(0, 0, front), 1, back);
        helper.assertValueEqual(ScreenSettings.face(both, 0), front, "front");
        helper.assertValueEqual(ScreenSettings.face(both, 1), back, "back");
        helper.assertValueEqual(ScreenSettings.valid(both), both, "a valid value stays");
        int sidewaysBack = ScreenSettings.withFace(both, 1, ScreenSettings.pack(Restaurant.SCREEN_ORDERS, 0, 0, ScreenSettings.SCROLL_SIDEWAYS));
        helper.assertValueEqual(ScreenSettings.scroll(ScreenSettings.face(sidewaysBack, 1)), ScreenSettings.SCROLL_SIDEWAYS, "back sideways");
        helper.assertValueEqual(ScreenSettings.scroll(ScreenSettings.face(sidewaysBack, 0)), ScreenSettings.SCROLL_SIDEWAYS, "front kept");
        // Old values: bit 9 clear (the pages turned) is down; set (the first page only) is off.
        helper.assertValueEqual(ScreenSettings.scroll(Restaurant.SCREEN_MENU), ScreenSettings.SCROLL_DOWN, "old turning pages");
        helper.assertValueEqual(ScreenSettings.scroll(Restaurant.SCREEN_MENU | 1 << 9), ScreenSettings.SCROLL_OFF, "old first page");
        helper.succeed();
    }

    /**
     * Sideways the pages of tickets stand in a row: the second page starts a gap after the first, the content is as long as the
     * pages, and a click on the scrolled picture finds the card drawn there.
     */
    @GameTest(template = "empty")
    public static void ticketsSideBySide(GameTestHelper helper) {
        List<List<Integer>> tickets = new ArrayList<>();
        for (int i = 0; i < 12; i++) tickets.add(List.of(2, 1));
        float w = 320, h = 200, u = 1;
        OrderLayout.Layout pages = OrderLayout.layout(w, h, u, tickets, OrderLayout.FIRST_PAGE);
        OrderLayout.Layout row = OrderLayout.layout(w, h, u, tickets, OrderLayout.SIDEWAYS);
        helper.assertTrue(pages.pages() > 1, "the tickets need more than a page: " + pages.pages());
        float step = row.areaWidth() - OrderLayout.GAP * u;
        for (int i = 0; i < tickets.size(); i++) {
            OrderLayout.Card a = pages.cards().get(i), b = row.cards().get(i);
            helper.assertTrue(b.page() == 0 && Math.abs(b.x() - (a.x() + a.page() * step)) < 0.01f && b.y() == a.y(),
                    "card " + i + " is not its page's card moved over: " + a + " / " + b);
        }
        helper.assertTrue(Math.abs(row.contentWidth() - (pages.pages() * step + OrderLayout.GAP * u)) < 0.01f, "content width " + row.contentWidth());
        helper.assertTrue(row.maxScroll() > 0 && row.length() == row.contentWidth(), "sideways it scrolls along its width");
        OrderLayout.Card last = row.cards().getLast();
        float offset = last.x() - 10;
        OrderLayout.Card hit = row.at(offset, 0, 10 + last.w() / 2, last.y() + 5);
        helper.assertTrue(hit != null && hit.index() == last.index(), "the click on the scrolled card found " + hit);
        helper.succeed();
    }
}
