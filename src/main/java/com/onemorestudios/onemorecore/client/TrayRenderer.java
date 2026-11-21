package com.onemorestudios.onemorecore.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TrayBlock;
import com.onemorestudios.onemorecore.TrayBlockEntity;
import com.onemorestudios.onemorecore.TrayItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * The dishes on a tray, in 3D as on a table, in one row of three, and the order ticket on its front rim with the table
 * number. The same drawing for a tray on a counter and a tray in the hand.
 */
public class TrayRenderer implements BlockEntityRenderer<TrayBlockEntity> {
    static final RenderType PAPER = RenderType.entityCutout(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/block/ticket_paper.png"));
    /** Places of the dishes on the tray bed, in pixels of a tray that faces north. */
    private static final float[][] SPOTS = {{3, 8}, {8, 8}, {13, 8}};
    private static final float BED_HEIGHT = 1 / 16f;
    private static final float SPOT_WIDTH = 5 / 16f;
    /** Dishes are drawn this much larger than the fit to their square (the user asked for 30-40% bigger). */
    private static final float DISH_DISPLAY_SCALE = 1.35f;

    public TrayRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(TrayBlockEntity tray, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-((tray.getBlockState().getValue(TrayBlock.FACING).toYRot() + 180) % 360)));
        pose.translate(-0.5, 0, -0.5);
        draw(tray.items.getItems(), tray.label, pose, buffers, light);
        pose.popPose();
    }

    /** Dishes and label on a north-facing tray in block space (0..1). */
    static void draw(List<ItemStack> dishes, String label, PoseStack pose, MultiBufferSource buffers, int light) {
        var level = Minecraft.getInstance().level;
        float scale = 0.7f * Config.FOOD_SCALE.get().floatValue();
        for (int i = 0; i < dishes.size() && i < SPOTS.length; i++) {
            ItemStack dish = dishes.get(i);
            if (dish.isEmpty()) continue;
            // Measure in dish space after the model has applied its FIXED display transform and food offset.
            DishBounds bounds = new DishBounds();
            CustomerRenderer.renderFood(dish, level, new PoseStack(), type -> bounds.consumer(), light, scale, i);
            pose.pushPose();
            pose.translate(SPOTS[i][0] / 16, BED_HEIGHT, SPOTS[i][1] / 16);
            pose.mulPose(Axis.YP.rotationDegrees(180));
            if (Float.isFinite(bounds.minY)) {
                float fit = DISH_DISPLAY_SCALE * Math.min(1, SPOT_WIDTH / Math.max(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ));
                pose.scale(fit, fit, fit);
                pose.translate(-(bounds.minX + bounds.maxX) / 2, -bounds.minY, -(bounds.minZ + bounds.maxZ) / 2);
            }
            CustomerRenderer.renderFood(dish, level, pose, buffers, light, scale, i);
            pose.popPose();
        }
        if (label.isEmpty()) return;
        // The ticket is stuck to the front rim (z 2), a small tag that leans out.
        pose.pushPose();
        pose.translate(0.5, 2.2 / 16, 1.9 / 16);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.mulPose(Axis.XP.rotationDegrees(-25));
        float w = 3.6f / 16, h = 2 / 16f;
        quad(pose, buffers.getBuffer(PAPER), -w / 2, -h / 2, w / 2, h / 2, 0, 0xFFFFFFFF, light);
        Font font = Minecraft.getInstance().font;
        String text = label;
        float s = 1 / 170f;
        pose.translate(0, 0, 0.002);
        pose.scale(s, -s, s);
        font.drawInBatch(text, -font.width(text) / 2f, -3.5f, 0xFF202020, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
        pose.popPose();
    }

    private static final class DishBounds {
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        VertexConsumer consumer() {
            // The base model and the glint require distinct consumers.
            return new VertexConsumer() {
                @Override
                public VertexConsumer addVertex(float x, float y, float z) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxZ = Math.max(maxZ, z);
                    return this;
                }

                @Override
                public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green,
                        float blue, float alpha, int[] light, int overlay, boolean readAlpha) {
                    int[] vertices = quad.getVertices();
                    for (int i = 0; i < vertices.length; i += 8) {
                        addVertex(pose, Float.intBitsToFloat(vertices[i]), Float.intBitsToFloat(vertices[i + 1]),
                                Float.intBitsToFloat(vertices[i + 2]));
                    }
                }

                @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
                @Override public VertexConsumer setUv(float u, float v) { return this; }
                @Override public VertexConsumer setUv1(int u, int v) { return this; }
                @Override public VertexConsumer setUv2(int u, int v) { return this; }
                @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
            };
        }
    }

    /** A quad from (x0, y0) to (x1, y1) at z, facing -z; the whole texture. Drawn from both sides. */
    static void quad(PoseStack pose, VertexConsumer vc, float x0, float y0, float x1, float y1, float z, int argb, int light) {
        PoseStack.Pose p = pose.last();
        vc.addVertex(p, x0, y1, z).setColor(argb).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, -1);
        vc.addVertex(p, x1, y1, z).setColor(argb).setUv(1, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, -1);
        vc.addVertex(p, x1, y0, z).setColor(argb).setUv(1, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, -1);
        vc.addVertex(p, x0, y0, z).setColor(argb).setUv(0, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, -1);
        vc.addVertex(p, x0, y0, z).setColor(argb).setUv(0, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, 1);
        vc.addVertex(p, x1, y0, z).setColor(argb).setUv(1, 1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, 1);
        vc.addVertex(p, x1, y1, z).setColor(argb).setUv(1, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, 1);
        vc.addVertex(p, x0, y1, z).setColor(argb).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 0, 1);
    }

    /** The full item model in north-facing block space, without item display transforms. */
    static void drawItem(ItemStack stack, PoseStack pose, MultiBufferSource buffers, int light, int overlay, boolean showContents) {
        if (!(stack.getItem() instanceof TrayItem tray)) return;
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(tray.getBlock().defaultBlockState(), pose, buffers, light, overlay);
        if (showContents) {
            draw(stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().toList(),
                    stack.getOrDefault(OneMoreCore.LABEL.get(), ""), pose, buffers, light);
        }
    }

    /** The tray item: the tray model with its dishes and label. */
    static class Item extends BlockEntityWithoutLevelRenderer {
        Item(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
            super(dispatcher, models);
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            if ((context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND)
                    && TrayCarryRenderer.rendersBetweenPlayerHands(stack, pose)) return;
            drawItem(stack, pose, buffers, light, overlay, context != ItemDisplayContext.GUI);
        }
    }
}
