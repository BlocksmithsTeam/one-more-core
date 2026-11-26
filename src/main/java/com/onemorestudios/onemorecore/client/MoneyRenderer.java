package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.OneMoreCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Single pieces, centred at the origin on the surface; coordinates are blocks. */
final class MoneyRenderer {
    private static final ModelResourceLocation COIN = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/coin_piece"));
    private static final ModelResourceLocation BANKNOTE = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/banknote"));

    static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(COIN);
        event.register(BANKNOTE);
    }

    static void coins(PoseStack pose, MultiBufferSource buffers, int light, int count) {
        for (int i = 0; i < Math.min(30, count); i++) {
            pose.pushPose();
            pose.translate((i / 10 - 1) * 3.5 / 16, (i % 10) * 0.5 / 16, 0);
            pose.scale(1, 0.5f, 1);
            pose.translate(-7.5 / 16, 0, -7.5 / 16);
            piece(pose, buffers, light, COIN);
            pose.popPose();
        }
    }

    static void banknotes(PoseStack pose, MultiBufferSource buffers, int light, int count) {
        for (int i = 0; i < Math.min(20, count); i++) {
            pose.pushPose();
            pose.translate(0, i * 0.25 / 16, 0);
            pose.mulPose(Axis.YP.rotationDegrees(((i * 73 + 19) % 161 - 80) / 10f));
            pose.scale(1, 0.25f, 1);
            pose.translate(-0.5, 0, -0.5);
            piece(pose, buffers, light, BANKNOTE);
            pose.popPose();
        }
    }

    private static void piece(PoseStack pose, MultiBufferSource buffers, int light, ModelResourceLocation model) {
        Minecraft mc = Minecraft.getInstance();
        mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null,
                mc.getModelManager().getModel(model), 1, 1, 1, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());
    }
}
