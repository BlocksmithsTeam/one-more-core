package com.onemorestudios.onemorecore.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.TrayItem;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class TrayCarryRenderer {
    private static final float FIRST_PERSON_SCALE = 0.85f;
    private static final float THIRD_PERSON_SCALE = 0.72f;
    private static final float ARM_PITCH = -61 * Mth.DEG_TO_RAD;
    private static final float RIM_HEIGHT = 2 / 16f;
    private static final float GRIP_X = 9 / 16f;
    private static final float HAND_Y = 9 / 16f;
    private static final float MAX_TRAY_ROLL = 3 * Mth.DEG_TO_RAD;
    private static final float MAX_DISH_SLIDE = 0.35f;
    private static final float MAX_DISH_TILT = 2.5f * Mth.DEG_TO_RAD;
    private static final Map<AbstractClientPlayer, CarrySway> CARRY_SWAY = new WeakHashMap<>();

    public static final EnumProxy<HumanoidModel.ArmPose> TRAY_POSE = new EnumProxy<>(HumanoidModel.ArmPose.class,
            true, (IArmPoseTransformer) TrayCarryRenderer::poseArms);

    private static class PoseRegistration {
        // Avoid querying the enum while NeoForge is initializing our proxy from its enum constructor.
        private static final boolean PRESENT = hasTrayPose();
    }

    private record PlayerTrayPass(PoseStack pose, PlayerModel<AbstractClientPlayer> model, ItemStack main, ItemStack off,
            CarryMotion motion, float ageInTicks) {}

    // Up and forward offsets in tray pixels.
    private record HandMotion(float up, float forward) {
        private static final HandMotion STILL = new HandMotion(0, 0);
    }

    private record CarryMotion(HandMotion right, HandMotion left, TrayRenderer.Sway dishes) {
        private static final CarryMotion STILL = new CarryMotion(HandMotion.STILL, HandMotion.STILL, TrayRenderer.Sway.NONE);

        float meanForward() {
            return (right.forward() + left.forward()) * 0.5f;
        }

        Quaternionf trayTilt(float width, float rise, float lead) {
            // Yaw and roll align the grip axis. Pitch then turns around that axis without moving the grips.
            float yaw = (float) Math.atan2(lead, width);
            float roll = Mth.clamp((float) Math.atan2(rise, Math.hypot(width, lead)), -MAX_TRAY_ROLL, MAX_TRAY_ROLL);
            return new Quaternionf().rotateY(yaw).rotateZ(roll).rotateX(-2.5f * Mth.DEG_TO_RAD * meanForward());
        }
    }

    private static PlayerTrayPass playerTrayPass;

    private LocalPlayer lastPlayer;
    private boolean trayDrawn;
    private boolean offHandWasTray;

    void beginFrame(RenderFrameEvent.Pre event) {
        clearPlayerTrayPass();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != lastPlayer) {
            lastPlayer = player;
            offHandWasTray = false;
        }
        trayDrawn = false;
    }

    void renderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isScoping()) return;

        boolean mainHand = event.getHand() == InteractionHand.MAIN_HAND;
        ItemStack displayed = event.getItemStack();
        if (!mainHand) offHandWasTray = isTray(displayed);

        if (trayDrawn) {
            event.setCanceled(true);
        } else if (isTray(displayed)) {
            event.setCanceled(true);
            trayDrawn = true;
            drawFirstPerson(event, player, displayed, event.getEquipProgress());
        } else if (onlyRenderingThisHand(player, event.getHand())) {
            // Using a bow, or holding a charged main-hand crossbow, can omit the tray hand's event.
            ItemStack other = player.getItemInHand(mainHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            if (isTray(other)) {
                event.setCanceled(true);
                trayDrawn = true;
                drawFirstPerson(event, player, other, 0);
            }
        } else if (mainHand ? isTray(player.getOffhandItem()) || offHandWasTray : isTray(player.getMainHandItem())) {
            event.setCanceled(true);
        }
    }

    private static boolean onlyRenderingThisHand(LocalPlayer player, InteractionHand hand) {
        if (player.isUsingItem()) {
            ItemStack used = player.getUseItem();
            if (used.is(Items.BOW) || used.is(Items.CROSSBOW)) return hand == player.getUsedItemHand();
            return hand == InteractionHand.MAIN_HAND && player.getUsedItemHand() == hand
                    && isChargedCrossbow(player.getOffhandItem());
        }
        return hand == InteractionHand.MAIN_HAND && isChargedCrossbow(player.getMainHandItem());
    }

    private static boolean isChargedCrossbow(ItemStack stack) {
        return stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack);
    }

    private static void drawFirstPerson(RenderHandEvent event, LocalPlayer player, ItemStack stack, float equip) {
        PoseStack pose = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        int light = event.getPackedLight();
        float pitch = event.getInterpolatedPitch();
        float down = Mth.clamp(pitch / 90, 0, 1);
        float tilt = pitch < 0 ? Math.max(-12, pitch * 0.25f) : 59.4f * down;
        CarryMotion motion = carryMotion(player, event.getPartialTick());
        Quaternionf trayTilt = motion.trayTilt(2 * GRIP_X, (motion.right().up() - motion.left().up()) / 16,
                (motion.right().forward() - motion.left().forward()) / 16);
        Vector3f rightGrip = new Vector3f(GRIP_X * FIRST_PERSON_SCALE, 0, 0).rotate(trayTilt);

        pose.pushPose();
        pose.translate(0, -0.38f - 0.025f * down - 1.2f * equip, -1.0f - 0.24f * down);
        pose.mulPose(Axis.XP.rotationDegrees(tilt));
        pose.translate(0, (RIM_HEIGHT + motion.right().up() / 32 + motion.left().up() / 32) * FIRST_PERSON_SCALE,
                -motion.meanForward() / 16 * FIRST_PERSON_SCALE);
        if (!player.isInvisible() && Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            drawFirstPersonArm(renderer, player, pose, buffers, light, tilt, HumanoidArm.RIGHT, rightGrip);
            drawFirstPersonArm(renderer, player, pose, buffers, light, tilt, HumanoidArm.LEFT, rightGrip);
        }
        pose.mulPose(trayTilt);
        // Face the ticket towards the carrier, with the tray turning around the middle of the grips.
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(FIRST_PERSON_SCALE, FIRST_PERSON_SCALE, FIRST_PERSON_SCALE);
        pose.translate(-0.5, -RIM_HEIGHT, -0.5);
        TrayRenderer.drawItem(stack, pose, buffers, light, OverlayTexture.NO_OVERLAY, true,
                motion.dishes());
        pose.popPose();
    }

    private static CarryMotion carryMotion(AbstractClientPlayer player, float partialTick) {
        if (Minecraft.getInstance().isPaused()) return CarryMotion.STILL;
        return CARRY_SWAY.computeIfAbsent(player, ignored -> new CarrySway()).sample(player, partialTick);
    }

    private static class CarrySway {
        /** Push on the dishes per degree/s² of the hands' turning: they lag when a turn starts, run on when it stops. */
        private static final float TURN_PUSH = 0.018f;
        /** Turning speeds are smoothed over about this many seconds, so mouse steps do not jolt the dishes. */
        private static final float TURN_SMOOTH = 0.05f;
        private final Spring side = new Spring(14, 0.22f);
        private final Spring fore = new Spring(14, 0.22f);
        private final HandSway right = new HandSway(20, 0.38f);
        private final HandSway left = new HandSway(21.5f, 0.40f);
        private long lastFrame;
        private float lastYaw, lastPitch, yawSpeed, pitchSpeed;

        CarryMotion sample(AbstractClientPlayer player, float partialTick) {
            long now = Util.getNanos();
            float dt = (now - lastFrame) / 1.0e9f;
            if (dt >= 0.001f) {
                float yaw = player.getViewYRot(partialTick);
                float pitch = player.getViewXRot(partialTick);
                lastFrame = now;
                if (dt > 0.25f) {
                    side.reset();
                    fore.reset();
                    right.reset();
                    left.reset();
                    yawSpeed = pitchSpeed = 0;
                } else {
                    float stride = player.walkAnimation.position(partialTick) * 0.6662f;
                    float walk = player.onGround() ? Mth.clamp(player.walkAnimation.speed(partialTick), 0, 1) : 0;
                    // Inertia: the dishes feel the change of the turning speed, standing still or not.
                    float blend = 1 - (float) Math.exp(-dt / TURN_SMOOTH);
                    float newYawSpeed = Mth.lerp(blend, yawSpeed, Mth.wrapDegrees(yaw - lastYaw) / dt);
                    float newPitchSpeed = Mth.lerp(blend, pitchSpeed, (pitch - lastPitch) / dt);
                    float yawAccel = (newYawSpeed - yawSpeed) / dt;
                    float pitchAccel = (newPitchSpeed - pitchSpeed) / dt;
                    yawSpeed = newYawSpeed;
                    pitchSpeed = newPitchSpeed;
                    side.step(0.75f * walk * Mth.sin(stride), TURN_PUSH * yawAccel, dt);
                    fore.step(0.6f * walk * Mth.cos(2 * stride), TURN_PUSH * pitchAccel, dt);
                    float walkUp = 0.65f * walk * Mth.sin(stride);
                    float walkForward = 0.7f * walk * Mth.cos(stride);
                    // Positive yaw turns right, putting the left hand on the outside of the turn.
                    float turn = Mth.clamp(newYawSpeed / 90, -1, 1);
                    right.step(walkUp, walkForward, yawAccel, pitchAccel, 1 - 0.25f * turn, dt);
                    left.step(-walkUp, -walkForward, -yawAccel, pitchAccel, 1 + 0.25f * turn, dt);
                }
                lastYaw = yaw;
                lastPitch = pitch;
            }
            return new CarryMotion(right.motion(), left.motion(), dishes());
        }

        private TrayRenderer.Sway dishes() {
            float amount = Mth.sqrt(side.position * side.position + fore.position * fore.position);
            if (amount < 0.001f) return TrayRenderer.Sway.NONE;
            float x = side.position / Math.max(1, amount);
            float z = fore.position / Math.max(1, amount);
            return new TrayRenderer.Sway(x * MAX_DISH_SLIDE, z * MAX_DISH_SLIDE, z * MAX_DISH_TILT, -x * MAX_DISH_TILT);
        }
    }

    private static class HandSway {
        private final Spring up;
        private final Spring forward;

        HandSway(float omega, float damping) {
            up = new Spring(omega, damping);
            forward = new Spring(omega, damping);
        }

        void reset() {
            up.reset();
            forward.reset();
        }

        void step(float walkUp, float walkForward, float yawAccel, float pitchAccel, float turnWeight, float dt) {
            up.step(walkUp, 0.015f * yawAccel * turnWeight, dt);
            forward.step(walkForward, (0.025f * yawAccel + 0.018f * pitchAccel) * turnWeight, dt);
        }

        HandMotion motion() {
            return new HandMotion(0.6f * Mth.clamp(up.position, -1, 1), 0.4f * Mth.clamp(forward.position, -1, 1));
        }
    }

    private static class Spring {
        private static final float STEP = 1 / 240f;
        private final float omega, damping;
        private float position, velocity;

        Spring(float omega, float damping) {
            this.omega = omega;
            this.damping = damping;
        }

        void reset() {
            position = velocity = 0;
        }

        /** Moves towards {@code target} as a lightly damped spring, pushed by {@code push} (units/s²). */
        void step(float target, float push, float dt) {
            // Fixed small sub-steps (semi-implicit Euler) keep it stable at any frame rate.
            for (float left = dt; left > 0; left -= STEP) {
                float h = Math.min(STEP, left);
                float accel = -omega * omega * (position - target) - 2 * damping * omega * velocity - push;
                velocity += accel * h;
                position += velocity * h;
            }
            position = Mth.clamp(position, -1.5f, 1.5f);
        }
    }

    private static void drawFirstPersonArm(PlayerRenderer renderer, LocalPlayer player, PoseStack pose,
            MultiBufferSource buffers, int light, float tilt, HumanoidArm arm, Vector3f rightGrip) {
        boolean right = arm == HumanoidArm.RIGHT;
        int side = right ? 1 : -1;
        float armCenter = armCenter(player);
        pose.pushPose();
        pose.translate(side * rightGrip.x, side * rightGrip.y - 0.024f, side * rightGrip.z);
        pose.mulPose(Axis.XP.rotationDegrees(-tilt));
        pose.mulPose(Axis.XP.rotationDegrees(-55));
        pose.mulPose(Axis.ZP.rotationDegrees(side * 24));
        pose.scale(0.86f, 0.86f, 0.86f);
        // PlayerRenderer draws vanilla arm geometry around its normal model pivot.
        pose.translate(side * (5 / 16f + armCenter), -(2 / 16f + HAND_Y), 0);
        if (right) renderer.renderRightHand(pose, buffers, light, player);
        else renderer.renderLeftHand(pose, buffers, light, player);
        pose.popPose();
    }

    static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            if (event.getSkin(skin) instanceof PlayerRenderer renderer) renderer.addLayer(new CarryLayer(renderer));
        }
    }

    private static boolean isTray(ItemStack stack) {
        return stack.getItem() instanceof TrayItem;
    }

    private static ItemStack heldTray(AbstractClientPlayer player) {
        return isTray(player.getMainHandItem()) ? player.getMainHandItem() : player.getOffhandItem();
    }

    private static float armCenter(AbstractClientPlayer player) {
        return (player.getSkin().model() == PlayerSkin.Model.SLIM ? 0.5f : 1) / 16;
    }

    private static boolean hasTrayPose() {
        for (HumanoidModel.ArmPose pose : HumanoidModel.ArmPose.values()) {
            if (pose.name().equals("ONEMORECORE_TRAY_CARRY")) return true;
        }
        return false;
    }

    static HumanoidModel.ArmPose armPose(LivingEntity entity) {
        return PoseRegistration.PRESENT && entity instanceof AbstractClientPlayer ? TRAY_POSE.getValue() : null;
    }

    private static void poseArms(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
        model.attackTime = 0;
        PlayerTrayPass pass = playerTrayPass;
        CarryMotion motion = pass != null && pass.model() == model ? pass.motion() : CarryMotion.STILL;
        // HumanoidModel adds a forward bend after the pose callback on a crouching model.
        float crouchBend = model.crouching ? 0.4f : 0;
        holdTray(model.rightArm, motion.right(), crouchBend);
        holdTray(model.leftArm, motion.left(), crouchBend);
        if (pass != null && pass.model() == model) {
            // Cancel vanilla's idle bob, which runs after this callback even when the springs are at rest.
            AnimationUtils.bobModelPart(model.rightArm, pass.ageInTicks(), -1);
            AnimationUtils.bobModelPart(model.leftArm, pass.ageInTicks(), 1);
        }
    }

    private static void holdTray(ModelPart part, HandMotion motion, float crouchBend) {
        float gripY = HAND_Y * Mth.cos(ARM_PITCH) - motion.up() * THIRD_PERSON_SCALE / 16;
        float pitch = -(float) Math.acos(gripY / HAND_Y);
        part.xRot = pitch - crouchBend;
        part.yRot = 0;
        part.zRot = 0;
        // Shoulder pitch sets the wrist height. Adjust its depth by the part the arm's arc cannot reach.
        part.z = HAND_Y * 16 * (Mth.sin(ARM_PITCH) - Mth.sin(pitch)) - motion.forward() * THIRD_PERSON_SCALE;
    }

    static void preparePlayer(RenderPlayerEvent.Pre event) {
        clearPlayerTrayPass();
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;
        if (player.isSpectator() || !isTray(heldTray(player))) return;
        PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();
        float partialTick = event.getPartialTick();
        playerTrayPass = new PlayerTrayPass(event.getPoseStack(), model, player.getMainHandItem(), player.getOffhandItem(),
                carryMotion(player, partialTick), player.tickCount + partialTick);
        if (PoseRegistration.PRESENT) {
            // Either hand can be the one vanilla selects to pose during an item use.
            model.rightArmPose = model.leftArmPose = TRAY_POSE.getValue();
        } else {
            // The mod metadata must opt in to enum extensions. Without it, draw the same pose in the layer.
            setSkipArms(model, true);
        }
    }

    static void finishPlayer(RenderPlayerEvent.Post event) {
        clearPlayerTrayPass();
    }

    private static void clearPlayerTrayPass() {
        if (playerTrayPass != null && !PoseRegistration.PRESENT) setSkipArms(playerTrayPass.model(), false);
        playerTrayPass = null;
    }

    static boolean rendersBetweenPlayerHands(ItemStack stack, PoseStack pose) {
        return playerTrayPass != null && playerTrayPass.pose() == pose
                && (playerTrayPass.main() == stack || playerTrayPass.off() == stack);
    }

    private static void setSkipArms(PlayerModel<AbstractClientPlayer> model, boolean skip) {
        model.leftArm.skipDraw = model.rightArm.skipDraw = skip;
        model.leftSleeve.skipDraw = model.rightSleeve.skipDraw = skip;
    }

    private static class CarryLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        CarryLayer(PlayerRenderer renderer) {
            super(renderer);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            ItemStack tray = heldTray(player);
            if (!isTray(tray)) return;

            PlayerModel<AbstractClientPlayer> model = getParentModel();
            PlayerTrayPass pass = playerTrayPass;
            CarryMotion motion = pass != null && pass.pose() == pose ? pass.motion() : carryMotion(player, partialTick);
            if (!PoseRegistration.PRESENT) {
                setSkipArms(model, false);
                holdTray(model.rightArm, motion.right(), 0);
                holdTray(model.leftArm, motion.left(), 0);
                model.rightSleeve.copyFrom(model.rightArm);
                model.leftSleeve.copyFrom(model.leftArm);
                drawSkinArms(pose, buffers, light, player, model);
            }

            float center = armCenter(player);
            Vector3f rightGrip = grip(model.rightArm, -center);
            Vector3f leftGrip = grip(model.leftArm, center);
            pose.pushPose();
            // Center the tray between the hands. At this scale the handles sit 6.48 model pixels out,
            // inside the grip of both the regular and slim arms.
            pose.translate((rightGrip.x + leftGrip.x) * 0.5f, (rightGrip.y + leftGrip.y) * 0.5f - 0.025f,
                    (rightGrip.z + leftGrip.z) * 0.5f);
            // Model coordinates are down-positive and mirrored relative to block coordinates.
            pose.mulPose(Axis.ZP.rotationDegrees(180));
            pose.mulPose(motion.trayTilt(leftGrip.x - rightGrip.x, leftGrip.y - rightGrip.y, leftGrip.z - rightGrip.z));
            pose.scale(THIRD_PERSON_SCALE, THIRD_PERSON_SCALE, THIRD_PERSON_SCALE);
            pose.translate(-0.5, -RIM_HEIGHT, -0.5);
            TrayRenderer.drawItem(tray, pose, buffers, light, OverlayTexture.NO_OVERLAY, true,
                    motion.dishes());
            pose.popPose();
        }

        private static void drawSkinArms(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                PlayerModel<AbstractClientPlayer> model) {
            if (player.isInvisibleTo(Minecraft.getInstance().player)) return;
            int color = player.isInvisible() ? 0x26FFFFFF : -1;
            int overlay = LivingEntityRenderer.getOverlayCoords(player, 0);
            var skin = buffers.getBuffer(RenderType.entityTranslucent(player.getSkin().texture()));
            model.rightArm.render(pose, skin, light, overlay, color);
            model.leftArm.render(pose, skin, light, overlay, color);
            model.rightSleeve.render(pose, skin, light, overlay, color);
            model.leftSleeve.render(pose, skin, light, overlay, color);
        }

        private static Vector3f grip(ModelPart arm, float center) {
            return new Vector3f(center, HAND_Y, 0).rotateX(arm.xRot).rotateY(arm.yRot).rotateZ(arm.zRot)
                    .add(arm.x / 16, arm.y / 16, arm.z / 16);
        }
    }
}
