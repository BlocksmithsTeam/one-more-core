package com.onemorestudios.onemorecore;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;

/** An order ticket from the order board: the table number and the dishes of the order. Stick it to a tray. */
public class TicketItem extends Item {
    public TicketItem(Properties properties) {
        super(properties);
    }

    /** Old tickets have no order identity; leave them for the player rather than guessing from table text. */
    public static boolean finished(net.minecraft.server.level.ServerLevel level, ItemStack ticket) {
        var data = ticket.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        if (!data.hasUUID("ticket_order")) return false;
        var dimension = net.minecraft.resources.ResourceLocation.tryParse(data.getString("ticket_dimension"));
        if (dimension == null) return false;
        var source = level.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension));
        return source != null && !Restaurants.get(source).hasOrder(data.getUUID("ticket_order"));
    }

    @Override
    public Component getName(ItemStack stack) {
        String table = stack.getOrDefault(OneMoreCore.LABEL.get(), "");
        return !table.isEmpty() ? Component.translatable("item.onemorecore.ticket.table", table) : super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        for (ItemStack dish : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems())
            lines.add(Component.literal("- ").append(dish.getHoverName()).withStyle(ChatFormatting.GRAY));
    }
}
