package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Status;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * When the restaurant the player is at opens, the screen darkens, "Day N" shows in the middle, and the dark goes again; a short
 * chime of three rising notes plays with it.
 */
final class DayFade {
    private DayFade() {}

    /** Seconds: the dark comes, stays with the text, goes. */
    private static final float IN = 0.6f, HOLD = 1.4f, OUT = 1.0f;
    /** The darkest the screen gets: the world shows a little through it. */
    private static final float DARK = 0.85f;
    /** The chime: a major chord, a note after the other. */
    private static final float[] NOTES = {1.0f, 1.26f, 1.5f};
    private static final long NOTE_GAP_MS = 140;

    private static BlockPos sign;
    private static boolean wasOpen = true;
    private static long start = -1;
    private static int day, notesPlayed;

    static void register(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.DEBUG_OVERLAY, ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "day_fade"), DayFade::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Status.Received near = Status.near(mc.player.blockPosition(), true);
        if (near != null) {
            Status s = near.status();
            // A restaurant first seen open (joined during its day) does not fade.
            if (!s.sign().equals(sign)) wasOpen = true;
            if (s.open() && !wasOpen) {
                start = System.currentTimeMillis();
                day = s.day();
                notesPlayed = 0;
            }
            sign = s.sign();
            wasOpen = s.open();
        }
        if (start < 0) return;
        long ms = System.currentTimeMillis() - start;
        while (notesPlayed < NOTES.length && ms >= notesPlayed * NOTE_GAP_MS)
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), NOTES[notesPlayed++], 0.6f));
        float t = ms / 1000f;
        if (t >= IN + HOLD + OUT) {
            start = -1;
            return;
        }
        float a = t < IN ? t / IN : t < IN + HOLD ? 1 : 1 - (t - IN - HOLD) / OUT;
        a = a * a * (3 - 2 * a);
        int w = g.guiWidth(), h = g.guiHeight();
        g.fill(0, 0, w, h, (int) (a * DARK * 255) << 24);
        int alpha = Mth.clamp((int) (a * 255), 4, 255);
        Component text = Component.translatable("onemorecore.open.started.title", day);
        g.pose().pushPose();
        g.pose().translate(w / 2f, h / 2f, 0);
        g.pose().scale(3, 3, 1);
        g.drawString(mc.font, text, -mc.font.width(text) / 2, -4, alpha << 24 | 0xFFFFFF, true);
        g.pose().popPose();
    }
}
