package com.onemorestudios.onemoreclient.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.onemorestudios.onemoreclient.Computer;
import com.onemorestudios.onemoreclient.OneMoreClient;
import com.onemorestudios.onemoreclient.Restaurant;

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

    private Computer.State state;
    /** 0: no window; otherwise one of Computer.APP_*. */
    private int app;
    /** In the menu app: the picker that adds a food. */
    private boolean adding;
    /** In the menu app: "all", a kind of dish, or "combos". */
    private String filter = "all";
    /** In the combos tab: the dishes of a new combo, while it is made. */
    private List<String> building;
    private static final String[] FILTERS = {"all", "main", "drink", "starter", "dessert", "combos"};
    private static final int CHIPS = 13;
    private double scroll;
    private int dx, dy, dw, dh, wx, wy, ww, wh, cx, cy, cw, ch;
    private final List<Hit> hits = new ArrayList<>();
    private long messageTime;
    private EditBox search;
    /** Every food and drink item of the game, for the picker. */
    private List<ItemStack> foods;

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
        adding = false;
        building = null;
        filter = "all";
        scroll = 0;
        if (search != null) search.setValue("");
        send(Computer.REFRESH, "");
    }

    @Override
    protected void init() {
        // The camera puts the monitor's 4:3 screen in the middle of the view, ComputerCamera.FILL of its height.
        dh = (int) Math.round(height * ComputerCamera.FILL);
        dw = Math.min(width - 8, dh * 4 / 3);
        dx = (width - dw) / 2;
        dy = (height - dh) / 2;
        wx = dx + 46;
        wy = dy + 6;
        ww = dx + dw - 6 - wx;
        wh = dy + dh - TASKBAR - 6 - wy;
        String old = search == null ? "" : search.getValue();
        search = new EditBox(font, wx + 22, wy + TITLE + 8, ww - 30, 10, Component.empty());
        search.setBordered(false);
        search.setMaxLength(40);
        search.setValue(old);
        search.setHint(Component.translatable("onemoreclient.computer.search").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        search.setResponder(s -> scroll = 0);
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
        g.fillGradient(dx, dy, dx + dw, dy + dh, 0xFF22607A, 0xFF0D2430);
        drawIcons(g, mouseX, mouseY);
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
        cy = wy + TITLE + 5 + (hasSearch() ? SEARCH + 5 : 0) + (chips ? CHIPS + 4 : 0);
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
            // Kind filters: all, the four kinds, and the combos.
            int x = cx, y = wy + TITLE + 5 + SEARCH + 5;
            for (String f : FILTERS) {
                Component label = text("kind." + f);
                int w = font.width(label) + 8;
                boolean on = filter.equals(f), over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CHIPS;
                rounded(g, x, y, w, CHIPS, on ? BLUE : over ? CARD_HOVER : CARD);
                g.drawString(font, label, x + 4, y + 3, on ? WHITE : GREY, false);
                hits.add(new Hit(x, y, w, CHIPS, () -> { filter = f; scroll = 0; }));
                x += w + 3;
            }
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
        if (System.currentTimeMillis() - messageTime < 3500 && !state.message().getString().isEmpty()) {
            var lines = font.split(state.message(), ww - 28);
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

    private ItemStack drawMenu(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.MenuLine> lines = state.menu().stream().filter(l -> matches(l.icon()) && (filter.equals("all") || filter.equals(l.kind()))).toList();
        int columns = columns(), w = cardWidth(columns);
        int count = lines.size() + 1; // the last card adds a dish
        scroll = Mth.clamp(scroll, 0, Math.max(0, ((count + columns - 1) / columns) * (CARD_H + GAP) - GAP - ch));
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < count; i++) {
            int x = cx + (i % columns) * (w + GAP), y = cy + (i / columns) * (CARD_H + GAP) - (int) scroll;
            if (y + CARD_H < cy || y > cy + ch) continue;
            if (i == lines.size()) {
                boolean over = inside(mouseX, mouseY, x, y, w, CARD_H);
                rounded(g, x, y, w, CARD_H, over ? CARD_HOVER : 0xFF20252C);
                g.blitSprite(sprite("add"), x + w / 2 - 8, y + 26, 16, 16);
                Component add = text("add");
                g.drawString(font, add, x + (w - font.width(add)) / 2, y + 50, GREY, false);
                clipHit(x, y, w, CARD_H, () -> { adding = true; scroll = 0; search.setValue(""); });
                continue;
            }
            Computer.MenuLine line = lines.get(i);
            boolean over = card(g, x, y, w, line.icon(), ItemStack.EMPTY, mouseX, mouseY);
            if (over && mouseY < y + 42) hovered = line.icon();
            int by = y + CARD_H - 15;
            // The details come only with the mouse: the kind (a click changes it), and for an added dish the price
            // buttons and the cross that removes it.
            if (over) {
                Component kind = text("kind." + line.kind());
                int kw = font.width(kind) + 6;
                boolean overKind = inside(mouseX, mouseY, x + 4, y + 4, kw, 11);
                rounded(g, x + 4, y + 4, kw, 11, overKind ? 0xEE3D7FE0 : 0xCC11151A);
                g.drawString(font, kind, x + 7, y + 6, WHITE, false);
                clipHit(x + 4, y + 4, kw, 11, () -> send(Computer.SET_KIND, line.id() + "," + nextKind(line.kind())));
                if (overKind) hovered = ItemStack.EMPTY;
            }
            if (line.custom() && over) {
                button(g, x + w - 13, y + 5, 9, 9, Component.literal("\u00d7"), 0xAA000000, RED, true, mouseX, mouseY, () -> send(Computer.REMOVE_DISH, line.id()));
                button(g, x + 4, by, 11, 11, Component.literal("-"), OFF, CARD_HOVER, line.price() > 1, mouseX, mouseY,
                        () -> send(Computer.SET_PRICE, line.id() + "," + Math.max(1, line.price() - step())));
                price(g, x + 18, by + 2, line.price());
                button(g, x + 18 + 14 + font.width(String.valueOf(line.price())), by, 11, 11, Component.literal("+"), OFF, CARD_HOVER, true, mouseX, mouseY,
                        () -> send(Computer.SET_PRICE, line.id() + "," + (line.price() + step())));
            } else {
                price(g, x + 4, by + 2, line.price());
            }
            // Right of the price: a switch for a dish that is open; the day of a locked dish; the unlock price.
            switch (line.state()) {
                case Computer.AVAILABLE -> toggle(g, x + w - 22, by + 1, line.on(), mouseX, mouseY, () -> send(Computer.TOGGLE_DISH, line.id()));
                case Computer.LOCKED_DAY -> {
                    Component day = text("locked", line.day());
                    g.drawString(font, day, x + w - 4 - font.width(day), by + 2, GREY, false);
                }
                default -> {
                    Component unlock = text("unlock_cost", line.cost());
                    int uw = font.width(unlock) + 8;
                    button(g, x + w - 4 - uw, by - 1, uw, 13, unlock, BLUE, BLUE_HOVER, state.balance() >= line.cost(), mouseX, mouseY,
                            () -> send(Computer.UNLOCK, line.id()));
                }
            }
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
        if (adding) adding = false;
        else if (building != null) building = null;
        else app = 0;
    }

    private Computer.MenuLine line(String id) {
        for (Computer.MenuLine l : state.menu()) if (l.id().equals(id)) return l;
        return null;
    }

    /** The combos: their dishes, price (- and +), a cross that removes them, and a card that makes a new one. */
    private ItemStack drawCombos(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.ComboLine> combos = state.combos();
        int columns = Math.max(1, (cw + GAP) / (96 + GAP)), w = (cw - (columns - 1) * GAP) / columns, h = 48;
        int count = combos.size() + 1;
        scroll = Mth.clamp(scroll, 0, Math.max(0, ((count + columns - 1) / columns) * (h + GAP) - GAP - ch));
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < count; i++) {
            int x = cx + (i % columns) * (w + GAP), y = cy + (i / columns) * (h + GAP) - (int) scroll;
            if (y + h < cy || y > cy + ch) continue;
            boolean over = inside(mouseX, mouseY, x, y, w, h);
            if (i == combos.size()) {
                rounded(g, x, y, w, h, over ? CARD_HOVER : 0xFF20252C);
                g.blitSprite(sprite("add"), x + w / 2 - 8, y + 8, 16, 16);
                Component add = text("combo_new");
                g.drawString(font, add, x + (w - font.width(add)) / 2, y + 30, GREY, false);
                clipHit(x, y, w, h, () -> { building = new ArrayList<>(); scroll = 0; search.setValue(""); });
                continue;
            }
            Computer.ComboLine c = combos.get(i);
            rounded(g, x, y, w, h, over ? CARD_HOVER : CARD);
            rounded(g, x + 3, y + 3, w - 6, 24, PICTURE);
            int ix = x + (w - c.dishes().size() * 18) / 2;
            for (String id : c.dishes()) {
                Computer.MenuLine l = line(id);
                if (l != null) {
                    g.renderItem(l.icon(), ix + 1, y + 7);
                    if (inside(mouseX, mouseY, ix, y + 6, 18, 18)) hovered = l.icon();
                }
                ix += 18;
            }
            if (over) {
                button(g, x + 4, y + 31, 11, 11, Component.literal("-"), OFF, CARD_HOVER, c.price() > 1, mouseX, mouseY,
                        () -> send(Computer.SET_COMBO_PRICE, c.id() + "," + Math.max(1, c.price() - step())));
                price(g, x + 18, y + 33, c.price());
                button(g, x + 18 + 14 + font.width(String.valueOf(c.price())), y + 31, 11, 11, Component.literal("+"), OFF, CARD_HOVER, true, mouseX, mouseY,
                        () -> send(Computer.SET_COMBO_PRICE, c.id() + "," + (c.price() + step())));
                button(g, x + w - 13, y + 5, 9, 9, Component.literal("\u00d7"), 0xAA000000, RED, true, mouseX, mouseY, () -> send(Computer.REMOVE_COMBO, c.id()));
            } else {
                price(g, x + 4, y + 33, c.price());
            }
            // A combo with a dish off the menu cannot be ordered: it is dimmed.
            if (!c.orderable()) rounded(g, x, y, w, h, 0x88151A20);
        }
        return hovered;
    }

    /** The combo maker: dishes on the menu to pick (2 to 4), then "Create" with 90 percent of their prices. */
    private ItemStack drawBuilder(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.MenuLine> lines = state.menu().stream().filter(l -> l.on() && l.state() == Computer.AVAILABLE && matches(l.icon())).toList();
        int size = 28, columns = Math.max(1, (cw + 3) / (size + 3));
        int top = 22;
        int sum = building.stream().map(this::line).filter(java.util.Objects::nonNull).mapToInt(Computer.MenuLine::price).sum();
        int comboPrice = Math.max(1, (int) Math.round(sum * 0.9));
        // The bar at the top: picked dishes and the Create button.
        rounded(g, cx, cy, cw, 18, CARD);
        int bx = cx + 4;
        for (String id : building) {
            Computer.MenuLine l = line(id);
            if (l != null) g.renderItem(l.icon(), bx, cy + 1);
            bx += 18;
        }
        if (building.size() >= 2) price(g, bx + 4, cy + 5, comboPrice);
        button(g, cx + cw - 52, cy + 2, 48, 14, text("combo_create"), GREEN, GREEN_HOVER, building.size() >= 2, mouseX, mouseY, () -> {
            send(Computer.ADD_COMBO, String.join(";", building) + "," + comboPrice);
            building = null;
            filter = "combos";
        });
        scroll = Mth.clamp(scroll, 0, Math.max(0, top + ((lines.size() + columns - 1) / columns) * (size + 3) - 3 - ch));
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < lines.size(); i++) {
            Computer.MenuLine l = lines.get(i);
            int x = cx + (i % columns) * (size + 3), y = cy + top + (i / columns) * (size + 3) - (int) scroll;
            if (y + size < cy + top || y > cy + ch) continue;
            boolean picked = building.contains(l.id()), over = inside(mouseX, mouseY, x, y, size, size);
            rounded(g, x, y, size, size, picked ? 0xFF1F3A27 : over ? CARD_HOVER : CARD);
            g.renderItem(l.icon(), x + (size - 16) / 2, y + (size - 16) / 2);
            if (over) hovered = l.icon();
            clipHit(x, Math.max(y, cy + top), size, size, () -> {
                if (picked) building.remove(l.id());
                else if (building.size() < 4) building.add(l.id());
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

    private ItemStack drawShop(GuiGraphics g, int mouseX, int mouseY) {
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
        for (int i = 0; i < lines.size(); i++) {
            Computer.ShopLine line = lines.get(i);
            int x = cx + (i % columns) * (w + GAP), y = cy + top + (i / columns) * (CARD_H + GAP) - (int) scroll;
            if (y + CARD_H < cy || y > cy + ch) continue;
            boolean over = card(g, x, y, w, line.icon(), line.stack(), mouseX, mouseY);
            if (over && mouseY < y + 42) hovered = line.stack();
            int by = y + CARD_H - 15;
            price(g, x + 4, by + 2, line.price());
            Component buy = text("buy");
            int bw = font.width(buy) + 8;
            button(g, x + w - 4 - bw, by - 1, bw, 13, buy, BLUE, BLUE_HOVER, state.balance() >= line.price() && !noDelivery,
                    mouseX, mouseY, () -> send(Computer.BUY, line.id()));
        }
        if (lines.isEmpty()) g.drawCenteredString(font, text("empty"), cx + cw / 2, cy + top + 20, GREY);
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
            g.drawString(font, Component.translatable("onemoreclient." + keys[i]), cx + 18, y + 6, WHITE, false);
            g.blitSprite(sprite(set[i] ? "check" : "cross"), cx + cw - 16, y + 6, 8, 8);
            y += 23;
        }
        // Tables: how many of them customers may use.
        long tables = state.surfaces().size(), off = state.offSurfaces().size();
        rounded(g, cx, y, cw, 20, CARD);
        g.fill(cx + 6, y + 7, cx + 12, y + 13, 0xFFE05555);
        g.drawString(font, Component.translatable("onemoreclient.free.tables"), cx + 18, y + 6, WHITE, false);
        String count = (tables - off) + "/" + tables;
        g.drawString(font, count, cx + cw - 8 - font.width(count), y + 6, GREY, false);
        y += 27;
        button(g, cx, y, cw, 20, text("edit_places"), BLUE, BLUE_HOVER, true, mouseX, mouseY, ComputerCamera::startFree);
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
        // Focus or unfocus the search bar.
        boolean onSearch = hasSearch() && search.isMouseOver(mouseX, mouseY);
        search.setFocused(onSearch);
        setFocused(onSearch ? search : null);
        return onSearch && search.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - scrollY * 16);
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
