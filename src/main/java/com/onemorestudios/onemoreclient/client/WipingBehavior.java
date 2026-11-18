package com.onemorestudios.onemoreclient.client;

import com.onemorestudios.onemoreclient.Dirt;
import com.onemorestudios.onemoreclient.WipingStation;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;

import net.minecraft.nbt.CompoundTag;

/** The carried cloth sends positions; only the server removes dirt. */
final class WipingBehavior implements StationBehavior {
    private boolean touching;
    private int ticks;
    private float height;

    static void register() { DiegesisClient.registerBehavior(WipingStation.ID, view -> new WipingBehavior()); }

    @Override
    public void onSceneReady(StationView view) {
        height = (float) (Dirt.top(view.level(), view.pos()) * 16);
        float offset = height - 16;
        view.node("cloth").ifPresent(node -> node.moveTo(0, offset, 0, 0));
        view.moveCamera(0, offset, 0);
    }

    @Override
    public void frame(StationView view, float deltaSeconds) {
        view.node("cloth").ifPresent(cloth -> view.node("cloth_model").ifPresent(model -> {
            if (cloth.isHeld() && view.isFocused() && height != 16) {
                // beta.11 carries at the scene's fixed height; project the model onto the furniture's actual top.
                view.cursorOn(height + 0.15f).ifPresent(at -> {
                    var origin = cloth.scenePosition();
                    model.moveTo(at.x - origin.x(), at.y - origin.y(), at.z - origin.z(), 0);
                });
            } else model.moveTo(0, cloth.isHeld() ? height - 16 : 0, 0, 0);
        }));
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        if (event instanceof SceneEvent.Contact contact && "cloth".equals(contact.node()) && "table".equals(contact.other()))
            touching = contact.touching();
        if (event instanceof SceneEvent.Release) touching = false;
    }

    /** Ticks since the surface became clean. */
    private int cleanTicks;

    @Override
    public void tick(StationView view) {
        // Clean (the server cleaned it at dirtCleanFraction): a short look at the clean table, then the station closes.
        if (view.isFocused() && !Dirt.CLIENT.containsKey(view.pos())) {
            if (++cleanTicks == 12) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.screen != null && com.pockyl.diegesis.api.client.DiegesisClient.isStationScreen(mc.screen)) mc.screen.onClose();
            }
            return;
        }
        cleanTicks = 0;
        if (!view.isFocused() || ++ticks % 4 != 0 || !touching) return;
        view.node("cloth").filter(node -> node.isHeld()).ifPresent(node -> {
            var at = view.node("cloth_model").orElse(node).scenePosition();
            CompoundTag data = new CompoundTag();
            data.putFloat("x", at.x());
            data.putFloat("z", at.z());
            view.sendAction("wipe", data);
        });
    }
}
