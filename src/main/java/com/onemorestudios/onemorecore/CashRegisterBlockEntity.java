package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class CashRegisterBlockEntity extends BlockEntity {
    private int balance;

    public CashRegisterBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreCore.CASH_REGISTER_ENTITY.get(), pos, state);
    }

    public int balance() { return balance; }

    int deposit(int amount) {
        int accepted = Math.min(amount, Integer.MAX_VALUE - balance);
        balance += accepted;
        setChanged();
        return accepted;
    }

    int take(int max) {
        int taken = Math.min(max, balance);
        balance -= taken;
        setChanged();
        return taken;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("balance", balance);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        balance = Math.max(0, tag.getInt("balance"));
    }
}
