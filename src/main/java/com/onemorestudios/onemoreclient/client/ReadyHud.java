package com.onemorestudios.onemoreclient.client;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.onemorestudios.onemoreclient.OneMoreClient;
import com.onemorestudios.onemoreclient.ReadyRequest;
import com.onemorestudios.onemoreclient.Status;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The ready screen of a closed restaurant, like the ready-up of PlateUp: a card for each player inside the area with a
 * ready mark, a "hold the key" prompt with a progress bar, and the countdown before the day starts.
 */
public class ReadyHud {
    public static final KeyMapping KEY = new KeyMapping("key.onemoreclient.ready", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.onemoreclient");
    /** Ticks the key must be held. */
    static final int HOLD = 20;
    private static final ResourceLocation CARD = sprite("card"), CARD_READY = sprite("card_ready"),
            CHECK = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "hud/served"),
            PANEL = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "hud/panel");
    private static final int CARD_H = 22;

    private static int held;
    /** True after a hold sent a request, until the key is released. One hold sends one request. */
    private static boolean sent;

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "ready/" + name);
    }

    static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.DEBUG_OVERLAY, ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "ready"), ReadyHud::render);
    }

    /**
     * The fresh status of a closed restaurant whose area contains this player and in which a player clicked the sign
     * (at least one player is ready), or null.
     */
    static Status status() {
        Minecraft mc = Minecraft.getInstance();
        Status s = Status.client;
        if (s == null || s.open() || s.readyCount() == 0 || mc.player == null || System.currentTimeMillis() - Status.clientTime > 1500) return null;
        return me(s) == null ? null : s;
    }

    private static Status.Member me(Status s) {
        Minecraft mc = Minecraft.getInstance();
        for (Status.Member m : s.players()) if (mc.player != null && m.id().equals(mc.player.getUUID())) return m;
        return null;
    }

    /** Counts the held ticks. A full hold toggles the ready state on the server. */
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Status s = status();
        if (s == null || mc.screen != null || !KEY.isDown()) {
            held = 0;
            sent = false;
            return;
        }
        if (sent || ++held < HOLD) return;
        PacketDistributor.sendToServer(new ReadyRequest(!me(s).ready()));
        sent = true;
        held = 0;
    }

    /** Ticks left until the day starts, counted on from the last status. */
    static float countdownLeft(Status s) {
        if (s.countdown() <= 0) return 0;
        return Math.max(0, s.countdown() - (System.currentTimeMillis() - Status.clientTime) / 50f);
    }

    /** Face, name and a slot for the ready check, so a card keeps its width when the player gets ready. */
    private static int cardWidth(Font font, Status.Member m) {
        return 3 + 16 + 4 + font.width(m.name()) + 4 + 9 + 3;
    }

    private static PlayerSkin skin(Status.Member m) {
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(m.id());
        return info == null ? DefaultPlayerSkin.get(m.id()) : info.getSkin();
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Status s = status();
        if (s == null || mc.options.hideGui) return;
        Font font = mc.font;
        List<Status.Member> players = s.players();
        Status.Member me = me(s);
        float left = countdownLeft(s);

        // Bottom centre, above the hotbar and the action bar text.
        Component title = Component.translatable("onemoreclient.ready.title", s.day());
        Component prompt = Component.translatable(me.ready() ? "onemoreclient.ready.cancel" : "onemoreclient.ready.hold");
        // The key as a glyph (a key cap, or a mouse if the player bound a mouse button), then the action.
        int keyW = KeyGlyphs.width(font, KEY);
        int promptW = keyW + 4 + font.width(prompt);
        // Cards: face, ready mark and name side by side; the cards wrap into rows that fit the screen.
        int maxRow = Math.max(80, g.guiWidth() - 40);
        List<List<Status.Member>> rows = new java.util.ArrayList<>();
        List<Integer> rowWidths = new java.util.ArrayList<>();
        int rowW = 0;
        for (Status.Member m : players) {
            int cw = cardWidth(font, m);
            if (rows.isEmpty() || rowW + 4 + cw > maxRow) { rows.add(new java.util.ArrayList<>()); rowWidths.add(0); rowW = -4; }
            rows.get(rows.size() - 1).add(m);
            rowW += 4 + cw;
            rowWidths.set(rows.size() - 1, rowW);
        }
        int cardsW = rowWidths.stream().mapToInt(Integer::intValue).max().orElse(0);
        int w = Math.max(Math.max(cardsW, promptW), font.width(title)) + 16;
        int h = 16 + rows.size() * (CARD_H + 3) + 4 + 18;
        int x = (g.guiWidth() - w) / 2, y = g.guiHeight() - 72 - h;
        g.blitSprite(PANEL, x, y, w, h);
        g.drawString(font, title, x + (w - font.width(title)) / 2, y + 5, 0xFFFFFFFF, false);
        int cy = y + 16;
        for (int r = 0; r < rows.size(); r++) {
            int cx = x + (w - rowWidths.get(r)) / 2;
            for (Status.Member m : rows.get(r)) {
                int cw = cardWidth(font, m);
                g.blitSprite(m.ready() ? CARD_READY : CARD, cx, cy, cw, CARD_H);
                PlayerFaceRenderer.draw(g, skin(m), cx + 3, cy + 3, 16);
                g.drawString(font, m.name(), cx + 23, cy + 7, 0xFFFFFFFF, false);
                if (m.ready()) g.blitSprite(CHECK, cx + cw - 12, cy + 6, 9, 9);
                cx += cw + 4;
            }
            cy += CARD_H + 3;
        }
        cy -= CARD_H + 3;

        // The key prompt and the progress of the hold under it. The countdown is the large number above the panel.
        int py = cy + CARD_H + 4, px = x + (w - promptW) / 2;
        KeyGlyphs.draw(g, font, px, py, KEY, 0xFFFFFFFF);
        g.drawString(font, prompt, px + keyW + 4, py + 3, 0xFFB0B0B0, false);
        float progress = sent ? 1 : Mth.clamp((held + (KEY.isDown() && !sent ? delta.getGameTimeDeltaPartialTick(false) : 0)) / HOLD, 0, 1);
        g.fill(px, py + 15, px + promptW, py + 16, 0x40FFFFFF);
        if (progress > 0) g.fill(px, py + 15, px + Math.round(promptW * progress), py + 16, me.ready() ? 0xFFE0705A : 0xFF7ADB64);

        // The countdown number above the panel, larger in the first part of each second.
        if (left > 0) {
            int second = Mth.ceil(left / 20);
            float phase = (left % 20) / 20f;
            float scale = 3 + 2 * phase;
            String text = String.valueOf(second);
            g.pose().pushPose();
            g.pose().translate(g.guiWidth() / 2f, y - 26, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, text, -font.width(text) / 2, -4, 0xFFFFFFFF, true);
            g.pose().popPose();
        }
    }
}
