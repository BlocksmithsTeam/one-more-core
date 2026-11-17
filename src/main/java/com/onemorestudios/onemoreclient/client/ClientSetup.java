package com.onemorestudios.onemoreclient.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemoreclient.Customer;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = OneMoreClient.MODID, dist = Dist.CLIENT)
public class ClientSetup {
    public ClientSetup(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(OneMoreClient.CUSTOMER.get(), CustomerRenderer::new));
        NeoForge.EVENT_BUS.addListener(ClientSetup::renderOrderTooltip);
        NeoForge.EVENT_BUS.addListener(ClientSetup::renderArea);
        DirtRenderer.register();
    }

    /** Shows the normal item tooltips of the order while the player looks at a customer. */
    private static void renderOrderTooltip(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || !(mc.crosshairPickEntity instanceof Customer customer)) return;
        List<Component> lines = new ArrayList<>();
        for (ItemStack stack : customer.getOrder()) lines.addAll(Screen.getTooltipFromItem(mc, stack));
        if (lines.isEmpty()) return;
        var g = event.getGuiGraphics();
        g.renderComponentTooltip(mc.font, lines, g.guiWidth() / 2 + 8, g.guiHeight() / 2 + 8);
    }

    /** Draws the selected area and the spawn point while the player holds the area tool. */
    private static void renderArea(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.player == null) return;
        ItemStack stack = mc.player.getMainHandItem();
        if (!stack.is(OneMoreClient.AREA_TOOL)) return;
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        int[] a = data.getIntArray("area");
        if (a.length == 6) LevelRenderer.renderLineBox(pose, lines, new AABB(a[0], a[1], a[2], a[3] + 1, a[4] + 1, a[5] + 1), 0.3f, 1f, 0.4f, 1f);
        if (data.contains("c1")) LevelRenderer.renderLineBox(pose, lines, new AABB(BlockPos.of(data.getLong("c1"))), 1f, 1f, 0.3f, 1f);
        if (data.contains("spawn")) LevelRenderer.renderLineBox(pose, lines, new AABB(BlockPos.of(data.getLong("spawn"))), 0.3f, 0.6f, 1f, 1f);
        if (data.contains("delivery")) LevelRenderer.renderLineBox(pose, lines, new AABB(BlockPos.of(data.getLong("delivery"))), 1f, 0.5f, 0.1f, 1f);
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
