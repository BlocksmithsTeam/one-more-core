package com.onemorestudios.onemoreclient.client;

import com.onemorestudios.onemoreclient.OneMoreClient;
import com.onemorestudios.onemoreclient.Status;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** The restaurant panel at the top left. It shows while the server sends a {@link Status}, that is, near a restaurant. */
public class RestaurantHud {
    private static final ResourceLocation PANEL = sprite("panel"), DAY = sprite("day"), CUSTOMER = sprite("customer"),
            SERVED = sprite("served"), LOST = sprite("lost"), READY = sprite("ready");
    private static final int WIDTH = 112, LINE = 11;

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "hud/" + name);
    }

    static void register(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.DEBUG_OVERLAY, ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "restaurant"), RestaurantHud::render);
    }

    private static net.minecraft.network.chat.MutableComponent text(String key, Object... args) {
        return Component.translatable("onemoreclient.hud." + key, args);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Status s = Status.client;
        // The server sends a status every 10 ticks. One second without one: the player left the restaurant.
        if (s == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen() || System.currentTimeMillis() - Status.clientTime > 1500) return;
        Font font = mc.font;
        // Open: day, customers, served and lost, register. Closed: day, what to do next, register.
        int x = 4, y = 4, lines = s.open() ? 4 : 3;
        Component hint = s.needed() > 0 ? text("ready", s.ready(), s.needed()) : text("click_sign");
        int width = Math.max(WIDTH, font.width(s.open() ? text("left", s.left()) : hint) + 24);
        g.blitSprite(PANEL, x, y, width, 8 + lines * LINE);
        x += 6;
        y += 5;

        g.blitSprite(DAY, x, y, 9, 9);
        g.drawString(font, text("day", s.day()), x + 12, y + 1, 0xFFF3E2C7, false);
        Component state = s.open() ? text("open").withStyle(ChatFormatting.GREEN) : text("closed").withStyle(ChatFormatting.RED);
        g.drawString(font, state, x + width - 12 - font.width(state), y + 1, 0xFFFFFFFF, false);
        y += LINE;

        if (s.open()) {
            g.blitSprite(CUSTOMER, x, y, 9, 9);
            g.drawString(font, text("left", s.left()), x + 12, y + 1, 0xFFF3E2C7, false);
            y += LINE;
            g.blitSprite(SERVED, x, y, 9, 9);
            g.drawString(font, String.valueOf(s.served()), x + 12, y + 1, 0xFF8EE07A, false);
            int half = x + WIDTH / 2 - 4;
            g.blitSprite(LOST, half, y, 9, 9);
            String lost = s.maxLost() > 0 ? s.lost() + "/" + s.maxLost() : String.valueOf(s.lost());
            g.drawString(font, lost, half + 12, y + 1, s.maxLost() > 0 && s.lost() * 2 >= s.maxLost() ? 0xFFFF6A5A : 0xFFE8B0A0, false);
        } else {
            g.blitSprite(READY, x, y, 9, 9);
            g.drawString(font, hint, x + 12, y + 1, s.needed() > 0 ? 0xFFFFE27A : 0xFFB8A58A, false);
        }
        y += LINE;

        g.pose().pushPose();
        g.pose().translate(x - 1, y - 1, 0);
        g.pose().scale(0.6875f, 0.6875f, 1);
        g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(s.currency())), 0, 0);
        g.pose().popPose();
        String money = String.valueOf(s.register());
        g.drawString(font, money, x + 12, y + 1, 0xFFFFE27A, false);
        if (s.open() && s.earned() > 0) g.drawString(font, "+" + s.earned(), x + 16 + font.width(money), y + 1, 0xFF8EE07A, false);
    }
}
