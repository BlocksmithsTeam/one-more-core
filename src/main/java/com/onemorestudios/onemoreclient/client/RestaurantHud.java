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
    private static final ResourceLocation PANEL = sprite("panel"), DAY = sprite("day"), CUSTOMER = sprite("customer"), CLOCK = sprite("clock"),
            SERVED = sprite("served"), LOST = sprite("lost");
    private static final int LINE = 11;

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "hud/" + name);
    }

    static void register(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.DEBUG_OVERLAY, ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "restaurant"), RestaurantHud::render);
    }

    private static net.minecraft.network.chat.MutableComponent text(String key, Object... args) {
        return Component.translatable("onemoreclient.hud." + key, args);
    }

    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFB0B0B0, PAD = 5;

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Status s = Status.client;
        // The server sends a status every 10 ticks. One second without one: the player left the restaurant.
        if (s == null || mc.options.hideGui || ComputerCamera.active() || mc.getDebugOverlay().showDebugScreen() || System.currentTimeMillis() - Status.clientTime > 1500) return;
        Font font = mc.font;
        Component day = text("day", s.day());
        Component state = s.open() ? text("open").withStyle(ChatFormatting.GREEN) : text("closed").withStyle(ChatFormatting.RED);
        String money = String.valueOf(s.register()), earned = s.open() && s.earned() > 0 ? "+" + s.earned() : "";
        // With a day length: the time of service left (counted on from the last status); otherwise the customers left.
        boolean timed = s.serviceLeft() > 0;
        long ticks = Math.max(0, s.serviceLeft() - (System.currentTimeMillis() - Status.clientTime) / 50);
        String left = timed ? String.format("%d:%02d", ticks / 1200, ticks / 20 % 60) : String.valueOf(s.left());
        String served = String.valueOf(s.served());
        String lost = s.maxLost() > 0 ? s.lost() + "/" + s.maxLost() : String.valueOf(s.lost());
        // Closed and outside the ready panel: one grey line that says what to do.
        Component hint = s.open() || ReadyHud.status() != null ? null
                : s.readyCount() > 0 ? text("ready", s.readyCount(), s.players().size()) : text("click_sign");

        // Lines: day and state; when open, the counters; the register; the hint.
        int counters = 3 * 12 + font.width(left) + font.width(served) + font.width(lost) + 2 * 8;
        int inner = Math.max(12 + font.width(day) + 12 + font.width(state), 12 + font.width(money) + (earned.isEmpty() ? 0 : 4 + font.width(earned)));
        if (s.open()) inner = Math.max(inner, counters);
        if (hint != null) inner = Math.max(inner, font.width(hint));
        int lines = 2 + (s.open() ? 1 : 0) + (hint != null ? 1 : 0);
        int x = 4, y = 4, width = inner + 2 * PAD;
        g.blitSprite(PANEL, x, y, width, 2 * PAD - 2 + lines * LINE);
        x += PAD;
        y += PAD;

        g.blitSprite(DAY, x, y, 9, 9);
        g.drawString(font, day, x + 12, y + 1, WHITE, false);
        g.drawString(font, state, x + inner - font.width(state), y + 1, WHITE, false);
        y += LINE;

        if (s.open()) {
            int cx = x;
            cx = counter(g, font, timed ? CLOCK : CUSTOMER, left, cx, y, timed && ticks < 600 ? 0xFFFFD866 : WHITE) + 8;
            cx = counter(g, font, SERVED, served, cx, y, WHITE) + 8;
            counter(g, font, LOST, lost, cx, y, s.maxLost() > 0 && s.lost() * 2 >= s.maxLost() ? 0xFFFF6A5A : WHITE);
            y += LINE;
        }

        g.pose().pushPose();
        g.pose().translate(x - 1, y - 1, 0);
        g.pose().scale(0.6875f, 0.6875f, 1);
        g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(s.currency())), 0, 0);
        g.pose().popPose();
        g.drawString(font, money, x + 12, y + 1, WHITE, false);
        if (!earned.isEmpty()) g.drawString(font, earned, x + 16 + font.width(money), y + 1, GREY, false);
        // Collected coins: "+N" next to the register, rising and fading for 1.5 seconds, like the pickup notices of items.
        long now = System.currentTimeMillis();
        var notices = com.onemorestudios.onemoreclient.TableItems.Collected.NOTICES;
        notices.removeIf(n -> now - n[1] > 1500);
        int nx = x + 16 + font.width(money) + (earned.isEmpty() ? 0 : font.width(earned) + 4);
        for (long[] n : notices) {
            float t = (now - n[1]) / 1500f;
            int alpha = (int) (255 * (1 - t * t));
            if (alpha < 8) continue;
            g.drawString(font, "+" + n[0], nx, (int) (y + 1 - 8 * t), (alpha << 24) | 0x7ADB64, true);
        }
        y += LINE;

        if (hint != null) g.drawString(font, hint, x, y + 1, GREY, false);
    }

    /** An icon and a number. Returns the x after the number. */
    private static int counter(GuiGraphics g, Font font, ResourceLocation icon, String value, int x, int y, int color) {
        g.blitSprite(icon, x, y, 9, 9);
        g.drawString(font, value, x + 12, y + 1, color, false);
        return x + 12 + font.width(value);
    }
}
