package com.onemorestudios.onemorecore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Optional beta.15 API adapter: no second inventory or unattended process. */
public final class TicketRailStation {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "ticket_rail");
    public static final StationType TYPE = Diegesis.station(ID).block(OneMoreCore.TICKET_RAIL)
            .orientation(StationType.Orientation.BLOCK)
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown()
                    && TicketRailBlock.mayUse(player, pos))
            .logic(context -> new StationLogic() {
                private final Map<UUID, Long> requests = new HashMap<>();

                @Override
                public void onCustomAction(StationContext station, Player player, String action, CompoundTag data) {
                    if (!station.users().contains(player) || !TicketRailBlock.mayUse(player, station.pos())
                            || !(station.blockEntity() instanceof TicketRailBlockEntity rail) || rail.isRemoved()
                            || !station.level().getBlockState(station.pos()).is(OneMoreCore.TICKET_RAIL.get())) return;
                    boolean hang = action.equals("hang");
                    if (!hang && (!action.equals("take") || !data.contains("slot", Tag.TAG_INT))) return;
                    int slot = data.getInt("slot");
                    if (!hang && (slot < 0 || slot >= TicketRailBlockEntity.SIZE)) return;
                    long now = station.level().getGameTime();
                    if (requests.getOrDefault(player.getUUID(), Long.MIN_VALUE) == now) return;
                    requests.put(player.getUUID(), now);
                    ItemStack carried = player.inventoryMenu.getCarried();
                    if (hang) rail.hang(carried);
                    else if (carried.isEmpty()) player.inventoryMenu.setCarried(rail.take(slot));
                    player.inventoryMenu.broadcastChanges();
                }

                @Override
                public void onClose(StationContext station, Player player) { requests.remove(player.getUUID()); }
            }).build();

    private TicketRailStation() {}
    public static void register() { Diegesis.register(TYPE); }
}
