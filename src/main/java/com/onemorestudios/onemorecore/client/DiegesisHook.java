package com.onemorestudios.onemorecore.client;

import net.minecraft.core.BlockPos;

/** Diegesis calls that are safe without Diegesis: the library's classes load only when the mod is there. */
final class DiegesisHook {
    private DiegesisHook() {}

    private static final boolean LOADED = net.neoforged.fml.ModList.get().isLoaded("diegesis");

    /** Whether the player works at the Diegesis station of this block now. */
    static boolean focusedAt(BlockPos pos) {
        return LOADED && Inner.focusedAt(pos);
    }

    private static final class Inner {
        static boolean focusedAt(BlockPos pos) {
            return com.pockyl.diegesis.api.client.DiegesisClient.focusedStation().map(v -> v.pos().equals(pos)).orElse(false);
        }
    }
}
