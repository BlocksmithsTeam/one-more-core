package com.onemorestudios.onemorecore;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Immutable server snapshot. Dish IDs carry no item components and are limited to 256 characters. */
public record DaySummary(int day, long income, long tips, long penalties, int served, int lost,
        String bestDish, int bestCount, int register, float reviewAverage, float rating) {
    public long net() { return income + tips - penalties; }

    public static final StreamCodec<ByteBuf, DaySummary> CODEC = StreamCodec.of((b, s) -> {
        b.writeInt(s.day); b.writeLong(s.income); b.writeLong(s.tips); b.writeLong(s.penalties);
        b.writeInt(s.served); b.writeInt(s.lost);
        ByteBufCodecs.stringUtf8(256).encode(b, s.bestDish);
        b.writeInt(s.bestCount); b.writeInt(s.register);
        b.writeFloat(s.reviewAverage); b.writeFloat(s.rating);
    }, b -> new DaySummary(b.readInt(), b.readLong(), b.readLong(), b.readLong(), b.readInt(), b.readInt(),
            ByteBufCodecs.stringUtf8(256).decode(b), b.readInt(), b.readInt(), b.readFloat(), b.readFloat()));

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("day", day); t.putLong("income", income); t.putLong("tips", tips); t.putLong("penalties", penalties);
        t.putInt("served", served); t.putInt("lost", lost); t.putString("bestDish", bestDish);
        t.putInt("bestCount", bestCount); t.putInt("register", register);
        t.putFloat("reviewAverage", reviewAverage); t.putFloat("rating", rating);
        return t;
    }

    static DaySummary load(CompoundTag t) {
        String dish = t.getString("bestDish");
        if (dish.length() > 256 || ResourceLocation.tryParse(dish) == null) dish = "";
        return new DaySummary(Math.max(1, t.getInt("day")), Math.max(0, t.getLong("income")), Math.max(0, t.getLong("tips")),
                Math.max(0, t.getLong("penalties")), Math.max(0, t.getInt("served")), Math.max(0, t.getInt("lost")),
                dish, Math.max(0, t.getInt("bestCount")), Math.max(0, t.getInt("register")),
                savedRating(t.getFloat("reviewAverage")), savedRating(t.getFloat("rating")));
    }

    private static float savedRating(float value) {
        return Float.isFinite(value) && value >= 1 && value <= 5 ? value : 0;
    }

    public record Open(DaySummary summary) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "day_summary"));
        static final StreamCodec<ByteBuf, Open> CODEC = DaySummary.CODEC.map(Open::new, Open::summary);
        public static java.util.function.Consumer<Open> handler = ignored -> {};
        @Override public Type<Open> type() { return TYPE; }
    }
}
