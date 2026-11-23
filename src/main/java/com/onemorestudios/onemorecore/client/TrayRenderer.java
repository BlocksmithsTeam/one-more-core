package com.onemorestudios.onemorecore.client;

import java.util.List;
import java.util.function.IntFunction;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TrayBlock;
import com.onemorestudios.onemorecore.TrayBlockEntity;
import com.onemorestudios.onemorecore.TrayItem;
import com.onemorestudios.onemorecore.TrayPose;

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
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Draws the tray's dishes and the table label on its front rim. */
public class TrayRenderer implements BlockEntityRenderer<TrayBlockEntity> {
    static final RenderType PAPER = RenderType.entityCutout(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/block/ticket_paper.png"));

    record Sway(float x, float z, float pitch, float roll) {
        static final Sway NONE = new Sway(0, 0, 0, 0);

        Quaternionfc turn(Quaternionfc rest) {
            return this == NONE ? rest : new Quaternionf().rotationXYZ(pitch, 0, roll).mul(rest);
        }
    }

    public TrayRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(TrayBlockEntity tray, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-TrayBlock.bedYaw(tray.getBlockState().getValue(TrayBlock.FACING))));
        pose.translate(-0.5, 0, -0.5);
        if (!DiegesisHook.LOADED || !TrayDiegesis.drawsDishes(tray.getBlockPos())) {
            drawDishes(tray.items.getItems(), tray::getPose, Sway.NONE, pose, buffers, light);
        }
        drawLabel(tray.label, pose, buffers, light);
        pose.popPose();
    }

    private static void drawDishes(List<ItemStack> dishes, IntFunction<TrayPose> positions, Sway sway,
            PoseStack pose, MultiBufferSource buffers, int light) {
        for (int i = 0; i < dishes.size() && i < TrayBlockEntity.SIZE; i++) {
            ItemStack dish = dishes.get(i);
            if (dish.isEmpty() || dish.getItem() instanceof TrayItem) continue;
            TrayPose at = positions.apply(i);
            // With Diegesis the tray draws the same shape as its close-up (PlacedShapeMixin gives block foods their block).
            if (DiegesisHook.LOADED) TrayDiegesis.drawDish(dish, at, sway, pose, buffers, light, i);
            else drawStandalone(dish, at, sway, pose, buffers, light, i);
        }
    }

    private static void drawStandalone(ItemStack dish, TrayPose at, Sway sway, PoseStack pose, MultiBufferSource buffers, int light, int seed) {
        Minecraft mc = Minecraft.getInstance();
        var items = mc.getItemRenderer();
        var model = items.getModel(dish, mc.level, null, 0);
        BlockState block = CustomerRenderer.showsBlock(dish) ? FoodModels.block(dish) : null;
        if (block != null && block.hasProperty(BlockStateProperties.LIT)) {
            block = block.setValue(BlockStateProperties.LIT, true);
        }
        ItemDisplayContext context = model.isGui3d() && !model.isCustomRenderer() ? ItemDisplayContext.NONE : ItemDisplayContext.FIXED;
        DishBounds bounds = new DishBounds();
        drawModel(dish, block, context, new PoseStack(), type -> bounds.consumer(), light, seed);
        Vector3f half = bounds.half();
        float longest = Math.max(half.x, Math.max(half.y, half.z));
        float shortest = Math.min(half.x, Math.min(half.y, half.z));
        float size = TrayPose.ITEM_SIZE * at.scale();
        float scale = size * (shortest > longest * 0.6f ? 0.8f : 1) / (2 * longest);
        Quaternionf rest = new Quaternionf(at.rotation());
        if (at.lift() == 0 && block == null && shortest < longest * 0.2f) rest.rotateX(Mth.HALF_PI);
        Quaternionfc turn = sway.turn(rest);
        Vector3f extents = rotatedHalf(half, turn).mul(scale);
        if (sway != Sway.NONE) {
            Vector3f still = rotatedHalf(half, rest).mul(scale);
            if (!allowsTilt(at, still, extents)) {
                turn = rest;
                extents = still;
            }
        }

        pose.pushPose();
        moveDish(pose, at, sway, turn, extents, extents.y, scale);
        Vector3f center = bounds.center();
        pose.translate(-center.x, -center.y, -center.z);
        drawModel(dish, block, context, pose, buffers, light, seed);
        pose.popPose();
    }

    private static Vector3f rotatedHalf(Vector3fc half, Quaternionfc turn) {
        Matrix3f m = new Matrix3f().rotation(turn);
        return new Vector3f(
                Math.abs(m.m00()) * half.x() + Math.abs(m.m10()) * half.y() + Math.abs(m.m20()) * half.z(),
                Math.abs(m.m01()) * half.x() + Math.abs(m.m11()) * half.y() + Math.abs(m.m21()) * half.z(),
                Math.abs(m.m02()) * half.x() + Math.abs(m.m12()) * half.y() + Math.abs(m.m22()) * half.z());
    }

    private static void drawModel(ItemStack dish, BlockState block, ItemDisplayContext context, PoseStack pose,
            MultiBufferSource buffers, int light, int seed) {
        Minecraft mc = Minecraft.getInstance();
        if (block != null) {
            mc.getBlockRenderer().renderSingleBlock(block, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        } else {
            mc.getItemRenderer().renderStatic(dish, context, light, OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, seed);
        }
    }

    static void moveDish(PoseStack pose, TrayPose at, Sway sway, Quaternionfc turn, Vector3fc extents, float lift, float scale) {
        float x = at.x() + slide(sway.x(), at.x(), extents.x(), TrayPose.MIN_X, TrayPose.MAX_X);
        float z = at.z() + slide(sway.z(), at.z(), extents.z(), TrayPose.MIN_Z, TrayPose.MAX_Z);
        pose.translate(x / 16, (at.y() + lift) / 16, z / 16);
        pose.mulPose(new Quaternionf(turn));
        pose.scale(scale / 16, scale / 16, scale / 16);
    }

    static boolean allowsTilt(TrayPose at, Vector3fc still, Vector3fc tilted) {
        float roomX = Math.min(at.x() - TrayPose.MIN_X, TrayPose.MAX_X - at.x());
        float roomZ = Math.min(at.z() - TrayPose.MIN_Z, TrayPose.MAX_Z - at.z());
        return tilted.x() <= Math.max(still.x(), roomX) && tilted.z() <= Math.max(still.z(), roomZ);
    }

    private static float slide(float offset, float center, float half, float min, float max) {
        return Mth.clamp(offset, Math.min(0, min + half - center), Math.max(0, max - half - center));
    }

    private static void drawLabel(String label, PoseStack pose, MultiBufferSource buffers, int light) {
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
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        Vector3f half() {
            return Float.isFinite(minY)
                    ? new Vector3f(maxX - minX, maxY - minY, maxZ - minZ).mul(0.5f).max(new Vector3f(0.012f))
                    : new Vector3f(0.5f);
        }

        Vector3f center() {
            return Float.isFinite(minY) ? new Vector3f(maxX + minX, maxY + minY, maxZ + minZ).mul(0.5f) : new Vector3f();
        }

        VertexConsumer consumer() {
            // The base model and the glint require distinct consumers.
            return new VertexConsumer() {
                @Override
                public VertexConsumer addVertex(float x, float y, float z) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
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
    static void drawItem(ItemStack stack, PoseStack pose, MultiBufferSource buffers, int light, int overlay, boolean showContents, Sway sway) {
        if (!(stack.getItem() instanceof TrayItem tray)) return;
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(tray.getBlock().defaultBlockState(), pose, buffers, light, overlay);
        if (showContents) {
            List<TrayPose> positions = stack.getOrDefault(OneMoreCore.TRAY_POSES.get(), List.of());
            drawDishes(stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().toList(),
                    slot -> TrayPose.inSlot(positions, slot), sway, pose, buffers, light);
            drawLabel(stack.getOrDefault(OneMoreCore.LABEL.get(), ""), pose, buffers, light);
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
            drawItem(stack, pose, buffers, light, overlay, context != ItemDisplayContext.GUI, Sway.NONE);
        }
    }
}
