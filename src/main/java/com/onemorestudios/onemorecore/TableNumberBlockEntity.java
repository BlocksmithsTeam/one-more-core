package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The text of a Table Number block and where the card stands on its block (an offset from the middle, in steps of 1/8 block
 * up to half a block, so it can stand between two blocks of a table), saved with it and sent to the clients for its renderer.
 */
public class TableNumberBlockEntity extends BlockEntity {
    /** Offsets are multiples of this (blocks): 2 model pixels. */
    public static final float STEP = 1 / 8f;
    private String text = "";
    private float offsetX, offsetZ;

    public TableNumberBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreCore.TABLE_NUMBER_ENTITY.get(), pos, state);
    }

    public String text() {
        return text;
    }

    public float offsetX() { return offsetX; }

    public float offsetZ() { return offsetZ; }

    /** Puts the card where a point (blocks, from the block's middle) is, on the grid of STEP, at most half a block away. */
    void setOffset(double x, double z) {
        offsetX = grid(x);
        offsetZ = grid(z);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    static float grid(double v) {
        if (!Double.isFinite(v)) return 0;
        return (float) (Math.round(Math.max(-0.5, Math.min(0.5, v)) / STEP) * STEP);
    }

    void setText(String text) {
        this.text = TableCards.clean(text);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("text", text);
        tag.putFloat("x", offsetX);
        tag.putFloat("z", offsetZ);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        text = TableCards.clean(tag.getString("text"));
        offsetX = grid(tag.getFloat("x"));
        offsetZ = grid(tag.getFloat("z"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
