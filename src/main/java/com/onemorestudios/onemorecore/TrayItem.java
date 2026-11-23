package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
        var contents = tray.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (contents.nonEmptyStream().count() <= TrayBlockEntity.SIZE) return List.of();
        List<ItemStack> slots = new ArrayList<>(contents.stream().toList());
        List<ItemStack> extras = new ArrayList<>();
        int free = 0;
        for (int i = TrayBlockEntity.SIZE; i < slots.size(); i++) {
            ItemStack dish = slots.get(i);
            if (dish.isEmpty()) continue;
            while (free < TrayBlockEntity.SIZE && !slots.get(free).isEmpty()) free++;
            if (free < TrayBlockEntity.SIZE) slots.set(free, dish);
            else extras.add(dish);
        }
        tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots.subList(0, TrayBlockEntity.SIZE)));
        return extras;
    }

    static void serveDishes(ItemStack tray, Predicate<ItemStack> wanted, Consumer<ItemStack> serve) {
        var contents = tray.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        List<ItemStack> left = new ArrayList<>(contents.stream().toList());
        for (int i = 0; i < left.size(); i++) {
            ItemStack dish = left.get(i);
            if (dish.isEmpty() || !wanted.test(dish)) continue;
            serve.accept(dish);
            left.set(i, ItemStack.EMPTY);
        }
        tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(left));
        if (left.stream().allMatch(ItemStack::isEmpty)) {
            tray.remove(OneMoreCore.LABEL.get());
            tray.remove(OneMoreCore.TRAY_TICKET.get());
            tray.remove(OneMoreCore.TRAY_POSES.get());
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        String table = stack.getOrDefault(OneMoreCore.LABEL.get(), "");
        if (!table.isEmpty()) lines.add(Component.translatable("onemorecore.tray.label", table).withStyle(ChatFormatting.GOLD));
        for (ItemStack dish : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems())
            lines.add(Component.literal("- ").append(dish.getHoverName()).withStyle(ChatFormatting.GRAY));
    }
}
