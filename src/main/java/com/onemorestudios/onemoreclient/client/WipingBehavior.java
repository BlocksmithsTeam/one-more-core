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
    /** The serving surfaces of the table this station stands on. */
    private java.util.Set<net.minecraft.core.BlockPos> table = java.util.Set.of();
    private int ticks;
    private float height;

    static void register() { DiegesisClient.registerBehavior(WipingStation.ID, view -> new WipingBehavior()); }

    @Override
    public void onSceneReady(StationView view) {
        height = (float) (Dirt.top(view.level(), view.pos()) * 16);
        float offset = height - 16;
        view.node("cloth").ifPresent(node -> node.moveTo(0, offset, 0, 0));
        // Coins and dishes lie on the real table top too.
        view.node("coins").ifPresent(node -> node.moveTo(0, offset, 0, 0));
        collected = false;
        // The camera sees the whole table: it moves over the middle of the table and back along its view (from the
        // target to the camera of wiping.json: 0, 21, -11 pixels) once per block that the table is wider than one.
        table = Dirt.tableOf(view.level(), view.pos());
        int minX = view.pos().getX(), maxX = minX, minZ = view.pos().getZ(), maxZ = minZ;
        for (var p : table) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        float back = Math.max(maxX - minX, maxZ - minZ) * 0.8f;
        float mx = ((minX + maxX + 1) / 2f - view.pos().getX() - 0.5f) * 16, mz = ((minZ + maxZ + 1) / 2f - view.pos().getZ() - 0.5f) * 16;
        view.moveCamera(mx, offset + 21 * back, mz - 11 * back);
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

    /** True after a click sent "collect": the coins and dishes hop up and go, before the server's answer comes. */
    private boolean collected;

    /** Shows the coins and the dishes that lie on this surface. */
    private void showTableItems(StationView view) {
        var items = com.onemorestudios.onemoreclient.TableItems.CLIENT.get(view.pos());
        show(view, "coins", !collected && items != null && items.coins() > 0);
    }

    /** A node that comes back starts on the table top again (it may have hopped away before). */
    private void show(StationView view, String id, boolean on) {
        view.node(id).ifPresent(n -> {
            if (n.isVisible() == on || n.isMoving()) return;
            if (on) n.moveTo(0, height - 16, 0, 0);
            n.setVisible(on);
        });
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        // A click on the coins collects them: they hop up towards the register with a pop.
        if (event instanceof SceneEvent.Click click && "coins".equals(click.node()) && !collected) {
            collected = true;
            view.sendAction("collect", new CompoundTag());
            float lift = height - 16;
            view.node("coins").filter(n -> n.isVisible()).ifPresent(n -> n.hopTo(0, lift + 10, 0, 6, 0.35f));
            return;
        }
        if (event instanceof SceneEvent.Contact contact && "cloth".equals(contact.node()) && "table".equals(contact.other()))
            touching = contact.touching();
        if (event instanceof SceneEvent.Release) touching = false;
    }

    /** Ticks since the surface became clean. */
    private int cleanTicks;

    @Override
    public void tick(StationView view) {
        showTableItems(view);
        if (collected && !com.onemorestudios.onemoreclient.TableItems.CLIENT.containsKey(view.pos())) collected = false;
        // Clean (the server cleaned it at dirtCleanFraction): a short look at the clean table, then the station closes.
        boolean dirty = false;
        for (var p : table) dirty |= Dirt.CLIENT.containsKey(p);
        if (view.isFocused() && !dirty && !com.onemorestudios.onemoreclient.TableItems.CLIENT.containsKey(view.pos())) {
            if (++cleanTicks == 12) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.screen != null && com.pockyl.diegesis.api.client.DiegesisClient.isStationScreen(mc.screen)) mc.screen.onClose();
            }
            return;
        }
        cleanTicks = 0;
        if (!view.isFocused() || ++ticks % 4 != 0) return;
        view.node("cloth").filter(node -> node.isHeld()).ifPresent(node -> {
            var at = view.node("cloth_model").orElse(node).scenePosition();
            // On this block the trigger tells the contact; on the other blocks of the table, the cloth is over them.
            var over = view.pos().offset(Math.floorDiv((int) Math.floor(at.x()), 16), 0, Math.floorDiv((int) Math.floor(at.z()), 16));
            if (!(touching || !over.equals(view.pos()) && table.contains(over))) return;
            CompoundTag data = new CompoundTag();
            data.putFloat("x", at.x());
            data.putFloat("z", at.z());
            view.sendAction("wipe", data);
        });
    }
}
