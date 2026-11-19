package com.onemorestudios.onemoreclient.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.Dirt;
import com.onemorestudios.onemoreclient.OneMoreClient;
import com.onemorestudios.onemoreclient.TableMarks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** A table number card on each numbered table: a small paper tent in the middle of the table with the number on both sides. */
final class TableMarkRenderer {
    private TableMarkRenderer() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(TableMarkRenderer::render);
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || mc.level == null || TableMarks.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        for (TableMarks marks : TableMarks.CLIENT.values()) {
            for (int i = 0; i < marks.marks().size(); i++) {
                BlockPos pos = marks.marks().get(i);
                if (pos.distToCenterSqr(cam) > 32 * 32 || !mc.level.getBlockState(pos).is(OneMoreClient.SURFACES)) continue;
                int light = LevelRenderer.getLightColor(mc.level, pos.above());
                pose.pushPose();
                pose.translate(pos.getX() + 0.5 - cam.x, pos.getY() + Dirt.top(mc.level, pos) - cam.y, pos.getZ() + 0.5 - cam.z);
                // The tent turns to face the camera around the vertical axis, so the number reads from any seat.
                pose.mulPose(Axis.YP.rotation((float) Math.atan2(cam.x - pos.getX() - 0.5, cam.z - pos.getZ() - 0.5)));
                String text = String.valueOf(marks.numbers().get(i));
                for (int side = 0; side < 2; side++) {
                    pose.pushPose();
                    pose.mulPose(Axis.YP.rotationDegrees(side * 180));
                    pose.translate(0, 0, 0.03);
                    pose.mulPose(Axis.XP.rotationDegrees(-18));
                    TrayRenderer.quad(pose, buffers.getBuffer(TrayRenderer.PAPER), -0.09f, 0, 0.09f, 0.14f, 0, -1, light);
                    float s = 1 / 110f;
                    pose.translate(0, 0.075, 0.002);
                    pose.scale(s, -s, s);
                    font.drawInBatch(text, -font.width(text) / 2f, -2.5f, 0xFF202020, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
                    pose.popPose();
                }
                pose.popPose();
            }
        }
        buffers.endBatch();
    }
}
