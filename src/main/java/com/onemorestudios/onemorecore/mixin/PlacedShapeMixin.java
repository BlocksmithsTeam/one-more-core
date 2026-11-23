package com.onemorestudios.onemorecore.mixin;

import com.onemorestudios.onemorecore.client.FoodModels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Diegesis places a food as the block it would place only for a block item. A food that another mod shows as a block
 * (Display Delight plates, see FoodModels) is a plain item there, so Diegesis drew and hit-tested it as a flat sprite while
 * the tray and the tables showed the block: here it gets the block state too, for its look, its hitbox and its resting pose.
 * Optional: without Diegesis the mixin has no target and is skipped (required false).
 */
@Mixin(targets = "com.pockyl.diegesis.impl.client.scene.PlacedShape", remap = false)
abstract class PlacedShapeMixin {
    @ModifyVariable(method = "of(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/multiplayer/ClientLevel;ZZ)Lcom/pockyl/diegesis/impl/client/scene/PlacedShape;",
            at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    private static BlockState onemorecore$foodBlock(BlockState state, ItemStack stack, ClientLevel level, boolean blockModels, boolean framePose) {
        if (state != null || !blockModels) return state;
        return FoodModels.shownBlock(stack);
    }
}
