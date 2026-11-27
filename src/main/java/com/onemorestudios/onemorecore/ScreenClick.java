package com.onemorestudios.onemorecore;

import java.util.UUID;
import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: the player clicked an order card at canvas coordinates x and y of one side of a screen (possibly outside the
 * block of a wide screen), with the tickets laid out by a flow (OrderLayout: the first page, down, sideways; below 0: the first
 * page) and scrolled by offset canvas units along it (as drawn; it can be negative where the scroll goes round to the start). The
 * server lays the tickets out again (OrderLayout), checks the point and finds the order by its ID.
 */
public record ScreenClick(BlockPos pos, UUID orderId, float x, float y, int side, float offset, int flow) implements CustomPacketPayload {
    public ScreenClick(BlockPos pos, UUID orderId, float x, float y) {
        this(pos, orderId, x, y, 0, 0, OrderLayout.FIRST_PAGE);
    }

    public static final Type<ScreenClick> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_click"));
    static final StreamCodec<ByteBuf, ScreenClick> CODEC = StreamCodec.of((b, c) -> {
        BlockPos.STREAM_CODEC.encode(b, c.pos);
        UUIDUtil.STREAM_CODEC.encode(b, c.orderId);
        b.writeFloat(c.x);
        b.writeFloat(c.y);
        b.writeByte(c.side);
        b.writeFloat(c.offset);
        net.minecraft.network.VarInt.write(b, c.flow + 1);
    }, b -> new ScreenClick(BlockPos.STREAM_CODEC.decode(b), UUIDUtil.STREAM_CODEC.decode(b), b.readFloat(), b.readFloat(), b.readByte(),
            b.readFloat(), net.minecraft.network.VarInt.read(b) - 1));
    /** The client points at screens up to 8 blocks away; the server allows a little more for movement. */
    private static final double REACH = 10;

    static void handle(ScreenClick click, ServerPlayer player) {
        var level = player.serverLevel();
        if (player.isSpectator() || !level.isLoaded(click.pos) || !level.mayInteract(player, click.pos)) return;
        if (!Float.isFinite(click.x) || !Float.isFinite(click.y) || !Float.isFinite(click.offset)) return;
        // Screens that join are one picture, drawn and clicked at their anchor.
        ScreenGroups.Group group = ScreenGroups.of(level, click.pos);
        if (group == null || !group.anchor().equals(click.pos)) return;
        var state = level.getBlockState(click.pos);
        if (click.side < 0 || click.side >= ScreenPicture.sides(state)) return;
        Restaurant r = ScreenSettings.owner(level, click.pos);
        if (r == null) return;
        var tickets = ticketsAt(level, click.pos, click.side);
        if (tickets == null) return;
        ScreenRect rect = group.rect();
        int settings = ScreenSettings.face(r.screens.getOrDefault(click.pos, Restaurant.SCREEN_OFF), click.side);
        float unit = ScreenRect.unit(ScreenRect.of(state), settings);
        int flow = click.flow == OrderLayout.DOWN || click.flow == OrderLayout.SIDEWAYS ? click.flow : OrderLayout.FIRST_PAGE;
        OrderLayout.Layout layout = OrderLayout.layout(rect.width(), rect.height(), unit, OrderLayout.guests(tickets), flow);
        // Drawn, the scroll goes round: one length of the content and its gap back at most.
        float offset = flow == OrderLayout.FIRST_PAGE ? 0 : Math.max(-layout.length(), Math.min(click.offset, layout.length()));
        OrderLayout.Card card = layout.at(flow == OrderLayout.SIDEWAYS ? offset : 0, flow == OrderLayout.DOWN ? offset : 0, click.x, click.y);
        if (card == null || !click.orderId.equals(tickets.get(card.index()).orderId())) return;
        ScreenPicture picture = ScreenPicture.of(click.pos, state, rect, click.side);
        Vec3 point = picture.world(click.x, click.y), eye = player.getEyePosition();
        if (eye.distanceToSqr(point) > REACH * REACH || !picture.faces(eye.subtract(point))
                || !ScreenRect.visible(level, player, group.members(), eye, point)) return;
        giveTicket(level, player, click.pos, tickets, click.orderId);
    }

    /** The orders that the screen at a position shows (an Order Screen, or a television in orders mode), or null. */
    static List<Tickets.Ticket> ticketsAt(ServerLevel level, BlockPos pos) {
        return ticketsAt(level, pos, 0);
    }

    /** The orders that one side of the screen at a position shows, or null. */
    static List<Tickets.Ticket> ticketsAt(ServerLevel level, BlockPos pos, int side) {
        BlockState state = level.getBlockState(pos);
        if (ScreenRect.of(state) == null) return null;
        Restaurant r = ScreenSettings.owner(level, pos);
        if (r != null && state.getBlock() instanceof MenuBoardBlock board) r.ensureScreen(pos, board.defaultMode());
        int v = r == null ? Restaurant.SCREEN_OFF : r.screens.getOrDefault(pos, Restaurant.SCREEN_OFF);
        return r != null && ScreenSettings.mode(ScreenSettings.face(v, side)) == Restaurant.SCREEN_ORDERS ? r.tickets() : null;
    }

    /** Gives the current ticket for a displayed order if it is still active. */
    static void giveTicket(ServerLevel level, Player player, BlockPos pos, List<Tickets.Ticket> tickets, UUID orderId) {
        if (tickets == null) return;
        Tickets.Ticket t = tickets.stream().filter(ticket -> orderId.equals(ticket.orderId())).findFirst().orElse(null);
        if (t == null) return;
        ItemStack ticket = new ItemStack(OneMoreCore.TICKET.get());
        ticket.set(OneMoreCore.LABEL.get(), t.table().isEmpty() ? "?" : t.table());
        ticket.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(t.items()));
        net.minecraft.nbt.CompoundTag identity = new net.minecraft.nbt.CompoundTag();
        identity.putUUID("ticket_order", t.orderId());
        identity.putString("ticket_dimension", level.dimension().location().toString());
        ticket.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(identity));
        TrayBlock.give(player, ticket);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8f, 1.4f);
    }

    @Override
    public Type<ScreenClick> type() { return TYPE; }
}
