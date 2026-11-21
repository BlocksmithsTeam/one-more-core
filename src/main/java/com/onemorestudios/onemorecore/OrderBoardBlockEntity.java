package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The tickets of the order rail. The server builds them twice a second and sends them when they changed; not saved. */
public class OrderBoardBlockEntity extends BlockEntity {
    /**
     * One taken order of a party: the text of its table's card ("" without one), a row of dishes still wanted for each guest, the
     * patience left (0..1), the price of the dishes.
     */
    public record Ticket(String table, List<List<ItemStack>> rows, float patience, int price) {
        /** Every dish of the order. */
        public List<ItemStack> items() {
            return rows.stream().flatMap(List::stream).toList();
        }
    }

    private CompoundTag sent = new CompoundTag();
    /** Client: the tickets of the last update. */
    public List<Ticket> tickets = List.of();

    public OrderBoardBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreCore.ORDER_BOARD_ENTITY.get(), pos, state);
    }

    List<Ticket> tickets(ServerLevel level) {
        Restaurant r = Restaurants.get(level).forComputer(worldPosition);
        return r == null ? List.of() : r.tickets();
    }

    void serverTick(ServerLevel level) {
        if (level.getGameTime() % 40 != Math.floorMod(worldPosition.asLong(), 40)) return;
        // The Order Screen joins the restaurant it stands in (or the nearest one), showing the orders at first.
        Restaurant r = ScreenSettings.owner(level, worldPosition);
        if (r != null) r.ensureScreen(worldPosition, Restaurant.SCREEN_ORDERS);
    }

    /** The tickets as a tag, at most OrderBoardBlock.TICKETS. */
    public static CompoundTag encode(List<Ticket> tickets, HolderLookup.Provider registries) {
        CompoundTag now = new CompoundTag();
        ListTag list = new ListTag();
        for (Ticket t : tickets) {
            if (list.size() >= OrderBoardBlock.TICKETS) break;
            CompoundTag c = new CompoundTag();
            c.putString("table", t.table());
            // Patience in steps of 5 percent, so that a waiting order does not send every half second.
            c.putInt("patience", Math.round(t.patience() * 20));
            c.putInt("price", t.price());
            ListTag rows = new ListTag();
            for (List<ItemStack> row : t.rows()) {
                ListTag items = new ListTag();
                for (ItemStack s : row) if (!s.isEmpty()) items.add(s.copyWithCount(1).save(registries));
                rows.add(items);
            }
            c.put("rows", rows);
            list.add(c);
        }
        now.put("tickets", list);
        return now;
    }

    /** The tickets of a tag; not trusted beyond its shape. */
    public static List<Ticket> decode(CompoundTag tag, HolderLookup.Provider registries) {
        List<Ticket> out = new ArrayList<>();
        for (Tag t : tag.getList("tickets", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            List<List<ItemStack>> rows = new ArrayList<>();
            for (Tag r : c.getList("rows", Tag.TAG_LIST)) {
                if (rows.size() >= 8) break;
                List<ItemStack> items = new ArrayList<>();
                for (Tag i : (ListTag) r) if (items.size() < 8) ItemStack.parse(registries, i).ifPresent(items::add);
                rows.add(items);
            }
            if (out.size() < OrderBoardBlock.TICKETS)
                out.add(new Ticket(TableCards.clean(c.getString("table")), rows, Math.max(0, Math.min(20, c.getInt("patience"))) / 20f, Math.max(0, c.getInt("price"))));
        }
        return out;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return sent.copy();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        tickets = decode(tag, registries);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }
}
