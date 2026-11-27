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

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        BlockPos above = worldPosition.above();
        BlockState part = level.getBlockState(above);
        // Old wall signs reserved this cell; remove only the part that points back to this sign.
        if (part.is(OneMoreCore.SCREEN_PART.get()) && ScreenPartBlock.main(above, part).equals(worldPosition)) level.removeBlock(above, false);
    }
}
