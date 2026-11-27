package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.KitchenStations;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.NodeView;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;

/** Server-owned counts, with local drawer motion and Diegesis model silhouettes. */
final class RegisterBehavior implements StationBehavior {
    private final RegisterDrawer drawer = new RegisterDrawer();

    static void register() {
        DiegesisClient.registerBehavior(KitchenStations.REGISTER_ID, view -> new RegisterBehavior());
    }

    @Override
    public void onSceneReady(StationView view) {
        pose(view);
    }

    @Override
    public void onOpen(StationView view) {
        drawer.target(true);
    }

    @Override
    public void onClose(StationView view) {
        drawer.target(false);
    }

    @Override
    public boolean holdsBlock(StationView view) {
        return drawer.progress() > 0;
    }

    @Override
    public void frame(StationView view, float seconds) {
        float before = drawer.progress();
        drawer.target(view.isInUse());
        drawer.advance(seconds);
        if (before == 0 && drawer.progress() > 0)
            view.playSound(SoundEvents.BARREL_OPEN, "drawer", 0.5F, 0.95F);
        if (before > 0 && drawer.progress() == 0)
            view.playSound(SoundEvents.BARREL_CLOSE, "drawer", 0.5F, 0.95F);
        pose(view);
    }

    private void pose(StationView view) {
        boolean shown = view.isBlockHidden();
        view.node("body").ifPresent(node -> node.setVisible(shown));
        view.node("drawer").ifPresent(node -> {
            node.setVisible(shown);
            node.moveTo(0, 0, 11 * (1 - drawer.progress()), 0);
        });
        pieces(view, "bills", "bill_", 2);
        pieces(view, "coins", "coin_", 6);
    }

    private void pieces(StationView view, String tray, String prefix, int limit) {
        int count = view.state().data().getInt(tray);
        boolean enabled = view.isFocused() && drawer.progress() == 1 && count > 0;
        view.node(tray).ifPresent(node -> node.setEnabled(enabled));
        var player = Minecraft.getInstance().player;
        boolean outlined = enabled && player != null && player.inventoryMenu.getCarried().isEmpty()
                && view.node(tray).map(NodeView::isHovered).orElse(false);
        for (int i = 0; i < limit; i++) {
            boolean visible = i < count;
            view.node(prefix + i).ifPresent(node -> {
                node.setVisible(visible);
                node.setOutlined(visible && outlined);
                node.setOutlineSeenOnly(true);
            });
        }
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        if (!view.isFocused() || drawer.progress() != 1 || !(event instanceof SceneEvent.Click click)) return;
        if ("bills".equals(click.node())) view.sendAction("take_banknote", new CompoundTag());
        if ("coins".equals(click.node())) view.sendAction("take_coin", new CompoundTag());
    }
}
