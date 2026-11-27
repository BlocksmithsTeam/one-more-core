package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TicketRailBlock;
import com.onemorestudios.onemorecore.TicketRailBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

public final class TicketRailRenderer implements BlockEntityRenderer<TicketRailBlockEntity> {
    private static final ModelResourceLocation PAPER = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/rail_ticket"));

    public TicketRailRenderer(BlockEntityRendererProvider.Context context) {}
    static void registerModels(ModelEvent.RegisterAdditional event) { event.register(PAPER); }

    @Override
    public void render(TicketRailBlockEntity rail, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (DiegesisHook.LOADED && TicketRailBehavior.draws(rail.getBlockPos())) return;
        var mc = Minecraft.getInstance();
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - rail.getBlockState().getValue(TicketRailBlock.FACING).toYRot()));
        pose.translate(-0.5, 0, -0.5);
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        for (int i = 0; i < TicketRailBlockEntity.SIZE; i++) {
            if (rail.ticket(i).isEmpty()) continue;
            pose.pushPose();
            pose.translate((i + 0.5f) * 16 / TicketRailBlockEntity.SIZE, 8 + rail.slide(i, partial), 14);
            pose.pushPose();
            pose.scale(16, 16, 16);
            mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()),
                    rail.getBlockState(), mc.getModelManager().getModel(PAPER), 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
            pose.popPose();
            ink(rail.ticket(i), rail.done(i), pose, buffers, light);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Shared ink in scene pixels, on the north face of the same paper model. */
    static void ink(ItemStack ticket, boolean done, PoseStack pose, MultiBufferSource buffers, int light) {
        if (ticket.isEmpty()) return;
        var mc = Minecraft.getInstance();
        pose.pushPose();
        pose.translate(0, 2.8, -0.18);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        String label = ticket.getOrDefault(OneMoreCore.LABEL.get(), "?");
        text(label, 0, 0, 0xFF262626, pose, buffers, light);
        var dishes = ticket.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().filter(s -> !s.isEmpty()).toList();
        // shortcut: eight visible icons plus a count; expand the paper layout if more must be visible without the tooltip.
        for (int i = 0; i < Math.min(8, dishes.size()); i++) {
            pose.pushPose();
            pose.translate((i % 2 - 0.5f) * 1.05f, -1.5f - (i / 2) * 1.1f, 0.08);
            pose.scale(0.9f, 0.9f, 0.025f);
            mc.getItemRenderer().renderStatic(dishes.get(i), ItemDisplayContext.GUI, light,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, i);
            pose.popPose();
        }
        if (dishes.size() > 8) text("+" + (dishes.size() - 8), 0, -6.2f, 0xFF262626, pose, buffers, light);
        if (done) text("X", 0, -3, 0xFFB52E2E, pose, buffers, light);
        pose.popPose();
    }

    private static void text(String text, float x, float y, int color, PoseStack pose, MultiBufferSource buffers, int light) {
        Font font = Minecraft.getInstance().font;
        float scale = Math.min(0.16f, 2f / Math.max(1, font.width(text)));
        pose.pushPose();
        pose.translate(x, y, 0.16);
        pose.scale(scale, -scale, scale);
        font.drawInBatch(text, -font.width(text) / 2f, -3.5f, color, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
    }
}
