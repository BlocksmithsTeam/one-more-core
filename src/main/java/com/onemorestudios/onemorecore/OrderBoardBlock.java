package com.onemorestudios.onemorecore;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Order Screen: a TV that shows the orders at first: a card for each party (its table card's text, a row of dishes for each
 * guest, patience, price), oldest first. A click on a card gives its ticket (ScreenClick), to stick on a tray.
 */
public class OrderBoardBlock extends MenuBoardBlock {
    /** Tickets on the rail; the rail is as wide as the menu board. */
    public static final int TICKETS = 16;

    public OrderBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OrderBoardBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != OneMoreCore.ORDER_BOARD_ENTITY.get() ? null
                : (l, p, s, be) -> ((OrderBoardBlockEntity) be).serverTick((ServerLevel) l);
    }

    @Override
    public int defaultMode() {
        return Restaurant.SCREEN_ORDERS;
    }

    /** The orders that the screen at a position shows (an Order Screen, or a television in orders mode), or null. */
    static List<OrderBoardBlockEntity.Ticket> ticketsAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (ScreenRect.of(state) == null) return null;
        Restaurant r = ScreenSettings.owner(level, pos);
        if (r != null && state.getBlock() instanceof MenuBoardBlock board) r.ensureScreen(pos, board.defaultMode());
        return r != null && ScreenSettings.mode(r.screens.getOrDefault(pos, Restaurant.SCREEN_OFF)) == Restaurant.SCREEN_ORDERS ? r.tickets() : null;
    }

    /** Gives the current ticket for a displayed order if it is still active. */
    static void giveTicket(ServerLevel level, Player player, BlockPos pos, List<OrderBoardBlockEntity.Ticket> tickets, UUID orderId) {
        if (tickets == null) return;
        OrderBoardBlockEntity.Ticket t = tickets.stream().filter(ticket -> orderId.equals(ticket.orderId())).findFirst().orElse(null);
        if (t == null) return;
        ItemStack ticket = new ItemStack(OneMoreCore.TICKET.get());
        ticket.set(OneMoreCore.LABEL.get(), t.table().isEmpty() ? "?" : t.table());
        ticket.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(t.items()));
        TrayBlock.give(player, ticket);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8f, 1.4f);
    }
}
