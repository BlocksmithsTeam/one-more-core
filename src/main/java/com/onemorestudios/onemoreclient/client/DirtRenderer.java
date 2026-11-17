package com.onemorestudios.onemoreclient.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.onemorestudios.onemoreclient.Dirt;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Draws the dirty cells on top of serving surfaces, and a cloth under the crosshair while the player wipes. */
class DirtRenderer {
    // The residue texture of Hands On Delight (MIT), see THIRD_PARTY_NOTICES.md.
    private static final RenderType DIRT = RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/misc/oil_film.png"));
    private static final RenderType CLOTH = RenderType.entityCutoutNoCull(ResourceLocation.withDefaultNamespace("textures/block/white_wool.png"));
    // Half of the cloth size in blocks. The server cleans 3x3 cells of 1/8 block around the hit.
    private static final float CLOTH_HALF = 3 / 16f;

    static void register() {
        NeoForge.EVENT_BUS.addListener(DirtRenderer::render);
        // The server sends the dirt again when the chunk comes back.
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
        VertexConsumer dirt = buffers.getBuffer(DIRT);
        Dirt.CLIENT.forEach((pos, cells) -> {
            BlockState state = mc.level.getBlockState(pos);
            if (!state.is(OneMoreClient.SURFACES) || pos.distToCenterSqr(cam) > 64 * 64) return;
            int light = LevelRenderer.getLightColor(mc.level, pos.above());
            pose.pushPose();
            pose.translate(pos.getX() - cam.x, pos.getY() + top(state, pos) + 0.005 - cam.y, pos.getZ() - cam.z);
            for (int i = 0; i < 64; i++) {
                if ((cells >> i & 1) == 0) continue;
                float x = (i % 8) / 8f, z = (i / 8) / 8f;
                quad(dirt, pose.last(), x, z, x + 0.125f, z + 0.125f, x, z, x + 0.125f, z + 0.125f, light);
            }
            pose.popPose();
        });
        buffers.endBatch(DIRT);

        if (mc.options.keyUse.isDown() && mc.player.getMainHandItem().isEmpty()
                && mc.hitResult instanceof BlockHitResult hit && Dirt.CLIENT.containsKey(hit.getBlockPos())) {
            BlockPos pos = hit.getBlockPos();
            Vec3 at = hit.getLocation();
            pose.pushPose();
            pose.translate(at.x - cam.x, pos.getY() + top(mc.level.getBlockState(pos), pos) + 0.02 - cam.y, at.z - cam.z);
            quad(buffers.getBuffer(CLOTH), pose.last(), -CLOTH_HALF, -CLOTH_HALF, CLOTH_HALF, CLOTH_HALF, 0, 0, 1, 1, LevelRenderer.getLightColor(mc.level, pos.above()));
            pose.popPose();
            buffers.endBatch(CLOTH);
        }
    }

    private static double top(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getShape(Minecraft.getInstance().level, pos);
        return shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y);
    }

    /** A flat quad that faces up. */
    private static void quad(VertexConsumer out, PoseStack.Pose pose, float x0, float z0, float x1, float z1, float u0, float v0, float u1, float v1, int light) {
        out.addVertex(pose, x0, 0, z0).setColor(-1).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
        out.addVertex(pose, x0, 0, z1).setColor(-1).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
        out.addVertex(pose, x1, 0, z1).setColor(-1).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
        out.addVertex(pose, x1, 0, z0).setColor(-1).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
    }
}
