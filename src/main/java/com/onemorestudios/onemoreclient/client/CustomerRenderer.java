package com.onemorestudios.onemoreclient.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.Config;
import com.onemorestudios.onemoreclient.Customer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A player model with a default skin, the patience bar, the order icons and the served food. */
public class CustomerRenderer extends HumanoidMobRenderer<Customer, PlayerModel<Customer>> {
    private static class Model extends PlayerModel<Customer> {
        Model(ModelPart root, boolean slim) {
            super(root, slim);
        }

        @Override
        public void setupAnim(Customer entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            riding = entity.isSeated();
            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            // On a high seat the legs hang down. Legs that point forward go through the counter.
            BlockPos surface = entity.getSurface();
            if (riding && surface != null && entity.getY() + 0.6 - surface.getY() >= 0.75) {
                rightLeg.xRot = leftLeg.xRot = -0.2f;
                rightLeg.yRot = leftLeg.yRot = 0;
                rightPants.copyFrom(rightLeg);
                leftPants.copyFrom(leftLeg);
            }
        }
    }

    private final PlayerModel<Customer> wide, slim;
    private final ItemRenderer items;

    public CustomerRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        wide = model;
        slim = new Model(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        items = context.getItemRenderer();
    }

    // ponytail: one of the vanilla default skins, chosen by entity UUID. Add a skin pool with the streamer feature.
    private static PlayerSkin skin(Customer customer) {
        return DefaultPlayerSkin.get(customer.getUUID());
    }

    @Override
    public ResourceLocation getTextureLocation(Customer customer) {
        return skin(customer).texture();
    }

    @Override
    public void render(Customer customer, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        model = skin(customer).model() == PlayerSkin.Model.SLIM ? slim : wide;
        super.render(customer, yaw, partialTick, pose, buffers, light);
        Customer.State state = customer.getState();
        if (state == Customer.State.WAITING_ORDER || state == Customer.State.WAITING_FOOD) renderPatience(customer, pose, buffers);
        renderOrder(customer, pose, buffers);
        renderServed(customer, partialTick, pose, buffers);
    }

    private void renderPatience(Customer customer, PoseStack pose, MultiBufferSource buffers) {
        float left = customer.getPatience();
        int filled = Mth.clamp(Mth.ceil(left * 10), 0, 10);
        ChatFormatting color = left > 0.5f ? ChatFormatting.GREEN : left > 0.25f ? ChatFormatting.YELLOW : ChatFormatting.RED;
        Component bar = Component.literal("█".repeat(filled)).withStyle(color)
                .append(Component.literal("█".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY));
        Font font = getFont();
        pose.pushPose();
        pose.translate(0, customer.getBbHeight() + 0.35, 0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(0.0125f, -0.0125f, 0.0125f);
        font.drawInBatch(bar, -font.width(bar) / 2f, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    private void renderOrder(Customer customer, PoseStack pose, MultiBufferSource buffers) {
        List<ItemStack> order = customer.getOrder();
        pose.pushPose();
        pose.translate(0, customer.getBbHeight() + 0.75, 0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(0.4f, 0.4f, 0.4f);
        for (int i = 0; i < order.size(); i++) {
            pose.pushPose();
            // The half turn above mirrors X, so the first item goes to the right in local space.
            pose.translate(-(i - (order.size() - 1) / 2f) * 1.1f, 0, 0);
            items.renderStatic(order.get(i), ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, customer.level(), customer.getId() + i);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Draws the delivered items on top of the serving surface, between the customer and the surface centre. */
    private void renderServed(Customer customer, float partialTick, PoseStack pose, MultiBufferSource buffers) {
        List<ItemStack> served = customer.getServed();
        BlockPos surface = customer.getSurface();
        if (served.isEmpty() || surface == null) return;
        VoxelShape shape = customer.level().getBlockState(surface).getShape(customer.level(), surface);
        double top = shape.isEmpty() ? 1 : shape.max(net.minecraft.core.Direction.Axis.Y);
        double x = surface.getX() + 0.5 - Mth.lerp(partialTick, customer.xo, customer.getX());
        double y = surface.getY() + top - Mth.lerp(partialTick, customer.yo, customer.getY());
        double z = surface.getZ() + 0.5 - Mth.lerp(partialTick, customer.zo, customer.getZ());
        // Unit vector from the customer to the surface. The items line up at a right angle to it.
        double len = Math.max(0.001, Math.sqrt(x * x + z * z));
        double dx = x / len, dz = z / len;
        float facing = (float) (Mth.atan2(dx, dz) * Mth.RAD_TO_DEG);
        int light = LevelRenderer.getLightColor(customer.level(), surface.above());
        float scale = Config.FOOD_SCALE.get().floatValue();
        for (int i = 0; i < served.size(); i++) {
            ItemStack stack = served.get(i);
            double side = (i - (served.size() - 1) / 2.0) * 0.4;
            pose.pushPose();
            pose.translate(x - dx * 0.2 - dz * side, y, z - dz * 0.2 + dx * side);
            pose.mulPose(Axis.YP.rotationDegrees(facing));
            if (isFlat(stack, customer)) {
                pose.translate(0, 0.5f * scale / 32, 0);
                pose.mulPose(Axis.XP.rotationDegrees(90));
                pose.scale(0.5f * scale, 0.5f * scale, 0.5f * scale);
            } else {
                // A block model in the FIXED context is a half block that is centred on the origin.
                pose.translate(0, 0.25f * scale, 0);
                pose.scale(scale, scale, scale);
            }
            items.renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, customer.level(), customer.getId() + i);
            pose.popPose();
        }
    }

    private boolean isFlat(ItemStack stack, Customer customer) {
        String override = Config.lookup(Config.FOOD_DISPLAY.get(), BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (override != null) return override.equals("flat");
        return !items.getModel(stack, customer.level(), null, 0).isGui3d();
    }
}
