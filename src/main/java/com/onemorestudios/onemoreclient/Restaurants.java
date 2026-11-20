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
    /** Needed to save and load the item stacks of pending deliveries. */
    HolderLookup.Provider registries;

    public static Restaurants get(ServerLevel level) {
        Restaurants data = level.getDataStorage().computeIfAbsent(new Factory<>(Restaurants::new, Restaurants::load), "onemoreclient_restaurants");
        data.registries = level.registryAccess();
        return data;
    }

    public Restaurant at(BlockPos sign) {
        return bySign.get(sign);
    }

    /** The restaurant that set a television at the position to a mode, or null. */
    public Restaurant withScreen(BlockPos pos) {
        for (Restaurant r : bySign.values()) if (r.screens.containsKey(pos)) return r;
        return null;
    }

    /** The restaurant whose area contains the position, or null. */
    public Restaurant containing(BlockPos pos) {
        for (Restaurant r : bySign.values()) if (r.area != null && r.area.isInside(pos)) return r;
        return null;
    }

    /**
     * The restaurant that a computer at the position works for: the one whose area contains it, otherwise the nearest
     * restaurant whose sign is at most {@link #COMPUTER_RANGE} blocks away in each axis (an area that is not set yet).
     */
    public Restaurant forComputer(BlockPos pos) {
        Restaurant in = containing(pos);
        if (in != null) return in;
        Restaurant best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Restaurant r : bySign.values()) {
            BlockPos s = r.sign;
            if (Math.abs(s.getX() - pos.getX()) > COMPUTER_RANGE || Math.abs(s.getY() - pos.getY()) > COMPUTER_RANGE
                    || Math.abs(s.getZ() - pos.getZ()) > COMPUTER_RANGE) continue;
            long d = (long) s.distManhattan(pos);
            if (d < bestDistance) { bestDistance = d; best = r; }
        }
        return best;
    }

    public static final int COMPUTER_RANGE = 32;

    /**
     * Sets the area of the restaurant if it is allowed, and returns the lang key suffix of the result (onemoreclient.tool.*).
     * The area must hold the sign, must not be larger than maxArea in each axis and must not overlap another area.
     */
    String setArea(Restaurant r, BoundingBox box) {
        int max = Config.MAX_AREA.get();
        if (box.getXSpan() > max || box.getYSpan() > max || box.getZSpan() > max) return "too_big";
        if (!box.isInside(r.sign)) return "sign_outside";
        if (overlaps(box, r)) return "overlap";
        r.setArea(box);
        return "area_set";
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

    void sendTableItems(ServerPlayer player, ChunkPos chunk) {
        for (Restaurant r : bySign.values()) r.sendTableItems(player, chunk);
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
        data.registries = registries;
        for (Tag t : tag.getList("restaurants", Tag.TAG_COMPOUND)) {
            Restaurant r = Restaurant.load(data, (CompoundTag) t);
            data.bySign.put(r.sign, r);
        }
        return data;
    }
}
