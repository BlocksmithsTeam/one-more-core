package com.onemorestudios.onemoreclient.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.MenuBoardBlock;
import com.onemorestudios.onemoreclient.OneMoreClient;
import com.onemorestudios.onemoreclient.OrderBoardBlock;
import com.onemorestudios.onemoreclient.OrderBoardBlockEntity;

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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The order rail: a steel bar across the wall, 2 blocks wide, with a paper ticket for each taken order: the table
 * number, the dishes still wanted (icons) and a patience bar that goes from green to red.
 */
public class OrderBoardRenderer implements BlockEntityRenderer<OrderBoardBlockEntity> {
    private static final RenderType RAIL = RenderType.entityCutout(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/block/order_rail.png")),
            WHITE = RenderType.entityCutout(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/block/white.png"));

    public OrderBoardRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public boolean shouldRenderOffScreen(OrderBoardBlockEntity board) {
        return true;
    }

    @Override
    public void render(OrderBoardBlockEntity board, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-board.getBlockState().getValue(MenuBoardBlock.FACING).toYRot()));
        pose.translate(0, 0, -0.5 + 1 / 16f);
        draw(pose, buffers, light, board.tickets);
        pose.popPose();
    }

    /** Draws the rail centred on the origin, facing +z. */
    static void draw(PoseStack pose, MultiBufferSource buffers, int light, List<OrderBoardBlockEntity.Ticket> tickets) {
        float w = OrderBoardBlock.W, slot = w / OrderBoardBlock.TICKETS;
        // Quads face -z in TrayRenderer.quad; turn round so they face the room.
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180));
        TrayRenderer.quad(pose, buffers.getBuffer(RAIL), -w / 2, 0.36f, w / 2, 0.44f, -0.02f, -1, light);
        pose.popPose();
        Font font = Minecraft.getInstance().font;
        if (tickets.isEmpty()) {
            Component none = Component.translatable("onemoreclient.rail.empty");
            text(font, pose, buffers, none, -font.width(none) * 0.008f / 2, 0.25f, 0.008f, 0xFF9AA0A8, light);
            return;
        }
        for (int i = 0; i < tickets.size() && i < OrderBoardBlock.TICKETS; i++) {
            OrderBoardBlockEntity.Ticket t = tickets.get(i);
            float x0 = -w / 2 + i * slot + 0.015f, x1 = x0 + slot - 0.03f, top = 0.4f, bottom = -0.42f;
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(180));
            // Mirrored by the turn: x runs the other way.
            TrayRenderer.quad(pose, buffers.getBuffer(TrayRenderer.PAPER), -x1, bottom, -x0, top, -0.03f, -1, light);
            int color = Mth.hsvToRgb(t.patience() / 3f, 0.75f, 0.9f) | 0xFF000000;
            TrayRenderer.quad(pose, buffers.getBuffer(WHITE), -x1 + 0.02f, bottom + 0.025f, -x1 + 0.02f + (x1 - x0 - 0.04f) * t.patience(), bottom + 0.06f, -0.035f, color, light);
            pose.popPose();
            String head = "#" + t.table();
            float hs = 0.011f;
            // The table number, white in the red head of the ticket.
            text(font, pose, buffers, Component.literal(head), (x0 + x1) / 2 - font.width(head) * hs / 2, 0.375f, hs, 0xFFFFFFFF, light);
            // Dishes: icons in a column, up to 5.
            float icon = 0.11f, y = 0.17f;
            for (int k = 0; k < t.items().size() && k < 5; k++) {
                ItemStack s = t.items().get(k);
                pose.pushPose();
                pose.translate((x0 + x1) / 2, y, 0.04f);
                pose.scale(icon, icon, 0.01f);
                Minecraft.getInstance().getItemRenderer().renderStatic(s, ItemDisplayContext.GUI, light, OverlayTexture.NO_OVERLAY, pose, buffers, null, 0);
                pose.popPose();
                y -= icon + 0.01f;
            }
            if (t.items().size() > 5) text(font, pose, buffers, Component.literal("+" + (t.items().size() - 5)), (x0 + x1) / 2 - 0.03f, y + 0.04f, 0.007f, 0xFF404040, light);
        }
    }

    private static void text(Font font, PoseStack pose, MultiBufferSource buffers, Component text, float x, float y, float scale, int color, int light) {
        pose.pushPose();
        pose.translate(x, y, 0.045f);
        pose.scale(scale, -scale, scale);
        font.drawInBatch(text, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
        pose.popPose();
    }

    /** The rail item: the empty rail. */
    static class Item extends BlockEntityWithoutLevelRenderer {
        Item(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
            super(dispatcher, models);
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            pose.pushPose();
            pose.translate(0.5, 0.5, 0.5);
            pose.scale(0.5f, 0.5f, 0.5f);
            draw(pose, buffers, LightTexture.FULL_BRIGHT, List.of(new OrderBoardBlockEntity.Ticket(3, List.of(), 0.7f), new OrderBoardBlockEntity.Ticket(5, List.of(), 0.3f)));
            pose.popPose();
        }
    }
}
