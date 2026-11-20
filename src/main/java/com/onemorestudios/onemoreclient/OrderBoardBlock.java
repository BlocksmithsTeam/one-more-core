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

/**
 * The order screen of the kitchen, a television on a wall: every order that a player took shows as a card (table number,
 * dishes, patience, price), oldest first. A click on a card gives its ticket, to stick on a tray.
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
        return level.isClientSide || type != OneMoreClient.ORDER_BOARD_ENTITY.get() ? null
                : (l, p, s, be) -> ((OrderBoardBlockEntity) be).serverTick((ServerLevel) l);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) return super.useWithoutItem(state, level, pos, player, hit);
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
        List<OrderBoardBlockEntity.Ticket> tickets = ticketsAt(sl, pos);
        float[] at = ScreenRect.OWN.canvas(pos, state, hit.getLocation());
        int i = at == null || tickets == null ? -1
                : ScreenRect.orderAt(ScreenRect.OWN.width(), ScreenRect.OWN.height(), tickets.size(), level.getGameTime(), at[0], at[1]);
        return giveTicket(sl, player, pos, tickets, i) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** The orders that the screen at a position shows (an Order Screen, or a television in orders mode), or null. */
    static List<OrderBoardBlockEntity.Ticket> ticketsAt(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof OrderBoardBlockEntity board) return board.tickets(level);
        Restaurant r = Restaurants.get(level).withScreen(pos);
        return r != null && r.screens.get(pos) == Restaurant.SCREEN_ORDERS ? r.tickets() : null;
    }

    /** Gives the ticket of order i (its table and dishes) to the player; false when there is no such order. */
    static boolean giveTicket(ServerLevel level, Player player, BlockPos pos, List<OrderBoardBlockEntity.Ticket> tickets, int i) {
        if (tickets == null || i < 0 || i >= tickets.size()) return false;
        OrderBoardBlockEntity.Ticket t = tickets.get(i);
        ItemStack ticket = new ItemStack(OneMoreClient.TICKET.get());
        ticket.set(OneMoreClient.TABLE.get(), t.table());
        ticket.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(t.items()));
        TrayBlock.give(player, ticket);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8f, 1.4f);
        return true;
    }
}
