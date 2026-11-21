package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A tray on a counter: up to three dishes and the table text of its label ("": no label). */
public class TrayBlockEntity extends BlockEntity {
    public static final int SIZE = 3;
    public final SimpleContainer items = new SimpleContainer(SIZE) {
        @Override
        public int getMaxStackSize() { return 1; }
    };
    public String label = "";
    Player placingPlayer;
    private final List<ItemStack> overflow = new ArrayList<>();

    public TrayBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreCore.TRAY_ENTITY.get(), pos, state);
        items.addListener(c -> changed());
    }

    void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, savedDishes(), true, registries);
        tag.putString("label", label);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        NonNullList<ItemStack> list = NonNullList.withSize(256, ItemStack.EMPTY); // ContainerHelper stores the slot as an unsigned byte.
        ContainerHelper.loadAllItems(tag, list, registries);
        loadDishes(list);
        // Old trays kept a table number.
        label = tag.contains("label", net.minecraft.nbt.Tag.TAG_INT) ? (tag.getInt("label") > 0 ? String.valueOf(tag.getInt("label")) : "") : TableCards.clean(tag.getString("label"));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        loadDishes(input.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().toList());
        label = TableCards.clean(input.getOrDefault(OneMoreCore.LABEL.get(), ""));
    }

    private void loadDishes(List<ItemStack> dishes) {
        overflow.clear();
        for (int i = 0; i < SIZE; i++) {
            items.getItems().set(i, i < dishes.size() ? dishes.get(i) : ItemStack.EMPTY);
        }
        int free = 0;
        for (int i = SIZE; i < dishes.size(); i++) {
            ItemStack dish = dishes.get(i);
            if (dish.isEmpty()) continue;
            while (free < SIZE && !items.getItem(free).isEmpty()) free++;
            if (free < SIZE) items.getItems().set(free, dish);
            else overflow.add(dish);
        }
    }

    private NonNullList<ItemStack> savedDishes() {
        NonNullList<ItemStack> saved = NonNullList.create();
        saved.addAll(items.getItems());
        saved.addAll(overflow);
        return saved;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        releaseOverflow(null);
    }

    boolean hasOverflow() {
        return !overflow.isEmpty();
    }

    void releaseOverflow(Player player) {
        if (!(level instanceof ServerLevel) || !hasOverflow()) return;
        List<ItemStack> extras = List.copyOf(overflow);
        overflow.clear();
        for (ItemStack dish : extras) {
            if (player != null) {
                TrayItem.returnDish(player, dish);
            } else {
                ItemEntity drop = new ItemEntity(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.2,
                        worldPosition.getZ() + 0.5, dish);
                drop.setDefaultPickUpDelay();
                level.addFreshEntity(drop);
            }
        }
        changed();
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        builder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(savedDishes()));
        if (!label.isEmpty()) builder.set(OneMoreCore.LABEL.get(), label);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void removeComponentsFromTag(CompoundTag tag) {
        tag.remove("Items");
        tag.remove("label");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
