package com.onemorestudios.onemorecore.mixin;

import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.client.SkewerPose;
import com.onemorestudios.onemorecore.compat.GrillingSkewers;
import com.pockyl.diegesis.api.scene.PlaceSurface;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.scene.PlacedItem;
import com.pockyl.diegesis.impl.client.scene.PlacedShape;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diegesis beta.15 computes the preview, release pose and collision bounds from the hand's turn. */
@Mixin(targets = "com.pockyl.diegesis.impl.client.focus.Placer", remap = false)
abstract class TrayPlacerMixin {
    @Shadow @Final private Quaternionf turn;
    @Shadow private PlacedShape heldShape;
    @Shadow private PlaceSurface restSurface;
    @Unique private boolean onemorecore$holdingSkewer;

    @Inject(method = "aim", at = @At(value = "FIELD",
            target = "Lcom/pockyl/diegesis/impl/client/focus/Placer;heldShape:Lcom/pockyl/diegesis/impl/client/scene/PlacedShape;",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void onemorecore$aimSkewer(ClientStation station, @Coerce Object ray, ItemStack carried,
            boolean overWorld, boolean frozen, float dt, CallbackInfoReturnable<ItemPose> callback) {
        onemorecore$holdingSkewer = station.type() == KitchenStations.TRAY && GrillingSkewers.matches(carried);
        if (onemorecore$holdingSkewer) turn.set(SkewerPose.lyingTurn(turn, heldShape.half()));
    }

    @Inject(method = "rebase", at = @At("HEAD"), cancellable = true)
    private void onemorecore$keepSkewerRest(PlaceSurface surface, PlacedShape shape, CallbackInfo callback) {
        if (onemorecore$holdingSkewer) {
            restSurface = surface;
            callback.cancel();
        }
    }

    @ModifyVariable(method = "apply", at = @At("HEAD"), argsOnly = true)
    private static Quaternionfc onemorecore$turnPlacedSkewer(Quaternionfc original, ClientStation station, PlacedItem item) {
        return station.type() == KitchenStations.TRAY && GrillingSkewers.matches(item.stack())
                ? SkewerPose.lyingTurn(original, item.shape().half()) : original;
    }
}
