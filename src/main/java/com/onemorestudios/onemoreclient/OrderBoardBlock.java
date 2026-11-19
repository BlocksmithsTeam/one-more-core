package com.onemorestudios.onemoreclient;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The order rail of the kitchen, on a wall: every order that a player took hangs on it as a small ticket (table number,
 * dishes, patience), oldest first. A click on a ticket gives a copy of it, to stick on a tray.
 */
public class OrderBoardBlock extends MenuBoardBlock {
    /** Tickets on the rail; the rail is as wide as the menu board. */
    public static final int TICKETS = 8;

    public OrderBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OrderBoardBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != OneMoreClient.ORDER_BOARD_ENTITY.get() ? null
                : (l, p, s, be) -> ((OrderBoardBlockEntity) be).serverTick((ServerLevel) l);
    }

    /** The ticket under a hit: the hit's place along the board, from its left edge, in tickets. -1 off the tickets. */
    public static int ticketAt(BlockState state, BlockPos pos, Vec3 hit) {
        Direction right = state.getValue(FACING).getCounterClockWise();
        double u = (hit.x - pos.getX() - 0.5) * right.getStepX() + (hit.z - pos.getZ() - 0.5) * right.getStepZ();
        int i = (int) Math.floor((u + W / 2) / (W / TICKETS));
        return i >= 0 && i < TICKETS ? i : -1;
    }

    /** Board width in blocks, as the renderer draws it. */
    public static final float W = 2;

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel sl) || !(level.getBlockEntity(pos) instanceof OrderBoardBlockEntity board)) return InteractionResult.SUCCESS;
        int i = ticketAt(state, pos, hit.getLocation());
        List<OrderBoardBlockEntity.Ticket> tickets = board.tickets(sl);
        if (i < 0 || i >= tickets.size()) return InteractionResult.PASS;
        OrderBoardBlockEntity.Ticket t = tickets.get(i);
        ItemStack ticket = new ItemStack(OneMoreClient.TICKET.get());
        ticket.set(OneMoreClient.TABLE.get(), t.table());
        ticket.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(t.items()));
        TrayBlock.give(player, ticket);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8f, 1.4f);
        return InteractionResult.SUCCESS;
    }
}
