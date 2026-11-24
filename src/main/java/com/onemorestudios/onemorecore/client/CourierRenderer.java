package com.onemorestudios.onemorecore.client;

import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.Courier;
import com.onemorestudios.onemorecore.OneMoreCore;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class CourierRenderer extends HumanoidMobRenderer<Courier, PlayerModel<Courier>> {
    // Every courier uses the same built-in skin and the matching arm width.
    private static final PlayerSkin SKIN = DefaultPlayerSkin.get(new UUID(0, 2));

    public CourierRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(
                SKIN.model() == PlayerSkin.Model.SLIM ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER),
                SKIN.model() == PlayerSkin.Model.SLIM) {
            @Override
            public void setupAnim(Courier courier, float swing, float amount, float age, float headYaw, float headPitch) {
                super.setupAnim(courier, swing, amount, age, headYaw, headPitch);
                if (!courier.carrying()) return;
                rightArm.setRotation(-1.25f, -0.18f, -0.05f);
                leftArm.setRotation(-1.25f, 0.18f, 0.05f);
                rightSleeve.copyFrom(rightArm);
                leftSleeve.copyFrom(leftArm);
            }
        }, 0.5f);
        ItemRenderer items = context.getItemRenderer();
        addLayer(new RenderLayer<Courier, PlayerModel<Courier>>(this) {
            private final ItemStack box = new ItemStack(OneMoreCore.CARDBOARD_BOX_ITEM.get());

            @Override
            public void render(PoseStack pose, MultiBufferSource buffers, int light, Courier courier,
                    float swing, float amount, float partialTick, float age, float yaw, float pitch) {
                if (!courier.carrying()) return;
                pose.pushPose();
                getParentModel().body.translateAndRotate(pose);
                pose.translate(0, 0.26, -0.46);
                pose.mulPose(Axis.XP.rotationDegrees(180));
                pose.scale(0.72f, 0.72f, 0.72f);
                items.renderStatic(box, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                        pose, buffers, courier.level(), courier.getId());
                pose.popPose();
            }
        });
    }

    @Override
    public ResourceLocation getTextureLocation(Courier courier) {
        return SKIN.texture();
    }
}
