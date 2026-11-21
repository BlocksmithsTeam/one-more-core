package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.ScreenSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

final class ScreenSettingsScreen extends Screen {
    private final ScreenSettings.Open screen;
    private int mode, kinds, size, tilt;
    private boolean paging;

    ScreenSettingsScreen(ScreenSettings.Open screen) {
        super(Component.literal("Screen settings"));
        this.screen = screen;
        mode = ScreenSettings.mode(screen.settings());
        kinds = ScreenSettings.kinds(screen.settings());
        size = ScreenSettings.size(screen.settings());
        paging = ScreenSettings.autoPages(screen.settings());
        tilt = screen.tilt();
    }

    private void option(int x, int y, int width, String label, Runnable change) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
            change.run();
            rebuildWidgets();
        }).bounds(x, y, width, 20).build());
    }

    @Override
    protected void init() {
        int x = width / 2 - 94, y = height / 2 - 74;
        option(x, y, 188, "Show: " + new String[]{"Off", "Menu", "Orders"}[mode], () -> mode = (mode + 1) % 3);
        option(x, y + 22, 92, "Size: " + new String[]{"Normal", "Small", "Large"}[size], () -> size = (size + 1) % 3);
        option(x + 96, y + 22, 92, paging ? "Auto pages: On" : "Auto pages: Off", () -> paging = !paging);
        for (int i = 0; i < ScreenSettings.KINDS.length; i++) {
            int bit = 1 << i;
            String kind = ScreenSettings.KINDS[i];
            option(x + i % 2 * 96, y + 44 + i / 2 * 22, 92, kind + (ScreenSettings.showsKind(kinds << 2, kind) ? ": On" : ": Off"),
                    () -> kinds = (kinds == 0 ? 31 : kinds) ^ bit);
        }
        if (tilt >= 0) option(x + 96, y + 88, 92, "Tilt: " + (tilt * 22.5) + "°", () -> tilt = (tilt + 1) % 3);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            PacketDistributor.sendToServer(new ScreenSettings.Save(screen.pos(), ScreenSettings.pack(mode, kinds, size, paging), tilt));
            onClose();
        }).bounds(x, y + 116, 188, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 90, 0xFFFFFFFF);
    }
}
