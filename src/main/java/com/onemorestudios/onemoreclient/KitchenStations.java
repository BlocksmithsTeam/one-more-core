package com.onemorestudios.onemoreclient;

import java.util.List;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.SlotRules;
import com.pockyl.diegesis.api.state.SlotStorage;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

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

    public static final StationType RAIL = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "order_board"))
            .block(OneMoreClient.ORDER_BOARD)
            .schema(railSchema())
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .logic(context -> new StationLogic() {
                @Override
                public void updateOutputs(StationContext station) {
                    List<OrderBoardBlockEntity.Ticket> tickets = station.level() instanceof ServerLevel level
                            && station.blockEntity() instanceof OrderBoardBlockEntity board ? board.tickets(level) : List.of();
                    for (int i = 0; i < OrderBoardBlock.TICKETS; i++)
                        station.state().setOutput("ticket" + i, i < tickets.size() ? ticket(tickets.get(i)) : ItemStack.EMPTY);
                }

                @Override
                public ItemStack takeOutput(StationContext station, Player player, String output) {
                    return station.state().getOutput(output).copy();
                }

                @Override
                public void tick(StationContext station) {
                    if (station.level().getGameTime() % 10 == 0) updateOutputs(station);
                }
            })
            .build();

    private static StationSchema railSchema() {
        var b = StationSchema.builder();
        for (int i = 0; i < OrderBoardBlock.TICKETS; i++) b.output("ticket" + i);
        return b.build();
    }

    static ItemStack ticket(OrderBoardBlockEntity.Ticket t) {
        ItemStack ticket = new ItemStack(OneMoreClient.TICKET.get());
        ticket.set(OneMoreClient.TABLE.get(), t.table());
        ticket.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(t.items()));
        return ticket;
    }

    public static void register() {
        Diegesis.register(TRAY);
        Diegesis.register(RAIL);
    }
}
