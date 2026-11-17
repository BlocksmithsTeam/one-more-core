package com.onemorestudios.onemoreclient;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the dirty cells of one serving surface. The block top is a grid of 8x8 cells;
 * bit z * 8 + x is one cell. 0 means the surface is clean.
 */
public record Dirt(BlockPos pos, long cells) implements CustomPacketPayload {
    public static final Type<Dirt> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "dirt"));
    static final StreamCodec<ByteBuf, Dirt> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Dirt::pos, ByteBufCodecs.VAR_LONG, Dirt::cells, Dirt::new);

    /** A new dirty spot: the inner 6x6 cells. The outer cells of the texture are transparent. */
    public static final long FULL = 0x007E7E7E7E7E7E00L;

    /** Client only: the dirty surfaces that the server sent. */
    public static final Map<BlockPos, Long> CLIENT = new HashMap<>();

    static void receive(Dirt dirt) {
        if (dirt.cells == 0) CLIENT.remove(dirt.pos); else CLIENT.put(dirt.pos, dirt.cells);
    }

    @Override
    public Type<Dirt> type() { return TYPE; }
}
