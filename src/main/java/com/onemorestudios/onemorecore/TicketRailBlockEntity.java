package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The real tickets; the optional station only sends intent to this storage. */
public class TicketRailBlockEntity extends BlockEntity {
    public static final int SIZE = 6, DONE_TICKS = 60;
    private final NonNullList<ItemStack> tickets = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final long[] hung = new long[SIZE], done = new long[SIZE];

    public TicketRailBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreCore.TICKET_RAIL_ENTITY.get(), pos, state);
    }

    public ItemStack ticket(int slot) { return slot >= 0 && slot < SIZE ? tickets.get(slot) : ItemStack.EMPTY; }
    public boolean done(int slot) { return slot >= 0 && slot < SIZE && done[slot] != 0; }

    public float slide(int slot, float partial) {
        return level == null ? 0 : Math.max(0, 1 - (level.getGameTime() - hung[slot] + partial) / 7f) * 2;
    }

    /** Moves exactly one ticket, including in creative; a refused stack stays untouched. */
    public boolean hang(ItemStack stack) {
        if (level == null || level.isClientSide || !stack.is(OneMoreCore.TICKET.get())) return false;
        for (int i = 0; i < SIZE; i++) {
            if (!tickets.get(i).isEmpty()) continue;
            tickets.set(i, stack.split(1));
            hung[i] = level.getGameTime();
            done[i] = 0;
            changed();
            paper();
            return true;
        }
        return false;
    }

    public ItemStack take(int slot) {
        if (level == null || level.isClientSide || ticket(slot).isEmpty()) return ItemStack.EMPTY;
        ItemStack ticket = tickets.set(slot, ItemStack.EMPTY);
        done[slot] = 0;
        changed();
        paper();
        return ticket;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void paper() {
        level.playSound(null, worldPosition, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.7f, 1.2f);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TicketRailBlockEntity rail) {
        if (!(level instanceof ServerLevel server)) return;
        long now = level.getGameTime();
        for (int i = 0; i < SIZE; i++) {
            ItemStack ticket = rail.ticket(i);
            if (ticket.isEmpty()) continue;
            if (rail.done(i)) {
                if (now >= rail.done[i]) Block.popResource(level, pos, rail.take(i));
            } else if (now % 20 == 0 && TicketItem.finished(server, ticket)) {
                rail.done[i] = now + DONE_TICKS;
                rail.changed();
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, tickets, true, registries);
        tag.putLongArray("hung", hung);
        tag.putLongArray("done", done);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tickets.clear();
        ContainerHelper.loadAllItems(tag, tickets, registries);
        long[] savedHung = tag.getLongArray("hung"), savedDone = tag.getLongArray("done");
        for (int i = 0; i < SIZE; i++) {
            hung[i] = i < savedHung.length ? savedHung[i] : 0;
            done[i] = i < savedDone.length ? savedDone[i] : 0;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
