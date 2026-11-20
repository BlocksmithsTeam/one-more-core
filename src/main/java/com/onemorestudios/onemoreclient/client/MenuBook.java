package com.onemorestudios.onemoreclient.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.MenuBoardBlockEntity;
import com.onemorestudios.onemoreclient.MenuBookBlockEntity;
import com.onemorestudios.onemoreclient.OneMoreClient;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The menu book: the menu on pages, two pages at a time, like a restaurant's menu folder. Each kind starts with its
 * heading (Main dishes, Starters, Drinks, Desserts, Combos); a line is the icons, the name and the price. The same
 * pages show in a book screen and, with Diegesis, on the open book on the table.
 */
public final class MenuBook {
    private MenuBook() {}

    static final int ROWS = 11;
    private static final String[] ORDER = {"main", "starter", "drink", "dessert", "combo"};

    /** A page: rows that are a heading (String kind) or a line. */
    static List<List<Object>> pages(List<MenuBoardBlockEntity.Line> lines) {
        List<Object> rows = new ArrayList<>();
        for (String kind : ORDER) {
            List<MenuBoardBlockEntity.Line> of = lines.stream().filter(l -> l.kind().equals(kind)).toList();
            if (of.isEmpty()) continue;
            if (!rows.isEmpty() && rows.size() % ROWS != 0) rows.add(null); // a gap before the next kind
            rows.add(kind);
            rows.addAll(of);
        }
        List<List<Object>> pages = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += ROWS) pages.add(rows.subList(i, Math.min(rows.size(), i + ROWS)));
        if (pages.isEmpty()) pages.add(List.of());
        return pages;
    }

    private static String name(MenuBoardBlockEntity.Line l) {
        return l.items().size() == 1 ? l.items().get(0).getHoverName().getString() : "";
    }

    // ---- Book screen (without Diegesis) ----

    static void register() {
        MenuBookBlockEntity.Open.handler = o -> Minecraft.getInstance().setScreen(new BookScreen(MenuBoardBlockEntity.parse(o.menu())));
        if (net.neoforged.fml.ModList.get().isLoaded("diegesis")) Behavior.register();
    }

    private static final ResourceLocation PAPER = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/gui/menu_book_paper.png");

    /** Two paper pages side by side on the brown cover, arrows below. */
    static final class BookScreen extends Screen {
        private final List<List<Object>> pages;
        private int spread;

        BookScreen(List<MenuBoardBlockEntity.Line> lines) {
            super(Component.translatable("block.onemoreclient.menu_book"));
            pages = pages(lines);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            // The paper is 3 by 4, as the pages of the book model.
            int ph = 14 + ROWS * 13, pw = ph * 3 / 4, x0 = width / 2 - pw - 6, y0 = (height - ph) / 2 - 8;
            g.fill(x0 - 6, y0 - 6, x0 + 2 * pw + 18, y0 + ph + 6, 0xFF4D3723);
            g.fill(x0 - 4, y0 - 4, x0 + 2 * pw + 16, y0 + ph + 4, 0xFF5D4733);
            for (int side = 0; side < 2; side++) {
                int px = x0 + side * (pw + 12);
                g.blit(PAPER, px, y0, pw, ph, 0, 0, 72, 96, 128, 128);
                int index = spread * 2 + side;
                if (index < pages.size()) drawPage(g, pages.get(index), px + 6, y0 + 7, pw - 12);
                String number = String.valueOf(index + 1);
                if (index < pages.size()) g.drawString(font, number, px + pw / 2 - font.width(number) / 2, y0 + ph - 10, 0xFF9A8E78, false);
            }
            int spreads = (pages.size() + 1) / 2;
            if (spread > 0) g.drawString(font, "<", x0, y0 + ph + 10, mouseX < width / 2 ? 0xFFFFFFFF : 0xFFB0B0B0, true);
            if (spread < spreads - 1) g.drawString(font, ">", x0 + 2 * pw + 6, y0 + ph + 10, mouseX >= width / 2 ? 0xFFFFFFFF : 0xFFB0B0B0, true);
        }

        private void drawPage(GuiGraphics g, List<Object> rows, int x, int y, int w) {
            for (Object r : rows) {
                if (r instanceof String kind) {
                    g.drawString(font, Component.translatable("onemoreclient.board." + kind), x, y + 2, 0xFF6B4A2E, false);
                    g.fill(x, y + 11, x + w, y + 12, 0xFFD8CBB0);
                } else if (r instanceof MenuBoardBlockEntity.Line l) {
                    int ix = x;
                    for (ItemStack s : l.items()) {
                        g.pose().pushPose();
                        g.pose().translate(ix, y, 0);
                        g.pose().scale(0.75f, 0.75f, 1);
                        g.renderItem(s, 0, 0);
                        g.pose().popPose();
                        ix += 12;
                    }
                    String price = String.valueOf(l.price());
                    g.drawString(font, price, x + w - font.width(price), y + 2, 0xFF3A2E22, false);
                    int nameW = x + w - font.width(price) - 6 - (ix + 2);
                    g.drawString(font, font.plainSubstrByWidth(name(l), nameW), ix + 2, y + 2, 0xFF3A2E22, false);
                }
                y += 13;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int spreads = (pages.size() + 1) / 2;
            int next = mouseX < width / 2 ? spread - 1 : spread + 1;
            if (next >= 0 && next < spreads) {
                spread = next;
                Minecraft.getInstance().player.playSound(SoundEvents.BOOK_PAGE_TURN, 1, 1);
            }
            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            int spreads = (pages.size() + 1) / 2;
            spread = Math.max(0, Math.min(spreads - 1, spread - (int) Math.signum(scrollY)));
            return true;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    // ---- Diegesis: the open book on the table ----

    /** Page size in the scaled layout (three times the 6 x 8 pages of the model); the book is two pages wide. */
    private static final float PW = 18, PH = 24;

    /** Clicks on the left or right page turn back or on; the wheel turns too. */
    static final class Behavior implements StationBehavior {
        private int spread;
        private List<List<Object>> pages = List.of(List.of());

        static void register() {
            DiegesisClient.registerBehavior(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "menu_book"), view -> new Behavior());
        }

        @Override
        public void onSceneReady(StationView view) {
            if (view.level().getBlockEntity(view.pos()) instanceof MenuBookBlockEntity book) pages = pages(book.lines);
            spread = 0;
            view.node("book").ifPresent(n -> n.setPainter((pose, buffers, light) -> paint(pose, buffers, light)));
        }

        @Override
        public void tick(StationView view) {
            if (view.level().getBlockEntity(view.pos()) instanceof MenuBookBlockEntity book) pages = pages(book.lines);
            spread = Math.min(spread, Math.max(0, (pages.size() - 1) / 2));
        }

        @Override
        public void onEvent(StationView view, SceneEvent event) {
            if (!(event instanceof SceneEvent.Click click)) return;
            int spreads = (pages.size() + 1) / 2;
            int next = "left_page".equals(click.node()) ? spread - 1 : "right_page".equals(click.node()) ? spread + 1 : spread;
            if (next != spread && next >= 0 && next < spreads) {
                spread = next;
                Minecraft.getInstance().player.playSound(SoundEvents.BOOK_PAGE_TURN, 1, 1);
            }
        }

        /** In node pixels: the book lies flat, its spine along z, read from the -z side (the front of the scene). */
        private void paint(PoseStack pose, MultiBufferSource buffers, int light) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.mulPose(Axis.XP.rotationDegrees(90));
            // Now x runs to the reader's right and y towards the reader, on the paper of the open book (menu_book_open:
            // two 6 x 8 pages); the layout is drawn three times as large and scaled down.
            pose.scale(1 / 3f, 1 / 3f, 1 / 3f);
            pose.translate(0, 0, -0.05f);
            Font font = Minecraft.getInstance().font;
            for (int side = 0; side < 2; side++) {
                float x0 = side == 0 ? -PW : 0.3f;
                int index = spread * 2 + side;
                if (index >= pages.size()) continue;
                pose.pushPose();
                pose.translate(x0 + 1, -PH / 2 + 1.2f, -0.05f);
                float s = 0.16f;
                float y = 0, rowH = (PH - 3) / ROWS;
                for (Object r : pages.get(index)) {
                    if (r instanceof String kind) text(font, pose, buffers, Component.translatable("onemoreclient.board." + kind).getString(), 0, y, s, 0xFF6B4A2E, light);
                    else if (r instanceof MenuBoardBlockEntity.Line l) {
                        float ix = 0;
                        for (ItemStack st : l.items()) {
                            pose.pushPose();
                            pose.translate(ix + 0.8f, y + 0.8f, 0);
                            pose.scale(1.5f, -1.5f, 0.01f);
                            Minecraft.getInstance().getItemRenderer().renderStatic(st, ItemDisplayContext.GUI, light, OverlayTexture.NO_OVERLAY, pose, buffers, null, 0);
                            pose.popPose();
                            ix += 1.7f;
                        }
                        String price = String.valueOf(l.price());
                        float priceX = PW - 2.4f - font.width(price) * s;
                        text(font, pose, buffers, price, priceX, y, s, 0xFF3A2E22, light);
                        int room = (int) ((priceX - ix - 0.6f) / s);
                        if (room > 6) text(font, pose, buffers, font.plainSubstrByWidth(name(l), room), ix + 0.3f, y, s, 0xFF3A2E22, light);
                    }
                    y += rowH;
                }
                pose.popPose();
            }
            pose.popPose();
        }

        private static void text(Font font, PoseStack pose, MultiBufferSource buffers, String text, float x, float y, float s, int color, int light) {
            pose.pushPose();
            pose.translate(x, y, 0);
            pose.scale(s, s, s);
            font.drawInBatch(text, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
            pose.popPose();
        }
    }
}
