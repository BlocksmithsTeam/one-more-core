package com.onemorestudios.onemoreclient.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemoreclient.MenuBookBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** The closed menu book on the table (its block model), hidden while Diegesis shows the open book in its place. */
public class MenuBookRenderer implements BlockEntityRenderer<MenuBookBlockEntity> {
    public MenuBookRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(MenuBookBlockEntity book, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (DiegesisHook.focusedAt(book.getBlockPos())) return;
        var state = book.getBlockState();
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.cutout()), state, model,
                1, 1, 1, light, OverlayTexture.NO_OVERLAY);
    }
}
