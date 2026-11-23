package com.onemorestudios.onemorecore.client;

import net.minecraft.core.BlockPos;

/** Diegesis calls that are safe without Diegesis: the library's classes load only when the mod is there. */
final class DiegesisHook {
    private DiegesisHook() {}

    static final boolean LOADED = net.neoforged.fml.ModList.get().isLoaded("diegesis");

    /** Whether the player works at the Diegesis station of this block now. */
    static boolean focusedAt(BlockPos pos) {
        return LOADED && Inner.focusedAt(pos);
    }

    static boolean focused() {
        return LOADED && Inner.focused();
    }

    /**
     * The upper (or side) cell of a Screen is not a Diegesis station block: a click on it opens the Screen's own close-up, as
     * Diegesis does for a click on the Screen (its client starts the camera and asks the server). Client side only.
     */
    static void register() {
        if (LOADED) net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH,
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class, Inner::onPart);
    }

    private static final class Inner {
        static void onPart(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
            var level = event.getLevel();
            if (!level.isClientSide() || !(event.getEntity() instanceof net.minecraft.client.player.LocalPlayer player)
                    || !level.getBlockState(event.getPos()).is(com.onemorestudios.onemorecore.OneMoreCore.SCREEN_PART.get())) return;
            BlockPos main = com.onemorestudios.onemorecore.ScreenGroups.main(level, event.getPos());
            if (!(level.getBlockState(main).getBlock() instanceof com.onemorestudios.onemorecore.ScreenBlock)) return;
            var result = com.pockyl.diegesis.impl.client.DiegesisClientRuntime.onUseBlock(player, event.getHand(), event.getHitVec().withPosition(main));
            if (result != null) {
                event.setCancellationResult(result);
                event.setCanceled(true);
            }
        }

        static boolean focused() {
            return com.pockyl.diegesis.api.client.DiegesisClient.focusedStation()
                    .filter(view -> java.util.Arrays.asList(com.onemorestudios.onemorecore.KitchenStations.SCREEN_BOARD).contains(view.type())).isPresent();
        }

        static boolean focusedAt(BlockPos pos) {
            return com.pockyl.diegesis.api.client.DiegesisClient.focusedStation().map(v -> v.pos().equals(pos)).orElse(false);
        }
    }
}
