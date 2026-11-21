package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A tray on a counter: up to 6 dishes and the table text of its label ("": no label). */
public class TrayBlockEntity extends BlockEntity {
    public static final int SIZE = 6;
    public final SimpleContainer items = new SimpleContainer(SIZE) {
        @Override
        public int getMaxStackSize() { return 1; }
    };
    public String label = "";

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
        ContainerHelper.saveAllItems(tag, items.getItems(), true, registries);
        tag.putString("label", label);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        NonNullList<ItemStack> list = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, list, registries);
        for (int i = 0; i < SIZE; i++) items.getItems().set(i, list.get(i));
        // Old trays kept a table number.
        label = tag.contains("label", net.minecraft.nbt.Tag.TAG_INT) ? (tag.getInt("label") > 0 ? String.valueOf(tag.getInt("label")) : "") : TableCards.clean(tag.getString("label"));
    }

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        NonNullList<ItemStack> list = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        input.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(list);
        for (int i = 0; i < SIZE; i++) items.getItems().set(i, list.get(i));
        label = TableCards.clean(input.getOrDefault(OneMoreCore.LABEL.get(), ""));
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        builder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items.getItems()));
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
