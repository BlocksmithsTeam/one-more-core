package com.onemorestudios.onemoreclient;

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
import net.minecraft.world.phys.Vec3;

/** Loaded only when Diegesis is present. Restaurant remains the owner of dirt and its saved cells. */
public final class WipingStation implements StationLogic {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "wiping");
    public static final StationType TYPE = Diegesis.station(ID)
            .tag(OneMoreClient.SURFACES)
            .orientation(StationType.Orientation.FIXED)
            .openWhen(WipingStation::opens)
            .logic(context -> new WipingStation())
            .build();
    private final Map<UUID, Long> lastWipe = new HashMap<>();

    public static void register() { Diegesis.register(TYPE); }

    public static boolean opens(Player player, BlockPos pos, BlockState state) {
        if (!state.is(OneMoreClient.SURFACES) || !player.getMainHandItem().isEmpty()
                || player.isSpectator() || !player.canInteractWithBlock(pos, 1)) return false;
        if (player.level() instanceof ServerLevel level) {
            Restaurant r = Restaurants.get(level).containing(pos);
            return r != null && (r.dirt.containsKey(pos) || r.hasTableItems(pos));
        }
        return Dirt.CLIENT.containsKey(pos) || TableItems.CLIENT.containsKey(pos);
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
        if (!Float.isFinite(x) || !Float.isFinite(z) || x < 0 || x >= 16 || z < 0 || z >= 16) return;
        long now = level.getGameTime();
        if (now - lastWipe.getOrDefault(player.getUUID(), now - 4) < 4) return;
        lastWipe.put(player.getUUID(), now);
        BlockPos pos = station.pos();
        Restaurants.get(level).containing(pos).wipe(level, player, pos,
                new Vec3(pos.getX() + x / 16.0, pos.getY() + Dirt.top(level, pos), pos.getZ() + z / 16.0));
    }

    @Override
    public void onClose(StationContext station, Player player) { lastWipe.remove(player.getUUID()); }
}
