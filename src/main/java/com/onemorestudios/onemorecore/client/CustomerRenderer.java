package com.onemorestudios.onemorecore.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.compat.GrillingSkewers;

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
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

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
            if (riding) liveliness(entity, ageInTicks);
        }

        /**
         * A seated customer is not a statue. While it waits it looks round: at the table, to the sides, at a player near it,
         * and it talks with a customer at the same table (turns to it, nods, moves a hand). While it eats, it looks down
         * at the food and its hand goes to its mouth. Everything is a function of the time and the entity id, so it needs no
         * state and every client shows the same.
         */
        private void liveliness(Customer entity, float age) {
            Customer.State state = entity.getState();
            if (state == Customer.State.EATING) {
                float bite = Mth.sin(age * 0.45f);
                head.xRot = 0.35f + 0.08f * bite;
                head.yRot = 0;
                rightArm.xRot = -1.25f + 0.35f * Math.max(0, bite);
                rightArm.yRot = -0.35f;
                copyLayers();
                return;
            }
            // Segments of 60 to 99 ticks; each one has a target. The head turns to it in the first 10 ticks of the segment.
            int phase = Math.floorMod(entity.getId() * 37, 200);
            float t = age + phase;
            int length = 60 + Math.floorMod(entity.getId() * 13, 40);
            int segment = (int) Math.floor(t / length);
            float into = t - segment * (float) length;
            float[] from = target(entity, segment - 1), to = target(entity, segment);
            float k = Mth.clamp(into / 10f, 0, 1);
            k = k * k * (3 - 2 * k);
            head.yRot = Mth.lerp(k, from[0], to[0]);
            head.xRot = Mth.lerp(k, from[1], to[1]);
            if (to[2] > 0) {
                // Talking: a small nod and a hand that moves.
                head.xRot += 0.06f * Mth.sin(age * 0.5f);
                rightArm.xRot = -0.55f + 0.18f * Mth.sin(age * 0.33f);
                rightArm.zRot = 0.1f;
            }
            copyLayers();
        }

        /** {head yaw, head pitch, talking 0/1} of a segment: the table, a side, a player near it, or a neighbour to talk to. */
        private static float[] target(Customer entity, int segment) {
            int pick = Math.floorMod(segment * 7919 + entity.getId() * 31, 10);
            if (pick <= 1) {
                Customer other = neighbour(entity);
                if (other != null) return new float[] {relativeYaw(entity, other.position()), 0.05f, 1};
            }
            if (pick == 2) {
                var player = Minecraft.getInstance().player;
                if (player != null && player.distanceToSqr(entity) < 64) return new float[] {relativeYaw(entity, player.getEyePosition()), -0.05f, 0};
            }
            if (pick == 3) return new float[] {0.6f, 0.05f, 0};
            if (pick == 4) return new float[] {-0.6f, 0.05f, 0};
            // Most of the time: the table in front.
            return new float[] {0, 0.3f, 0};
        }

        /** Another seated customer at most 3 blocks away: at the same table in a group. */
        private static Customer neighbour(Customer entity) {
            Customer best = null;
            double bestD = 9;
            for (Customer c : entity.level().getEntitiesOfClass(Customer.class, entity.getBoundingBox().inflate(3))) {
                double d = c.distanceToSqr(entity);
                if (c != entity && c.isSeated() && d < bestD) { best = c; bestD = d; }
            }
            return best;
        }

        /** The head yaw that looks at the point, relative to the body, at most 70 degrees to each side. */
        private static float relativeYaw(Customer entity, net.minecraft.world.phys.Vec3 point) {
            double dx = point.x - entity.getX(), dz = point.z - entity.getZ();
            float world = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
            float rel = Mth.wrapDegrees(world - entity.yBodyRot);
            return Mth.clamp(rel, -70, 70) * Mth.DEG_TO_RAD;
        }

        private void copyLayers() {
            hat.copyFrom(head);
            rightSleeve.copyFrom(rightArm);
            leftSleeve.copyFrom(leftArm);
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

    /** One lookup per account name; a failed or empty one is asked again after RETRY_MS (the account can be new, the network down). */
    private static final Map<String, CompletableFuture<PlayerSkin>> SKINS = new HashMap<>();
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final long RETRY_MS = 30_000;

    /** The skin of the customer's Minecraft account. A vanilla default skin until it is loaded, or if the account does not exist. */
    private static PlayerSkin skin(Customer customer) {
        PlayerSkin fallback = DefaultPlayerSkin.get(customer.getUUID());
        String name = customer.getSkinName();
        if (name.isEmpty()) return fallback;
        // Both steps load in the background, like a player head. The render thread only reads the result.
        Long failedAt = FAILED.get(name);
        if (failedAt != null && System.currentTimeMillis() - failedAt > RETRY_MS) { FAILED.remove(name); SKINS.remove(name); }
        CompletableFuture<PlayerSkin> lookup = SKINS.computeIfAbsent(name, n -> SkullBlockEntity.fetchGameProfile(n).thenComposeAsync(
                profile -> profile.isPresent() ? Minecraft.getInstance().getSkinManager().getOrLoad(profile.get())
                        : CompletableFuture.completedFuture(null), Minecraft.getInstance()));
        PlayerSkin skin = lookup.isCompletedExceptionally() ? null : lookup.getNow(null);
        if (lookup.isDone() && skin == null) FAILED.putIfAbsent(name, System.currentTimeMillis());
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

    private static final ResourceLocation BUBBLE = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "textures/entity/order_bubble.png");
    /** Bubble size in blocks. The texture body is 32x20 pixels with 8 pixel caps; the tail is 8x8 pixels. */
    static final float BODY_H = 0.66f, CAP = BODY_H * 8 / 20, SLOT = 0.44f;

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
        quad(vc, m, barL, barB, barR, barT, z + 0.005f, 28, 28, 32, 32, 0xFF3A3A3A);
        int red = left < 0.5f ? 230 : (int) Mth.lerp((left - 0.5f) * 2, 230, 90);
        int green = left < 0.5f ? (int) Mth.lerp(left * 2, 60, 200) : 200;
        int blue = 70;
        // Under a quarter of the patience the bar blinks white twice a second.
        if (left < 0.25f && customer.tickCount % 10 < 5) red = green = blue = 255;
        quad(vc, m, barL, barB, barL + (barR - barL) * left, barT, z + 0.01f, 28, 28, 32, 32, 0xFF000000 | red << 16 | green << 8 | blue);
        float itemsY = (barT + top) / 2;
        // A badge on the top left corner of the bubble for special guests: a star (VIP), a heart (regular), a house (family).
        var guest = customer.getGuestType();
        if (guest != Customer.Type.NORMAL) {
            String mark = guest == Customer.Type.VIP ? "\u2605" : guest == Customer.Type.REGULAR ? "\u2665" : "\u2302";
            int color = guest == Customer.Type.VIP ? 0xFFFFD24A : guest == Customer.Type.REGULAR ? 0xFFFF6B7A : 0xFF8FD3FF;
            Font font = getFont();
            pose.pushPose();
            pose.translate(-half + 0.06f, top - 0.03f, 0);
            pose.scale(0.022f, -0.022f, 0.022f);
            font.drawInBatch(mark, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        if (order.isEmpty()) {
            Font font = getFont();
            pose.pushPose();
            pose.translate(0, itemsY + 0.13f, 0);
            pose.scale(0.025f, -0.025f, 0.025f);
            font.drawInBatch("!", -font.width("!") / 2f, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        } else {
            int hovered = BubbleHover.slot(customer, order.size(), itemsY);
            if (hovered >= 0) {
                // A darker square behind the item that the player looks at.
                float sx = (hovered - (order.size() - 1) / 2f) * SLOT, r = SLOT / 2 - 0.02f;
                quad(buffers.getBuffer(RenderType.text(BUBBLE)), m, sx - r, itemsY - r, sx + r, itemsY + r, z + 0.012f,
                        28, 28, 32, 32, 0xB0000000);
            }
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.translate(0, itemsY, 0);
            pose.scale(0.4f, 0.4f, 0.4f);
            float time = customer.tickCount + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            for (int i = 0; i < order.size(); i++) {
                pose.pushPose();
                // The half turn above mirrors X, so the first item goes to the right in local space.
                pose.translate(-(i - (order.size() - 1) / 2f) * SLOT / 0.4f, 0, 0);
                if (i == hovered) {
                    // The looked-at item grows a little and bobs.
                    float grow = 1.2f + 0.04f * Mth.sin(time * 0.3f);
                    pose.scale(grow, grow, grow);
                }
                // The bubble shows the item as it is; the 3D form is only for the food on the table.
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
        // The first item (the main dish, as the server puts it first) stands big right in front of the guest; the others are
        // smaller, to the left and right of it and a little further in.
        for (int i = 0; i < served.size(); i++) {
            ItemStack stack = served.get(i);
            int k = i == 0 ? 0 : (i + 1) / 2 * (i % 2 == 1 ? 1 : -1);
            double side = k * 0.27, in = i == 0 ? 0.22 : 0.08;
            pose.pushPose();
            pose.translate(x - dx * in - dz * side, y, z - dz * in + dx * side);
            pose.mulPose(Axis.YP.rotationDegrees(facing));
            renderFood(stack, customer.level(), pose, buffers, light, scale * (i == 0 ? 1.15f : 0.75f), customer.getId() + i);
            pose.popPose();
        }
    }

    /**
     * Draws a food with the middle of its bottom at the origin, as on a table: the placed block of a food item at 0.4
     * size, a 3D item standing, or a 2D item or grilling skewer lying flat.
     */
    public static void renderFood(ItemStack stack, net.minecraft.world.level.Level level, PoseStack pose, MultiBufferSource buffers, int light, float scale, int seed) {
        if (GrillingSkewers.matches(stack)) {
            renderSkewer(stack, level, pose, buffers, light, scale, seed);
            return;
        }
        var items = Minecraft.getInstance().getItemRenderer();
        pose.pushPose();
        String display = display(stack, level);
        if (display.equals("block")) {
            pose.scale(0.4f * scale, 0.4f * scale, 0.4f * scale);
            pose.translate(-0.5, 0, -0.5);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(FoodModels.block(stack), pose, buffers, light, OverlayTexture.NO_OVERLAY);
        } else {
            if (display.equals("flat")) {
                pose.translate(0, 0.5f * scale / 32, 0);
                pose.mulPose(Axis.XP.rotationDegrees(90));
                pose.scale(0.5f * scale, 0.5f * scale, 0.5f * scale);
            } else {
                // A block model in the FIXED context is a half block that is centred on the origin.
                pose.translate(0, 0.25f * scale, 0);
                pose.scale(scale, scale, scale);
            }
            items.renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, seed);
        }
        pose.popPose();
    }

    private static void renderSkewer(ItemStack stack, net.minecraft.world.level.Level level, PoseStack pose,
            MultiBufferSource buffers, int light, float scale, int seed) {
        var items = Minecraft.getInstance().getItemRenderer();
        var bounds = new TrayRenderer.DishBounds();
        items.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                new PoseStack(), type -> bounds.consumer(), level, seed);
        var half = bounds.half();
        var turn = SkewerPose.lyingTurn(new Quaternionf(), half);
        var center = bounds.center();
        float size = 0.5f * scale;
        pose.pushPose();
        pose.scale(size, size, size);
        pose.translate(0, TrayRenderer.rotatedHalf(half, turn).y, 0);
        pose.mulPose(turn);
        pose.translate(-center.x, -center.y, -center.z);
        items.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, seed);
        pose.popPose();
    }

    /** Whether a dish shows as the block it places (see display). */
    static boolean showsBlock(ItemStack stack) {
        return display(stack, Minecraft.getInstance().level).equals("block");
    }

    /**
     * "flat", "upright" or "block". Without a config entry: a 3D item model stays upright; a 2D item whose item places
     * a modelled block (cake, pie and meal blocks) shows that block; any other 2D item lies flat.
     */
    private static String display(ItemStack stack, net.minecraft.world.level.Level level) {
        boolean placesBlock = FoodModels.block(stack) != null;
        String override = Config.lookup(Config.FOOD_DISPLAY.get(), BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (override != null) return override.equals("block") && !placesBlock ? "upright" : override;
        if (Minecraft.getInstance().getItemRenderer().getModel(stack, level, null, 0).isGui3d()) return "upright";
        return placesBlock ? "block" : "flat";
    }
}
