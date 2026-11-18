package com.onemorestudios.onemoreclient;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Client to server: the player held the ready key. The server uses the restaurant whose area contains the player. */
public record ReadyRequest(boolean ready) implements CustomPacketPayload {
    public static final Type<ReadyRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "ready"));
    static final StreamCodec<ByteBuf, ReadyRequest> CODEC = ByteBufCodecs.BOOL.map(ReadyRequest::new, ReadyRequest::ready);

    static void handle(ReadyRequest request, ServerPlayer player) {
        Restaurant r = Restaurants.get(player.serverLevel()).containing(player.blockPosition());
        if (r != null && !r.isOpen()) r.setReady(player.serverLevel(), player, request.ready);
    }

    @Override
    public Type<ReadyRequest> type() { return TYPE; }
}
