package com.onemorestudios.onemorecore.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.systems.RenderSystem;
import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Status;

import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The desktop of the Restaurant Computer, drawn on the monitor after the camera flew to it: a flat background, a row of
 * app icons and a task bar. An app opens in a window with a close button. Once per opening of the computer the monitor boots first (a CRT
 * power-on line, boot text, the logo with a loading bar; a click or a key skips it), and Escape on the desktop turns the
 * picture off like a CRT (a line, then a dot) before the camera flies back.
 * Menu: a searchable catalog of the restaurant's dishes (on and off, unlock, price and removal of added dishes) and a
 * picker that adds any food. Shop: a searchable catalog. Manager: what is set, and the free camera that sets it.
 */
public class ComputerScreen extends Screen {
    private static final int[] APPS = {Computer.APP_MENU, Computer.APP_SHOP, Computer.APP_MANAGER, Computer.APP_UPGRADES, Computer.APP_STREAM};
    private static final String[] KEYS = {"menu", "shop", "manager", "upgrades", "stream"};
    private static final String[] PLATFORMS = {"none", "twitch", "kick", "youtube"};
    private static final int[] PLATFORM_COLORS = {0xFF5A6270, 0xFF9146FF, 0xFF53FC18, 0xFFFF0033};
    /**
     * The look of Hands-On Cookery's progress panel (Kaleidoscope's drawer): a warm near-black brown panel with a faint cream
     * edge and corners cut by a pixel, cream text, dim cream for what is secondary, amber for prices and the main action, a
     * light wash under the pointer, the track colour for empty and disabled things, no text shadows.
     */
    private static final int PANEL = 0xB4181210, EDGE = 0x30FFF2DC, HOVER = 0x30FFE9A8, TEXT = 0xE0E8E0D0, DIM = 0x90E8E0D0,
            AMBER = 0xFFFFCB6B, AMBER_HOVER = 0xFFFFDA8F, GREY = 0xFF8E877E, TRACK = 0x40FFF2DC, RIPE = 0xFFD8E36A,
            RED = 0xFFE0614A, DARK = 0xFF181210, CARD = 0x14FFF2DC, CARD_DIM = 0x0CFFF2DC, PICTURE = 0x1EFFF2DC,
            TITLE_WASH = 0x14FFF2DC, FIELD = 0x40000000, FIELD_FOCUS = 0x60000000, PICKED = 0x40FFCB6B, CLOSE = 0x80181210;
    private static final int CARD_MIN_W = 66, GAP = 5, CARD_H = 70, TASKBAR = 16, TITLE = 18, SEARCH = 16;

    /** The boot, in milliseconds from its start: power-on line, the lit area opening, boot text, logo, desktop fade. */
    private static final long POWER_LINE = 250, POWER_OPEN = 500, TEXT_START = 600, LINE_EVERY = 260, TEXT_END = 2400,
            LOGO_START = 2500, LOGO_END = 3500, DESKTOP_FADE = 200, SHUTDOWN = 300;
    private static final int POWER = 0xE8FFE8, PHOSPHOR = 0xFF9DF59D, TERMINAL = 0xFF0B0F0B, WARM = 0x181210;
    /** The width of the boot's reference picture: the loading bar is 180 px of it. */
    private static final float BOOT_REFERENCE = 320;
    /** The advance of a boot text character, so that the text looks monospace. */
    private static final int CELL = 6;
    /** The desktop's flat background. */
    private static final int DESKTOP = 0xFF2F4A48;
    /**
     * The desktop's layout in px of a 560 px wide picture, scaled to the monitor: padding, an icon's cell and the gap
     * between cells, the tile, the gap to the label. The sprites' corners have a radius of 7 of 32 px (make_icons.py).
     */
    private static final float DESKTOP_REFERENCE = 560, PAD = 16, ICON_CELL = 72, ICON_GAP = 6, ICON_TILE = 44, LABEL_GAP = 4,
            TILE_RADIUS = 7 / 32f;
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
    private String editingCombo;
    /** The shop's cart: entry id and count, in the order added. */
    private final java.util.LinkedHashMap<String, Integer> cart = new java.util.LinkedHashMap<>();
    private static long nextCheckoutId;
    private long pendingCheckout;
    /** The shop shows the cart instead of the catalog. */
    private boolean cartOpen;
    private static final int SIDEBAR = 62, ROW = 22, COMBO_PLACES = Restaurant.MAX_ORDER_ITEMS;
    private static final String[] FILTERS = {"all", "main", "drink", "starter", "dessert", "combos"};
    private static final int CHIPS = 13;
    private double scroll;
    private int dx, dy, dw, dh, wx, wy, ww, wh, cx, cy, cw, ch;
    private final List<Hit> hits = new ArrayList<>();
    private Component message = Component.empty();
    private long messageTime;
    private EditBox search;
    /** The Stream app: the channel and the chat command, and the platform picked (null: as the server has it). */
    private EditBox channel, command;
    private String platform;
    /** Every food and drink item of the game, for the picker. */
    private List<ItemStack> foods;
    /** The boot: true until it ends or is skipped; its start on the client clock (-1 before the first frame). */
    private boolean booting;
    private long bootStart = -1, fadeStart = -1, shutdownStart = -1;
    private List<String> bootLines;
    private int bootShown;
    /** The key that skipped the boot also types a character: it is dropped. */
    private boolean swallowChar;
    private boolean leaveQueued;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    ComputerScreen(Computer.State state, int app) {
        super(Component.translatable("block.onemorecore.computer"));
        this.state = state;
        this.app = (state.apps() & app) != 0 ? app : 0;
        booting = !ComputerCamera.booted;
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
        platform = null;
        send(which == Computer.APP_MANAGER ? Computer.REFRESH_MANAGER : Computer.REFRESH, "");
    }

    @Override
    protected void init() {
        hits.clear();
        // Match the CRT glass centred in the camera view.
        dh = (int) Math.round(height * ComputerCamera.FILL);
        dw = Math.min(width - 8, (int) Math.round(dh * ComputerCamera.SCREEN_ASPECT));
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
        search.setHint(Component.translatable("onemorecore.computer.search").withColor(GREY & 0xFFFFFF));
        search.setTextColor(TEXT);
        search.setResponder(s -> { scroll = 0; hits.clear(); });
        addWidget(search);
        channel = streamBox(channel, 64);
        command = streamBox(command, 16);
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
        swallowChar = false;
        long now = Util.getMillis();
        if (shutdownStart >= 0) {
            drawShutdown(g, now - shutdownStart);
            return;
        }
        if (booting) {
            if (bootStart < 0) {
                bootStart = now;
                bootLines = bootLines();
                sound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 0.3f);
            }
            long t = now - bootStart;
            if (t < LOGO_END) {
                drawBoot(g, t);
                return;
            }
            endBoot();
            fadeStart = bootStart + LOGO_END;
        }
        drawDesktop(g, mouseX, mouseY, partialTick);
        if (fadeStart >= 0) {
            float p = (now - fadeStart) / (float) DESKTOP_FADE;
            if (p >= 1) fadeStart = -1;
            else {
                // In front of the desktop's items, which are drawn further out.
                g.pose().pushPose();
                g.pose().translate(0, 0, 400);
                drawLogo(g, 1, 1 - p, 1 - p);
                g.pose().popPose();
            }
        }
    }

    private void drawDesktop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(dx, dy, dx + dw, dy + dh, DESKTOP);
        if (app == 0) drawIcons(g, mouseX, mouseY);
        drawTaskbar(g);
        if (!state.found()) {
            box(g, text("outside"));
        } else if (app != 0) {
            drawWindow(g, mouseX, mouseY, partialTick);
        }
    }

    private void sound(SoundEvent sound, float pitch, float volume) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** The boot ends (by itself or skipped): the desktop and its chime. It does not play again in this opening. */
    private void endBoot() {
        booting = false;
        ComputerCamera.booted = true;
        sound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2f, 0.35f);
    }

    private void skipBoot() {
        endBoot();
        fadeStart = -1;
        hits.clear();
    }

    /** The lines of the boot text, from what the client already has; a line whose value is unknown is left out. */
    private List<String> bootLines() {
        List<String> lines = new ArrayList<>();
        lines.add(text("boot.title").getString());
        lines.add(text("boot.memory").getString());
        if (state.found()) lines.add(text("boot.register", state.balance(), state.currency().getHoverName()).getString());
        int day = day();
        if (day > 0) lines.add(text("boot.day", day).getString());
        if (!state.surfaces().isEmpty()) lines.add(text("boot.tables", state.surfaces().size()).getString());
        lines.add(text("boot.ready").getString());
        return lines;
    }

    /** The restaurant's day from the HUD's status, if the client has it for this computer's restaurant; otherwise -1. */
    private int day() {
        if (!state.found() || state.sign().isEmpty()) return -1;
        BlockPos sign = state.sign().get();
        Status.Received near = Status.near(sign, false);
        if (near != null && sign.equals(near.status().sign())) return near.status().day();
        Status last = Status.client;
        return last != null && sign.equals(last.sign()) ? last.day() : -1;
    }

    private static int argb(float alpha, int rgb) {
        return (Math.round(Mth.clamp(alpha, 0, 1) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static float easeOut(float p) {
        p = Mth.clamp(p, 0, 1);
        return 1 - (1 - p) * (1 - p);
    }

    /** A bright line of the CRT, with a faint glow above and below it. */
    private void glowLine(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        g.fill(x, y - 1, x + w, y, argb(alpha * 0.35f, POWER));
        g.fill(x, y + h, x + w, y + h + 1, argb(alpha * 0.35f, POWER));
        g.fill(x, y, x + w, y + h, argb(alpha, POWER));
    }

    /**
     * The boot at t milliseconds. Power on (0-0.5 s): a line grows from the centre to the full width, then the lit area
     * opens to the full height and fades. Boot text (0.6-2.4 s), a line every 0.26 s. Logo and loading bar (2.5-3.5 s).
     */
    private void drawBoot(GuiGraphics g, long t) {
        int mid = dy + dh / 2;
        if (t < TEXT_START) {
            g.fill(dx, dy, dx + dw, dy + dh, t < POWER_OPEN ? 0xFF000000 : TERMINAL);
            if (t < POWER_LINE) {
                int w = Math.max(2, Math.round(dw * easeOut(t / (float) POWER_LINE)));
                glowLine(g, dx + (dw - w) / 2, mid, w, 1, 1);
            } else {
                float p = (t - POWER_LINE) / (float) (POWER_OPEN - POWER_LINE);
                int h = Math.max(1, Math.round(dh * easeOut(p)));
                float alpha = t < POWER_OPEN ? 1 - 0.6f * p : 0.4f * (1 - (t - POWER_OPEN) / (float) (TEXT_START - POWER_OPEN));
                g.fill(dx, mid - h / 2, dx + dw, mid - h / 2 + h, argb(alpha, POWER));
            }
            return;
        }
        if (t < LOGO_START) {
            drawTerminal(g, t);
            // The text gives way to the logo's warm background.
            if (t >= TEXT_END) g.fill(dx, dy, dx + dw, dy + dh, argb((t - TEXT_END) / (float) (LOGO_START - TEXT_END), WARM));
            return;
        }
        float p = (t - LOGO_START) / (float) (LOGO_END - LOGO_START);
        drawLogo(g, p * p * (3 - 2 * p), Mth.clamp((t - LOGO_START) / 120f, 0, 1), 1);
    }

    /** Phosphor green text on near black, top-left, with a blinking block cursor after the last line. */
    private void drawTerminal(GuiGraphics g, long t) {
        g.fill(dx, dy, dx + dw, dy + dh, TERMINAL);
        int shown = (int) Math.min(bootLines.size(), (t - TEXT_START) / LINE_EVERY + 1);
        if (shown > bootShown) {
            bootShown = shown;
            sound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.6f, 0.08f);
        }
        int longest = 0;
        for (String line : bootLines) longest = Math.max(longest, line.codePointCount(0, line.length()));
        float scale = Math.min(1, (dw - 12) / (float) (longest * CELL + CELL));
        g.pose().pushPose();
        g.pose().translate(dx + 6, dy + 6, 0);
        g.pose().scale(scale, scale, 1);
        for (int i = 0; i < shown; i++) mono(g, bootLines.get(i), 0, i * 10, PHOSPHOR);
        if (t / 265 % 2 == 0) {
            String last = bootLines.get(shown - 1);
            int x = last.codePointCount(0, last.length()) * CELL + 1, y = (shown - 1) * 10;
            g.fill(x, y - 1, x + CELL - 1, y + 8, PHOSPHOR);
        }
        g.pose().popPose();
    }

    /** Text with every character in a cell of the same width, so that the boot lines look monospace. */
    private void mono(GuiGraphics g, String s, int x, int y, int color) {
        int i = 0;
        for (int k = 0; k < s.length(); ) {
            int cp = s.codePointAt(k);
            String c = new String(Character.toChars(cp));
            k += Character.charCount(cp);
            if (cp != ' ') g.drawString(font, c, x + i * CELL + (CELL - font.width(c) + 1) / 2, y, color, false);
            i++;
        }
    }

    /**
     * The logo screen: the logo and "One More OS" centred on a warm dark background, with a loading bar (180 x 8 px of a
     * 320 px wide picture, scaled to the monitor) filled to progress. alpha fades the logo, the text and the bar, back the
     * background (both fall for the desktop's fade-in).
     */
    private void drawLogo(GuiGraphics g, float progress, float alpha, float back) {
        g.fill(dx, dy, dx + dw, dy + dh, argb(back, WARM));
        if (alpha < 0.05f) return;
        float f = dw / BOOT_REFERENCE;
        int logo = 16 * Math.max(1, Math.round(2 * f)), titleScale = f >= 1.75f ? 2 : 1;
        int barW = Math.min(dw - 16, Math.round(180 * f)), barH = Math.max(6, Math.round(8 * f));
        int gap1 = Math.round(8 * f), gap2 = Math.round(10 * f);
        int y = dy + (dh - (logo + gap1 + 8 * titleScale + gap2 + barH)) / 2, mid = dx + dw / 2;
        RenderSystem.enableBlend();
        g.setColor(1, 1, 1, alpha);
        g.blitSprite(sprite("logo"), mid - logo / 2, y, logo, logo);
        g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        y += logo + gap1;
        Component title = text("os");
        g.pose().pushPose();
        g.pose().translate(mid - font.width(title) * titleScale / 2f, y, 0);
        g.pose().scale(titleScale, titleScale, 1);
        g.drawString(font, title, 0, 0, argb(alpha, TEXT), false);
        g.pose().popPose();
        y += 8 * titleScale + gap2;
        int x = mid - barW / 2, border = argb(alpha, TEXT);
        g.fill(x, y, x + barW, y + 1, border);
        g.fill(x, y + barH - 1, x + barW, y + barH, border);
        g.fill(x, y + 1, x + 1, y + barH - 1, border);
        g.fill(x + barW - 1, y + 1, x + barW, y + barH - 1, border);
        int inset = barH >= 7 ? 2 : 1, inner = barW - 2 * inset;
        g.fill(x + inset, y + inset, x + inset + Math.round(inner * Mth.clamp(progress, 0, 1)), y + barH - inset, argb(alpha, AMBER));
    }

    /**
     * The CRT turning off (0.3 s): the picture squeezes into a bright horizontal line, the line shrinks to a dot, the dot
     * fades. Then the camera flies back.
     */
    private void drawShutdown(GuiGraphics g, long t) {
        int mid = dy + dh / 2;
        g.fill(dx, dy, dx + dw, dy + dh, 0xFF000000);
        if (t < 170) {
            float p = t / 170f, sy = Math.max(1f / dh, 1 - p * p);
            g.pose().pushPose();
            g.pose().translate(0, mid, 0);
            g.pose().scale(1, sy, 1);
            g.pose().translate(0, -mid, 0);
            drawDesktop(g, -1, -1, 0);
            g.pose().popPose();
            hits.clear();
            int h = Math.max(1, Math.round(dh * sy));
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.fill(dx, mid - h / 2, dx + dw, mid - h / 2 + h, argb(p, POWER));
            g.pose().popPose();
        } else if (t < 250) {
            float p = (t - 170) / 80f;
            int w = Math.max(2, Math.round(dw * (1 - p * p)));
            glowLine(g, dx + (dw - w) / 2, mid, w, 1, 1);
        } else if (t < SHUTDOWN) {
            float a = 1 - (t - 250) / (float) (SHUTDOWN - 250);
            glowLine(g, dx + dw / 2 - 1, mid, 2, 1, a);
        } else if (!leaveQueued) {
            leaveQueued = true;
            // Leaves between frames, not in the middle of this one.
            minecraft.tell(() -> { if (minecraft.screen == this) ComputerCamera.leave(); });
        }
    }

    /** Fills a square with rounded corners of radius r, cut like the app sprites: a pixel stays if its centre is in the circle. */
    private static void tileShape(GuiGraphics g, int x, int y, int size, int r, int color) {
        r = Math.min(r, size / 2);
        for (int i = 0; i < r; i++) {
            double d = r - i - 0.5;
            int cut = 0;
            while ((r - cut - 0.5) * (r - cut - 0.5) + d * d > r * r) cut++;
            g.fill(x + cut, y + i, x + size - cut, y + i + 1, color);
            g.fill(x + cut, y + size - 1 - i, x + size - cut, y + size - i, color);
        }
        g.fill(x, y + r, x + size, y + size - r, color);
    }

    /**
     * The app icons in a row from the top-left that wraps: a tile and the name under it; a white outline and a gold name
     * under the pointer. The sizes are those of a 560 px wide desktop, scaled to the monitor.
     */
    private void drawIcons(GuiGraphics g, int mouseX, int mouseY) {
        float f = dw / DESKTOP_REFERENCE;
        int pad = Math.round(PAD * f), cell = Math.round(ICON_CELL * f), gap = Math.round(ICON_GAP * f);
        int tile = Math.round(ICON_TILE * f), labelGap = Math.max(2, Math.round(LABEL_GAP * f)), r = Math.round(tile * TILE_RADIUS);
        int labelScale = 11 * f / 8 >= 1.75f ? 2 : 1, rowH = tile + labelGap + 9 * labelScale;
        int x = dx + pad, y = dy + pad;
        for (int i = 0; i < APPS.length; i++) {
            int bit = APPS[i];
            boolean on = state.found() && (state.apps() & bit) != 0;
            boolean hover = on && mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + rowH;
            int tx = x + (cell - tile) / 2;
            // The outline: the tile grown by a pixel, in white, under the tile.
            if (hover) tileShape(g, tx - 1, y - 1, tile + 2, r + 1, EDGE);
            g.blitSprite(sprite("app_" + KEYS[i]), tx, y, tile, tile);
            if (!on) tileShape(g, tx, y, tile, r, 0xB0000000 | GREY & 0xFFFFFF);
            String name = font.plainSubstrByWidth(text("app." + KEYS[i]).getString(), (cell + gap) / labelScale);
            g.pose().pushPose();
            g.pose().translate(x + cell / 2f - font.width(name) * labelScale / 2f, y + tile + labelGap, 0);
            g.pose().scale(labelScale, labelScale, 1);
            g.drawString(font, name, 0, 0, hover ? AMBER : on ? TEXT : GREY, false);
            g.pose().popPose();
            if (on) hits.add(new Hit(x, y, cell, rowH, () -> open(bit)));
            x += cell + gap;
            // A row that reaches the right edge continues under it.
            if (x + cell > dx + dw - pad) { x = dx + pad; y += rowH + gap; }
        }
    }

    /** Left: the logo, "One More OS" and the open app's chip. Right: the register with the coin, the day and the clock. */
    private void drawTaskbar(GuiGraphics g) {
        int y = dy + dh - TASKBAR;
        g.fill(dx, y, dx + dw, dy + dh, PANEL);
        g.fill(dx, y, dx + dw, y + 1, EDGE);
        // The right side, from the right edge: clock · day · register.
        long time = minecraft.level == null ? 0 : (minecraft.level.getDayTime() + 6000) % 24000;
        String clock = String.format("%02d:%02d", time / 1000, time % 1000 * 60 / 1000);
        int rx = dx + dw - 6 - font.width(clock);
        g.drawString(font, clock, rx, y + 4, TEXT, false);
        int day = day();
        if (day > 0) {
            // "Day" in the dim cream, the number in cream.
            String n = String.valueOf(day);
            rx = separator(g, rx, y) - font.width(n);
            g.drawString(font, n, rx, y + 4, TEXT, false);
            Component label = text("taskbar.day");
            rx -= font.width(label) + font.width(" ");
            g.drawString(font, label, rx, y + 4, DIM, false);
        }
        if (state.found()) {
            String money = String.valueOf(state.balance());
            rx = separator(g, rx, y) - font.width(money);
            g.drawString(font, money, rx, y + 4, TEXT, false);
            rx -= 12;
            g.pose().pushPose();
            g.pose().translate(rx, y + 3, 0);
            g.pose().scale(0.625f, 0.625f, 1);
            g.renderItem(state.currency(), 0, 0);
            g.pose().popPose();
        }
        g.blitSprite(sprite("logo"), dx + 3, y + 2, 12, 12);
        int x = dx + 18;
        Component os = text("os");
        Component name = app == 0 ? null : text("app." + KEYS[index(app)]);
        int chip = name == null ? 0 : font.width(name) + 20;
        // "One More OS" gives way to the chip on a narrow monitor.
        if (x + font.width(os) + 6 + chip <= rx - 4) {
            g.drawString(font, os, x, y + 4, TEXT, false);
            x += font.width(os) + 6;
        }
        if (name != null) {
            rounded(g, x, y + 2, chip, TASKBAR - 4, HOVER);
            g.blitSprite(sprite("app_" + KEYS[index(app)]), x + 3, y + 3, 10, 10);
            g.drawString(font, name, x + 16, y + 4, TEXT, false);
        }
    }

    /** A dot between two items of the task bar's right side, left of x; returns where the next item ends. */
    private int separator(GuiGraphics g, int x, int y) {
        x -= 4 + font.width("\u00b7");
        g.drawString(font, "\u00b7", x, y + 4, DIM, false);
        return x - 4;
    }

    private static int index(int app) {
        return app == Computer.APP_MENU ? 0 : app == Computer.APP_SHOP ? 1 : app == Computer.APP_MANAGER ? 2 : app == Computer.APP_UPGRADES ? 3 : 4;
    }

    /** A rectangle with its corners cut by one pixel. */
    private static void rounded(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Centred text without a shadow. */
    private void centered(GuiGraphics g, Object text, int x, int y, int color) {
        if (text instanceof Component c) g.drawString(font, c, x - font.width(c) / 2, y, color, false);
        else g.drawString(font, text.toString(), x - font.width(text.toString()) / 2, y, color, false);
    }

    private void box(GuiGraphics g, Component message) {
        int w = Math.min(dw - 40, 200);
        var lines = font.split(message, w - 16);
        int h = 16 + lines.size() * 10, x = dx + (dw - w) / 2, y = dy + (dh - TASKBAR - h) / 2;
        rounded(g, x - 1, y - 1, w + 2, h + 2, EDGE);
        rounded(g, x, y, w, h, PANEL);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 8, y + 8 + i * 10, TEXT, false);
    }

    private void drawWindow(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int i = index(app);
        rounded(g, wx - 1, wy - 1, ww + 2, wh + 2, EDGE);
        rounded(g, wx, wy, ww, wh, PANEL);
        g.fill(wx + 1, wy, wx + ww - 1, wy + TITLE, TITLE_WASH);
        g.fill(wx + 1, wy + TITLE - 1, wx + ww - 1, wy + TITLE, EDGE);
        g.blitSprite(sprite("app_" + KEYS[i]), wx + 4, wy + 2, 14, 14);
        Component title = adding ? text("title.add") : building != null ? text(editingCombo == null ? "title.combo" : "combo_edit") : text("title." + KEYS[i]);
        g.drawString(font, title, wx + 22, wy + 5, TEXT, false);
        // The register, at the right of the title bar.
        String money = String.valueOf(state.balance());
        int mx = wx + ww - TITLE - 8 - font.width(money);
        g.drawString(font, money, mx, wy + 5, AMBER, false);
        g.pose().pushPose();
        g.pose().translate(mx - 12, wy + 4, 0);
        g.pose().scale(0.625f, 0.625f, 1);
        g.renderItem(state.currency(), 0, 0);
        g.pose().popPose();
        int bx = wx + ww - TITLE;
        boolean overClose = mouseX >= bx && mouseX < wx + ww && mouseY >= wy && mouseY < wy + TITLE;
        if (overClose) g.fill(bx, wy, wx + ww - 1, wy + TITLE, RED);
        g.drawString(font, "×", bx + (TITLE - font.width("×")) / 2 + 1, wy + 5, TEXT, false);
        hits.add(new Hit(bx, wy, TITLE, TITLE, this::back));

        cx = wx + 6;
        boolean chips = app == Computer.APP_MENU && !adding && building == null;
        cy = wy + TITLE + 5 + (hasSearch() ? SEARCH + 5 : 0);
        cw = ww - 12;
        ch = wy + wh - 6 - cy;
        if (hasSearch()) {
            int sy = wy + TITLE + 5;
            rounded(g, cx, sy, cw, SEARCH, FIELD);
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
                rounded(g, cx, y, SIDEBAR, 16, on ? AMBER : over ? HOVER : 0);
                g.drawString(font, font.plainSubstrByWidth(label.getString(), SIDEBAR - 22), cx + 5, y + 4, on ? DARK : over ? TEXT : DIM, false);
                String count = String.valueOf(n);
                g.drawString(font, count, cx + SIDEBAR - 5 - font.width(count), y + 4, on ? DARK : DIM, false);
                hits.add(new Hit(cx, y, SIDEBAR, 16, () -> { filter = f; scroll = 0; }));
                y += 18;
            }
            g.fill(cx + SIDEBAR + 3, cy, cx + SIDEBAR + 4, cy + ch, EDGE);
            cx += SIDEBAR + 8;
            cw -= SIDEBAR + 8;
        }
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        ItemStack hovered = switch (app) {
            case Computer.APP_MENU -> adding ? drawPicker(g, mouseX, mouseY) : building != null ? drawBuilder(g, mouseX, mouseY)
                    : filter.equals("combos") ? drawCombos(g, mouseX, mouseY) : drawMenu(g, mouseX, mouseY);
            case Computer.APP_SHOP -> drawShop(g, mouseX, mouseY);
            case Computer.APP_UPGRADES -> { drawUpgrades(g, mouseX, mouseY); yield ItemStack.EMPTY; }
            case Computer.APP_STREAM -> { drawStream(g, mouseX, mouseY, partialTick); yield ItemStack.EMPTY; }
            default -> { drawManager(g, mouseX, mouseY); yield ItemStack.EMPTY; }
        };
        g.disableScissor();
        if (System.currentTimeMillis() - messageTime < 3500 && !message.getString().isEmpty()) {
            var lines = font.split(message, ww - 28);
            int h = 8 + lines.size() * 10, y = wy + wh - h - 6;
            rounded(g, wx + 8, y, ww - 16, h, 0xF0181210);
            for (int l = 0; l < lines.size(); l++) g.drawString(font, lines.get(l), wx + 14, y + 5 + l * 10, AMBER, false);
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
        rounded(g, x, y, w, h, !active ? TRACK : over ? hover : color);
        boolean primary = color == AMBER;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, !active ? GREY : primary ? DARK : TEXT, false);
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
        rounded(g, x, y, w, CARD_H, over ? HOVER : CARD);
        rounded(g, x + 3, y + 3, w - 6, 38, PICTURE);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - 16, y + 6, 0);
        g.pose().scale(2, 2, 1);
        g.renderItem(picture, 0, 0);
        if (!decorations.isEmpty()) g.renderItemDecorations(font, decorations, 0, 0);
        g.pose().popPose();
        String name = picture.getHoverName().getString();
        g.drawString(font, font.plainSubstrByWidth(name, w - 8), x + 4, y + 45, TEXT, false);
        return over;
    }

    private void price(GuiGraphics g, int x, int y, int price) {
        g.pose().pushPose();
        g.pose().translate(x, y - 1, 0);
        g.pose().scale(0.625f, 0.625f, 1);
        g.renderItem(state.currency(), 0, 0);
        g.pose().popPose();
        g.drawString(font, String.valueOf(price), x + 12, y, AMBER, false);
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
                rounded(g, x, y, w, ROW, over ? HOVER : CARD_DIM);
                g.blitSprite(sprite("add"), x + 5, y + 5, 12, 12);
                g.drawString(font, text("add"), x + 24, y + 7, over ? TEXT : DIM, false);
                clipHit(x, y, w, ROW, () -> { adding = true; scroll = 0; search.setValue(""); });
                continue;
            }
            Computer.MenuLine line = lines.get(i);
            rounded(g, x, y, w, ROW, over ? HOVER : CARD);
            g.renderItem(line.icon(), x + 3, y + 3);
            if (inside(mouseX, mouseY, x + 3, y + 3, 16, 16)) hovered = line.icon();
            // Right side, from the right edge: state widget, then the price.
            int right = x + w - 5;
            switch (line.state()) {
                case Computer.AVAILABLE -> { toggle(g, right - 18, y + 6, line.on(), mouseX, mouseY, () -> send(Computer.TOGGLE_DISH, line.id())); right -= 24; }
                case Computer.LOCKED_DAY -> {
                    Component day = text("locked", line.day());
                    g.drawString(font, day, right - font.width(day), y + 7, DIM, false);
                    right -= font.width(day) + 6;
                }
                default -> {
                    Component unlock = text("unlock_cost", line.cost());
                    int uw = font.width(unlock) + 8;
                    button(g, right - uw, y + 4, uw, 14, unlock, AMBER, AMBER_HOVER, state.balance() >= line.cost(), mouseX, mouseY, () -> send(Computer.UNLOCK, line.id()));
                    right -= uw + 6;
                }
            }
            if (line.custom()) {
                int pw = font.width(String.valueOf(line.price())) + 12;
                button(g, right - 11, y + 5, 11, 12, Component.literal("+"), TRACK, HOVER, true, mouseX, mouseY,
                        () -> send(Computer.SET_PRICE, line.id() + "," + (line.price() + step())));
                price(g, right - 13 - pw, y + 7, line.price());
                button(g, right - 27 - pw, y + 5, 11, 12, Component.literal("-"), TRACK, HOVER, line.price() > 1, mouseX, mouseY,
                        () -> send(Computer.SET_PRICE, line.id() + "," + Math.max(1, line.price() - step())));
                right -= 31 + pw;
                if (over) button(g, x + w - 12, y + 1, 9, 9, Component.literal("\u00d7"), CLOSE, RED, true, mouseX, mouseY, () -> send(Computer.REMOVE_DISH, line.id()));
            } else {
                int pw = font.width(String.valueOf(line.price())) + 12;
                price(g, right - pw, y + 7, line.price());
                right -= pw + 6;
            }
            int nameW = right - (x + 24) - 4;
            g.drawString(font, font.plainSubstrByWidth(line.icon().getHoverName().getString(), nameW), x + 24, y + 3, line.on() || line.state() != Computer.AVAILABLE ? TEXT : DIM, false);
            Component kind = text("kind." + line.kind());
            boolean overKind = inside(mouseX, mouseY, x + 24, y + 12, font.width(kind), 9);
            g.drawString(font, kind, x + 24, y + 12, overKind ? AMBER_HOVER : DIM, false);
            clipHit(x + 24, y + 12, font.width(kind), 9, () -> send(Computer.SET_KIND, line.id() + "," + nextKind(line.kind())));
        }
        return hovered;
    }

    /** A switch: amber with the knob at the right when on, the track colour with the knob at the left when off. */
    private void toggle(GuiGraphics g, int x, int y, boolean on, int mouseX, int mouseY, Runnable action) {
        boolean over = inside(mouseX, mouseY, x, y, 18, 10);
        rounded(g, x, y, 18, 10, on ? (over ? AMBER_HOVER : AMBER) : (over ? HOVER : TRACK));
        int kx = on ? x + 10 : x + 2;
        rounded(g, kx, y + 2, 6, 6, on ? DARK : TEXT);
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
            g.drawString(font, "?", x + 11, y + 9, AMBER, false);
            g.pose().popPose();
        }
        return 18;
    }

    /** The combos as rows: their places joined by "+", the price (- and +), a cross that removes one, and a row that makes a new one. */
    private ItemStack drawCombos(GuiGraphics g, int mouseX, int mouseY) {
        List<Computer.ComboLine> combos = state.combos();
        int count = combos.size() + 1;
        int height = count * (ROW + 4) + 13 * (int) combos.stream().filter(c -> c.dishes().size() > COMBO_PLACES).count();
        scroll = Mth.clamp(scroll, 0, Math.max(0, height - 2 - ch));
        ItemStack hovered = ItemStack.EMPTY;
        int top = cy - (int) scroll;
        for (int i = 0; i < count; i++) {
            boolean oversized = i < combos.size() && combos.get(i).dishes().size() > COMBO_PLACES;
            int h = ROW + 2 + (oversized ? 13 : 0);
            int x = cx, y = top, w = cw;
            top += h + 2;
            if (y + h < cy || y > cy + ch) continue;
            boolean over = inside(mouseX, mouseY, x, y, w, h);
            if (i == combos.size()) {
                rounded(g, x, y, w, h, over ? HOVER : CARD_DIM);
                g.blitSprite(sprite("add"), x + 5, y + 6, 12, 12);
                g.drawString(font, text("combo_new"), x + 24, y + 8, over ? TEXT : DIM, false);
                clipHit(x, y, w, h, () -> { building = new ArrayList<>(); editingCombo = null; comboPrice = -1; scroll = 0; search.setValue(""); });
                continue;
            }
            Computer.ComboLine c = combos.get(i);
            rounded(g, x, y, w, h, over ? HOVER : CARD);
            int ix = x + 4;
            for (int k = 0; k < c.dishes().size(); k++) {
                if (k > 0) { g.drawString(font, "+", ix, y + 8, DIM, false); ix += 8; }
                if (inside(mouseX, mouseY, ix, y + 4, 16, 16)) hovered = slotIcon(c.dishes().get(k));
                ix += slotChip(g, c.dishes().get(k), ix, y + 4);
            }
            int pw = font.width(String.valueOf(c.price())) + 12, right = x + w - 5;
            button(g, right - 11, y + 6, 11, 12, Component.literal("+"), TRACK, HOVER, true, mouseX, mouseY,
                    () -> send(Computer.SET_COMBO_PRICE, c.id() + "," + (c.price() + step())));
            price(g, right - 13 - pw, y + 8, c.price());
            button(g, right - 27 - pw, y + 6, 11, 12, Component.literal("-"), TRACK, HOVER, c.price() > 1, mouseX, mouseY,
                    () -> send(Computer.SET_COMBO_PRICE, c.id() + "," + Math.max(1, c.price() - step())));
            if (over) button(g, x + w - 12, y + 1, 9, 9, Component.literal("\u00d7"), CLOSE, RED, true, mouseX, mouseY, () -> send(Computer.REMOVE_COMBO, c.id()));
            if (!c.orderable()) rounded(g, x, y, w, h, 0x88181210);
            if (oversized) {
                g.drawString(font, text("combo_too_large"), x + 5, y + ROW + 3, AMBER, false);
                Component edit = text("combo_edit");
                int editWidth = font.width(edit) + 10;
                button(g, right - editWidth, y + ROW, editWidth, 13, edit, TRACK, HOVER, true, mouseX, mouseY, () -> {
                    building = new ArrayList<>(c.dishes());
                    editingCombo = c.id();
                    comboPrice = c.price();
                    scroll = 0;
                    search.setValue("");
                });
            }
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
        rounded(g, cx, cy, lw, ch, CARD_DIM);
        int y = cy + 4;
        boolean oversized = building.size() > COMBO_PLACES;
        int placeSpace = ch - (oversized ? 26 : 42);
        boolean compact = oversized || building.size() * 30 - 8 > placeSpace;
        int placeHeight = compact ? Mth.clamp(placeSpace / building.size(), 16, 20) : 20;
        for (int k = 0; k < building.size(); k++) {
            String slot = building.get(k);
            boolean over = inside(mouseX, mouseY, cx + 3, y, lw - 6, placeHeight);
            rounded(g, cx + 3, y, lw - 6, placeHeight, over ? HOVER : CARD);
            int iconY = y + (placeHeight - 16) / 2;
            slotChip(g, slot, cx + 6, iconY);
            if (inside(mouseX, mouseY, cx + 6, iconY, 16, 16)) hovered = slotIcon(slot);
            g.drawString(font, font.plainSubstrByWidth(slotName(slot).getString(), lw - 46), cx + 26, iconY + 4, TEXT, false);
            int index = k;
            button(g, cx + lw - 16, iconY + 3, 10, 10, Component.literal("\u00d7"), CLOSE, RED, true, mouseX, mouseY, () -> {
                if (building == builder && index < builder.size() && slot.equals(builder.get(index))) builder.remove(index);
            });
            y += placeHeight + (compact ? 0 : 2);
            if (!compact && k < building.size() - 1) { centered(g, "+", cx + lw / 2, y - 1, DIM); y += 8; }
        }
        // The hint, when it fits above the price row.
        if (building.size() < COMBO_PLACES && y + 16 <= cy + ch - 38) {
            g.drawString(font, text(building.isEmpty() ? "combo_first" : "combo_next"), cx + 6, y + 4, DIM, false);
            y += 16;
        }
        int sum = building.stream().mapToInt(this::slotPrice).sum();
        int suggested = Math.max(1, (int) Math.round(sum * 0.9));
        int p = comboPrice < 0 ? suggested : comboPrice;
        // The price row under the places, and the Create button at the bottom.
        int by = Math.min(y + 2, cy + ch - 38);
        if (!oversized && building.size() >= 2) {
            g.drawString(font, text("combo_price"), cx + 6, by + 3, DIM, false);
            int pw = font.width(String.valueOf(p)) + 12;
            button(g, cx + lw - 17, by, 11, 12, Component.literal("+"), TRACK, HOVER, true, mouseX, mouseY, () -> {
                if (building == builder && builder.size() >= 2) comboPrice = p + step();
            });
            price(g, cx + lw - 19 - pw, by + 2, p);
            button(g, cx + lw - 33 - pw, by, 11, 12, Component.literal("-"), TRACK, HOVER, p > 1, mouseX, mouseY, () -> {
                if (building == builder && builder.size() >= 2) comboPrice = Math.max(1, p - step());
            });
        }
        boolean canSave = building.size() >= 2 && building.size() <= COMBO_PLACES;
        button(g, cx + 3, cy + ch - 20, lw - 6, 16, text(editingCombo == null ? "combo_create" : "combo_save"),
                AMBER, AMBER_HOVER, canSave, mouseX, mouseY, () -> {
            if (building != builder || builder.size() < 2 || builder.size() > COMBO_PLACES) return;
            String data = String.join(";", builder) + "," + p;
            if (editingCombo == null) send(Computer.ADD_COMBO, data);
            else send(Computer.EDIT_COMBO, editingCombo + "," + data);
            building = null;
            filter = "combos";
        });
        // Right pane: kinds, then dishes.
        boolean full = building.size() >= COMBO_PLACES;
        int kx = rx, ky = cy;
        g.drawString(font, text("combo_kinds"), rx, ky, DIM, false);
        ky += 11;
        for (String kind : new String[] {"main", "drink", "starter", "dessert"}) {
            Component label = text("any." + kind);
            int w = font.width(label) + 10;
            if (kx + w > rx + rw) { kx = rx; ky += 16; }
            String slot = "kind:" + kind;
            button(g, kx, ky, w, 14, label, CARD, HOVER, !full && state.menu().stream().anyMatch(l -> l.kind().equals(kind)), mouseX, mouseY, () -> {
                if (building == builder && builder.size() < COMBO_PLACES) builder.add(slot);
            });
            kx += w + 3;
        }
        ky += 20;
        g.drawString(font, text("combo_dishes_title"), rx, ky, DIM, false);
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
            rounded(g, x, yy, size, size, picked ? PICKED : over ? HOVER : CARD);
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
            rounded(g, x, y, size, size, added ? PICKED : over ? HOVER : CARD);
            g.renderItem(stack, x + (size - 16) / 2, y + (size - 16) / 2);
            if (over) hovered = stack;
            if (!added) clipHit(x, y, size, size, () -> { send(Computer.ADD_DISH, id + "," + suggested(stack)); adding = false; scroll = 0; search.setValue(""); });
        }
        if (list.isEmpty()) centered(g, text("empty"), cx + cw / 2, cy + 20, DIM);
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
        rounded(g, cx, y, cw, bar, CARD_DIM);
        g.blitSprite(sprite("app_shop"), cx + 4, y + 3, 16, 16);
        int n = cartCount();
        g.drawString(font, text("cart_count", n), cx + 24, y + 3, TEXT, false);
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
            button(g, cx + cw - 4 - kw, y + 4, kw, 14, checkout, AMBER, AMBER_HOVER, can, mouseX, mouseY, () -> {
                pendingCheckout = ++nextCheckoutId;
                PacketDistributor.sendToServer(new Computer.Request(state.pos(), Computer.BUY_CART, encoded.toString(), total, pendingCheckout));
            });
            button(g, cx + cw - 8 - kw - aw, y + 4, aw, 14, action, TRACK, HOVER, true, mouseX, mouseY, () -> { cartOpen = false; scroll = 0; });
        } else {
            button(g, cx + cw - 4 - aw, y + 4, aw, 14, action, AMBER, AMBER_HOVER, n > 0, mouseX, mouseY, () -> { cartOpen = true; scroll = 0; });
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
            rounded(g, cx, y, cw, 14, 0x50E0614A);
            g.drawString(font, font.plainSubstrByWidth(text("no_delivery_short").getString(), cw - 8), cx + 4, y + 3, TEXT, false);
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
                button(g, x + w - 15, by - 1, 11, 13, Component.literal("+"), AMBER, AMBER_HOVER,
                        pendingCheckout == 0 && inCart < Computer.MAX_CART_COUNT, mouseX, mouseY, () -> cart.merge(line.id(), 1, Integer::sum));
                String c = String.valueOf(inCart);
                g.drawString(font, c, x + w - 18 - font.width(c), by + 2, TEXT, false);
                button(g, x + w - 32 - font.width(c), by - 1, 11, 13, Component.literal("-"), TRACK, HOVER, pendingCheckout == 0, mouseX, mouseY,
                        () -> { if (cart.merge(line.id(), -1, Integer::sum) <= 0) cart.remove(line.id()); });
            } else {
                Component add = text("cart_add");
                int bw = font.width(add) + 8;
                button(g, x + w - 4 - bw, by - 1, bw, 13, add, AMBER, AMBER_HOVER, pendingCheckout == 0, mouseX, mouseY, () -> cart.put(line.id(), 1));
            }
        }
        g.disableScissor();
        if (lines.isEmpty()) centered(g, text("empty"), cx + cw / 2, cy + top + 20, DIM);
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
            rounded(g, x, y, cw, ROW, over ? HOVER : CARD);
            if (l != null) {
                g.renderItem(l.icon(), x + 3, y + 3);
                g.renderItemDecorations(font, l.stack(), x + 3, y + 3);
                if (inside(mouseX, mouseY, x + 3, y + 3, 16, 16)) hovered = l.stack();
            }
            int right = x + cw - 5;
            button(g, right - 10, y + 6, 10, 10, Component.literal("\u00d7"), CLOSE, RED,
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
            button(g, right - 11, y + 5, 11, 12, Component.literal("+"), TRACK, HOVER,
                    pendingCheckout == 0 && count < Computer.MAX_CART_COUNT, mouseX, mouseY, () -> cart.merge(id, 1, Integer::sum));
            String c = String.valueOf(count);
            g.drawString(font, c, right - 14 - font.width(c), y + 7, TEXT, false);
            button(g, right - 28 - font.width(c), y + 5, 11, 12, Component.literal("-"), TRACK, HOVER, pendingCheckout == 0, mouseX, mouseY,
                    () -> { if (cart.merge(id, -1, Integer::sum) <= 0) cart.remove(id); });
            int nameW = right - 32 - font.width(c) - (x + 24);
            g.drawString(font, font.plainSubstrByWidth(l.stack().getHoverName().getString(), nameW), x + 24, y + 7, TEXT, false);
        }
        g.disableScissor();
        if (ids.isEmpty()) centered(g, text("cart_empty"), cx + cw / 2, cy + 20, DIM);
        return hovered;
    }

    /** One row per upgrade: its name and effect, its level as dots, and the button that buys the next level. */
    private void drawUpgrades(GuiGraphics g, int mouseX, int mouseY) {
        int y = cy + 2;
        for (Computer.UpgradeLine u : state.upgrades()) {
            rounded(g, cx, y, cw, 30, CARD);
            g.drawString(font, text("upgrade." + u.id()), cx + 6, y + 5, TEXT, false);
            g.drawString(font, text("upgrade." + u.id() + ".effect"), cx + 6, y + 17, DIM, false);
            // Level dots, right of the name.
            int dx = cx + 10 + font.width(text("upgrade." + u.id()));
            for (int i = 0; i < u.max(); i++) rounded(g, dx + i * 7, y + 6, 5, 5, i < u.level() ? AMBER : TRACK);
            if (u.level() >= u.max()) {
                Component max = text("upgrade.max");
                g.drawString(font, max, cx + cw - 6 - font.width(max), y + 11, AMBER_HOVER, false);
            } else {
                Component buy = text("buy_cost", u.nextCost());
                int bw = font.width(buy) + 10;
                button(g, cx + cw - 6 - bw, y + 8, bw, 14, buy, AMBER, AMBER_HOVER, state.balance() >= u.nextCost(), mouseX, mouseY,
                        () -> send(Computer.BUY_UPGRADE, u.id()));
            }
            y += 34;
        }
    }

    private EditBox streamBox(EditBox old, int max) {
        EditBox box = new EditBox(font, 0, 0, 100, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(max);
        if (old != null) box.setValue(old.getValue());
        addWidget(box);
        return box;
    }

    private List<EditBox> streamBoxes() {
        return app == Computer.APP_STREAM ? List.of(channel, command) : List.of();
    }

    /**
     * The Stream app: the platform whose chat viewers become customers, the channel, the chat command, whether the reader
     * is connected and how many viewers wait in the pool. Save sets the server's config and connects again.
     */
    private void drawStream(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        List<String> st = state.stream();
        if (st.size() < 5) return;
        if (platform == null) {
            platform = st.get(0);
            channel.setValue(st.get(1));
            command.setValue(st.get(2));
        }
        int y = cy + 2;
        g.drawString(font, text("stream.platform"), cx + 2, y + 2, DIM, false);
        y += 13;
        int bw = (cw - 3 * 4) / 4;
        for (int i = 0; i < PLATFORMS.length; i++) {
            String p = PLATFORMS[i];
            boolean on = p.equals(platform);
            int bx = cx + i * (bw + 4);
            boolean over = inside(mouseX, mouseY, bx, y, bw, 18);
            rounded(g, bx, y, bw, 18, on ? PLATFORM_COLORS[i] : over ? HOVER : CARD);
            if (!on) g.fill(bx + 3, y + 7, bx + 7, y + 11, PLATFORM_COLORS[i]);
            Component label = text("stream." + p);
            g.drawString(font, label, bx + (bw - font.width(label)) / 2 + (on ? 0 : 3), y + 5, on && i == 2 ? 0xFF101010 : TEXT, false);
            clipHit(bx, y, bw, 18, () -> platform = p);
        }
        y += 24;
        boolean needsChannel = !platform.equals("none");
        for (int k = 0; k < 2; k++) {
            EditBox box = k == 0 ? channel : command;
            g.drawString(font, text(k == 0 ? "stream.channel." + platform : "stream.command"), cx + 2, y, DIM, false);
            y += 11;
            boolean active = k == 1 || needsChannel;
            rounded(g, cx, y, cw, 16, box.isFocused() ? FIELD_FOCUS : FIELD);
            if (box.isFocused()) g.fill(cx + 1, y + 15, cx + cw - 1, y + 16, AMBER);
            box.setX(cx + 6);
            box.setY(y + 4);
            box.setWidth(cw - 12);
            box.setEditable(active);
            box.setTextColor(active ? TEXT : GREY);
            box.render(g, mouseX, mouseY, partialTick);
            y += 22;
        }
        // The state of the reader.
        boolean on = st.get(3).equals("on");
        rounded(g, cx, y, cw, 20, CARD);
        rounded(g, cx + 6, y + 7, 6, 6, on ? RIPE : st.get(0).equals("none") ? TRACK : RED);
        Component status = on ? text("stream.status_on", st.get(0), st.get(1), st.get(4)) : text("stream.status_off");
        g.drawString(font, font.plainSubstrByWidth(status.getString(), cw - 24), cx + 18, y + 6, on ? TEXT : DIM, false);
        y += 26;
        Component save = text("stream.save");
        button(g, cx, y, cw, 18, save, AMBER, AMBER_HOVER, true, mouseX, mouseY,
                () -> send(Computer.SET_STREAM, platform + "\n" + channel.getValue() + "\n" + command.getValue()));
        y += 24;
        var help = font.split(text("stream.help", command.getValue().isBlank() ? "!omc" : command.getValue().strip()), cw - 4);
        for (var line : help) {
            g.drawString(font, line, cx + 2, y, DIM, false);
            y += 10;
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
            g.drawString(font, Component.translatable("onemorecore." + keys[i]), cx + 18, y + 6, TEXT, false);
            g.blitSprite(sprite(set[i] ? "check" : "cross"), cx + cw - 16, y + 6, 8, 8);
            y += 23;
        }
        // Tables: how many of them customers may use.
        long tables = state.surfaces().size(), off = state.offSurfaces().size();
        rounded(g, cx, y, cw, 20, CARD);
        g.fill(cx + 6, y + 7, cx + 12, y + 13, 0xFFE05555);
        g.drawString(font, Component.translatable("onemorecore.free.tables"), cx + 18, y + 6, TEXT, false);
        String count = (tables - off) + "/" + tables;
        g.drawString(font, count, cx + cw - 8 - font.width(count), y + 6, DIM, false);
        y += 27;
        button(g, cx, y, cw, 20, text("edit_places"), AMBER, AMBER_HOVER, pendingCheckout == 0, mouseX, mouseY, ComputerCamera::startFree);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (shutdownStart >= 0) return true;
        if (booting) {
            skipBoot();
            return true;
        }
        if (button == 0) {
            for (Hit h : hits) {
                if (h.contains(mouseX, mouseY)) {
                    hits.clear();
                    h.action().run();
                    return true;
                }
            }
        }
        for (EditBox box : streamBoxes()) {
            boolean on = box.isMouseOver(mouseX, mouseY) && (box == command || !"none".equals(platform));
            box.setFocused(on);
            if (on) {
                setFocused(box);
                return box.mouseClicked(mouseX, mouseY, button);
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
        if (booting || shutdownStart >= 0) return true;
        scroll = Math.max(0, scroll - scrollY * 16);
        hits.clear();
        return true;
    }

    /**
     * Escape: the picker, then the window, then the computer (the CRT turns off first). Typing goes to the search bar.
     * During the boot any key but a movement key skips to the desktop.
     */
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (shutdownStart >= 0) return true;
        if (booting) {
            if (movementKey(key, scanCode)) return false;
            skipBoot();
            swallowChar = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (adding || building != null || app != 0) back();
            else {
                shutdownStart = Util.getMillis();
                fadeStart = -1;
                setFocused(null);
            }
            return true;
        }
        if (hasSearch() && search.keyPressed(key, scanCode, modifiers)) return true;
        for (EditBox box : streamBoxes()) if (box.isFocused() && box.keyPressed(key, scanCode, modifiers)) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }

    private boolean movementKey(int key, int scanCode) {
        var o = minecraft.options;
        for (KeyMapping k : new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint})
            if (k.matches(key, scanCode)) return true;
        return false;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (booting || shutdownStart >= 0 || swallowChar) {
            swallowChar = false;
            return true;
        }
        for (EditBox box : streamBoxes()) if (box.isFocused()) return box.charTyped(c, modifiers);
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
