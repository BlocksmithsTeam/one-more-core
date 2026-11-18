package com.onemorestudios.onemoreclient.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemoreclient.Dirt;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** The same raised crumbs and thin smears remain in the world while the station camera moves in. */
class DirtRenderer {
    private static final ModelResourceLocation CLOTH = model("cloth");
    // Most cells have only a smear, with fewer crumbs and food bits.
    private static final ModelResourceLocation[] RESIDUE = {
            model("residue_0"), model("residue_1"), model("residue_2"), model("residue_3"),
            model("residue_4"), model("residue_5"), model("residue_3"), model("residue_4")};

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "diegesis/" + name));
    }

    static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(CLOTH);
        for (var model : RESIDUE) event.register(model);
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(DirtRenderer::render);
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload e) -> {
            if (e.getLevel().isClientSide()) Dirt.CLIENT.keySet().removeIf(pos -> e.getChunk().getPos().equals(new ChunkPos(pos)));
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> Dirt.CLIENT.clear());
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null || Dirt.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Dirt.CLIENT.forEach((pos, cells) -> {
            if (!mc.level.getBlockState(pos).is(OneMoreClient.SURFACES) || pos.distToCenterSqr(cam) > 64 * 64) return;
            int light = LevelRenderer.getLightColor(mc.level, pos.above());
            pose.pushPose();
            pose.translate(pos.getX() - cam.x, pos.getY() + Dirt.top(mc.level, pos) + 0.002 - cam.y, pos.getZ() - cam.z);
            for (int i = 0; i < 64; i++) {
                if ((cells >> i & 1) == 0) continue;
                pose.pushPose();
                pose.translate((i % 8) / 8f, 0, (i / 8) / 8f);
                draw(pose, buffers, RESIDUE[(i * 13 + i / 8) % RESIDUE.length], light);
                pose.popPose();
            }
            pose.popPose();
        });

        if (mc.screen == null && mc.options.keyUse.isDown() && mc.player != null && mc.player.getMainHandItem().isEmpty()
                && mc.hitResult instanceof BlockHitResult hit && Dirt.CLIENT.containsKey(hit.getBlockPos())) {
            BlockPos pos = hit.getBlockPos();
            Vec3 at = hit.getLocation();
            pose.pushPose();
            pose.translate(at.x - cam.x - 0.5, pos.getY() + Dirt.top(mc.level, pos) + 0.01 - cam.y, at.z - cam.z - 0.5);
            draw(pose, buffers, CLOTH, LevelRenderer.getLightColor(mc.level, pos.above()));
            pose.popPose();
        }
        buffers.endBatch(Sheets.cutoutBlockSheet());
    }

    private static void draw(PoseStack pose, MultiBufferSource buffers, ModelResourceLocation id, int light) {
        Minecraft mc = Minecraft.getInstance();
        mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null,
                mc.getModelManager().getModel(id), 1, 1, 1, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());
    }
}
