package com.onemorestudios.onemorecore;

import java.util.List;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.SlotRules;
import com.pockyl.diegesis.api.state.SlotStorage;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Loaded only when Diegesis is present. The tray up close: drag dishes from the inventory onto it and back (its six
 * places are the tray's own slots). The order rail up close: click a ticket to take a copy of it.
 */
public final class KitchenStations {
    private KitchenStations() {}

    public static final StationType TRAY = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "tray"))
            .block(OneMoreCore.TRAY).block(OneMoreCore.IRON_TRAY)
            .schema(StationSchema.builder().slots("dishes", TrayBlockEntity.SIZE, SlotRules.DEFAULT.maxStack(1).kept()).build())
            // An empty hand opens it; with an item the tray takes the dish or the ticket as without Diegesis; sneaking picks it up.
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown()
                    && mayUseTray(player, pos))
            .logic(context -> new StationLogic() {
                private final TrayBlockEntity tray = context.blockEntity() instanceof TrayBlockEntity entity ? entity : null;
                private boolean closing;

                {
                    if (tray != null) context.state().bindSlots("dishes", SlotStorage.of(tray.items, 0, TrayBlockEntity.SIZE));
                }

                private boolean bound(StationContext station) {
                    return tray != null && !tray.isRemoved() && station.blockEntity() == tray;
                }

                @Override
                public boolean mayTake(StationContext station, Player player, SlotRef slot) {
                    return bound(station) && mayUseTray(player, station.pos());
                }

                @Override
                public boolean mayPlace(StationContext station, Player player, SlotRef slot, ItemStack stack) {
                    return bound(station) && mayUseTray(player, station.pos());
                }

                @Override
                public void tick(StationContext station) {
                    if (bound(station) || closing || !(station.level() instanceof ServerLevel level)) return;
                    closing = true;
                    var server = level.getServer();
                    // Defer closing until Diegesis has finished ticking this station.
                    server.tell(new TickTask(server.getTickCount(), () -> {
                        if (Diegesis.activeStation(level, station.pos()).orElse(null) != station) return;
                        for (Player user : List.copyOf(station.users()))
                            if (user instanceof ServerPlayer player) Diegesis.close(player);
                    }));
                }
            })
            .build();

    private static boolean mayUseTray(Player player, BlockPos pos) {
        return !player.isSpectator() && (!(player.level() instanceof ServerLevel level) || level.mayInteract(player, pos));
    }

    /**
     * The menu screen and the order screen up close: the camera in front of the television, the mouse as the pointer
     * (tooltips of the dishes), and on the order screen a click on a card gives its ticket.
     */
    public static final StationType SCREEN = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen"))
            .block(OneMoreCore.MENU_BOARD).block(OneMoreCore.ORDER_BOARD)
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .logic(context -> new StationLogic() {
                @Override
                public void onCustomAction(StationContext station, Player player, String action, CompoundTag data) {
                    if (!"ticket".equals(action) || !(player instanceof ServerPlayer serverPlayer) || !station.users().contains(player)
                            || !data.hasUUID("order")) return;
                    ScreenClick.handle(new ScreenClick(station.pos(), data.getUUID("order"), data.getFloat("x"), data.getFloat("y")), serverPlayer);
                }
            })
            .build();

    /** The menu book up close: the open book on the table, its pages turned by clicks (client only, it shows the synced menu). */
    public static final StationType BOOK = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "menu_book"))
            .block(OneMoreCore.MENU_BOOK)
            .clientOnly()
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .build();

    public static void register() {
        Diegesis.register(BOOK);
        Diegesis.register(TRAY);
        Diegesis.register(SCREEN);
    }
}
