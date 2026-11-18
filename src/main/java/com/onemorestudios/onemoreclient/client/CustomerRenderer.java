package com.onemorestudios.onemoreclient.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.Config;
import com.onemorestudios.onemoreclient.Customer;
import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

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

    // ponytail: one map entry per account name for the whole session, and a failed lookup stays failed. Add expiry if this shows.
    private static final Map<String, CompletableFuture<PlayerSkin>> SKINS = new HashMap<>();

    /** The skin of the customer's Minecraft account. A vanilla default skin until it is loaded, or if the account does not exist. */
    private static PlayerSkin skin(Customer customer) {
        PlayerSkin fallback = DefaultPlayerSkin.get(customer.getUUID());
        String name = customer.getSkinName();
        if (name.isEmpty()) return fallback;
        // Both steps load in the background, like a player head. The render thread only reads the result.
        PlayerSkin skin = SKINS.computeIfAbsent(name, n -> SkullBlockEntity.fetchGameProfile(n).thenComposeAsync(
                profile -> profile.isPresent() ? Minecraft.getInstance().getSkinManager().getOrLoad(profile.get())
                        : CompletableFuture.completedFuture(null), Minecraft.getInstance())).getNow(null);
        return skin == null ? fallback : skin;
    }

    // The vanilla name tag is at the height of the patience bar. This puts it above the order icons.
    @Override
    protected void renderNameTag(Customer customer, Component name, PoseStack pose, MultiBufferSource buffers, int light, float partialTick) {
        pose.pushPose();
        pose.translate(0, 0.75, 0);
        super.renderNameTag(customer, name, pose, buffers, light, partialTick);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(Customer customer) {
        return skin(customer).texture();
    }

    @Override
    public void render(Customer customer, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        model = skin(customer).model() == PlayerSkin.Model.SLIM ? slim : wide;
        super.render(customer, yaw, partialTick, pose, buffers, light);
        renderBubble(customer, pose, buffers);
        renderServed(customer, partialTick, pose, buffers);
    }

    private static final ResourceLocation BUBBLE = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "textures/entity/order_bubble.png");
    /** Bubble size in blocks. The texture body is 32x20 pixels with 8 pixel caps; the tail is 8x8 pixels. */
    private static final float BODY_H = 0.66f, CAP = BODY_H * 8 / 20, SLOT = 0.44f;

    /**
     * A speech bubble above the head: a "!" while the customer waits for a player to take the order, then the ordered
     * items. A patience bar at the bottom of the bubble.
     */
    private void renderBubble(Customer customer, PoseStack pose, MultiBufferSource buffers) {
        Customer.State state = customer.getState();
        if (state != Customer.State.WAITING_ORDER && state != Customer.State.WAITING_FOOD) return;
        List<ItemStack> order = customer.getOrder();
        int slots = Math.max(1, order.size());
        float half = Math.max(CAP, (slots * SLOT + 0.16f) / 2);
        pose.pushPose();
        pose.translate(0, customer.getBbHeight() + 0.75, 0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(BUBBLE));
        float top = BODY_H / 2, bottom = -BODY_H / 2, z = -0.04f;
        // Body: left cap, stretched middle, right cap. Tail under the middle.
        quad(vc, m, -half, bottom, -half + CAP, top, z, 0, 0, 8, 20, 0xFFFFFFFF);
        quad(vc, m, -half + CAP, bottom, half - CAP, top, z, 8, 0, 24, 20, 0xFFFFFFFF);
        quad(vc, m, half - CAP, bottom, half, top, z, 24, 0, 32, 20, 0xFFFFFFFF);
        quad(vc, m, -CAP / 2, bottom - CAP, CAP / 2, bottom, z, 12, 20, 20, 28, 0xFFFFFFFF);
        // Patience bar: a dark track and a coloured fill, from red (0) over yellow to green (1).
        float left = Mth.clamp(customer.getPatience(), 0, 1);
        float barL = -half + 0.1f, barR = half - 0.1f, barB = bottom + 0.07f, barT = barB + 0.06f;
        quad(vc, m, barL, barB, barR, barT, z + 0.005f, 28, 28, 32, 32, 0xFF4A3C32);
        int red = left < 0.5f ? 230 : (int) Mth.lerp((left - 0.5f) * 2, 230, 90);
        int green = left < 0.5f ? (int) Mth.lerp(left * 2, 60, 200) : 200;
        int blue = 70;
        // Under a quarter of the patience the bar blinks white twice a second.
        if (left < 0.25f && customer.tickCount % 10 < 5) red = green = blue = 255;
        quad(vc, m, barL, barB, barL + (barR - barL) * left, barT, z + 0.01f, 28, 28, 32, 32, 0xFF000000 | red << 16 | green << 8 | blue);
        float itemsY = (barT + top) / 2;
        if (order.isEmpty()) {
            Font font = getFont();
            pose.pushPose();
            pose.translate(0, itemsY + 0.13f, 0);
            pose.scale(0.025f, -0.025f, 0.025f);
            font.drawInBatch("!", -font.width("!") / 2f, 0, 0xFF3C3028, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        } else {
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.translate(0, itemsY, 0);
            pose.scale(0.4f, 0.4f, 0.4f);
            for (int i = 0; i < order.size(); i++) {
                pose.pushPose();
                // The half turn above mirrors X, so the first item goes to the right in local space.
                pose.translate(-(i - (order.size() - 1) / 2f) * SLOT / 0.4f, 0, 0);
                items.renderStatic(order.get(i), ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, customer.level(), customer.getId() + i);
                pose.popPose();
            }
        }
        pose.popPose();
    }

    /** One textured rectangle, drawn from both sides. u and v are pixels of the 32x32 bubble texture. */
    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int u0, int v0, int u1, int v1, int argb) {
        float a0 = u0 / 32f, a1 = u1 / 32f, b0 = v0 / 32f, b1 = v1 / 32f;
        int light = LightTexture.FULL_BRIGHT;
        vc.addVertex(m, x0, y1, z).setColor(argb).setUv(a0, b0).setLight(light);
        vc.addVertex(m, x0, y0, z).setColor(argb).setUv(a0, b1).setLight(light);
        vc.addVertex(m, x1, y0, z).setColor(argb).setUv(a1, b1).setLight(light);
        vc.addVertex(m, x1, y1, z).setColor(argb).setUv(a1, b0).setLight(light);
        vc.addVertex(m, x1, y1, z).setColor(argb).setUv(a1, b0).setLight(light);
        vc.addVertex(m, x1, y0, z).setColor(argb).setUv(a1, b1).setLight(light);
        vc.addVertex(m, x0, y0, z).setColor(argb).setUv(a0, b1).setLight(light);
        vc.addVertex(m, x0, y1, z).setColor(argb).setUv(a0, b0).setLight(light);
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
            String display = display(stack, customer);
            if (display.equals("block")) {
                // The placed block of a food item (a cake, a pie block): drawn at 0.4 size, with its bottom centre on the surface.
                pose.scale(0.4f * scale, 0.4f * scale, 0.4f * scale);
                pose.translate(-0.5, 0, -0.5);
                Minecraft.getInstance().getBlockRenderer().renderSingleBlock(((BlockItem) stack.getItem()).getBlock().defaultBlockState(),
                        pose, buffers, light, OverlayTexture.NO_OVERLAY);
                pose.popPose();
                continue;
            }
            if (display.equals("flat")) {
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

    /**
     * "flat", "upright" or "block". Without a config entry: a 3D item model stays upright; a 2D item whose item places
     * a modelled block (cake, pie and meal blocks) shows that block; any other 2D item lies flat.
     */
    private String display(ItemStack stack, Customer customer) {
        boolean placesBlock = stack.getItem() instanceof BlockItem item
                && item.getBlock().defaultBlockState().getRenderShape() == RenderShape.MODEL;
        String override = Config.lookup(Config.FOOD_DISPLAY.get(), BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (override != null) return override.equals("block") && !placesBlock ? "upright" : override;
        if (items.getModel(stack, customer.level(), null, 0).isGui3d()) return "upright";
        return placesBlock ? "block" : "flat";
    }
}
