package com.onemorestudios.onemoreclient;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the state of the restaurant that the player is in or near. The HUD shows it.
 * The server sends it twice a second; the HUD hides when it stops.
 */
public record Status(boolean open, int day, int left, int served, int lost, int maxLost, int register, int earned, int ready, int needed,
        ResourceLocation currency)
        implements CustomPacketPayload {
    public static final Type<Status> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "status"));
    static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.of((buf, s) -> {
        buf.writeBoolean(s.open);
        for (int v : new int[] {s.day, s.left, s.served, s.lost, s.maxLost, s.register, s.earned, s.ready, s.needed})
            ByteBufCodecs.VAR_INT.encode(buf, v);
        ResourceLocation.STREAM_CODEC.encode(buf, s.currency);
    }, buf -> {
        boolean open = buf.readBoolean();
        int[] v = new int[9];
        for (int i = 0; i < v.length; i++) v[i] = ByteBufCodecs.VAR_INT.decode(buf);
        return new Status(open, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], ResourceLocation.STREAM_CODEC.decode(buf));
    });

    /** Client only: the last status and System.currentTimeMillis() when it came. */
    public static Status client;
    public static long clientTime;

    @Override
    public Type<Status> type() { return TYPE; }
}
