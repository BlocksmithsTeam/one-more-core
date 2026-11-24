package com.onemorestudios.onemorecore;

import java.util.ArrayList;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

/** All restaurants of one dimension. This is the entry point for other mods. */
public class Restaurants extends SavedData {
    private final Map<BlockPos, Restaurant> bySign = new HashMap<>();
    private record WaitingParcel(BlockPos pos, ItemStack stack) {}
    private final List<WaitingParcel> waitingParcels = new ArrayList<>();

    /** Needed to save and load the item stacks of pending deliveries. */
    HolderLookup.Provider registries;

    public static Restaurants get(ServerLevel level) {
        Restaurants data = level.getDataStorage().computeIfAbsent(new Factory<>(Restaurants::new, Restaurants::load), "onemorecore_restaurants");
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
            if (r.area != null) continue;
            BlockPos s = r.sign;
            if (Math.abs(s.getX() - pos.getX()) > COMPUTER_RANGE || Math.abs(s.getY() - pos.getY()) > COMPUTER_RANGE
                    || Math.abs(s.getZ() - pos.getZ()) > COMPUTER_RANGE) continue;
            long d = (long) s.distManhattan(pos);
            if (d < bestDistance) { bestDistance = d; best = r; }
        }
        return best;
    }

    public static final int COMPUTER_RANGE = 32;

    /** The restaurant with an area nearest to the position, at most range blocks from its edge on each axis, or null. */
    public Restaurant nearArea(BlockPos pos, int range) {
        Restaurant best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Restaurant r : bySign.values()) {
            if (r.area == null || !r.area.inflatedBy(range).isInside(pos)) continue;
            long dx = Math.max(0, Math.max(r.area.minX() - pos.getX(), pos.getX() - r.area.maxX())),
                    dy = Math.max(0, Math.max(r.area.minY() - pos.getY(), pos.getY() - r.area.maxY())),
                    dz = Math.max(0, Math.max(r.area.minZ() - pos.getZ(), pos.getZ() - r.area.maxZ()));
            if (dx + dy + dz < bestDistance) { bestDistance = dx + dy + dz; best = r; }
        }
        return best;
    }

    /**
     * Sets the area of the restaurant if it is allowed, and returns the lang key suffix of the result (onemorecore.tool.*).
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

    /** A screen belongs to one restaurant: the others forget it. */
    void claimScreen(Restaurant owner, BlockPos pos) {
        for (Restaurant r : bySign.values()) if (r != owner && r.screens.remove(pos) != null) setDirty();
    }

    Restaurant create(BlockPos sign) {
        setDirty();
        return bySign.computeIfAbsent(sign.immutable(), p -> {
            Restaurant restaurant = new Restaurant(this, p);
            restaurant.register = Config.STARTING_BALANCE.get();
            return restaurant;
        });
    }

    Restaurant remove(BlockPos sign) {
        setDirty();
        // The players' screens forget what this restaurant showed; else its last picture stays on them for good.
        if (net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() != null)
            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(new ScreenData(sign.immutable(), new net.minecraft.nbt.CompoundTag()));
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

    void blockChanged(BlockPos pos) {
        Restaurant r = containing(pos);
        if (r != null) r.invalidateLayout();
    }

    void invalidateLayouts() {
        bySign.values().forEach(Restaurant::invalidateLayout);
    }

    // An unloaded drop location or an entity-spawn veto can prevent an overflow parcel from entering the world.
    void keepParcel(BlockPos pos, ItemStack stack) {
        waitingParcels.add(new WaitingParcel(pos.immutable(), stack));
        setDirty();
    }

    void tick(ServerLevel level) {
        if (level.getGameTime() % 20 == 0 && waitingParcels.removeIf(parcel -> Courier.dropParcel(level, parcel.pos(), parcel.stack())))
            setDirty();
        for (Restaurant r : List.copyOf(bySign.values())) r.tick(level);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Restaurant r : bySign.values()) list.add(r.save());
        tag.put("restaurants", list);
        ListTag parcels = new ListTag();
        for (WaitingParcel parcel : waitingParcels) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", parcel.pos().asLong());
            entry.put("stack", parcel.stack().save(registries));
            parcels.add(entry);
        }
        tag.put("waiting_parcels", parcels);
        return tag;
    }

    static Restaurants load(CompoundTag tag, HolderLookup.Provider registries) {
        Restaurants data = new Restaurants();
        data.registries = registries;
        for (Tag t : tag.getList("restaurants", Tag.TAG_COMPOUND)) {
            Restaurant r = Restaurant.load(data, (CompoundTag) t);
            data.bySign.put(r.sign, r);
        }
        for (Tag t : tag.getList("waiting_parcels", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            ItemStack.parse(registries, entry.getCompound("stack")).ifPresent(stack ->
                    data.waitingParcels.add(new WaitingParcel(BlockPos.of(entry.getLong("pos")), stack)));
        }
        // Old saves could give one screen to two restaurants, which drew two pictures on it: the first keeps it.
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (Restaurant r : data.bySign.values()) r.screens.keySet().removeIf(p -> !seen.add(p));
        return data;
    }
}
