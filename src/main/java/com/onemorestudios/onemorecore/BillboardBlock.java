package com.onemorestudios.onemorecore;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Menu Screen: a street billboard on a post, 1 block wide and 2 high (the block above is a ScreenPartBlock). Both sides
 * show the picture. It stands upright and joins no other screen.
 */
public class BillboardBlock extends MenuBoardBlock {
    /** The frame, 4 px thick in the middle of the block (the model's z 6..10). */
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(new double[] {0, 0, 6, 16, 16, 10});

    public BillboardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public List<BlockPos> parts(BlockPos pos, Direction facing) {
        return List.of(pos.above());
    }

    @Override
    public boolean wide() {
        return false;
    }

    @Override
    public boolean tilts() {
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }
}
