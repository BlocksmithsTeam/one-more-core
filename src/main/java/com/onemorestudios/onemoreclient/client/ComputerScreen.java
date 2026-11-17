package com.onemorestudios.onemoreclient.client;

import java.util.List;

import com.onemorestudios.onemoreclient.Computer;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** The screen of the Restaurant Computer: a main menu of apps and one view per app. It shows the last state from the server. */
public class ComputerScreen extends Screen {
    private static final int W = 320, H = 212, ROWS = 6, ROW = 20;
    private static final int TEXT = 0xFFE0E0E0, DIM = 0xFF9A9A9A;
    private static final int[] APPS = {Computer.APP_MENU, Computer.APP_SHOP, Computer.APP_MANAGER};
    private static final String[] APP_KEYS = {"menu", "shop", "manager"};

    private Computer.State state;
    /** 0 is the main menu, otherwise one of Computer.APP_*. */
    private int app;
    private int page, left, top;

    private ComputerScreen(Computer.State state) {
        super(Component.translatable("block.onemoreclient.computer"));
        this.state = state;
    }

    /** Called for each state payload from the server. */
    public static void receive(Computer.State s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ComputerScreen screen && screen.state.pos().equals(s.pos())) {
            screen.state = s;
            screen.rebuildWidgets();
        } else if (s.open()) {
            mc.setScreen(new ComputerScreen(s));
        }
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("onemoreclient.computer." + key, args);
    }

    private void send(int action, String id) {
        PacketDistributor.sendToServer(new Computer.Request(state.pos(), action, id));
    }

    private int rows() {
        return app == Computer.APP_MENU ? state.menu().size() : app == Computer.APP_SHOP ? state.shop().size() : 0;
    }

    private int rowY(int row) {
        return top + 42 + row * ROW;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int bottom = top + H - 26;
        if (!state.found() || app == 0) {
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(left + W / 2 - 40, bottom, 80, 20).build());
            if (!state.found()) return;
            for (int i = 0; i < APPS.length; i++) {
                int bit = APPS[i];
                Button button = addRenderableWidget(Button.builder(text("app." + APP_KEYS[i]), b -> {
                    app = bit;
                    page = 0;
                    send(Computer.REFRESH, "");
                    rebuildWidgets();
                }).bounds(left + 28 + i * 96, top + 104, 72, 20).build());
                button.active = (state.apps() & bit) != 0;
                if (!button.active) button.setTooltip(Tooltip.create(text("app.disabled")));
            }
            return;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> {
            app = 0;
            rebuildWidgets();
        }).bounds(left + 8, bottom, 60, 20).build());
        // ponytail: pages instead of a scroll list; use an ObjectSelectionList if the lists get long.
        int pages = Math.max(1, (rows() + ROWS - 1) / ROWS);
        page = Math.min(page, pages - 1);
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(left + W - 52, bottom, 20, 20).build()).active = page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(left + W - 28, bottom, 20, 20).build()).active = page < pages - 1;
        }
        for (int row = 0; row < ROWS && page * ROWS + row < rows(); row++) {
            int index = page * ROWS + row;
            if (app == Computer.APP_MENU) {
                Computer.MenuLine line = state.menu().get(index);
                if (line.state() != Computer.BUYABLE) continue;
                addRenderableWidget(Button.builder(text("unlock"), b -> send(Computer.UNLOCK, line.id())).bounds(left + 250, rowY(row) - 1, 62, 18).build())
                        .active = state.balance() >= line.cost();
            } else {
                Computer.ShopLine line = state.shop().get(index);
                addRenderableWidget(Button.builder(text("buy"), b -> send(Computer.BUY, line.id())).bounds(left + 250, rowY(row) - 1, 62, 18).build())
                        .active = state.balance() >= line.price();
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + H, 0xF0101820);
        g.fill(left, top, left + W, top + 22, 0xFF24364A);
        g.renderOutline(left, top, W, H, 0xFF5A7A9A);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Component heading = app == 0 ? title : text("title." + APP_KEYS[app == Computer.APP_MENU ? 0 : app == Computer.APP_SHOP ? 1 : 2]);
        g.drawString(font, heading, left + 8, top + 7, 0xFFFFFFFF);
        if (!state.found()) {
            g.drawCenteredString(font, text("outside"), left + W / 2, top + 90, TEXT);
            return;
        }
        Component balance = text("balance", state.balance());
        g.drawString(font, balance, left + W - 28 - font.width(balance), top + 7, 0xFFFFFFFF);
        g.renderItem(state.currency(), left + W - 24, top + 3);
        g.drawWordWrap(font, state.message(), left + 8, top + H - 48, W - 16, 0xFFFFE080);

        ItemStack hovered = ItemStack.EMPTY;
        if (app == 0) {
            ItemStack[] icons = {new ItemStack(Items.WRITABLE_BOOK), new ItemStack(Items.CHEST), new ItemStack(OneMoreClient.OPEN_SIGN_ITEM.get())};
            for (int i = 0; i < APPS.length; i++) {
                // The icon is drawn at double size above the button of the app.
                g.pose().pushPose();
                g.pose().translate(left + 48 + i * 96, top + 62, 0);
                g.pose().scale(2, 2, 1);
                g.renderItem(icons[i], 0, 0);
                g.pose().popPose();
                if ((state.apps() & APPS[i]) == 0) g.fill(left + 48 + i * 96, top + 62, left + 80 + i * 96, top + 94, 0xB0101820);
            }
        } else if (app == Computer.APP_MANAGER) {
            List<Component> info = state.info();
            for (int i = 0; i < info.size(); i++) g.drawString(font, info.get(i), left + 10, top + 30 + i * 12, TEXT);
            g.drawWordWrap(font, text("delivery_hint"), left + 10, top + 34 + info.size() * 12, W - 20, DIM);
        } else {
            boolean menu = app == Computer.APP_MENU;
            g.drawString(font, text("col.item"), left + 30, top + 28, DIM);
            g.drawString(font, text("col.price"), left + (menu ? 136 : 200), top + 28, DIM);
            if (menu) {
                g.drawString(font, text("col.day"), left + 176, top + 28, DIM);
                g.drawString(font, text("col.cost"), left + 212, top + 28, DIM);
            }
            for (int row = 0; row < ROWS && page * ROWS + row < rows(); row++) {
                int index = page * ROWS + row, y = rowY(row);
                ItemStack icon, tooltip;
                String name;
                if (menu) {
                    Computer.MenuLine line = state.menu().get(index);
                    icon = tooltip = line.icon();
                    name = icon.isEmpty() ? line.id() : icon.getHoverName().getString();
                    g.drawString(font, String.valueOf(line.price()), left + 136, y + 4, TEXT);
                    g.drawString(font, String.valueOf(line.day()), left + 176, y + 4, TEXT);
                    g.drawString(font, line.cost() == 0 ? "-" : String.valueOf(line.cost()), left + 212, y + 4, TEXT);
                    if (line.state() == Computer.AVAILABLE) g.drawString(font, text("available"), left + 250, y + 4, 0xFF70E070);
                    if (line.state() == Computer.LOCKED_DAY) g.drawString(font, text("locked", line.day()), left + 250, y + 4, 0xFFE07070);
                } else {
                    Computer.ShopLine line = state.shop().get(index);
                    icon = line.icon();
                    tooltip = line.stack();
                    name = tooltip.getHoverName().getString() + (tooltip.getCount() > 1 ? " x" + tooltip.getCount() : "");
                    g.drawString(font, String.valueOf(line.price()), left + 200, y + 4, TEXT);
                }
                g.renderItem(icon, left + 8, y);
                g.drawString(font, font.plainSubstrByWidth(name, menu ? 100 : 164), left + 30, y + 4, TEXT);
                if (mouseX >= left + 8 && mouseX < left + (menu ? 130 : 194) && mouseY >= y && mouseY < y + 16) hovered = tooltip;
            }
            if (rows() == 0) g.drawCenteredString(font, text("empty"), left + W / 2, top + 80, DIM);
        }
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
