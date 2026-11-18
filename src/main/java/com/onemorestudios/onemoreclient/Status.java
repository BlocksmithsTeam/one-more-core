package com.onemorestudios.onemoreclient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the state of the restaurant that the player is in or near. The HUD shows it.
 * The server sends it twice a second; the HUD hides when it stops.
 *
 * @param players   the players inside the area, for the ready screen
 * @param countdown ticks until the day starts; 0 when no countdown runs
 */
public record Status(boolean open, int day, int left, int served, int lost, int maxLost, int register, int earned,
        ResourceLocation currency, List<Member> players, int countdown) implements CustomPacketPayload {
    /** A player inside the area and whether the player is ready. */
    public record Member(UUID id, String name, boolean ready) {}

    public static final Type<Status> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "status"));
    /** The largest player list that the client accepts. */
    static final int MAX_PLAYERS = 64;
    static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.of((buf, s) -> {
        buf.writeBoolean(s.open);
        for (int v : new int[] {s.day, s.left, s.served, s.lost, s.maxLost, s.register, s.earned, s.countdown})
            ByteBufCodecs.VAR_INT.encode(buf, v);
        ResourceLocation.STREAM_CODEC.encode(buf, s.currency);
        int n = Math.min(MAX_PLAYERS, s.players.size());
        ByteBufCodecs.VAR_INT.encode(buf, n);
        for (Member m : s.players.subList(0, n)) {
            UUIDUtil.STREAM_CODEC.encode(buf, m.id);
            ByteBufCodecs.stringUtf8(16).encode(buf, m.name);
            buf.writeBoolean(m.ready);
        }
    }, buf -> {
        boolean open = buf.readBoolean();
        int[] v = new int[8];
        for (int i = 0; i < v.length; i++) v[i] = ByteBufCodecs.VAR_INT.decode(buf);
        ResourceLocation currency = ResourceLocation.STREAM_CODEC.decode(buf);
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        if (n < 0 || n > MAX_PLAYERS) throw new IllegalArgumentException("bad player count " + n);
        List<Member> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            players.add(new Member(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.stringUtf8(16).decode(buf), buf.readBoolean()));
        return new Status(open, v[0], v[1], v[2], v[3], v[4], v[5], v[6], currency, List.copyOf(players), v[7]);
    });

    public int readyCount() { return (int) players.stream().filter(Member::ready).count(); }

    /** Client only: the last status and System.currentTimeMillis() when it came. */
    public static Status client;
    public static long clientTime;

    @Override
    public Type<Status> type() { return TYPE; }
}
