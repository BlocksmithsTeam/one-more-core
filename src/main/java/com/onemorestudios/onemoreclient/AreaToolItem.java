package com.onemorestudios.onemoreclient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Selects the box area and the customer spawn point of a restaurant.
 * Click a sign to link the tool, click two corners, sneak-click the spawn point.
 * Sneak-click a container to set the delivery point of the shop instead.
 */
public class AreaToolItem extends Item {
    public AreaToolItem(Properties properties) {
        super(properties);
    }

    /** Copies the area and the spawn point into the tool, so the client can draw them. */
    static void showArea(ItemStack stack, Restaurant r) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove("area");
            tag.remove("spawn");
            tag.remove("delivery");
            BoundingBox a = r.area;
            if (a != null) tag.putIntArray("area", new int[] {a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()});
            if (r.spawn != null) tag.putLong("spawn", r.spawn.asLong());
            if (r.delivery != null) tag.putLong("delivery", r.delivery.asLong());
        });
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        Restaurants all = Restaurants.get(level);
        Restaurant r = data.contains("sign") ? all.at(BlockPos.of(data.getLong("sign"))) : null;
        String message;
        if (player == null) {
            return InteractionResult.PASS;
        } else if (r == null) {
            message = "no_sign";
        } else if (r.isOpen()) {
            message = "busy";
        } else if (player.isShiftKeyDown() && Restaurant.container(level, context.getClickedPos()) != null) {
            r.setDelivery(context.getClickedPos());
            message = "delivery_set";
        } else if (player.isShiftKeyDown()) {
            r.setSpawn(context.getClickedPos().relative(context.getClickedFace()));
            message = "spawn_set";
        } else if (!data.contains("c1")) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putLong("c1", context.getClickedPos().asLong()));
            message = "corner1";
        } else {
            BoundingBox box = BoundingBox.fromCorners(BlockPos.of(data.getLong("c1")), context.getClickedPos());
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove("c1"));
            int max = Config.MAX_AREA.get();
            if (box.getXSpan() > max || box.getYSpan() > max || box.getZSpan() > max) {
                player.displayClientMessage(Component.translatable("onemoreclient.tool.too_big", max), true);
                return InteractionResult.SUCCESS;
            }
            if (!box.isInside(r.sign)) message = "sign_outside";
            else if (all.overlaps(box, r)) message = "overlap";
            else {
                r.setArea(box);
                message = "area_set";
            }
        }
        if (r != null) showArea(stack, r);
        player.displayClientMessage(Component.translatable("onemoreclient.tool." + message), true);
        return InteractionResult.SUCCESS;
    }
}
