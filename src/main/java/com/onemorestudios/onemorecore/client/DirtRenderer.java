package com.onemorestudios.onemorecore.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.onemorestudios.onemorecore.Dirt;
import com.onemorestudios.onemorecore.OneMoreCore;

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
 * Draws the dirt on serving surfaces, cut to the dirty cells. Each surface has its own 16x16 texture: one dirt cell is
 * 2x2 texels, the vanilla density.
 * While the player wipes without Diegesis, the cloth model lies under the crosshair.
 */
class DirtRenderer {
    private static final ModelResourceLocation CLOTH = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "diegesis/cloth"));

    /** The texture of one dirty surface and the cells it was made for. */
    private record Surface(long cells, DynamicTexture texture, ResourceLocation id, RenderType type) {}

    private static final Map<BlockPos, Surface> SURFACES = new HashMap<>();

    static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(CLOTH);
        MoneyRenderer.registerModels(event);
    }

    /** The coins and dirty dishes that customers left on the tables, in the world. */
    private static void renderTableItems(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam) {
        Minecraft mc = Minecraft.getInstance();
        com.onemorestudios.onemorecore.TableItems.CLIENT.forEach((pos, t) -> {
            if (!mc.level.getBlockState(pos).is(OneMoreCore.SURFACES) || pos.distToCenterSqr(cam) > 64 * 64) return;
            int light = LevelRenderer.getLightColor(mc.level, pos.above());
            pose.pushPose();
            pose.translate(pos.getX() - cam.x, pos.getY() + Dirt.top(mc.level, pos) + 0.004 - cam.y, pos.getZ() - cam.z);
            var split = com.onemorestudios.onemorecore.Money.split(t.coins());
            // The world also draws these pieces in the wiping close-up; its node is only a click target.
            pose.translate(8.0 / 16, 0, 4.0 / 16);
            MoneyRenderer.banknotes(pose, buffers, light, split.bills());
            pose.translate(0, 0, 7.5 / 16);
            MoneyRenderer.coins(pose, buffers, light, split.coins());
            pose.popPose();
        });
        buffers.endBatch(Sheets.cutoutBlockSheet());
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(DirtRenderer::render);
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload e) -> {
            if (!e.getLevel().isClientSide()) return;
            ChunkPos chunk = e.getChunk().getPos();
            Dirt.CLIENT.keySet().removeIf(pos -> chunk.equals(new ChunkPos(pos)));
            com.onemorestudios.onemorecore.TableItems.CLIENT.keySet().removeIf(pos -> chunk.equals(new ChunkPos(pos)));
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> { Dirt.CLIENT.clear(); com.onemorestudios.onemorecore.TableItems.CLIENT.clear(); });
    }

    /** Whether world dirt cell (cx, cz) at height y is dirty (cells are 1/8 block). */
    static boolean dirtyCell(int y, int cx, int cz) {
        Long cells = Dirt.CLIENT.get(new BlockPos(Math.floorDiv(cx, 8), y, Math.floorDiv(cz, 8)));
        return cells != null && (cells >> (Math.floorMod(cz, 8) * 8 + Math.floorMod(cx, 8)) & 1) != 0;
    }

    private static float hash(int x, int z, int seed) {
        int h = x * 374761393 + z * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    /** Smooth value noise in [0, 1] over world texels, one value per {@code scale} texels. */
    static float noise(int x, int z, int scale, int seed) {
        int gx = Math.floorDiv(x, scale), gz = Math.floorDiv(z, scale);
        float fx = (Math.floorMod(x, scale) + 0.5f) / scale, fz = (Math.floorMod(z, scale) + 0.5f) / scale;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        float a = hash(gx, gz, seed), b = hash(gx + 1, gz, seed), c = hash(gx, gz + 1, seed), d = hash(gx + 1, gz + 1, seed);
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }

    /** Stain colours (ABGR without alpha): sauce, grease, coffee, crumbs. */
    private static final int[] KINDS = {0x1F3B7A, 0x3C7A9C, 0x1D2F4A, 0x6AA4C9};

    /**
     * The stain of one surface, drawn in world texels so that the stain of a table runs on over the joins of its blocks.
     * The colour changes from patch to patch (sauce, grease, coffee) with soft holes and crumbs, and the border of a
     * stain, also where a cloth wiped it, is a little darker. Texel x is the block's x, texel y its z.
     */
    static void paint(NativeImage out, BlockPos pos) {
        int y0 = pos.getY();
        for (int ty = 0; ty < 16; ty++) {
            for (int tx = 0; tx < 16; tx++) {
                int wx = pos.getX() * 16 + tx, wz = pos.getZ() * 16 + ty;
                if (!dirtyCell(y0, wx >> 1, wz >> 1)) { out.setPixelRGBA(tx, ty, 0); continue; }
                float n = noise(wx, wz, 4, 7), patch = noise(wx, wz, 12, 11);
                int kind = patch < 0.42f ? 0 : patch < 0.66f ? 1 : 2;
                if (hash(wx, wz, 3) < 0.07f) kind = 3; // a crumb
                else if (n < 0.22f) { out.setPixelRGBA(tx, ty, 0); continue; } // a soft hole
                boolean edge = false;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                    if (!dirtyCell(y0, (wx + d[0]) >> 1, (wz + d[1]) >> 1)) edge = true;
                int bgr = KINDS[kind];
                float shade = (edge ? 0.7f : 0.85f + 0.3f * n);
                int r = Math.min(255, (int) ((bgr & 0xFF) * shade)), g = Math.min(255, (int) ((bgr >> 8 & 0xFF) * shade)), b = Math.min(255, (int) ((bgr >> 16 & 0xFF) * shade));
                int alpha = kind == 3 ? 230 : edge ? 175 : 95 + (int) (70 * n);
                out.setPixelRGBA(tx, ty, alpha << 24 | b << 16 | g << 8 | r);
            }
        }
    }

    /** A key of the cells of pos and its 8 neighbours: the texture of pos depends on all of them. */
    private static long key(BlockPos pos) {
        long k = 0;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) k = k * 31 + Dirt.CLIENT.getOrDefault(pos.offset(dx, 0, dz), 0L);
        return k;
    }

    private static Surface surface(BlockPos pos) {
        long cells = key(pos);
        Surface old = SURFACES.get(pos);
        if (old != null && old.cells == cells) return old;
        if (old == null) {
            DynamicTexture texture = new DynamicTexture(16, 16, true);
            String name = ("dirt/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ()).replace('-', 'm');
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, name);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            old = new Surface(0, texture, id, RenderType.entityTranslucent(id));
        }
        paint(old.texture.getPixels(), pos);
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
        if (!com.onemorestudios.onemorecore.TableItems.CLIENT.isEmpty()) renderTableItems(event.getPoseStack(), mc.renderBuffers().bufferSource(), event.getCamera().getPosition());
        if (Dirt.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Dirt.CLIENT.forEach((pos, cells) -> {
            if (!mc.level.getBlockState(pos).is(OneMoreCore.SURFACES) || pos.distToCenterSqr(cam) > 64 * 64) return;
            Surface s = surface(pos);
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
                && mc.hitResult instanceof BlockHitResult hit && Dirt.tableDirty(mc.level, hit.getBlockPos())) {
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
