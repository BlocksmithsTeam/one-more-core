package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Only for the renderer of the sign: it keeps the turn of the board on the client. It saves nothing. */
public class OpenSignBlockEntity extends BlockEntity {
    /** Client only: the open state that the board turns to, and the game time (with the partial tick) when the turn started. */
    public boolean target;
    public double turnStart = Double.NEGATIVE_INFINITY;
    public boolean seen;

    public OpenSignBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreCore.OPEN_SIGN_ENTITY.get(), pos, state);
    }
}
