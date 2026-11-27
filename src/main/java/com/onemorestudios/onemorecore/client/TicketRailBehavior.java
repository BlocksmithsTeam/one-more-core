package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TicketRailBlockEntity;
import com.onemorestudios.onemorecore.TicketRailStation;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Model nodes provide silhouettes; the painter adds the existing ticket's table and dish icons. */
final class TicketRailBehavior implements StationBehavior {
    static void register() { DiegesisClient.registerBehavior(TicketRailStation.ID, view -> new TicketRailBehavior()); }
    static boolean draws(BlockPos pos) { return DiegesisClient.isSceneDrawn(pos); }

    @Override
    public void onSceneReady(StationView view) {
        view.setInventoryFilter(stack -> stack.is(OneMoreCore.TICKET.get()));
        for (int i = 0; i < TicketRailBlockEntity.SIZE; i++) {
            int slot = i;
            view.node("ticket_" + i).ifPresent(node -> node.setPainter((pose, buffers, light) -> {
                if (view.level().getBlockEntity(view.pos()) instanceof TicketRailBlockEntity rail)
                    TicketRailRenderer.ink(rail.ticket(slot), rail.done(slot), pose, buffers, light);
            }));
        }
        frame(view, 0);
    }

    @Override
    public void frame(StationView view, float seconds) {
        var rail = view.level().getBlockEntity(view.pos()) instanceof TicketRailBlockEntity entity ? entity : null;
        for (int i = 0; i < TicketRailBlockEntity.SIZE; i++) {
            int slot = i;
            view.node("ticket_" + i).ifPresent(node -> {
                boolean occupied = rail != null && !rail.ticket(slot).isEmpty();
                node.setVisible(occupied);
                node.setEnabled(occupied);
                node.setTooltipItem(occupied ? rail.ticket(slot) : ItemStack.EMPTY);
                node.setOutlineSeenOnly(true);
                node.moveTo(0, occupied ? rail.slide(slot, net.minecraft.client.Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)) : 0, 0, 0);
            });
        }
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        if (!view.isFocused() || !(event instanceof SceneEvent.Click click) || click.button() != 0) return;
        for (int i = 0; i < TicketRailBlockEntity.SIZE; i++) {
            if (!click.node().equals("ticket_" + i)) continue;
            CompoundTag data = new CompoundTag();
            data.putInt("slot", i);
            view.sendAction("take", data);
        }
    }

    @Override
    public boolean onItemClick(StationView view, String node, ItemStack stack, int button, boolean shift) {
        if (!node.equals("rail") && !node.startsWith("ticket_")) return false;
        if (view.isFocused() && button == 0 && stack.is(OneMoreCore.TICKET.get())) view.sendAction("hang", new CompoundTag());
        return true;
    }
}
