package com.onemorestudios.onemoreclient;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;

/** A tray in the hand: its label and its dishes in the tooltip. Placing it puts it down with everything on it. */
public class TrayItem extends BlockItem {
    public TrayItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        int table = stack.getOrDefault(OneMoreClient.TABLE.get(), 0);
        if (table > 0) lines.add(Component.translatable("onemoreclient.tray.label", table).withStyle(ChatFormatting.GOLD));
        for (ItemStack dish : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems())
            lines.add(Component.literal("- ").append(dish.getHoverName()).withStyle(ChatFormatting.GRAY));
    }
}
