package com.onemorestudios.onemorecore;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the televisions of other mods that a restaurant (by its sign) uses, their modes, and what they show:
 * the menu (MenuBoardBlockEntity.build) and the tickets (Tickets.encode). An empty tag: no television.
 */
public record ScreenData(BlockPos sign, CompoundTag data) implements CustomPacketPayload {
    // At 12 bytes per television this leaves room for the menu and ticket byte budgets and packet framing.
    private static final int MAX_POSITIONS = 16 * 1024;
    public static final Type<ScreenData> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screens"));
    static final StreamCodec<ByteBuf, ScreenData> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, ScreenData::sign,
            ByteBufCodecs.COMPOUND_TAG, ScreenData::wireData, ScreenData::new);

    /** Client only: by restaurant sign. */
    public static final Map<BlockPos, ScreenData> CLIENT = new HashMap<>();

    static void receive(ScreenData d) {
        if (d.data.isEmpty()) CLIENT.remove(d.sign); else CLIENT.put(d.sign, d);
    }

    private CompoundTag wireData() {
        long[] positions = data.getLongArray("pos");
        int[] modes = data.getIntArray("mode");
        if (positions.length <= MAX_POSITIONS && modes.length <= MAX_POSITIONS) return data;
        CompoundTag bounded = data.copy();
        int count = Math.min(MAX_POSITIONS, Math.min(positions.length, modes.length));
        bounded.putLongArray("pos", Arrays.copyOf(positions, count));
        bounded.putIntArray("mode", Arrays.copyOf(modes, count));
        return bounded;
    }

    /** Uncompressed NBT size, including the tag type. An unencodable tag exceeds any display budget. */
    static int wireSize(CompoundTag tag) {
        var output = new DataOutputStream(OutputStream.nullOutputStream());
        try {
            NbtIo.writeAnyTag(tag, output);
            return output.size();
        } catch (IOException e) {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public Type<ScreenData> type() { return TYPE; }
}
