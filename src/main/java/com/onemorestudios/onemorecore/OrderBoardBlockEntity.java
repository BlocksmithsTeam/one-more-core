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
    /** One taken order: the table number, the dishes still wanted, the patience left (0..1), the price of the dishes. */
    public record Ticket(int table, List<ItemStack> items, float patience, int price) {
        public Ticket(int table, List<ItemStack> items, float patience) {
            this(table, items, patience, 0);
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
        if (level.getGameTime() % 10 != Math.floorMod(worldPosition.asLong(), 10)) return;
        CompoundTag now = encode(tickets(level), level.registryAccess());
        if (now.equals(sent)) return;
        sent = now;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** The tickets as a tag, at most OrderBoardBlock.TICKETS. */
    public static CompoundTag encode(List<Ticket> tickets, HolderLookup.Provider registries) {
        CompoundTag now = new CompoundTag();
        ListTag list = new ListTag();
        for (Ticket t : tickets) {
            if (list.size() >= OrderBoardBlock.TICKETS) break;
            CompoundTag c = new CompoundTag();
            c.putInt("table", t.table());
            // Patience in steps of 5 percent, so that a waiting order does not send every half second.
            c.putInt("patience", Math.round(t.patience() * 20));
            c.putInt("price", t.price());
            ListTag items = new ListTag();
            for (ItemStack s : t.items()) if (!s.isEmpty()) items.add(s.copyWithCount(1).save(registries));
            c.put("items", items);
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
            List<ItemStack> items = new ArrayList<>();
            for (Tag i : c.getList("items", Tag.TAG_COMPOUND)) if (items.size() < 16) ItemStack.parse(registries, i).ifPresent(items::add);
            if (out.size() < OrderBoardBlock.TICKETS)
                out.add(new Ticket(c.getInt("table"), items, Math.max(0, Math.min(20, c.getInt("patience"))) / 20f, Math.max(0, c.getInt("price"))));
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
