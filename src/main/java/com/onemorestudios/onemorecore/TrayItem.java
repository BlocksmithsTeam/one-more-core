package com.onemorestudios.onemorecore;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;

/** A tray in the hand: its label and its dishes in the tooltip. Old trays can still show their extra dishes. */
public class TrayItem extends BlockItem {
    public TrayItem(Block block, Properties properties) {
        super(block, properties);
    }

    static void returnDish(Player player, ItemStack dish) {
        player.getInventory().placeItemBackInInventory(dish);
    }

    static List<ItemStack> dishes(ItemStack tray) {
        return tray.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .nonEmptyStream().toList();
    }

    /** A serving attempt returns old overflow before checking what the customer wants. */
    static void returnOverflow(Player player, ItemStack tray) {
        trimOverflow(tray).forEach(dish -> returnDish(player, dish));
    }

    static List<ItemStack> trimOverflow(ItemStack tray) {
        List<ItemStack> dishes = dishes(tray);
        if (dishes.size() <= TrayBlockEntity.SIZE) return List.of();
        tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(dishes.subList(0, TrayBlockEntity.SIZE)));
        return dishes.subList(TrayBlockEntity.SIZE, dishes.size());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        int table = stack.getOrDefault(OneMoreCore.TABLE.get(), 0);
        if (table > 0) lines.add(Component.translatable("onemorecore.tray.label", table).withStyle(ChatFormatting.GOLD));
        for (ItemStack dish : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems())
            lines.add(Component.literal("- ").append(dish.getHoverName()).withStyle(ChatFormatting.GRAY));
    }
}
