package com.onemorestudios.onemoreclient.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.MenuBoardBlock;
import com.onemorestudios.onemoreclient.MenuBoardBlockEntity;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The menu board: a chalkboard 2 blocks wide and 1.5 high on the wall, with "MENU", then the dishes by kind (an icon,
 * the name and the price) in two columns, and the combos. The text gets smaller when the menu is long.
 */
public class MenuBoardRenderer implements BlockEntityRenderer<MenuBoardBlockEntity> {
    private static final RenderType BOARD = RenderType.entityCutout(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/block/menu_board.png"));
    private static final String[] ORDER = {"main", "starter", "drink", "dessert", "combo"};
    /** Board size in blocks, and the frame inside it. */
    static final float W = 2, H = 1.5f, FRAME = 2 / 16f;

    private final Font font;

    public MenuBoardRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    @Override
    public boolean shouldRenderOffScreen(MenuBoardBlockEntity board) {
        // The board is larger than its block.
        return true;
    }

    @Override
    public void render(MenuBoardBlockEntity board, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Direction facing = board.getBlockState().getValue(MenuBoardBlock.FACING);
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        // Like a vanilla wall sign: south faces +z at yaw 0. The board lies 1 px in front of the wall behind it.
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(0, 0, -0.5 + 1 / 16f);
        draw(font, pose, buffers, light, overlay, board.lines);
        pose.popPose();
    }

    /** Draws the board centred on the origin, facing +z. */
    static void draw(Font font, PoseStack pose, MultiBufferSource buffers, int light, int overlay, List<MenuBoardBlockEntity.Line> lines) {
        float x0 = -W / 2, x1 = W / 2, y0 = -H / 2, y1 = H / 2;
        VertexConsumer vc = buffers.getBuffer(BOARD);
        PoseStack.Pose p = pose.last();
        vc.addVertex(p, x0, y1, 0).setColor(-1).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(p, 0, 0, 1);
        vc.addVertex(p, x0, y0, 0).setColor(-1).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(p, 0, 0, 1);
        vc.addVertex(p, x1, y0, 0).setColor(-1).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(p, 0, 0, 1);
        vc.addVertex(p, x1, y1, 0).setColor(-1).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(p, 0, 0, 1);

        // Rows: a heading for each kind that has dishes, then its dishes.
        List<Object> rows = new ArrayList<>();
        for (String kind : ORDER) {
            List<MenuBoardBlockEntity.Line> of = lines.stream().filter(l -> l.kind().equals(kind)).toList();
            if (of.isEmpty()) continue;
            rows.add(kind);
            rows.addAll(of);
        }
        float innerW = W - 2 * FRAME, top = y1 - FRAME - 0.02f;
        float titleScale = 0.016f;
        Component title = Component.translatable("onemoreclient.board.title");
        text(font, pose, buffers, title, -font.width(title) * titleScale / 2, top, titleScale, 0xFFF4EBD0, light);
        top -= 10 * titleScale + 0.03f;
        if (rows.isEmpty()) {
            Component empty = Component.translatable("onemoreclient.board.empty");
            float s = 0.009f;
            text(font, pose, buffers, empty, -font.width(empty) * s / 2, top - 0.1f, s, 0xFFB8C4B0, light);
            return;
        }
        // Two columns; the line height shrinks so that every row fits, at most 0.13 block.
        int perColumn = (rows.size() + 1) / 2;
        float space = top - (y0 + FRAME + 0.02f);
        float line = Math.min(0.13f, space / perColumn), scale = line / 11f;
        float colW = innerW / 2 - 0.03f;
        for (int i = 0; i < rows.size(); i++) {
            int col = i / perColumn, row = i % perColumn;
            float x = -W / 2 + FRAME + 0.03f + col * (innerW / 2), y = top - row * line;
            Object r = rows.get(i);
            if (r instanceof String kind) {
                text(font, pose, buffers, Component.translatable("onemoreclient.board." + kind), x, y, scale, 0xFFFFD866, light);
                continue;
            }
            MenuBoardBlockEntity.Line l = (MenuBoardBlockEntity.Line) r;
            float icon = line * 0.85f;
            float ix = x;
            for (ItemStack stack : l.items()) {
                pose.pushPose();
                pose.translate(ix + icon / 2, y - icon / 2 + scale, 0.01f);
                pose.scale(icon, icon, 0.01f);
                Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI, light, OverlayTexture.NO_OVERLAY, pose, buffers, null, 0);
                pose.popPose();
                ix += icon;
            }
            String price = String.valueOf(l.price());
            float priceW = font.width(price) * scale;
            String name = l.items().size() == 1 ? l.items().get(0).getHoverName().getString() : "";
            float nameX = ix + 0.02f, nameMax = (x + colW - priceW - 0.03f - nameX) / scale;
            if (nameMax > 4) text(font, pose, buffers, Component.literal(font.plainSubstrByWidth(name, (int) nameMax)), nameX, y, scale, 0xFFF4EBD0, light);
            text(font, pose, buffers, Component.literal(price), x + colW - priceW, y, scale, 0xFFFFD866, light);
        }
    }

    /** Text with its top left at (x, y) in blocks; font pixels times the scale. */
    private static void text(Font font, PoseStack pose, MultiBufferSource buffers, Component text, float x, float y, float scale, int color, int light) {
        pose.pushPose();
        pose.translate(x, y, 0.005f);
        pose.scale(scale, -scale, scale);
        font.drawInBatch(text, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
        pose.popPose();
    }

    /** The board item: an empty board with its title. */
    static class Item extends BlockEntityWithoutLevelRenderer {
        Item(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
            super(dispatcher, models);
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            pose.pushPose();
            pose.translate(0.5, 0.5, 0.5);
            pose.scale(0.5f, 0.5f, 0.5f);
            draw(Minecraft.getInstance().font, pose, buffers, LightTexture.FULL_BRIGHT, overlay, List.of());
            pose.popPose();
        }
    }
}
