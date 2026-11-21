package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.OrderBoardBlockEntity;
import com.onemorestudios.onemorecore.ScreenRect;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;

import net.minecraft.nbt.CompoundTag;

/** The order screen up close: a click on a card asks the server for its ticket (the card under the mouse, by the drawn layout). */
final class ScreenBehavior implements StationBehavior {
    static void register() {
        DiegesisClient.registerBehavior(KitchenStations.SCREEN.id(), view -> new ScreenBehavior());
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        if (!(event instanceof SceneEvent.Click click) || !"screen".equals(click.node())
                || !(view.level().getBlockEntity(view.pos()) instanceof OrderBoardBlockEntity board)) return;
        float[] p = ScreenRenderer.pointer(view.pos(), view.level().getBlockState(view.pos()), ScreenRect.OWN);
        if (Float.isNaN(p[0])) return;
        int i = ScreenRect.orderAt(ScreenRect.OWN.width(), ScreenRect.OWN.height(), board.tickets.size(), view.level().getGameTime(), p[0], p[1]);
        if (i < 0 || board.tickets.get(i).orderId() == null) return;
        CompoundTag data = new CompoundTag();
        data.putUUID("order", board.tickets.get(i).orderId());
        data.putFloat("x", p[0]);
        data.putFloat("y", p[1]);
        view.sendAction("ticket", data);
    }
}
