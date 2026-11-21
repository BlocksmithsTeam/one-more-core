package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.Dirt;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TableMarks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;

/** A camera-facing number stand in the middle of each numbered table. */
final class TableMarkRenderer {
    private static final ModelResourceLocation STAND = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/table_number_stand"));

    private TableMarkRenderer() {}

    static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(STAND);
    }

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
        var model = mc.getModelManager().getModel(STAND);
        var renderer = mc.getBlockRenderer().getModelRenderer();
        for (TableMarks marks : TableMarks.CLIENT.values()) {
            for (int i = 0; i < marks.marks().size(); i++) {
                BlockPos pos = marks.marks().get(i);
                if (pos.distToCenterSqr(cam) > 32 * 32 || !mc.level.getBlockState(pos).is(OneMoreCore.SURFACES)) continue;
                int light = LevelRenderer.getLightColor(mc.level, pos.above());
                pose.pushPose();
                pose.translate(pos.getX() + 0.5 - cam.x, pos.getY() + Dirt.top(mc.level, pos) - cam.y, pos.getZ() + 0.5 - cam.z);
                pose.mulPose(Axis.YP.rotation((float) Math.atan2(cam.x - pos.getX() - 0.5, cam.z - pos.getZ() - 0.5)));
                // The 13-pixel model stands 13/32 blocks high at half scale.
                pose.scale(0.5f, 0.5f, 0.5f);
                pose.pushPose();
                pose.translate(-0.5, 0, -0.5);
                renderer.renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null, model,
                        1, 1, 1, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());
                pose.popPose();

                String text = String.valueOf(marks.numbers().get(i));
                int width = font.width(text);
                float scale = Math.min(0.023f, (3.5f / 16) / width);
                for (int side = 0; side < 2; side++) {
                    pose.pushPose();
                    pose.mulPose(Axis.YP.rotationDegrees(side * 180));
                    // The grey panel is centred at y=8.5 on both faces, one pixel either side of the stand's centre.
                    pose.translate(0, 8.5 / 16, 1 / 16.0 + 0.001);
                    pose.scale(scale, -scale, scale);
                    font.drawInBatch(text, -width / 2f, -3.5f, 0xFF202020, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
                    pose.popPose();
                }
                pose.popPose();
            }
        }
        buffers.endBatch();
    }
}
