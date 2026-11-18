package com.onemorestudios.onemoreclient.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.onemorestudios.onemoreclient.Computer;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The desktop of the Restaurant Computer, drawn on the monitor after the camera flew to it: a wallpaper, app icons and a
 * task bar. An app opens in a window with a close button. Menu: unlock dishes. Shop: a catalog of cards. Manager: the
 * restaurant data and the free camera that sets the area and the points.
 */
public class ComputerScreen extends Screen {
    private static final int[] APPS = {Computer.APP_MENU, Computer.APP_SHOP, Computer.APP_MANAGER};
    private static final String[] KEYS = {"menu", "shop", "manager"};
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA8B0BA, DARK = 0xFF1C2026, BAR = 0xFF2A3038, LINE = 0xFF353C46,
            GREEN = 0xFF5BD46A, RED = 0xFFE8695E, BLUE = 0xFF4C8DF0, BLUE_HOVER = 0xFF6AA3FF, YELLOW = 0xFFFFE27A;
    private static final int CARD_MIN_W = 64, CARD_GAP = 6, CARD_H = 92, ROW_H = 24, TASKBAR = 16, TITLE = 18;

    private Computer.State state;
    /** 0: no window; otherwise one of Computer.APP_*. */
    private int app;
    private double scroll;
    /** Desktop rectangle in GUI pixels: the monitor's screen. */
    private int dx, dy, dw, dh;
    /** Window rectangle and its content area. */
    private int wx, wy, ww, wh, cx, cy, cw, ch;
    private final List<Hit> hits = new ArrayList<>();
    private long messageTime;

    /** A clickable rectangle, rebuilt every frame. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    ComputerScreen(Computer.State state, int app) {
        super(Component.translatable("block.onemoreclient.computer"));
        this.state = state;
        this.app = (state.apps() & app) != 0 ? app : 0;
    }

    void update(Computer.State s) {
        state = s;
        if (!s.message().getString().isEmpty()) messageTime = System.currentTimeMillis();
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("onemoreclient.computer." + key, args);
    }

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "computer/" + name);
    }

    private void send(int action, String id) {
        PacketDistributor.sendToServer(new Computer.Request(state.pos(), action, id));
    }

    private void open(int which) {
        app = which;
        scroll = 0;
        send(Computer.REFRESH, "");
    }

    @Override
    protected void init() {
        // The camera puts the monitor's 4:3 screen in the middle of the view, ComputerCamera.FILL of its height.
        dh = (int) Math.round(height * ComputerCamera.FILL);
        dw = Math.min(width - 8, dh * 4 / 3);
        dx = (width - dw) / 2;
        dy = (height - dh) / 2;
        wx = dx + 52;
        wy = dy + 8;
        ww = dx + dw - 8 - wx;
        wh = dy + dh - TASKBAR - 8 - wy;
        cx = wx + 6;
        cy = wy + TITLE + 4;
        cw = ww - 12;
        ch = wh - TITLE - 8;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The world with the monitor stays visible round the desktop.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hits.clear();
        // Wallpaper: a vertical gradient.
        g.fillGradient(dx, dy, dx + dw, dy + dh, 0xFF1F5A6B, 0xFF0E2733);
        drawIcons(g, mouseX, mouseY);
        drawTaskbar(g);
        if (!state.found()) {
            box(g, text("outside"), mouseX, mouseY);
        } else if (app != 0) {
            drawWindow(g, mouseX, mouseY);
        }
    }

    private void drawIcons(GuiGraphics g, int mouseX, int mouseY) {
        int x = dx + 8, y = dy + 8;
        for (int i = 0; i < APPS.length; i++) {
            int bit = APPS[i];
            boolean on = state.found() && (state.apps() & bit) != 0;
            boolean hover = mouseX >= x && mouseX < x + 36 && mouseY >= y && mouseY < y + 44;
            if (hover && on) g.fill(x, y, x + 36, y + 44, 0x33FFFFFF);
            if (app == bit) g.fill(x, y, x + 36, y + 44, 0x44FFFFFF);
            g.blitSprite(sprite("app_" + KEYS[i]), x + 2, y + 2, 32, 32);
            if (!on) g.fill(x + 2, y + 2, x + 34, y + 34, 0xAA0E2733);
            Component name = text("app." + KEYS[i]);
            g.drawString(font, name, x + 18 - font.width(name) / 2, y + 35, on ? WHITE : GREY, true);
            if (on) hits.add(new Hit(x, y, 36, 44, () -> open(bit)));
            else if (hover && state.found()) g.renderTooltip(font, text("app.disabled"), mouseX, mouseY);
            y += 48;
        }
    }

    private void drawTaskbar(GuiGraphics g) {
        int y = dy + dh - TASKBAR;
        g.fill(dx, y, dx + dw, dy + dh, 0xCC0A0D11);
        g.blitSprite(sprite("logo"), dx + 3, y + 2, 12, 12);
        int x = dx + 20;
        if (app != 0) {
            int i = app == Computer.APP_MENU ? 0 : app == Computer.APP_SHOP ? 1 : 2;
            Component name = text("app." + KEYS[i]);
            g.fill(x, y + 2, x + font.width(name) + 22, y + TASKBAR - 2, 0x33FFFFFF);
            g.blitSprite(sprite("app_" + KEYS[i]), x + 3, y + 3, 10, 10);
            g.drawString(font, name, x + 16, y + 4, WHITE, false);
        }
        // The clock: the time of day of the world.
        long time = minecraft.level == null ? 0 : (minecraft.level.getDayTime() + 6000) % 24000;
        String clock = String.format("%02d:%02d", time / 1000, time % 1000 * 60 / 1000);
        g.drawString(font, clock, dx + dw - 6 - font.width(clock), y + 4, WHITE, false);
    }

    /** A message box in the middle of the desktop. */
    private void box(GuiGraphics g, Component message, int mouseX, int mouseY) {
        int w = Math.min(dw - 40, 220);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(message, w - 16);
        int h = 16 + lines.size() * 10;
        int x = dx + (dw - w) / 2, y = dy + (dh - TASKBAR - h) / 2;
        g.fill(x, y, x + w, y + h, DARK);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 8, y + 8 + i * 10, WHITE, false);
    }

    private void drawWindow(GuiGraphics g, int mouseX, int mouseY) {
        int i = app == Computer.APP_MENU ? 0 : app == Computer.APP_SHOP ? 1 : 2;
        g.fill(wx - 1, wy - 1, wx + ww + 1, wy + wh + 1, 0x66000000);
        g.fill(wx, wy, wx + ww, wy + wh, DARK);
        g.fill(wx, wy, wx + ww, wy + TITLE, BAR);
        g.blitSprite(sprite("app_" + KEYS[i]), wx + 4, wy + 1, 16, 16);
        g.drawString(font, text("title." + KEYS[i]), wx + 24, wy + 5, WHITE, false);
        // Register balance in the title bar.
        String money = String.valueOf(state.balance());
        int mx = wx + ww - 22 - 16 - font.width(money);
        g.pose().pushPose();
        g.pose().translate(mx, wy + 3, 0);
        g.pose().scale(0.75f, 0.75f, 1);
        g.renderItem(state.currency(), 0, 0);
        g.pose().popPose();
        g.drawString(font, money, mx + 14, wy + 5, YELLOW, false);
        // Close button.
        int bx = wx + ww - TITLE;
        boolean overClose = mouseX >= bx && mouseX < wx + ww && mouseY >= wy && mouseY < wy + TITLE;
        if (overClose) g.fill(bx, wy, wx + ww, wy + TITLE, 0xFFD9443B);
        g.drawString(font, "×", bx + (TITLE - font.width("×")) / 2, wy + 5, WHITE, false);
        hits.add(new Hit(bx, wy, TITLE, TITLE, () -> app = 0));

        g.enableScissor(cx, cy, cx + cw, cy + ch);
        ItemStack hovered = switch (app) {
            case Computer.APP_MENU -> drawMenu(g, mouseX, mouseY);
            case Computer.APP_SHOP -> drawShop(g, mouseX, mouseY);
            default -> { drawManager(g, mouseX, mouseY); yield ItemStack.EMPTY; }
        };
        g.disableScissor();
        // The last answer of the server, at the bottom of the window for a few seconds.
        if (System.currentTimeMillis() - messageTime < 4000 && !state.message().getString().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(state.message(), ww - 24);
            int h = 6 + lines.size() * 10, y = wy + wh - h - 6;
            g.fill(wx + 6, y, wx + ww - 6, y + h, 0xF0101418);
            for (int l = 0; l < lines.size(); l++) g.drawString(font, lines.get(l), wx + 12, y + 4 + l * 10, YELLOW, false);
        }
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    /** A flat button. Returns true if the mouse is over it. */
    private boolean button(GuiGraphics g, int x, int y, int w, int h, Component label, boolean active, int mouseX, int mouseY, Runnable action) {
        boolean over = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= cy && mouseY < cy + ch;
        g.fill(x, y, x + w, y + h, !active ? LINE : over ? BLUE_HOVER : BLUE);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, active ? WHITE : GREY, false);
        if (active) hits.add(new Hit(x, Math.max(y, cy), w, Math.min(y + h, cy + ch) - Math.max(y, cy), action));
        return over;
    }

    private void clampScroll(int contentHeight) {
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - ch));
    }

    private ItemStack drawMenu(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.MenuLine> menu = state.menu();
        clampScroll(menu.size() * ROW_H);
        ItemStack hovered = ItemStack.EMPTY;
        if (menu.isEmpty()) g.drawCenteredString(font, text("empty"), cx + cw / 2, cy + 20, GREY);
        for (int i = 0; i < menu.size(); i++) {
            Computer.MenuLine line = menu.get(i);
            int y = cy + i * ROW_H - (int) scroll;
            if (y + ROW_H < cy || y > cy + ch) continue;
            if (i % 2 == 0) g.fill(cx, y, cx + cw, y + ROW_H, 0x10FFFFFF);
            g.renderItem(line.icon(), cx + 4, y + 4);
            String name = line.icon().isEmpty() ? line.id() : line.icon().getHoverName().getString();
            // Name, then the price right-aligned at cw - 82, then the state (text or a 72 px button) from cw - 76.
            int nameW = cw - 26 - 82 - 24;
            g.drawString(font, font.plainSubstrByWidth(name, nameW), cx + 26, y + 8, WHITE, false);
            if (mouseX >= cx && mouseX < cx + 26 + nameW && mouseY >= y && mouseY < y + ROW_H && mouseY >= cy && mouseY < cy + ch) hovered = line.icon();
            String price = String.valueOf(line.price());
            g.drawString(font, price, cx + cw - 82 - font.width(price), y + 8, YELLOW, false);
            int sx = cx + cw - 76;
            switch (line.state()) {
                case Computer.AVAILABLE -> g.drawString(font, text("available"), sx, y + 8, GREEN, false);
                case Computer.LOCKED_DAY -> g.drawString(font, text("locked", line.day()), sx, y + 8, GREY, false);
                default -> button(g, sx, y + 3, 72, ROW_H - 6, text("unlock_cost", line.cost()), state.balance() >= line.cost(), mouseX, mouseY,
                        () -> send(Computer.UNLOCK, line.id()));
            }
        }
        return hovered;
    }

    private ItemStack drawShop(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.ShopLine> shop = state.shop();
        // As many cards as fit in a row, at least CARD_MIN_W wide; the cards share the width, the gaps stay CARD_GAP.
        int columns = Math.max(1, (cw + CARD_GAP) / (CARD_MIN_W + CARD_GAP));
        int cardW = (cw - (columns - 1) * CARD_GAP) / columns, gap = CARD_GAP;
        int rows = (shop.size() + columns - 1) / columns;
        boolean noDelivery = state.delivery().isEmpty();
        int top = noDelivery ? 26 : 0;
        clampScroll(top + rows * (CARD_H + CARD_GAP));
        ItemStack hovered = ItemStack.EMPTY;
        if (noDelivery) {
            int y = cy - (int) scroll;
            g.fill(cx, y, cx + cw, y + 20, 0x40E8695E);
            g.drawString(font, font.plainSubstrByWidth(text("no_delivery_short").getString(), cw - 12), cx + 6, y + 6, WHITE, false);
        }
        if (shop.isEmpty()) g.drawCenteredString(font, text("empty"), cx + cw / 2, cy + top + 20, GREY);
        for (int i = 0; i < shop.size(); i++) {
            Computer.ShopLine line = shop.get(i);
            int x = cx + (i % columns) * (cardW + gap), y = cy + top + (i / columns) * (CARD_H + CARD_GAP) - (int) scroll;
            if (y + CARD_H < cy || y > cy + ch) continue;
            boolean over = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + CARD_H && mouseY >= cy && mouseY < cy + ch;
            g.fill(x, y, x + cardW, y + CARD_H, over ? 0xFF323A45 : 0xFF2A3038);
            // The picture: the item at double size on a lighter field.
            g.fill(x + 4, y + 4, x + cardW - 4, y + 44, 0xFF3A4350);
            g.pose().pushPose();
            g.pose().translate(x + cardW / 2f - 16, y + 8, 0);
            g.pose().scale(2, 2, 1);
            g.renderItem(line.icon(), 0, 0);
            g.renderItemDecorations(font, line.stack(), 0, 0);
            g.pose().popPose();
            String name = line.stack().getHoverName().getString();
            g.drawString(font, font.plainSubstrByWidth(name, cardW - 8), x + 4, y + 48, WHITE, false);
            g.pose().pushPose();
            g.pose().translate(x + 3, y + 58, 0);
            g.pose().scale(0.625f, 0.625f, 1);
            g.renderItem(state.currency(), 0, 0);
            g.pose().popPose();
            g.drawString(font, String.valueOf(line.price()), x + 15, y + 60, YELLOW, false);
            button(g, x + 4, y + CARD_H - 20, cardW - 8, 16, text("buy"), state.balance() >= line.price() && !noDelivery, mouseX, mouseY,
                    () -> send(Computer.BUY, line.id()));
            if (over && mouseY < y + 58) hovered = line.stack();
        }
        return hovered;
    }

    private void drawManager(GuiGraphics g, int mouseX, int mouseY) {
        List<Component> info = state.info();
        int y = cy + 2 - (int) scroll;
        for (Component line : info) {
            g.drawString(font, line, cx + 4, y, WHITE, false);
            y += 11;
        }
        y += 4;
        // The colours of the free camera's boxes.
        int[][] legend = {{0xFF4DE066}, {0xFF4D99FF}, {0xFFFF8C1A}};
        String[] keys = {"free.area", "free.spawn", "free.delivery"};
        for (int i = 0; i < 3; i++) {
            g.fill(cx + 4, y + 1, cx + 10, y + 7, legend[i][0]);
            g.drawString(font, Component.translatable("onemoreclient." + keys[i]), cx + 14, y, GREY, false);
            y += 11;
        }
        y += 6;
        button(g, cx + 4, y, Math.min(cw - 8, 180), 20, text("edit_places"), true, mouseX, mouseY, ComputerCamera::startFree);
        clampScroll(y + 24 - (cy - (int) scroll));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Hit h : List.copyOf(hits)) {
                if (h.contains(mouseX, mouseY)) {
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= scrollY * 16;
        if (scroll < 0) scroll = 0;
        return true;
    }

    /** Escape closes the open window first, then leaves the computer. */
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (app != 0) app = 0;
            else ComputerCamera.leave();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
