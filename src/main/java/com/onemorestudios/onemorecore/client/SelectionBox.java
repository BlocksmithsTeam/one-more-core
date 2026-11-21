package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.onemorestudios.onemorecore.OneMoreCore;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/**
 * A selection box in the style of Create's Super Glue: see-through faces with a grid of one cell per block and a light
 * hatch, and thick bright edges, in one colour. Drawn from inside too, so the free camera can stand in it.
 */
final class SelectionBox {
    private SelectionBox() {}

    private static final RenderType FACES = RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/misc/selection.png"));
    private static final RenderType EDGES = RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/block/white.png"));

    /** rgb: 0xRRGGBB. The pose is in world coordinates (camera already subtracted). */
    static void draw(PoseStack pose, MultiBufferSource buffers, AABB box, int rgb, float faceAlpha) {
        int face = (Math.round(faceAlpha * 255) << 24) | rgb, edge = 0xF0000000 | rgb;
        VertexConsumer f = buffers.getBuffer(FACES);
        PoseStack.Pose p = pose.last();
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ, x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        float sx = x1 - x0, sy = y1 - y0, sz = z1 - z0;
        // Six faces, each from both sides; the texture repeats once per block.
        quad(f, p, face, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, sx, sy);
        quad(f, p, face, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, sx, sy);
        quad(f, p, face, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, sz, sy);
        quad(f, p, face, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, sz, sy);
        quad(f, p, face, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, sx, sz);
        quad(f, p, face, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, sx, sz);
        // Twelve edges as thin bars.
        VertexConsumer e = buffers.getBuffer(EDGES);
        float t = 0.035f;
        for (float y : new float[] {y0, y1}) for (float z : new float[] {z0, z1}) bar(e, p, edge, x0 - t, y - t, z - t, x1 + t, y + t, z + t);
        for (float x : new float[] {x0, x1}) for (float z : new float[] {z0, z1}) bar(e, p, edge, x - t, y0 - t, z - t, x + t, y1 + t, z + t);
        for (float x : new float[] {x0, x1}) for (float y : new float[] {y0, y1}) bar(e, p, edge, x - t, y - t, z0 - t, x + t, y + t, z1 + t);
    }

    private static void quad(VertexConsumer c, PoseStack.Pose p, int argb, float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz, float u, float v) {
        vertex(c, p, argb, ax, ay, az, 0, 0);
        vertex(c, p, argb, bx, by, bz, u, 0);
        vertex(c, p, argb, cx, cy, cz, u, v);
        vertex(c, p, argb, dx, dy, dz, 0, v);
        vertex(c, p, argb, dx, dy, dz, 0, v);
        vertex(c, p, argb, cx, cy, cz, u, v);
        vertex(c, p, argb, bx, by, bz, u, 0);
        vertex(c, p, argb, ax, ay, az, 0, 0);
    }

    private static void vertex(VertexConsumer c, PoseStack.Pose p, int argb, float x, float y, float z, float u, float v) {
        c.addVertex(p, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(p, 0, 1, 0);
    }

    /** A solid bar from (x0, y0, z0) to (x1, y1, z1). */
    private static void bar(VertexConsumer c, PoseStack.Pose p, int argb, float x0, float y0, float z0, float x1, float y1, float z1) {
        quad(c, p, argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 1, 1);
        quad(c, p, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 1, 1);
        quad(c, p, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, 1, 1);
        quad(c, p, argb, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 1);
        quad(c, p, argb, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 1, 1);
        quad(c, p, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 1, 1);
    }
}
