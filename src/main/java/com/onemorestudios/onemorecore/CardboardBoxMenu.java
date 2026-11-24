package com.onemorestudios.onemorecore;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public final class CardboardBoxMenu extends ChestMenu {
    CardboardBoxMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(CardboardBoxBlockEntity.CAPACITY));
    }

    CardboardBoxMenu(int id, Inventory inventory, Container items) {
        super(OneMoreCore.CARDBOARD_BOX_MENU.get(), id, inventory, items, 3);
    }

    @Override
    protected Slot addSlot(Slot slot) {
        // ChestMenu builds the box slots before the player's inventory slots.
        if (slots.size() >= CardboardBoxBlockEntity.CAPACITY) return super.addSlot(slot);
        return super.addSlot(new ShulkerBoxSlot(slot.container, slot.getContainerSlot(), slot.x, slot.y));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Vanilla's merge pass can add to an occupied slot without checking mayPlace.
        if (index >= CardboardBoxBlockEntity.CAPACITY && !getSlot(index).getItem().canFitInsideContainerItems())
            return ItemStack.EMPTY;
        return super.quickMoveStack(player, index);
    }

    @EventBusSubscriber(modid = OneMoreCore.MODID, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        public static void registerScreen(RegisterMenuScreensEvent event) {
            event.register(OneMoreCore.CARDBOARD_BOX_MENU.get(), ContainerScreen::new);
        }
    }
}
