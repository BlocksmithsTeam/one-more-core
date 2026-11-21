package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Server to client: the state of the restaurant that the player is in or near. The HUD shows it.
 * The server sends it twice a second; the HUD hides when it stops.
 *
 * @param players   the required players for the current ready check
 * @param countdown ticks until the day starts; 0 when no countdown runs
 * @param serviceLeft ticks of service left today; 0 when the day has a fixed number of customers (then "left" counts them)
 */
public record Status(boolean open, int day, int left, int served, int lost, int maxLost, int register, int earned,
        ResourceLocation currency, List<Member> players, int countdown, int serviceLeft,
        BlockPos sign, BoundingBox area, boolean readyCheckActive) implements CustomPacketPayload {
    /** A required player and whether the player is ready. */
    public record Member(UUID id, String name, boolean ready) {}

    public static final Type<Status> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "status"));
    /** The largest player list that the client accepts. */
    static final int MAX_PLAYERS = 4096;
    static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.of((buf, s) -> {
        buf.writeBoolean(s.open);
        buf.writeBoolean(s.readyCheckActive);
        BlockPos.STREAM_CODEC.encode(buf, s.sign);
        BlockPos.STREAM_CODEC.encode(buf, new BlockPos(s.area.minX(), s.area.minY(), s.area.minZ()));
        BlockPos.STREAM_CODEC.encode(buf, new BlockPos(s.area.maxX(), s.area.maxY(), s.area.maxZ()));
        for (int v : new int[] {s.day, s.left, s.served, s.lost, s.maxLost, s.register, s.earned, s.countdown, s.serviceLeft})
            ByteBufCodecs.VAR_INT.encode(buf, v);
        ResourceLocation.STREAM_CODEC.encode(buf, s.currency);
        int n = Math.min(MAX_PLAYERS, s.players.size());
        ByteBufCodecs.VAR_INT.encode(buf, n);
        for (Member m : s.players.subList(0, n)) {
            UUIDUtil.STREAM_CODEC.encode(buf, m.id);
            // Fake players of other mods can have longer names than accounts; the codec refuses more than 16 characters.
            ByteBufCodecs.stringUtf8(16).encode(buf, m.name.length() > 16 ? m.name.substring(0, 16) : m.name);
            buf.writeBoolean(m.ready);
        }
    }, buf -> {
        boolean open = buf.readBoolean();
        boolean readyCheckActive = buf.readBoolean();
        BlockPos sign = BlockPos.STREAM_CODEC.decode(buf);
        BoundingBox area = BoundingBox.fromCorners(BlockPos.STREAM_CODEC.decode(buf), BlockPos.STREAM_CODEC.decode(buf));
        int[] v = new int[9];
        for (int i = 0; i < v.length; i++) v[i] = ByteBufCodecs.VAR_INT.decode(buf);
        ResourceLocation currency = ResourceLocation.STREAM_CODEC.decode(buf);
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        if (n < 0 || n > MAX_PLAYERS) throw new IllegalArgumentException("bad player count " + n);
        List<Member> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            players.add(new Member(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.stringUtf8(16).decode(buf), buf.readBoolean()));
        return new Status(open, v[0], v[1], v[2], v[3], v[4], v[5], v[6], currency, List.copyOf(players), v[7], v[8], sign, area, readyCheckActive);
    });

    public int readyCount() { return (int) players.stream().filter(Member::ready).count(); }

    private static final long EXPIRY_MS = 1500;
    private static final Map<BlockPos, Received> CLIENT = new HashMap<>();

    public record Received(Status status, long time) {}

    /** Last arrival, used by the screen and book painters for the local currency. */
    public static Status client;

    static void receive(Status status) {
        client = status;
        CLIENT.put(status.sign(), new Received(status, System.currentTimeMillis()));
    }

    static void clearClient() {
        CLIENT.clear();
        client = null;
    }

    /** The restaurant containing pos, or the nearest sign if outside restaurants. */
    public static Received near(BlockPos pos, boolean insideOnly) {
        long now = System.currentTimeMillis();
        CLIENT.values().removeIf(r -> now - r.time() > EXPIRY_MS);
        Received nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Received r : CLIENT.values()) {
            if (r.status().area().isInside(pos)) return r;
            if (!insideOnly) {
                double d = r.status().sign().distSqr(pos);
                if (d < distance) { nearest = r; distance = d; }
            }
        }
        return nearest;
    }

    @Override
    public Type<Status> type() { return TYPE; }
}
