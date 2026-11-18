package com.onemorestudios.onemoreclient.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.onemorestudios.onemoreclient.Dirt;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.DynamicTexture;
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

/**
 * Draws the dirt on serving surfaces: the flat stain texture (Hands On Delight, MIT, see THIRD_PARTY_NOTICES.md) cut to
 * the dirty cells. Where a wiped cell cuts the stain, the stain gets a dark edge, so a wiped part looks wiped away and
 * not like missing pixels. Each surface has its own 16x16 texture: one dirt cell is 2x2 texels, the vanilla density.
 * While the player wipes without Diegesis, the cloth model lies under the crosshair.
 */
class DirtRenderer {
    private static final ResourceLocation STAIN = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/misc/oil_film.png");
    private static final ModelResourceLocation CLOTH = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "diegesis/cloth"));

    /** The texture of one dirty surface and the cells it was made for. */
    private record Surface(long cells, DynamicTexture texture, ResourceLocation id, RenderType type) {}

    private static final Map<BlockPos, Surface> SURFACES = new HashMap<>();
    private static NativeImage stain;

    private static final ModelResourceLocation COINS = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "diegesis/coins")),
            DISHES = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "diegesis/dishes"));

    static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(CLOTH);
        event.register(COINS);
        event.register(DISHES);
    }

    /** The coins and dirty dishes that customers left on the tables, in the world. */
    private static void renderTableItems(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        com.onemorestudios.onemoreclient.TableItems.CLIENT.forEach((pos, t) -> {
            if (!mc.level.getBlockState(pos).is(OneMoreClient.SURFACES) || pos.distToCenterSqr(cam) > 64 * 64) return;
            int light = LevelRenderer.getLightColor(mc.level, pos.above());
            pose.pushPose();
            pose.translate(pos.getX() - cam.x, pos.getY() + Dirt.top(mc.level, pos) + 0.004 - cam.y, pos.getZ() - cam.z);
            var renderer = mc.getBlockRenderer().getModelRenderer();
            // Coins to one side, dishes to the other.
            pose.pushPose();
            pose.translate(0.18, 0, 0.15);
            if (t.coins() > 0) renderer.renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null, mc.getModelManager().getModel(COINS),
                    1, 1, 1, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());
            pose.popPose();
            pose.translate(-0.2, 0, -0.12);
            if (t.plates() > 0 || t.glasses() > 0) renderer.renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null,
                    mc.getModelManager().getModel(DISHES), 1, 1, 1, light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());
            pose.popPose();
        });
        buffers.endBatch(Sheets.cutoutBlockSheet());
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(DirtRenderer::render);
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload e) -> {
            if (e.getLevel().isClientSide()) Dirt.CLIENT.keySet().removeIf(pos -> e.getChunk().getPos().equals(new ChunkPos(pos)));
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> { Dirt.CLIENT.clear(); com.onemorestudios.onemoreclient.TableItems.CLIENT.clear(); });
    }

    private static NativeImage stain() {
        if (stain == null) {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(STAIN)) {
                stain = NativeImage.read(in);
            } catch (IOException e) {
                OneMoreClient.LOGGER.error("Cannot read {}", STAIN, e);
                stain = new NativeImage(16, 16, true);
            }
        }
        return stain;
    }

    static boolean dirty(long cells, int px, int py) {
        if (px < 0 || py < 0 || px > 15 || py > 15) return false;
        return (cells >> ((py / 2) * 8 + px / 2) & 1) != 0;
    }

    /**
     * The stain of the dirty cells. A stain texel next to a stain texel of a clean cell is an edge: darker and less
     * transparent. Texel x is the block's x, texel y its z.
     */
    static void paint(NativeImage out, NativeImage base, long cells) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int abgr = base.getPixelRGBA(x * base.getWidth() / 16, y * base.getHeight() / 16);
                int alpha = abgr >>> 24;
                if (!dirty(cells, x, y) || alpha == 0) { out.setPixelRGBA(x, y, 0); continue; }
                boolean edge = false;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < 0 || ny < 0 || nx > 15 || ny > 15) continue;
                    int nAlpha = base.getPixelRGBA(nx * base.getWidth() / 16, ny * base.getHeight() / 16) >>> 24;
                    if (nAlpha != 0 && !dirty(cells, nx, ny)) edge = true;
                }
                if (edge) {
                    int r = (abgr & 0xFF) * 55 / 100, g = (abgr >> 8 & 0xFF) * 55 / 100, b = (abgr >> 16 & 0xFF) * 55 / 100;
                    abgr = Math.max(alpha, 210) << 24 | b << 16 | g << 8 | r;
                }
                out.setPixelRGBA(x, y, abgr);
            }
        }
    }

    private static Surface surface(BlockPos pos, long cells) {
        Surface old = SURFACES.get(pos);
        if (old != null && old.cells == cells) return old;
        if (old == null) {
            DynamicTexture texture = new DynamicTexture(16, 16, true);
            String name = ("dirt/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ()).replace('-', 'm');
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, name);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            old = new Surface(0, texture, id, RenderType.entityTranslucent(id));
        }
        paint(old.texture.getPixels(), stain(), cells);
        old.texture.upload();
        Surface now = new Surface(cells, old.texture, old.id, old.type);
        SURFACES.put(pos, now);
        return now;
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null) return;
        // Textures of surfaces that are clean or gone are freed.
        SURFACES.entrySet().removeIf(e -> {
            if (Dirt.CLIENT.containsKey(e.getKey())) return false;
            mc.getTextureManager().release(e.getValue().id());
            return true;
        });
        if (!com.onemorestudios.onemoreclient.TableItems.CLIENT.isEmpty()) renderTableItems(event.getPoseStack(), mc.renderBuffers().bufferSource(), event.getCamera().getPosition());
        if (Dirt.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Dirt.CLIENT.forEach((pos, cells) -> {
            if (!mc.level.getBlockState(pos).is(OneMoreClient.SURFACES) || pos.distToCenterSqr(cam) > 64 * 64) return;
            Surface s = surface(pos, cells);
            int light = LevelRenderer.getLightColor(mc.level, pos.above());
            pose.pushPose();
            pose.translate(pos.getX() - cam.x, pos.getY() + Dirt.top(mc.level, pos) + 0.003 - cam.y, pos.getZ() - cam.z);
            VertexConsumer out = buffers.getBuffer(s.type());
            PoseStack.Pose p = pose.last();
            out.addVertex(p, 0, 0, 0).setColor(-1).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
            out.addVertex(p, 0, 0, 1).setColor(-1).setUv(0, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
            out.addVertex(p, 1, 0, 1).setColor(-1).setUv(1, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
            out.addVertex(p, 1, 0, 0).setColor(-1).setUv(1, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
            pose.popPose();
            buffers.endBatch(s.type());
        });

        if (mc.screen == null && mc.options.keyUse.isDown() && mc.player != null && mc.player.getMainHandItem().isEmpty()
                && mc.hitResult instanceof BlockHitResult hit && Dirt.CLIENT.containsKey(hit.getBlockPos())) {
            BlockPos pos = hit.getBlockPos();
            Vec3 at = hit.getLocation();
            pose.pushPose();
            pose.translate(at.x - cam.x - 0.5, pos.getY() + Dirt.top(mc.level, pos) + 0.01 - cam.y, at.z - cam.z - 0.5);
            mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null,
                    mc.getModelManager().getModel(CLOTH), 1, 1, 1, LevelRenderer.getLightColor(mc.level, pos.above()), OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, RenderType.cutout());
            pose.popPose();
            buffers.endBatch(Sheets.cutoutBlockSheet());
        }
    }
}
