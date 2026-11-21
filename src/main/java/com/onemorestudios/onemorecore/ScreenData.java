package com.onemorestudios.onemorecore;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the televisions of other mods that a restaurant (by its sign) uses, their modes, and what they show:
 * the menu (MenuBoardBlockEntity.build) and the tickets (OrderBoardBlockEntity.encode). An empty tag: no television.
 */
public record ScreenData(BlockPos sign, CompoundTag data) implements CustomPacketPayload {
    public static final Type<ScreenData> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screens"));
    static final StreamCodec<ByteBuf, ScreenData> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ScreenData::sign,
            ByteBufCodecs.COMPOUND_TAG, ScreenData::data, ScreenData::new);

    /** Client only: by restaurant sign. */
    public static final Map<BlockPos, ScreenData> CLIENT = new HashMap<>();

    static void receive(ScreenData d) {
        if (d.data.isEmpty()) CLIENT.remove(d.sign); else CLIENT.put(d.sign, d);
    }

    @Override
    public Type<ScreenData> type() { return TYPE; }
}
