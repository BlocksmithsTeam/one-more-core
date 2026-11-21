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
 * Client to server: the player clicked an order card at canvas coordinates x and y, possibly outside the block
 * of a wide television. The server checks the point and finds the order by its ID.
 */
public record ScreenClick(BlockPos pos, UUID orderId, float x, float y) implements CustomPacketPayload {
    public static final Type<ScreenClick> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_click"));
    static final StreamCodec<ByteBuf, ScreenClick> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ScreenClick::pos,
            UUIDUtil.STREAM_CODEC, ScreenClick::orderId, ByteBufCodecs.FLOAT, ScreenClick::x, ByteBufCodecs.FLOAT, ScreenClick::y,
            ScreenClick::new);
    /** The client points at screens up to 8 blocks away; the server allows a little more for movement. */
    private static final double REACH = 10;

    static void handle(ScreenClick click, ServerPlayer player) {
        var level = player.serverLevel();
        if (player.isSpectator() || !level.isLoaded(click.pos) || !level.mayInteract(player, click.pos)) return;
        // Screens that join are one picture, drawn and clicked at their anchor.
        ScreenGroups.Group group = ScreenGroups.of(level, click.pos);
        if (group == null || !group.anchor().equals(click.pos)) return;
        var state = level.getBlockState(click.pos);
        Restaurant r = ScreenSettings.owner(level, click.pos);
        if (r == null) return;
        ScreenRect rect = group.rect();
        float unit = ScreenRect.unit(ScreenRect.of(state), r.screens.getOrDefault(click.pos, Restaurant.SCREEN_OFF));
        if (rect.cardSlot(unit, click.x, click.y) < 0) return;
        Vec3 point = rect.cardPoint(click.pos, state, click.x, click.y);
        Vec3 eye = player.getEyePosition();
        if (eye.distanceToSqr(point) > REACH * REACH || !rect.faces(state, eye.subtract(point))
                || !ScreenRect.visible(level, player, group.members(), eye, point)) return;
        OrderBoardBlock.giveTicket(level, player, click.pos, OrderBoardBlock.ticketsAt(level, click.pos), click.orderId);
    }

    @Override
    public Type<ScreenClick> type() { return TYPE; }
}
