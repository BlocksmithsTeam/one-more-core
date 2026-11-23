package com.onemorestudios.onemorecore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** Loaded only when Diegesis is present. Restaurant remains the owner of dirt and its saved cells. */
public final class WipingStation implements StationLogic {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "wiping");
    public static final StationType TYPE = Diegesis.station(ID)
            .tag(OneMoreCore.SURFACES)
            .orientation(StationType.Orientation.FIXED)
            .openWhen(WipingStation::opens)
            .logic(context -> new WipingStation())
            .build();
    private final Map<UUID, Long> lastWipe = new HashMap<>();

    public static void register() { Diegesis.register(TYPE); }

    public static boolean opens(Player player, BlockPos pos, BlockState state) {
        if (!state.is(OneMoreCore.SURFACES) || !player.getMainHandItem().isEmpty()
                || player.isSpectator() || !player.canInteractWithBlock(pos, 1)) return false;
        if (player.level() instanceof ServerLevel level) {
            Restaurant r = Restaurants.get(level).containing(pos);
            return r != null && (!r.dirt.isEmpty() || !r.coins.isEmpty())
                    && r.tableAt(level, pos).stream().anyMatch(p -> r.dirt.containsKey(p) || r.hasTableItems(p));
        }
        if (Dirt.CLIENT.isEmpty() && TableItems.CLIENT.isEmpty()) return false;
        Status.Received status = Status.near(pos, true);
        BoundingBox area = status == null ? new BoundingBox(pos).inflatedBy(Config.MAX_AREA.get()) : status.status().area();
        return Restaurant.connectedTable(player.level(), area, pos, TableCuts.client()).stream()
                .anyMatch(p -> Dirt.CLIENT.containsKey(p) || TableItems.CLIENT.containsKey(p));
    }

    @Override
    public void onCustomAction(StationContext station, Player player, String action, CompoundTag data) {
        // "collect": the coins and the dirty dishes of the table.
        if ("collect".equals(action) && station.level() instanceof ServerLevel cl && player.level() == cl && station.users().contains(player)
                && player.canInteractWithBlock(station.pos(), 1)) {
            Restaurant r = Restaurants.get(cl).containing(station.pos());
            if (r != null) r.collect(cl, player, station.pos());
            return;
        }
        if (!"wipe".equals(action) || !(station.level() instanceof ServerLevel level)
                || player.level() != level || !station.users().contains(player)
                || !opens(player, station.pos(), station.blockState())
                || !data.contains("x", Tag.TAG_FLOAT) || !data.contains("z", Tag.TAG_FLOAT)) return;
        float x = data.getFloat("x"), z = data.getFloat("z");
        // A stroke runs from where the cloth was in the last tick (x0, z0) to where it is; without them it is one point.
        float x0 = data.contains("x0", Tag.TAG_FLOAT) ? data.getFloat("x0") : x, z0 = data.contains("z0", Tag.TAG_FLOAT) ? data.getFloat("z0") : z;
        // The view shows the whole table: x and z are scene pixels from this block, up to 8 blocks away.
        if (!Float.isFinite(x) || !Float.isFinite(z) || Math.abs(x) > 128 || Math.abs(z) > 128
                || !Float.isFinite(x0) || !Float.isFinite(z0) || Math.abs(x0) > 128 || Math.abs(z0) > 128) return;
        BlockPos pos = station.pos();
        Restaurant r = Restaurants.get(level).containing(pos);
        var table = r.tableAt(level, pos);
        int cx = pos.getX() * 8 + (int) Math.floor(x / 2), cz = pos.getZ() * 8 + (int) Math.floor(z / 2);
        // A point off the table is no stroke: it does not use up the stroke rate either.
        if (!table.contains(new BlockPos(Math.floorDiv(cx, 8), pos.getY(), Math.floorDiv(cz, 8)))) return;
        // A stroke longer than a block in one tick is a jump, not a wipe: only its end counts.
        int sx = pos.getX() * 8 + (int) Math.floor(x0 / 2), sz = pos.getZ() * 8 + (int) Math.floor(z0 / 2);
        if (Math.max(Math.abs(sx - cx), Math.abs(sz - cz)) > 8) { sx = cx; sz = cz; }
        long now = level.getGameTime();
        if (now - lastWipe.getOrDefault(player.getUUID(), now - 1) < 1) return;
        lastWipe.put(player.getUUID(), now);
        r.wipeCells(level, table, sx, sz, cx, cz, new Vec3(cx / 8.0 + 1 / 16.0, pos.getY() + Dirt.top(level, pos), cz / 8.0 + 1 / 16.0));
    }

    @Override
    public void onClose(StationContext station, Player player) { lastWipe.remove(player.getUUID()); }
}
