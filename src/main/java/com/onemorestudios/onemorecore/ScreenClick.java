package com.onemorestudios.onemorecore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: the player clicked order card {@code index} on the screen at {@code pos} (the card under the
 * crosshair, which can be outside the block of a wide television). The server gives the ticket of that order.
 */
public record ScreenClick(BlockPos pos, int index) implements CustomPacketPayload {
    public static final Type<ScreenClick> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_click"));
    static final StreamCodec<ByteBuf, ScreenClick> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ScreenClick::pos,
            ByteBufCodecs.VAR_INT, ScreenClick::index, ScreenClick::new);
    /** The client points at screens up to 8 blocks away; the server allows a little more for movement. */
    static final double REACH = 10;

    static void handle(ScreenClick click, ServerPlayer player) {
        var level = player.serverLevel();
        if (!level.isLoaded(click.pos)) return;
        ScreenGroups.Group group = ScreenGroups.of(level, click.pos);
        if (group == null || !group.anchor().equals(click.pos)
                || group.members().stream().noneMatch(p -> player.distanceToSqr(Vec3.atCenterOf(p)) <= REACH * REACH)) return;
        OrderBoardBlock.giveTicket(level, player, click.pos, OrderBoardBlock.ticketsAt(level, click.pos), click.index);
    }

    @Override
    public Type<ScreenClick> type() { return TYPE; }
}
