package com.onemorestudios.onemorecore.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Restaurant;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The desktop of the Restaurant Computer, drawn on the monitor after the camera flew to it: a wallpaper, app icons and a
 * task bar. An app opens in a window with a close button.
 * Menu: a searchable catalog of the restaurant's dishes (on and off, unlock, price and removal of added dishes) and a
 * picker that adds any food. Shop: a searchable catalog. Manager: what is set, and the free camera that sets it.
 */
public class ComputerScreen extends Screen {
    private static final int[] APPS = {Computer.APP_MENU, Computer.APP_SHOP, Computer.APP_MANAGER, Computer.APP_UPGRADES};
    private static final String[] KEYS = {"menu", "shop", "manager", "upgrades"};
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFF9AA3AD, WINDOW = 0xFF1B1F25, TITLE_BAR = 0xFF262B33, CARD = 0xFF262C34,
            CARD_HOVER = 0xFF2E3540, PICTURE = 0xFF323A45, OFF = 0xFF3A414B, GREEN = 0xFF3FB55A, GREEN_HOVER = 0xFF52C96C,
            BLUE = 0xFF3D7FE0, BLUE_HOVER = 0xFF5A95F0, RED = 0xFFD9443B, YELLOW = 0xFFFFD866;
    private static final int CARD_MIN_W = 66, GAP = 5, CARD_H = 70, TASKBAR = 16, TITLE = 18, SEARCH = 16;

    private static final ResourceLocation WALLPAPER = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/gui/wallpaper.png");
    private Computer.State state;
    /** 0: no window; otherwise one of Computer.APP_*. */
    private int app;
    /** In the menu app: the picker that adds a food. */
    private boolean adding;
    /** In the menu app: "all", a kind of dish, or "combos". */
    private String filter = "all";
    /** In the combos tab: the places of a new combo (dish ids or "kind:<kind>"), while it is made. */
    private List<String> building;
    /** The price of the combo being made; -1 follows the suggested price until the player changes it. */
    private int comboPrice = -1;
    /** The shop's cart: entry id and count, in the order added. */
    private final java.util.LinkedHashMap<String, Integer> cart = new java.util.LinkedHashMap<>();
    private static long nextCheckoutId;
    private long pendingCheckout;
    /** The shop shows the cart instead of the catalog. */
    private boolean cartOpen;
    private static final int SIDEBAR = 62, ROW = 22, COMBO_PLACES = 4;
    private static final String[] FILTERS = {"all", "main", "drink", "starter", "dessert", "combos"};
    private static final int CHIPS = 13;
    private double scroll;
    private int dx, dy, dw, dh, wx, wy, ww, wh, cx, cy, cw, ch;
    private final List<Hit> hits = new ArrayList<>();
    private Component message = Component.empty();
    private long messageTime;
    private EditBox search;
    /** Every food and drink item of the game, for the picker. */
    private List<ItemStack> foods;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    ComputerScreen(Computer.State state, int app) {
        super(Component.translatable("block.onemorecore.computer"));
        this.state = state;
        this.app = (state.apps() & app) != 0 ? app : 0;
    }

    void update(Computer.State s) {
        state = s;
        hits.clear();
        if (!s.message().getString().isEmpty()) showMessage(s.message());
    }

    boolean checkoutResult(Computer.CheckoutResult result) {
        if (pendingCheckout == 0 || result.checkoutId() != pendingCheckout) return false;
        pendingCheckout = 0;
        result.state().ifPresent(this::update);
        showMessage(result.message());
        hits.clear();
        if (result.success()) {
            cart.clear();
            cartOpen = false;
            scroll = 0;
        }
        return true;
    }

    private void showMessage(Component message) {
        this.message = message;
        messageTime = System.currentTimeMillis();
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("onemorecore.computer." + key, args);
    }

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "computer/" + name);
    }

    private void send(int action, String id) {
        PacketDistributor.sendToServer(new Computer.Request(state.pos(), action, id));
    }

    private void open(int which) {
        app = which;
        adding = false;
        building = null;
        cartOpen = false;
        filter = "all";
        scroll = 0;
        if (search != null) search.setValue("");
        send(which == Computer.APP_MANAGER ? Computer.REFRESH_MANAGER : Computer.REFRESH, "");
    }

    @Override
    protected void init() {
        hits.clear();
        // The camera puts the monitor's 4:3 screen in the middle of the view, ComputerCamera.FILL of its height.
        dh = (int) Math.round(height * ComputerCamera.FILL);
        dw = Math.min(width - 8, dh * 4 / 3);
        dx = (width - dw) / 2;
        dy = (height - dh) / 2;
        // A window covers the desktop but the task bar, so that its lists have room; the task bar shows the open app.
        wx = dx + 6;
        wy = dy + 6;
        ww = dx + dw - 6 - wx;
        wh = dy + dh - TASKBAR - 6 - wy;
        String old = search == null ? "" : search.getValue();
        search = new EditBox(font, wx + 22, wy + TITLE + 8, ww - 30, 10, Component.empty());
        search.setBordered(false);
        search.setMaxLength(40);
        search.setValue(old);
        search.setHint(Component.translatable("onemorecore.computer.search").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        search.setResponder(s -> { scroll = 0; hits.clear(); });
        addWidget(search);
    }

    private boolean hasSearch() {
        return app == Computer.APP_MENU || app == Computer.APP_SHOP;
    }

    /** True if the item's name contains every word of the search text. */
    private boolean matches(ItemStack stack) {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String word : q.split("\\s+")) if (!name.contains(word)) return false;
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The world with the monitor stays visible round the desktop.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hits.clear();
        // The wallpaper: an evening diner, pixel art (art/wallpaper.py).
        g.blit(WALLPAPER, dx, dy, dw, dh, 0, 0, 160, 120, 160, 120);
        if (app == 0) drawIcons(g, mouseX, mouseY);
        drawTaskbar(g);
        if (!state.found()) {
            box(g, text("outside"));
        } else if (app != 0) {
            drawWindow(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawIcons(GuiGraphics g, int mouseX, int mouseY) {
        int x = dx + 5, y = dy + 6;
        for (int i = 0; i < APPS.length; i++) {
            int bit = APPS[i];
            boolean on = state.found() && (state.apps() & bit) != 0;
            boolean hover = on && mouseX >= x && mouseX < x + 36 && mouseY >= y && mouseY < y + 44;
            if (hover || app == bit) rounded(g, x, y, 36, 44, app == bit ? 0x40FFFFFF : 0x26FFFFFF);
            g.blitSprite(sprite("app_" + KEYS[i]), x + 2, y + 2, 32, 32);
            if (!on) rounded(g, x + 2, y + 2, 32, 32, 0xB00D2430);
            Component name = text("app." + KEYS[i]);
            g.drawString(font, name, x + 18 - font.width(name) / 2, y + 35, on ? WHITE : GREY, true);
            if (on) hits.add(new Hit(x, y, 36, 44, () -> open(bit)));
            y += 48;
        }
    }

    private void drawTaskbar(GuiGraphics g) {
        int y = dy + dh - TASKBAR;
        g.fill(dx, y, dx + dw, dy + dh, 0xD90A0D11);
        g.blitSprite(sprite("logo"), dx + 3, y + 2, 12, 12);
        if (app != 0) {
            int i = index(app), x = dx + 20;
            Component name = text("app." + KEYS[i]);
            rounded(g, x, y + 2, font.width(name) + 20, TASKBAR - 4, 0x33FFFFFF);
            g.blitSprite(sprite("app_" + KEYS[i]), x + 3, y + 3, 10, 10);
            g.drawString(font, name, x + 16, y + 4, WHITE, false);
        }
        long time = minecraft.level == null ? 0 : (minecraft.level.getDayTime() + 6000) % 24000;
        String clock = String.format("%02d:%02d", time / 1000, time % 1000 * 60 / 1000);
        g.drawString(font, clock, dx + dw - 6 - font.width(clock), y + 4, WHITE, false);
    }

    private static int index(int app) {
        return app == Computer.APP_MENU ? 0 : app == Computer.APP_SHOP ? 1 : app == Computer.APP_MANAGER ? 2 : 3;
    }

    /** A rectangle with its corners cut by one pixel. */
    private static void rounded(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private void box(GuiGraphics g, Component message) {
        int w = Math.min(dw - 40, 200);
        var lines = font.split(message, w - 16);
        int h = 16 + lines.size() * 10, x = dx + (dw - w) / 2, y = dy + (dh - TASKBAR - h) / 2;
        rounded(g, x, y, w, h, WINDOW);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 8, y + 8 + i * 10, WHITE, false);
    }

    private void drawWindow(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int i = index(app);
        rounded(g, wx - 1, wy - 1, ww + 2, wh + 2, 0x55000000);
        rounded(g, wx, wy, ww, wh, WINDOW);
        g.fill(wx + 1, wy, wx + ww - 1, wy + TITLE, TITLE_BAR);
        g.blitSprite(sprite("app_" + KEYS[i]), wx + 4, wy + 2, 14, 14);
        Component title = adding ? text("title.add") : building != null ? text("title.combo") : text("title." + KEYS[i]);
        g.drawString(font, title, wx + 22, wy + 5, WHITE, false);
        // The register, at the right of the title bar.
        String money = String.valueOf(state.balance());
        int mx = wx + ww - TITLE - 8 - font.width(money);
        g.drawString(font, money, mx, wy + 5, YELLOW, false);
        g.pose().pushPose();
        g.pose().translate(mx - 12, wy + 4, 0);
        g.pose().scale(0.625f, 0.625f, 1);
        g.renderItem(state.currency(), 0, 0);
        g.pose().popPose();
        int bx = wx + ww - TITLE;
        boolean overClose = mouseX >= bx && mouseX < wx + ww && mouseY >= wy && mouseY < wy + TITLE;
        if (overClose) g.fill(bx, wy, wx + ww - 1, wy + TITLE, RED);
        g.drawString(font, "×", bx + (TITLE - font.width("×")) / 2 + 1, wy + 5, WHITE, false);
        hits.add(new Hit(bx, wy, TITLE, TITLE, this::back));

        cx = wx + 6;
        boolean chips = app == Computer.APP_MENU && !adding && building == null;
        cy = wy + TITLE + 5 + (hasSearch() ? SEARCH + 5 : 0);
        cw = ww - 12;
        ch = wy + wh - 6 - cy;
        if (hasSearch()) {
            int sy = wy + TITLE + 5;
            rounded(g, cx, sy, cw, SEARCH, 0xFF11151A);
            g.blitSprite(sprite("search"), cx + 4, sy + 4, 8, 8);
            search.setX(cx + 16);
            search.setY(sy + 4);
            search.setWidth(cw - 20);
            search.render(g, mouseX, mouseY, partialTick);
        }
        if (chips) {
            // A side bar like the category list of a restaurant game's menu editor: all, the four kinds, the combos,
            // each with its count.
            int y = cy;
            for (String f : FILTERS) {
                Component label = text("kind." + f);
                long n = f.equals("combos") ? state.combos().size() : state.menu().stream().filter(l -> f.equals("all") || f.equals(l.kind())).count();
                boolean on = filter.equals(f), over = mouseX >= cx && mouseX < cx + SIDEBAR && mouseY >= y && mouseY < y + 16;
                rounded(g, cx, y, SIDEBAR, 16, on ? BLUE : over ? CARD_HOVER : 0);
                g.drawString(font, font.plainSubstrByWidth(label.getString(), SIDEBAR - 22), cx + 5, y + 4, on ? WHITE : GREY, false);
                String count = String.valueOf(n);
                g.drawString(font, count, cx + SIDEBAR - 5 - font.width(count), y + 4, on ? WHITE : 0xFF6B7480, false);
                hits.add(new Hit(cx, y, SIDEBAR, 16, () -> { filter = f; scroll = 0; }));
                y += 18;
            }
            g.fill(cx + SIDEBAR + 3, cy, cx + SIDEBAR + 4, cy + ch, 0xFF2A3038);
            cx += SIDEBAR + 8;
            cw -= SIDEBAR + 8;
        }
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        ItemStack hovered = switch (app) {
            case Computer.APP_MENU -> adding ? drawPicker(g, mouseX, mouseY) : building != null ? drawBuilder(g, mouseX, mouseY)
                    : filter.equals("combos") ? drawCombos(g, mouseX, mouseY) : drawMenu(g, mouseX, mouseY);
            case Computer.APP_SHOP -> drawShop(g, mouseX, mouseY);
            case Computer.APP_UPGRADES -> { drawUpgrades(g, mouseX, mouseY); yield ItemStack.EMPTY; }
            default -> { drawManager(g, mouseX, mouseY); yield ItemStack.EMPTY; }
        };
        g.disableScissor();
        if (System.currentTimeMillis() - messageTime < 3500 && !message.getString().isEmpty()) {
            var lines = font.split(message, ww - 28);
            int h = 8 + lines.size() * 10, y = wy + wh - h - 6;
            rounded(g, wx + 8, y, ww - 16, h, 0xF00E1115);
            for (int l = 0; l < lines.size(); l++) g.drawString(font, lines.get(l), wx + 14, y + 5 + l * 10, YELLOW, false);
        }
        // A short tooltip: only the name. The item's long tooltip of other mods is not needed here.
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered.getHoverName(), mouseX, mouseY);
    }

    private boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= cy && mouseY < cy + ch;
    }

    /** A flat button; only the part inside the content area can be clicked. */
    private void button(GuiGraphics g, int x, int y, int w, int h, Component label, int color, int hover, boolean active, int mouseX, int mouseY, Runnable action) {
        boolean over = active && inside(mouseX, mouseY, x, y, w, h);
        rounded(g, x, y, w, h, !active ? OFF : over ? hover : color);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, active ? WHITE : GREY, false);
        if (active) clipHit(x, y, w, h, action);
    }

    private void clipHit(int x, int y, int w, int h, Runnable action) {
        int top = Math.max(y, cy), bottom = Math.min(y + h, cy + ch);
        if (bottom > top) hits.add(new Hit(x, top, w, bottom - top, action));
    }

    /** The grid of cards: columns and card width for the content width. */
    private int columns() {
        return Math.max(1, (cw + GAP) / (CARD_MIN_W + GAP));
    }

    private int cardWidth(int columns) {
        return (cw - (columns - 1) * GAP) / columns;
    }

    /** One card: a picture with the item at double size, the name, and space below for the caller. Returns true if hovered. */
    private boolean card(GuiGraphics g, int x, int y, int w, ItemStack picture, ItemStack decorations, int mouseX, int mouseY) {
        boolean over = inside(mouseX, mouseY, x, y, w, CARD_H);
        rounded(g, x, y, w, CARD_H, over ? CARD_HOVER : CARD);
        rounded(g, x + 3, y + 3, w - 6, 38, PICTURE);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - 16, y + 6, 0);
        g.pose().scale(2, 2, 1);
        g.renderItem(picture, 0, 0);
        if (!decorations.isEmpty()) g.renderItemDecorations(font, decorations, 0, 0);
        g.pose().popPose();
        String name = picture.getHoverName().getString();
        g.drawString(font, font.plainSubstrByWidth(name, w - 8), x + 4, y + 45, WHITE, false);
        return over;
    }

    private void price(GuiGraphics g, int x, int y, int price) {
        g.pose().pushPose();
        g.pose().translate(x, y - 1, 0);
        g.pose().scale(0.625f, 0.625f, 1);
        g.renderItem(state.currency(), 0, 0);
        g.pose().popPose();
        g.drawString(font, String.valueOf(price), x + 12, y, YELLOW, false);
    }

    /**
     * The menu as rows, like the menu editor of a restaurant game: icon, name and kind (a click on the kind changes it),
     * then the price (with - and + for a dish the players added), then a switch, the unlock day or the unlock price. The
     * last row adds a dish.
     */
    private ItemStack drawMenu(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.MenuLine> lines = state.menu().stream().filter(l -> matches(l.icon()) && (filter.equals("all") || filter.equals(l.kind()))).toList();
        int count = lines.size() + 1;
        scroll = Mth.clamp(scroll, 0, Math.max(0, count * (ROW + 2) - 2 - ch));
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < count; i++) {
            int x = cx, y = cy + i * (ROW + 2) - (int) scroll, w = cw;
            if (y + ROW < cy || y > cy + ch) continue;
            boolean over = inside(mouseX, mouseY, x, y, w, ROW);
            if (i == lines.size()) {
                rounded(g, x, y, w, ROW, over ? CARD_HOVER : 0xFF20252C);
                g.blitSprite(sprite("add"), x + 5, y + 5, 12, 12);
                g.drawString(font, text("add"), x + 24, y + 7, over ? WHITE : GREY, false);
                clipHit(x, y, w, ROW, () -> { adding = true; scroll = 0; search.setValue(""); });
                continue;
            }
            Computer.MenuLine line = lines.get(i);
            rounded(g, x, y, w, ROW, over ? CARD_HOVER : CARD);
            g.renderItem(line.icon(), x + 3, y + 3);
            if (inside(mouseX, mouseY, x + 3, y + 3, 16, 16)) hovered = line.icon();
            // Right side, from the right edge: state widget, then the price.
            int right = x + w - 5;
            switch (line.state()) {
                case Computer.AVAILABLE -> { toggle(g, right - 18, y + 6, line.on(), mouseX, mouseY, () -> send(Computer.TOGGLE_DISH, line.id())); right -= 24; }
                case Computer.LOCKED_DAY -> {
                    Component day = text("locked", line.day());
                    g.drawString(font, day, right - font.width(day), y + 7, GREY, false);
                    right -= font.width(day) + 6;
                }
                default -> {
                    Component unlock = text("unlock_cost", line.cost());
                    int uw = font.width(unlock) + 8;
                    button(g, right - uw, y + 4, uw, 14, unlock, BLUE, BLUE_HOVER, state.balance() >= line.cost(), mouseX, mouseY, () -> send(Computer.UNLOCK, line.id()));
                    right -= uw + 6;
                }
            }
            if (line.custom()) {
                int pw = font.width(String.valueOf(line.price())) + 12;
                button(g, right - 11, y + 5, 11, 12, Component.literal("+"), OFF, CARD_HOVER, true, mouseX, mouseY,
                        () -> send(Computer.SET_PRICE, line.id() + "," + (line.price() + step())));
                price(g, right - 13 - pw, y + 7, line.price());
                button(g, right - 27 - pw, y + 5, 11, 12, Component.literal("-"), OFF, CARD_HOVER, line.price() > 1, mouseX, mouseY,
                        () -> send(Computer.SET_PRICE, line.id() + "," + Math.max(1, line.price() - step())));
                right -= 31 + pw;
                if (over) button(g, x + w - 12, y + 1, 9, 9, Component.literal("\u00d7"), 0xAA000000, RED, true, mouseX, mouseY, () -> send(Computer.REMOVE_DISH, line.id()));
            } else {
                int pw = font.width(String.valueOf(line.price())) + 12;
                price(g, right - pw, y + 7, line.price());
                right -= pw + 6;
            }
            int nameW = right - (x + 24) - 4;
            g.drawString(font, font.plainSubstrByWidth(line.icon().getHoverName().getString(), nameW), x + 24, y + 3, line.on() || line.state() != Computer.AVAILABLE ? WHITE : GREY, false);
            Component kind = text("kind." + line.kind());
            boolean overKind = inside(mouseX, mouseY, x + 24, y + 12, font.width(kind), 9);
            g.drawString(font, kind, x + 24, y + 12, overKind ? BLUE_HOVER : 0xFF6B7480, false);
            clipHit(x + 24, y + 12, font.width(kind), 9, () -> send(Computer.SET_KIND, line.id() + "," + nextKind(line.kind())));
        }
        return hovered;
    }

    /** A switch: green with the knob at the right when on, grey with the knob at the left when off. */
    private void toggle(GuiGraphics g, int x, int y, boolean on, int mouseX, int mouseY, Runnable action) {
        boolean over = inside(mouseX, mouseY, x, y, 18, 10);
        rounded(g, x, y, 18, 10, on ? (over ? GREEN_HOVER : GREEN) : (over ? CARD_HOVER : OFF));
        int kx = on ? x + 10 : x + 2;
        rounded(g, kx, y + 2, 6, 6, WHITE);
        clipHit(x, y, 18, 10, action);
    }

    private static String nextKind(String kind) {
        int i = java.util.Arrays.asList(FILTERS).indexOf(kind);
        return FILTERS[i < 1 || i >= 4 ? 1 : i + 1];
    }

    /** Closes the picker or the combo maker first, then the window. */
    private void back() {
        hits.clear();
        if (adding) adding = false;
        else if (building != null) building = null;
        else app = 0;
    }

    private Computer.MenuLine line(String id) {
        for (Computer.MenuLine l : state.menu()) if (l.id().equals(id)) return l;
        return null;
    }

    /** The icon of a combo place: the dish, or for a kind the first dish of that kind on the menu. */
    private ItemStack slotIcon(String slot) {
        String kind = slot.startsWith("kind:") ? slot.substring(5) : null;
        if (kind == null) {
            Computer.MenuLine l = line(slot);
            return l == null ? ItemStack.EMPTY : l.icon();
        }
        return state.menu().stream().filter(l -> l.kind().equals(kind)).map(Computer.MenuLine::icon).findFirst().orElse(ItemStack.EMPTY);
    }

    /** The name of a combo place: the dish's name, or "Any drink". */
    private Component slotName(String slot) {
        if (slot.startsWith("kind:")) return text("any." + slot.substring(5));
        return slotIcon(slot).getHoverName();
    }

    /** The usual price of a place: the dish's price, or the mean price of its kind. */
    private int slotPrice(String slot) {
        if (slot.startsWith("kind:")) {
            String kind = slot.substring(5);
            return (int) Math.round(state.menu().stream().filter(l -> l.kind().equals(kind)).mapToInt(Computer.MenuLine::price).average().orElse(1));
        }
        Computer.MenuLine l = line(slot);
        return l == null ? 0 : l.price();
    }

    /** One place as a chip: an icon with a "?" for a kind. Returns the width. */
    private int slotChip(GuiGraphics g, String slot, int x, int y) {
        g.renderItem(slotIcon(slot), x, y);
        if (slot.startsWith("kind:")) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            g.drawString(font, "?", x + 11, y + 9, YELLOW, true);
            g.pose().popPose();
        }
        return 18;
    }

    /** The combos as rows: their places joined by "+", the price (- and +), a cross that removes one, and a row that makes a new one. */
    private ItemStack drawCombos(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.ComboLine> combos = state.combos();
        int h = ROW + 2, count = combos.size() + 1;
        scroll = Mth.clamp(scroll, 0, Math.max(0, count * (h + 2) - 2 - ch));
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < count; i++) {
            int x = cx, y = cy + i * (h + 2) - (int) scroll, w = cw;
            if (y + h < cy || y > cy + ch) continue;
            boolean over = inside(mouseX, mouseY, x, y, w, h);
            if (i == combos.size()) {
                rounded(g, x, y, w, h, over ? CARD_HOVER : 0xFF20252C);
                g.blitSprite(sprite("add"), x + 5, y + 6, 12, 12);
                g.drawString(font, text("combo_new"), x + 24, y + 8, over ? WHITE : GREY, false);
                clipHit(x, y, w, h, () -> { building = new ArrayList<>(); comboPrice = -1; scroll = 0; search.setValue(""); });
                continue;
            }
            Computer.ComboLine c = combos.get(i);
            rounded(g, x, y, w, h, over ? CARD_HOVER : CARD);
            int ix = x + 4;
            for (int k = 0; k < c.dishes().size(); k++) {
                if (k > 0) { g.drawString(font, "+", ix, y + 8, GREY, false); ix += 8; }
                if (inside(mouseX, mouseY, ix, y + 4, 16, 16)) hovered = slotIcon(c.dishes().get(k));
                ix += slotChip(g, c.dishes().get(k), ix, y + 4);
            }
            int pw = font.width(String.valueOf(c.price())) + 12, right = x + w - 5;
            button(g, right - 11, y + 6, 11, 12, Component.literal("+"), OFF, CARD_HOVER, true, mouseX, mouseY,
                    () -> send(Computer.SET_COMBO_PRICE, c.id() + "," + (c.price() + step())));
            price(g, right - 13 - pw, y + 8, c.price());
            button(g, right - 27 - pw, y + 6, 11, 12, Component.literal("-"), OFF, CARD_HOVER, c.price() > 1, mouseX, mouseY,
                    () -> send(Computer.SET_COMBO_PRICE, c.id() + "," + Math.max(1, c.price() - step())));
            if (over) button(g, x + w - 12, y + 1, 9, 9, Component.literal("\u00d7"), 0xAA000000, RED, true, mouseX, mouseY, () -> send(Computer.REMOVE_COMBO, c.id()));
            // A combo that cannot be ordered now (a dish off the menu) is dimmed.
            if (!c.orderable()) rounded(g, x, y, w, h, 0x88151A20);
        }
        return hovered;
    }

    /**
     * The combo maker. Left: the combo, place by place ("Burger + Any drink + Any starter"), each with a cross, its price
     * with - and +, and Create. Right: what the next place can be: a whole kind, or one dish of the menu.
     */
    private ItemStack drawBuilder(GuiGraphics g, int mouseX, int mouseY) {
        List<String> builder = building;
        int lw = Math.max(110, cw * 2 / 5), rx = cx + lw + 6, rw = cw - lw - 6;
        ItemStack hovered = ItemStack.EMPTY;
        // Left pane.
        rounded(g, cx, cy, lw, ch, 0xFF20252C);
        int y = cy + 4;
        for (int k = 0; k < building.size(); k++) {
            String slot = building.get(k);
            boolean over = inside(mouseX, mouseY, cx + 3, y, lw - 6, 20);
            rounded(g, cx + 3, y, lw - 6, 20, over ? CARD_HOVER : CARD);
            slotChip(g, slot, cx + 6, y + 2);
            if (inside(mouseX, mouseY, cx + 6, y + 2, 16, 16)) hovered = slotIcon(slot);
            g.drawString(font, font.plainSubstrByWidth(slotName(slot).getString(), lw - 46), cx + 26, y + 6, WHITE, false);
            int index = k;
            button(g, cx + lw - 16, y + 5, 10, 10, Component.literal("\u00d7"), 0xAA000000, RED, true, mouseX, mouseY, () -> {
                if (building == builder && index < builder.size() && slot.equals(builder.get(index))) builder.remove(index);
            });
            y += 22;
            if (k < building.size() - 1) { g.drawCenteredString(font, "+", cx + lw / 2, y - 1, GREY); y += 8; }
        }
        // The hint, when it fits above the price row.
        if (building.size() < COMBO_PLACES && y + 16 <= cy + ch - 38) {
            g.drawString(font, text(building.isEmpty() ? "combo_first" : "combo_next"), cx + 6, y + 4, GREY, false);
            y += 16;
        }
        int sum = building.stream().mapToInt(this::slotPrice).sum();
        int suggested = Math.max(1, (int) Math.round(sum * 0.9));
        int p = comboPrice < 0 ? suggested : comboPrice;
        // The price row under the places, and the Create button at the bottom.
        int by = Math.min(y + 2, cy + ch - 38);
        if (building.size() >= 2) {
            g.drawString(font, text("combo_price"), cx + 6, by + 3, GREY, false);
            int pw = font.width(String.valueOf(p)) + 12;
            button(g, cx + lw - 17, by, 11, 12, Component.literal("+"), OFF, CARD_HOVER, true, mouseX, mouseY, () -> {
                if (building == builder && builder.size() >= 2) comboPrice = p + step();
            });
            price(g, cx + lw - 19 - pw, by + 2, p);
            button(g, cx + lw - 33 - pw, by, 11, 12, Component.literal("-"), OFF, CARD_HOVER, p > 1, mouseX, mouseY, () -> {
                if (building == builder && builder.size() >= 2) comboPrice = Math.max(1, p - step());
            });
        }
        button(g, cx + 3, cy + ch - 20, lw - 6, 16, text("combo_create"), GREEN, GREEN_HOVER, building.size() >= 2, mouseX, mouseY, () -> {
            if (building != builder || builder.size() < 2) return;
            send(Computer.ADD_COMBO, String.join(";", builder) + "," + p);
            building = null;
            filter = "combos";
        });
        // Right pane: kinds, then dishes.
        boolean full = building.size() >= COMBO_PLACES;
        int kx = rx, ky = cy;
        g.drawString(font, text("combo_kinds"), rx, ky, GREY, false);
        ky += 11;
        for (String kind : new String[] {"main", "drink", "starter", "dessert"}) {
            Component label = text("any." + kind);
            int w = font.width(label) + 10;
            if (kx + w > rx + rw) { kx = rx; ky += 16; }
            String slot = "kind:" + kind;
            button(g, kx, ky, w, 14, label, CARD, CARD_HOVER, !full && state.menu().stream().anyMatch(l -> l.kind().equals(kind)), mouseX, mouseY, () -> {
                if (building == builder && builder.size() < COMBO_PLACES) builder.add(slot);
            });
            kx += w + 3;
        }
        ky += 20;
        g.drawString(font, text("combo_dishes_title"), rx, ky, GREY, false);
        ky += 11;
        List<Computer.MenuLine> lines = state.menu().stream().filter(l -> l.on() && l.state() == Computer.AVAILABLE && matches(l.icon())).toList();
        int size = 22, columns = Math.max(1, (rw + 3) / (size + 3));
        int top = ky - cy;
        scroll = Mth.clamp(scroll, 0, Math.max(0, top + ((lines.size() + columns - 1) / columns) * (size + 3) - ch));
        for (int i = 0; i < lines.size(); i++) {
            Computer.MenuLine l = lines.get(i);
            int x = rx + (i % columns) * (size + 3), yy = ky + (i / columns) * (size + 3) - (int) scroll;
            if (yy + size < ky || yy > cy + ch) continue;
            boolean picked = building.contains(l.id()), over = inside(mouseX, mouseY, x, yy, size, size);
            rounded(g, x, yy, size, size, picked ? 0xFF1F3A27 : over ? CARD_HOVER : CARD);
            g.renderItem(l.icon(), x + 3, yy + 3);
            if (over) hovered = l.icon();
            if (!picked && !full) clipHit(x, Math.max(yy, ky), size, size, () -> {
                if (building == builder && builder.size() < COMBO_PLACES && !builder.contains(l.id())) builder.add(l.id());
            });
        }
        return hovered;
    }

    private static int step() {
        return Screen.hasShiftDown() ? 10 : 1;
    }

    /** A suggested price for a food: its nutrition plus its saturation, at least 1. */
    private static int suggested(ItemStack stack) {
        var food = stack.get(DataComponents.FOOD);
        return food == null ? 2 : Math.max(1, food.nutrition() + Math.round(food.saturation()));
    }

    private ItemStack drawPicker(GuiGraphics g, int mouseX, int mouseY) {
        if (foods == null) foods = BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(Restaurant::isFood).toList();
        List<String> have = state.menu().stream().map(l -> BuiltInRegistries.ITEM.getKey(l.icon().getItem()).toString()).toList();
        List<ItemStack> list = foods.stream().filter(this::matches).toList();
        int columns = Math.max(1, (cw + 3) / (28 + 3)), size = (cw - (columns - 1) * 3) / columns;
        scroll = Mth.clamp(scroll, 0, Math.max(0, ((list.size() + columns - 1) / columns) * (size + 3) - 3 - ch));
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = list.get(i);
            int x = cx + (i % columns) * (size + 3), y = cy + (i / columns) * (size + 3) - (int) scroll;
            if (y + size < cy || y > cy + ch) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean added = have.contains(id);
            boolean over = inside(mouseX, mouseY, x, y, size, size);
            rounded(g, x, y, size, size, added ? 0xFF1F3A27 : over ? CARD_HOVER : CARD);
            g.renderItem(stack, x + (size - 16) / 2, y + (size - 16) / 2);
            if (over) hovered = stack;
            if (!added) clipHit(x, y, size, size, () -> { send(Computer.ADD_DISH, id + "," + suggested(stack)); adding = false; scroll = 0; search.setValue(""); });
        }
        if (list.isEmpty()) g.drawCenteredString(font, text("empty"), cx + cw / 2, cy + 20, GREY);
        return hovered;
    }

    private Computer.ShopLine shopLine(String id) {
        for (Computer.ShopLine l : state.shop()) if (l.id().equals(id)) return l;
        return null;
    }

    private int cartCount() {
        return cart.values().stream().mapToInt(Integer::intValue).sum();
    }

    private long cartTotal() {
        long total = 0;
        for (var e : cart.entrySet()) {
            Computer.ShopLine l = shopLine(e.getKey());
            if (l != null) total += (long) l.price() * e.getValue();
        }
        return total;
    }

    /**
     * The shop: a catalog whose cards go into a cart ("Add"; the count shows on the card), and a cart bar at the bottom
     * with the count, the total and the button that opens the cart. A total over the register is red, with how much is missing.
     */
    private ItemStack drawShop(GuiGraphics g, int mouseX, int mouseY) {
        int bar = 22;
        int saved = ch;
        ch -= bar + 4;
        ItemStack hovered = cartOpen ? drawCart(g, mouseX, mouseY) : drawCatalog(g, mouseX, mouseY);
        ch = saved;
        // The cart bar.
        int y = cy + ch - bar;
        long total = cartTotal();
        boolean enough = total <= state.balance();
        rounded(g, cx, y, cw, bar, 0xFF20252C);
        g.blitSprite(sprite("app_shop"), cx + 4, y + 3, 16, 16);
        int n = cartCount();
        g.drawString(font, text("cart_count", n), cx + 24, y + 3, WHITE, false);
        if (n > 0) {
            price(g, cx + 24, y + 13, (int) Math.min(Integer.MAX_VALUE, total));
            if (!enough) g.drawString(font, text("cart_missing", total - state.balance()), cx + 40 + font.width(String.valueOf(total)), y + 13, RED, false);
        }
        Component action = cartOpen ? text("cart_back") : text("cart_open");
        int aw = font.width(action) + 12;
        if (cartOpen) {
            Component checkout = text("checkout");
            int kw = font.width(checkout) + 12;
            StringBuilder encoded = new StringBuilder();
            cart.forEach((id, count) -> encoded.append(encoded.isEmpty() ? "" : ";").append(id).append(':').append(count));
            boolean withinLimits = cart.size() <= Computer.MAX_CART_LINES && encoded.length() <= Computer.MAX_REQUEST_LENGTH;
            boolean available = cart.keySet().stream().allMatch(id -> id.indexOf(';') < 0 && shopLine(id) != null);
            boolean can = n > 0 && enough && state.delivery().isPresent() && withinLimits && available && pendingCheckout == 0;
            button(g, cx + cw - 4 - kw, y + 4, kw, 14, checkout, GREEN, GREEN_HOVER, can, mouseX, mouseY, () -> {
                pendingCheckout = ++nextCheckoutId;
                PacketDistributor.sendToServer(new Computer.Request(state.pos(), Computer.BUY_CART, encoded.toString(), total, pendingCheckout));
            });
            button(g, cx + cw - 8 - kw - aw, y + 4, aw, 14, action, OFF, CARD_HOVER, true, mouseX, mouseY, () -> { cartOpen = false; scroll = 0; });
        } else {
            button(g, cx + cw - 4 - aw, y + 4, aw, 14, action, BLUE, BLUE_HOVER, n > 0, mouseX, mouseY, () -> { cartOpen = true; scroll = 0; });
        }
        return hovered;
    }

    private ItemStack drawCatalog(GuiGraphics g, int mouseX, int mouseY) {
        boolean noDelivery = state.delivery().isEmpty();
        List<Computer.ShopLine> lines = state.shop().stream().filter(l -> matches(l.stack())).toList();
        int columns = columns(), w = cardWidth(columns), top = noDelivery ? 18 : 0;
        scroll = Mth.clamp(scroll, 0, Math.max(0, top + ((lines.size() + columns - 1) / columns) * (CARD_H + GAP) - GAP - ch));
        ItemStack hovered = ItemStack.EMPTY;
        if (noDelivery) {
            int y = cy - (int) scroll;
            rounded(g, cx, y, cw, 14, 0x55D9443B);
            g.drawString(font, font.plainSubstrByWidth(text("no_delivery_short").getString(), cw - 8), cx + 4, y + 3, WHITE, false);
        }
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        for (int i = 0; i < lines.size(); i++) {
            Computer.ShopLine line = lines.get(i);
            int x = cx + (i % columns) * (w + GAP), y = cy + top + (i / columns) * (CARD_H + GAP) - (int) scroll;
            if (y + CARD_H < cy || y > cy + ch) continue;
            boolean over = card(g, x, y, w, line.icon(), line.stack(), mouseX, mouseY);
            if (over && mouseY < y + 42) hovered = line.stack();
            int by = y + CARD_H - 15;
            price(g, x + 4, by + 2, line.price());
            int inCart = cart.getOrDefault(line.id(), 0);
            if (inCart > 0) {
                // In the cart: - count +.
                button(g, x + w - 15, by - 1, 11, 13, Component.literal("+"), BLUE, BLUE_HOVER,
                        pendingCheckout == 0 && inCart < Computer.MAX_CART_COUNT, mouseX, mouseY, () -> cart.merge(line.id(), 1, Integer::sum));
                String c = String.valueOf(inCart);
                g.drawString(font, c, x + w - 18 - font.width(c), by + 2, WHITE, false);
                button(g, x + w - 32 - font.width(c), by - 1, 11, 13, Component.literal("-"), OFF, CARD_HOVER, pendingCheckout == 0, mouseX, mouseY,
                        () -> { if (cart.merge(line.id(), -1, Integer::sum) <= 0) cart.remove(line.id()); });
            } else {
                Component add = text("cart_add");
                int bw = font.width(add) + 8;
                button(g, x + w - 4 - bw, by - 1, bw, 13, add, BLUE, BLUE_HOVER, pendingCheckout == 0, mouseX, mouseY, () -> cart.put(line.id(), 1));
            }
        }
        g.disableScissor();
        if (lines.isEmpty()) g.drawCenteredString(font, text("empty"), cx + cw / 2, cy + top + 20, GREY);
        return hovered;
    }

    /** The cart: one row per entry (icon, name, - count +, line price, cross). */
    private ItemStack drawCart(GuiGraphics g, int mouseX, int mouseY) {
        List<String> ids = new ArrayList<>(cart.keySet());
        scroll = Mth.clamp(scroll, 0, Math.max(0, ids.size() * (ROW + 2) - ch));
        ItemStack hovered = ItemStack.EMPTY;
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Computer.ShopLine l = shopLine(id);
            int count = cart.get(id), x = cx, y = cy + i * (ROW + 2) - (int) scroll;
            if (y + ROW < cy || y > cy + ch) continue;
            boolean over = inside(mouseX, mouseY, x, y, cw, ROW);
            rounded(g, x, y, cw, ROW, over ? CARD_HOVER : CARD);
            if (l != null) {
                g.renderItem(l.icon(), x + 3, y + 3);
                g.renderItemDecorations(font, l.stack(), x + 3, y + 3);
                if (inside(mouseX, mouseY, x + 3, y + 3, 16, 16)) hovered = l.stack();
            }
            int right = x + cw - 5;
            button(g, right - 10, y + 6, 10, 10, Component.literal("\u00d7"), 0xAA000000, RED,
                    pendingCheckout == 0, mouseX, mouseY, () -> cart.remove(id));
            if (l == null) {
                Component unavailable = Component.translatableWithFallback("onemorecore.computer.cart_unavailable", "%s (unavailable)", id);
                g.drawString(font, font.plainSubstrByWidth(unavailable.getString(), right - 24 - x), x + 6, y + 7, RED, false);
                continue;
            }
            right -= 16;
            String lineTotal = String.valueOf((long) l.price() * count);
            price(g, right - font.width(lineTotal) - 12, y + 7, (int) Math.min(Integer.MAX_VALUE, (long) l.price() * count));
            right -= font.width(lineTotal) + 20;
            button(g, right - 11, y + 5, 11, 12, Component.literal("+"), OFF, CARD_HOVER,
                    pendingCheckout == 0 && count < Computer.MAX_CART_COUNT, mouseX, mouseY, () -> cart.merge(id, 1, Integer::sum));
            String c = String.valueOf(count);
            g.drawString(font, c, right - 14 - font.width(c), y + 7, WHITE, false);
            button(g, right - 28 - font.width(c), y + 5, 11, 12, Component.literal("-"), OFF, CARD_HOVER, pendingCheckout == 0, mouseX, mouseY,
                    () -> { if (cart.merge(id, -1, Integer::sum) <= 0) cart.remove(id); });
            int nameW = right - 32 - font.width(c) - (x + 24);
            g.drawString(font, font.plainSubstrByWidth(l.stack().getHoverName().getString(), nameW), x + 24, y + 7, WHITE, false);
        }
        g.disableScissor();
        if (ids.isEmpty()) g.drawCenteredString(font, text("cart_empty"), cx + cw / 2, cy + 20, GREY);
        return hovered;
    }

    /** One row per upgrade: its name and effect, its level as dots, and the button that buys the next level. */
    private void drawUpgrades(GuiGraphics g, int mouseX, int mouseY) {
        int y = cy + 2;
        for (Computer.UpgradeLine u : state.upgrades()) {
            rounded(g, cx, y, cw, 30, CARD);
            g.drawString(font, text("upgrade." + u.id()), cx + 6, y + 5, WHITE, false);
            g.drawString(font, text("upgrade." + u.id() + ".effect"), cx + 6, y + 17, GREY, false);
            // Level dots, right of the name.
            int dx = cx + 10 + font.width(text("upgrade." + u.id()));
            for (int i = 0; i < u.max(); i++) rounded(g, dx + i * 7, y + 6, 5, 5, i < u.level() ? GREEN : OFF);
            if (u.level() >= u.max()) {
                Component max = text("upgrade.max");
                g.drawString(font, max, cx + cw - 6 - font.width(max), y + 11, GREEN_HOVER, false);
            } else {
                Component buy = text("buy_cost", u.nextCost());
                int bw = font.width(buy) + 10;
                button(g, cx + cw - 6 - bw, y + 8, bw, 14, buy, BLUE, BLUE_HOVER, state.balance() >= u.nextCost(), mouseX, mouseY,
                        () -> send(Computer.BUY_UPGRADE, u.id()));
            }
            y += 34;
        }
    }

    /** What is set, with a mark each, and the button of the free camera. */
    private void drawManager(GuiGraphics g, int mouseX, int mouseY) {
        boolean[] set = {!state.area().isEmpty(), state.spawn().isPresent(), state.delivery().isPresent()};
        String[] keys = {"free.area", "free.spawn", "free.delivery"};
        int[] colors = {0xFF4DE066, 0xFF4D99FF, 0xFFFF8C1A};
        int y = cy + 2;
        for (int i = 0; i < 3; i++) {
            rounded(g, cx, y, cw, 20, CARD);
            g.fill(cx + 6, y + 7, cx + 12, y + 13, colors[i]);
            g.drawString(font, Component.translatable("onemorecore." + keys[i]), cx + 18, y + 6, WHITE, false);
            g.blitSprite(sprite(set[i] ? "check" : "cross"), cx + cw - 16, y + 6, 8, 8);
            y += 23;
        }
        // Tables: how many of them customers may use.
        long tables = state.surfaces().size(), off = state.offSurfaces().size();
        rounded(g, cx, y, cw, 20, CARD);
        g.fill(cx + 6, y + 7, cx + 12, y + 13, 0xFFE05555);
        g.drawString(font, Component.translatable("onemorecore.free.tables"), cx + 18, y + 6, WHITE, false);
        String count = (tables - off) + "/" + tables;
        g.drawString(font, count, cx + cw - 8 - font.width(count), y + 6, GREY, false);
        y += 27;
        button(g, cx, y, cw, 20, text("edit_places"), BLUE, BLUE_HOVER, pendingCheckout == 0, mouseX, mouseY, ComputerCamera::startFree);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Hit h : hits) {
                if (h.contains(mouseX, mouseY)) {
                    hits.clear();
                    h.action().run();
                    return true;
                }
            }
        }
        // Focus or unfocus the search bar.
        boolean onSearch = hasSearch() && search.isMouseOver(mouseX, mouseY);
        search.setFocused(onSearch);
        setFocused(onSearch ? search : null);
        return onSearch && search.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - scrollY * 16);
        hits.clear();
        return true;
    }

    /** Escape: the picker, then the window, then the computer. Typing goes to the search bar. */
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (adding || building != null || app != 0) back();
            else ComputerCamera.leave();
            return true;
        }
        if (hasSearch() && search.keyPressed(key, scanCode, modifiers)) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (hasSearch()) {
            if (!search.isFocused()) { search.setFocused(true); setFocused(search); }
            return search.charTyped(c, modifiers);
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
