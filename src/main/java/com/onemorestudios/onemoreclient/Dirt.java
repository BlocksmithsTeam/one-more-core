package com.onemorestudios.onemoreclient;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Server to client: the dirty cells of one serving surface. The block top is a grid of 8x8 cells;
 * bit z * 8 + x is one cell. 0 means the surface is clean.
 */
public record Dirt(BlockPos pos, long cells) implements CustomPacketPayload {
    public static final Type<Dirt> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "dirt"));
    static final StreamCodec<ByteBuf, Dirt> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Dirt::pos, ByteBufCodecs.VAR_LONG, Dirt::cells, Dirt::new);

    /** A new dirty spot: the inner 6x6 cells, leaving a clean border. */
    public static final long FULL = 0x007E7E7E7E7E7E00L;

    /** Client only: the dirty surfaces that the server sent. */
    public static final Map<BlockPos, Long> CLIENT = new HashMap<>();

    /** Height of the actual furniture shape, shared by wiping and drawing. */
    public static double top(Level level, BlockPos pos) {
        var shape = level.getBlockState(pos).getShape(level, pos);
        return shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y);
    }

    /** Client: whether a serving surface that touches pos (the same table, up to 64 blocks) is dirty. */
    public static boolean tableDirty(Level level, BlockPos pos) {
        if (CLIENT.isEmpty()) return false;
        for (BlockPos p : tableOf(level, pos)) if (CLIENT.containsKey(p)) return true;
        return false;
    }

    /** The serving surfaces that touch pos, on one level, at most 64. */
    public static java.util.Set<BlockPos> tableOf(Level level, BlockPos pos) {
        java.util.Set<BlockPos> table = new java.util.HashSet<>();
        if (!level.getBlockState(pos).is(OneMoreClient.SURFACES)) return table;
        java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(java.util.List.of(pos.immutable()));
        table.add(pos.immutable());
        while (!todo.isEmpty() && table.size() < 64) {
            BlockPos p = todo.pop();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = p.relative(dir);
                if (level.getBlockState(next).is(OneMoreClient.SURFACES) && table.add(next)) todo.push(next);
            }
        }
        return table;
    }

    static void receive(Dirt dirt) {
        if (dirt.cells == 0) CLIENT.remove(dirt.pos); else CLIENT.put(dirt.pos, dirt.cells);
    }

    @Override
    public Type<Dirt> type() { return TYPE; }
}
