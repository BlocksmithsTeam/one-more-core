package com.onemorestudios.onemorecore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/** Server rules shared by the drawer's actions and server GameTests; no optional client classes. */
public final class RegisterTake {
    private final Map<UUID, Long> lastTake = new HashMap<>();

    public boolean take(ServerLevel level, Player player, BlockPos pos, String action) {
        boolean bill = "take_banknote".equals(action);
        if (!bill && !"take_coin".equals(action)) return false;
        if (player.level() != level || player.isSpectator() || !player.canInteractWithBlock(pos, 1)
                || !level.mayInteract(player, pos) || !level.hasChunkAt(pos)
                || !level.getBlockState(pos).is(OneMoreCore.CASH_REGISTER.get())
                || !(level.getBlockEntity(pos) instanceof CashRegisterBlockEntity cash)) return false;
        long now = level.getGameTime();
        if (now - lastTake.getOrDefault(player.getUUID(), now - 1) < 1) return false;
        lastTake.put(player.getUUID(), now);
        if (bill && !Money.hasBanknote()) return false;
        Restaurant restaurant = Restaurants.get(level).containing(pos);
        int balance = restaurant == null ? cash.balance() : restaurant.register();
        int amount = bill ? Money.banknoteValue() : 1;
        return Money.take(amount, balance, restaurant == null ? cash::take : restaurant::take,
                player.getInventory()::placeItemBackInInventory);
    }

    public void close(Player player) { lastTake.remove(player.getUUID()); }
}
