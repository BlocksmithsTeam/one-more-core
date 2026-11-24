package com.onemorestudios.onemorecore;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The inventory is private to the menu and the parcel. The block entity exposes no hopper inventory. */
public class CardboardBoxBlockEntity extends BlockEntity implements MenuProvider {
    public static final int CAPACITY = 27;
    final SimpleContainer items = new SimpleContainer(CAPACITY) {
        @Override
        public boolean stillValid(Player player) {
            return Container.stillValidBlockEntity(CardboardBoxBlockEntity.this, player);
        }

        @Override
        public void stopOpen(Player player) {
            if (level == null || level.isClientSide || level.getBlockEntity(worldPosition) != CardboardBoxBlockEntity.this
                    || !isEmpty()) return;
            level.removeBlock(worldPosition, false);
            CardboardBoxBlock.playPaperSound(level, worldPosition);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return stack.canFitInsideContainerItems();
        }
    };

    public CardboardBoxBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreCore.CARDBOARD_BOX_ENTITY.get(), pos, state);
        items.addListener(container -> setChanged());
    }

    @Override
    public Component getDisplayName() {
        return OneMoreCore.CARDBOARD_BOX.get().getName();
    }

    @Override
    public CardboardBoxMenu createMenu(int id, Inventory inventory, Player player) {
        return new CardboardBoxMenu(id, inventory, items);
    }

    ItemStack asItem() {
        return parcel(items.getItems());
    }

    static ItemStack parcel(List<ItemStack> stacks) {
        ItemStack stack = new ItemStack(OneMoreCore.CARDBOARD_BOX_ITEM.get());
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
        return stack;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clearContent();
        ContainerHelper.loadAllItems(tag, items.getItems(), registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items.getItems(), registries);
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        input.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items.getItems());
        setChanged();
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items.getItems()));
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("Items");
    }
}
