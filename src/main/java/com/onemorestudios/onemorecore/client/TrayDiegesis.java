package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.TrayBlockEntity;
import com.onemorestudios.onemorecore.TrayPose;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.impl.client.scene.PlacedShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/** Uses the same baked shape, scale, and pivot as the beta.15 placing renderer. */
final class TrayDiegesis implements StationBehavior {
    static void register() {
        DiegesisClient.registerBehavior(KitchenStations.TRAY.id(), view -> new TrayDiegesis());
    }

    static boolean drawsDishes(BlockPos pos) {
        return DiegesisClient.isSceneDrawn(pos)
                && DiegesisClient.station(pos).filter(view -> view.type() == KitchenStations.TRAY).isPresent();
    }

    @Override
    public void onSceneReady(StationView view) {
        resolveRestingPoses(view.state());
    }

    @Override
    public void onStateChanged(StationView view, StationState.Changes changes) {
        resolveRestingPoses(view.state());
    }

    private static void resolveRestingPoses(StationState state) {
        for (int i = 0; i < TrayBlockEntity.SIZE; i++) {
            SlotRef slot = new SlotRef(KitchenStations.TRAY_DISHES, i);
            ItemPose pose = state.getPose(slot);
            ItemStack stack = state.getItem(slot);
            if (pose == null || pose.lift() != 0 || stack.isEmpty()) continue;
            // Only the client has the baked model. Complete the server's rest marker before Diegesis lays out its nodes.
            PlacedShape shape = shape(stack);
            Quaternionf turn = restingTurn(shape, pose.rotation());
            float lift = shape.extents(turn, TrayPose.ITEM_SIZE * pose.scale()).y;
            state.setPose(slot, new ItemPose(pose.x(), pose.y(), pose.z(), lift, turn, pose.scale(), pose.on(), pose.wanted()));
        }
    }

    static void drawDish(ItemStack stack, TrayPose at, TrayRenderer.Sway sway, PoseStack pose, MultiBufferSource buffers, int light, int seed) {
        PlacedShape shape = shape(stack);
        float size = TrayPose.ITEM_SIZE * at.scale();
        Quaternionfc rest = at.lift() == 0 ? restingTurn(shape, at.rotation()) : at.rotation();
        Quaternionfc turn = sway.turn(rest);
        var extents = shape.extents(turn, size);
        float lift = at.lift();
        if (sway != TrayRenderer.Sway.NONE) {
            var still = shape.extents(rest, size);
            if (!TrayRenderer.allowsTilt(at, still, extents)) {
                turn = rest;
                extents = still;
            }
            lift += extents.y - still.y;
        }
        if (at.lift() == 0) lift = extents.y;
        pose.pushPose();
        TrayRenderer.moveDish(pose, at, sway, turn, extents, lift, shape.modelScale(size));
        shape.draw(stack, pose, buffers, light, Minecraft.getInstance().level, seed);
        pose.popPose();
    }

    private static Quaternionf restingTurn(PlacedShape shape, Quaternionfc yaw) {
        Quaternionf turn = new Quaternionf(yaw);
        return shape.isFlat() ? turn.rotateX(Mth.HALF_PI) : turn;
    }

    private static PlacedShape shape(ItemStack stack) {
        return PlacedShape.of(stack, Minecraft.getInstance().level, true, false);
    }
}
