package com.onemorestudios.onemorecore;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: the player clicked an order card at canvas coordinates x and y of one side of a screen (possibly outside the
 * block of a wide screen), on a page (-1: the page the server shows now) or scrolled down (scroll >= 0, a player at the screen
 * with Diegesis). The server lays the tickets out again (OrderLayout), checks the point and finds the order by its ID.
 */
public record ScreenClick(BlockPos pos, UUID orderId, float x, float y, int side, float scroll, int page) implements CustomPacketPayload {
    public ScreenClick(BlockPos pos, UUID orderId, float x, float y) {
        this(pos, orderId, x, y, 0, -1, -1);
    }

    public static final Type<ScreenClick> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_click"));
    static final StreamCodec<ByteBuf, ScreenClick> CODEC = StreamCodec.of((b, c) -> {
        BlockPos.STREAM_CODEC.encode(b, c.pos);
        UUIDUtil.STREAM_CODEC.encode(b, c.orderId);
        b.writeFloat(c.x);
        b.writeFloat(c.y);
        b.writeByte(c.side);
        b.writeFloat(c.scroll);
        net.minecraft.network.VarInt.write(b, c.page + 1);
    }, b -> new ScreenClick(BlockPos.STREAM_CODEC.decode(b), UUIDUtil.STREAM_CODEC.decode(b), b.readFloat(), b.readFloat(), b.readByte(),
            b.readFloat(), net.minecraft.network.VarInt.read(b) - 1));
    /** The client points at screens up to 8 blocks away; the server allows a little more for movement. */
    private static final double REACH = 10;

    static void handle(ScreenClick click, ServerPlayer player) {
        var level = player.serverLevel();
        if (player.isSpectator() || !level.isLoaded(click.pos) || !level.mayInteract(player, click.pos)) return;
        if (!Float.isFinite(click.x) || !Float.isFinite(click.y) || !Float.isFinite(click.scroll)) return;
        // Screens that join are one picture, drawn and clicked at their anchor.
        ScreenGroups.Group group = ScreenGroups.of(level, click.pos);
        if (group == null || !group.anchor().equals(click.pos)) return;
        var state = level.getBlockState(click.pos);
        if (click.side < 0 || click.side >= ScreenPicture.sides(state)) return;
        Restaurant r = ScreenSettings.owner(level, click.pos);
        if (r == null) return;
        var tickets = OrderBoardBlock.ticketsAt(level, click.pos, click.side);
        if (tickets == null) return;
        ScreenRect rect = group.rect();
        int settings = ScreenSettings.face(r.screens.getOrDefault(click.pos, Restaurant.SCREEN_OFF), click.side);
        float unit = ScreenRect.unit(ScreenRect.of(state), settings);
        boolean scrolling = click.scroll >= 0;
        OrderLayout.Layout layout = OrderLayout.layout(rect.width(), rect.height(), unit, OrderLayout.guests(tickets), scrolling);
        int page = scrolling ? 0 : click.page >= 0 ? click.page : OrderLayout.page(layout.pages(), ScreenSettings.autoPages(settings) ? level.getGameTime() : 0);
        OrderLayout.Card card = layout.at(page, scrolling ? Math.min(click.scroll, layout.maxScroll()) : 0, click.x, click.y);
        if (card == null || !click.orderId.equals(tickets.get(card.index()).orderId())) return;
        ScreenPicture picture = ScreenPicture.of(click.pos, state, rect, click.side);
        Vec3 point = picture.world(click.x, click.y), eye = player.getEyePosition();
        if (eye.distanceToSqr(point) > REACH * REACH || !picture.faces(eye.subtract(point))
                || !ScreenRect.visible(level, player, group.members(), eye, point)) return;
        OrderBoardBlock.giveTicket(level, player, click.pos, tickets, click.orderId);
    }

    @Override
    public Type<ScreenClick> type() { return TYPE; }
}
