package com.onemorestudios.onemoreclient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

/** All restaurants of one dimension. This is the entry point for other mods. */
public class Restaurants extends SavedData {
    private final Map<BlockPos, Restaurant> bySign = new HashMap<>();

    public static Restaurants get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(Restaurants::new, Restaurants::load), "onemoreclient_restaurants");
    }

    public Restaurant at(BlockPos sign) {
        return bySign.get(sign);
    }

    /** The restaurant whose area contains the position, or null. */
    public Restaurant containing(BlockPos pos) {
        for (Restaurant r : bySign.values()) if (r.area != null && r.area.isInside(pos)) return r;
        return null;
    }

    boolean overlaps(BoundingBox box, Restaurant except) {
        for (Restaurant r : bySign.values()) if (r != except && r.area != null && r.area.intersects(box)) return true;
        return false;
    }

    Restaurant create(BlockPos sign) {
        setDirty();
        return bySign.computeIfAbsent(sign.immutable(), p -> new Restaurant(this, p));
    }

    Restaurant remove(BlockPos sign) {
        setDirty();
        return bySign.remove(sign);
    }

    /** Sends the dirt of a chunk to a player that starts to see the chunk. */
    void sendDirt(ServerPlayer player, ChunkPos chunk) {
        for (Restaurant r : bySign.values())
            r.dirt.forEach((pos, cells) -> {
                if (chunk.equals(new ChunkPos(pos))) PacketDistributor.sendToPlayer(player, new Dirt(pos, cells));
            });
    }

    void tick(ServerLevel level) {
        for (Restaurant r : List.copyOf(bySign.values())) r.tick(level);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Restaurant r : bySign.values()) list.add(r.save());
        tag.put("restaurants", list);
        return tag;
    }

    static Restaurants load(CompoundTag tag, HolderLookup.Provider registries) {
        Restaurants data = new Restaurants();
        for (Tag t : tag.getList("restaurants", Tag.TAG_COMPOUND)) {
            Restaurant r = Restaurant.load(data, (CompoundTag) t);
            data.bySign.put(r.sign, r);
        }
        return data;
    }
}
