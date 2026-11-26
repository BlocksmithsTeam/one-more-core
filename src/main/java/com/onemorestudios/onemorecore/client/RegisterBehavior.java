package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.KitchenStations;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;
import net.minecraft.nbt.CompoundTag;

/** The server supplies piece counts; the nodes draw pieces and handle withdrawal clicks. */
final class RegisterBehavior implements StationBehavior {
    static void register() {
        DiegesisClient.registerBehavior(KitchenStations.REGISTER_ID, view -> new RegisterBehavior());
    }

    @Override
    public void onSceneReady(StationView view) {
        view.node("bills").ifPresent(node -> node.setPainter((pose, buffers, light) -> {
            pose.pushPose();
            pose.scale(16, 16, 16);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
            MoneyRenderer.banknotes(pose, buffers, light, view.state().data().getInt("bills"));
            pose.popPose();
        }));
        view.node("coins").ifPresent(node -> node.setPainter((pose, buffers, light) -> {
            pose.pushPose();
            pose.scale(16, 16, 16);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90));
            MoneyRenderer.coins(pose, buffers, light, view.state().data().getInt("coins"));
            pose.popPose();
        }));
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        if (!view.isFocused() || !(event instanceof SceneEvent.Click click)) return;
        if ("bills".equals(click.node())) view.sendAction("take_banknote", new CompoundTag());
        if ("coins".equals(click.node())) view.sendAction("take_coin", new CompoundTag());
    }
}
