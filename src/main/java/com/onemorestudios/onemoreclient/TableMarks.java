package com.onemorestudios.onemoreclient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: the numbered tables of one restaurant (by its sign): a surface in the middle of each table and its number. */
public record TableMarks(BlockPos sign, List<BlockPos> marks, List<Integer> numbers) implements CustomPacketPayload {
    public static final Type<TableMarks> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "table_marks"));
    static final StreamCodec<ByteBuf, TableMarks> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, TableMarks::sign,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(256)), TableMarks::marks, ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(256)), TableMarks::numbers, TableMarks::new);

    /** Client only: the marks of each restaurant. */
    public static final Map<BlockPos, TableMarks> CLIENT = new HashMap<>();

    static void receive(TableMarks t) {
        if (t.marks.size() == t.numbers.size()) CLIENT.put(t.sign, t);
    }

    @Override
    public Type<TableMarks> type() { return TYPE; }
}
