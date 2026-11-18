package com.onemorestudios.onemoreclient.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Input prompts drawn as icons, like the button icons of a game console: a key cap with the key's short name, or a mouse
 * with the pressed button lit. A key mapping shows the key that the player bound, so the prompt stays right after a
 * change in the controls. A prompt is a row of parts: KeyMapping, {@link Mouse}, a key label (String) or a Component.
 */
public final class KeyGlyphs {
    private KeyGlyphs() {}

    /** A mouse button or the wheel, for actions that are not key mappings. */
    public enum Mouse { LEFT, RIGHT, MIDDLE }

    static final int HEIGHT = 14;
    private static final ResourceLocation CAP = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "ready/key");

    private static ResourceLocation mouse(String name) {
        return ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "glyph/mouse_" + name);
    }

    /** The short name of a key: "Shift" for "Left Shift", "Ctrl", "Esc"; a mouse button gives null. */
    static String label(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) return null;
        return shorten(mapping.getTranslatedKeyMessage().getString());
    }

    static String shorten(String name) {
        return name.replace("Left ", "").replace("Right ", "").replace("Control", "Ctrl").replace("Escape", "Esc")
                .replace("Backspace", "Bksp").replace("Enter", "Enter");
    }

    private static Mouse mouseOf(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.getType() != InputConstants.Type.MOUSE) return null;
        return key.getValue() == 0 ? Mouse.LEFT : key.getValue() == 1 ? Mouse.RIGHT : Mouse.MIDDLE;
    }

    /** Width of one part, in GUI pixels. */
    static int width(Font font, Object part) {
        if (part instanceof KeyMapping m) {
            Mouse mouse = mouseOf(m);
            return mouse != null ? 11 : cap(font, label(m));
        }
        if (part instanceof Mouse) return 11;
        if (part instanceof String s) return cap(font, s);
        return font.width((Component) part);
    }

    private static int cap(Font font, String label) {
        return Math.max(14, font.width(label) + 8);
    }

    /** Draws one part with its top left at (x, y); returns its width. */
    static int draw(GuiGraphics g, Font font, int x, int y, Object part, int textColor) {
        if (part instanceof KeyMapping m) {
            Mouse mouse = mouseOf(m);
            return mouse != null ? drawMouse(g, x, y, mouse) : drawCap(g, font, x, y, label(m));
        }
        if (part instanceof Mouse mouse) return drawMouse(g, x, y, mouse);
        if (part instanceof String s) return drawCap(g, font, x, y, s);
        Component c = (Component) part;
        g.drawString(font, c, x, y + 3, textColor, true);
        return font.width(c);
    }

    private static int drawCap(GuiGraphics g, Font font, int x, int y, String label) {
        int w = cap(font, label);
        g.blitSprite(CAP, x, y, w, HEIGHT);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, 0xFFFFFFFF, false);
        return w;
    }

    private static int drawMouse(GuiGraphics g, int x, int y, Mouse mouse) {
        g.blitSprite(mouse(mouse.name().toLowerCase(java.util.Locale.ROOT)), x, y, 11, 14);
        return 11;
    }

    /** Width of a row: parts with 2 px between glyphs and 4 px before text. */
    static int width(Font font, List<Object> parts) {
        int w = 0;
        for (int i = 0; i < parts.size(); i++) w += width(font, parts.get(i)) + (i == 0 ? 0 : gap(parts.get(i - 1), parts.get(i)));
        return w;
    }

    private static int gap(Object before, Object part) {
        return part instanceof Component || before instanceof Component ? 4 : 2;
    }

    /** Draws a row; returns its width. */
    static int draw(GuiGraphics g, Font font, int x, int y, List<Object> parts, int textColor) {
        int start = x;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) x += gap(parts.get(i - 1), parts.get(i));
            x += draw(g, font, x, y, parts.get(i), textColor);
        }
        return x - start;
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
