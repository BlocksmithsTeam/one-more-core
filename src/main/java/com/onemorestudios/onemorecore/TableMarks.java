package com.onemorestudios.onemorecore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the table number cards of one restaurant (by its sign): the surface of each card, its text, and its place
 * and turn (three floats a card: x and z on the block, yaw).
 */
public record TableMarks(BlockPos sign, List<BlockPos> marks, List<String> texts, List<Float> places) implements CustomPacketPayload {
    public static final Type<TableMarks> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "table_marks"));
    static final StreamCodec<ByteBuf, TableMarks> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, TableMarks::sign,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(256)), TableMarks::marks, ByteBufCodecs.stringUtf8(16).apply(ByteBufCodecs.list(256)), TableMarks::texts,
            ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list(768)), TableMarks::places, TableMarks::new);

    /** Client only: the cards of each restaurant. */
    public static final Map<BlockPos, TableMarks> CLIENT = new HashMap<>();

    static void receive(TableMarks t) {
        if (t.marks.size() == t.texts.size() && t.places.size() == 3 * t.marks.size()) CLIENT.put(t.sign, t);
    }

    /** Client: whether a click at (x, z) of a surface is on a card. */
    static boolean near(BlockPos surface, float x, float z) {
        for (TableMarks t : CLIENT.values())
            for (int i = 0; i < t.marks.size(); i++)
                if (t.marks.get(i).equals(surface)) {
                    float dx = t.places.get(3 * i) - x, dz = t.places.get(3 * i + 1) - z;
                    if (dx * dx + dz * dz < TableCards.NEAR * TableCards.NEAR) return true;
                }
        return false;
    }

    @Override
    public Type<TableMarks> type() { return TYPE; }
}
