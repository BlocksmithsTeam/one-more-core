package com.onemorestudios.onemoreclient;


import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.SlotRules;
import com.pockyl.diegesis.api.state.SlotStorage;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Loaded only when Diegesis is present. The tray up close: drag dishes from the inventory onto it and back (its six
 * places are the tray's own slots). The order rail up close: click a ticket to take a copy of it.
 */
public final class KitchenStations {
    private KitchenStations() {}

    public static final StationType TRAY = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "tray"))
            .block(OneMoreClient.TRAY)
            .schema(StationSchema.builder().slots("dishes", TrayBlockEntity.SIZE, SlotRules.DEFAULT.maxStack(1).kept()).build())
            // An empty hand opens it; with an item the tray takes the dish or the ticket as without Diegesis; sneaking picks it up.
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .logic(context -> new StationLogic() {
                {
                    if (context.blockEntity() instanceof TrayBlockEntity tray) context.state().bindSlots("dishes", SlotStorage.of(tray.items, 0, TrayBlockEntity.SIZE));
                }
            })
            .build();

    /**
     * The menu screen and the order screen up close: the camera in front of the television, the mouse as the pointer
     * (tooltips of the dishes), and on the order screen a click on a card gives its ticket.
     */
    public static final StationType SCREEN = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "screen"))
            .block(OneMoreClient.MENU_BOARD).block(OneMoreClient.ORDER_BOARD)
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .logic(context -> new StationLogic() {
                @Override
                public void onCustomAction(StationContext station, Player player, String action, net.minecraft.nbt.CompoundTag data) {
                    if (!"ticket".equals(action) || !(station.level() instanceof ServerLevel level) || !station.users().contains(player)) return;
                    OrderBoardBlock.giveTicket(level, player, station.pos(), OrderBoardBlock.ticketsAt(level, station.pos()), data.getInt("index"));
                }
            })
            .build();

    /** The menu book up close: the open book on the table, its pages turned by clicks (client only, it shows the synced menu). */
    public static final StationType BOOK = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "menu_book"))
            .block(OneMoreClient.MENU_BOOK)
            .clientOnly()
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .build();

    public static void register() {
        Diegesis.register(BOOK);
        Diegesis.register(TRAY);
        Diegesis.register(SCREEN);
    }
}
