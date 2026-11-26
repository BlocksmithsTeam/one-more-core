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
            pose.translate(((i / 5 % 3 - 1) * 4.5 + ((i * 37 + 11) % 101 - 50) / 100.0) / 16,
                    (i % 5) / 16.0, ((i / 15 - 0.5) * 4.5 + ((i * 53 + 7) % 101 - 50) / 100.0) / 16);
            pose.mulPose(Axis.YP.rotationDegrees(((i * 47 + 13) % 201 - 100) / 10f));
            pose.translate(-7.5 / 16, 0, -7.5 / 16);
            piece(pose, buffers, light, COIN);
            pose.popPose();
        }
    }

    static void banknotes(PoseStack pose, MultiBufferSource buffers, int light, int count) {
        for (int i = 0; i < Math.min(20, count); i++) {
            pose.pushPose();
            pose.translate(0, i / 16.0, 0);
            pose.mulPose(Axis.YP.rotationDegrees(((i * 73 + 19) % 161 - 80) / 10f));
            pose.translate(-0.5, 0, -0.5);
            piece(pose, buffers, light, BANKNOTE);
            pose.popPose();
        }
    }

    // Drawer positions are model pixels; the painter origin is the tray centre at floor height.
    private static final double[][] DRAWER_COINS = {
            {2.5, 0.5, -1.5}, {6.2, 0.5, -1.5}, {10.3, 0.5, -1.5},
            {6.7, 1.5, -1.5}, {10.8, 1.5, -1.5}, {10.3, 2.5, -1.0}
    };

    static void drawerCoins(PoseStack pose, MultiBufferSource buffers, int light, int count) {
        for (int i = 0; i < Math.min(6, count); i++) {
            double[] at = DRAWER_COINS[i];
            pose.pushPose();
            pose.translate((at[0] - 8) / 16, (at[1] - 0.5) / 16, at[2] / 16);
            pose.translate(-6 / 16.0, 0, -6 / 16.0);
            piece(pose, buffers, light, COIN);
            pose.popPose();
        }
    }

    static void drawerBanknotes(PoseStack pose, MultiBufferSource buffers, int light, int count) {
        for (int i = 0; i < Math.min(2, count); i++) {
            pose.pushPose();
            pose.translate(0, i / 16.0, 0);
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
