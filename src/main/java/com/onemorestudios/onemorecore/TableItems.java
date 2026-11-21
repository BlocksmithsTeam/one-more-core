package com.onemorestudios.onemorecore;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: the coins on one serving surface. 0: nothing is left. */
public record TableItems(BlockPos pos, int coins) implements CustomPacketPayload {
    public static final Type<TableItems> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "table_items"));
    static final StreamCodec<ByteBuf, TableItems> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, TableItems::pos,
            ByteBufCodecs.VAR_INT, TableItems::coins, TableItems::new);

    /** Client only: what lies on each surface. */
    public static final Map<BlockPos, TableItems> CLIENT = new HashMap<>();

    static void receive(TableItems t) {
        if (t.coins <= 0) CLIENT.remove(t.pos); else CLIENT.put(t.pos, t);
    }

    @Override
    public Type<TableItems> type() { return TYPE; }

    /** Server to client: the player collected coins; the HUD shows "+amount" next to the register. */
    public record Collected(int amount) implements CustomPacketPayload {
        public static final Type<Collected> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "collected"));
        static final StreamCodec<ByteBuf, Collected> CODEC = ByteBufCodecs.VAR_INT.map(Collected::new, Collected::amount);

        /** Client only: the last collections, newest last, with the time they came. */
        public static final java.util.ArrayDeque<long[]> NOTICES = new java.util.ArrayDeque<>();

        static void receive(Collected c) {
            NOTICES.addLast(new long[] {c.amount, System.currentTimeMillis()});
            while (NOTICES.size() > 5) NOTICES.removeFirst();
        }

        @Override
        public Type<Collected> type() { return TYPE; }
    }
}
