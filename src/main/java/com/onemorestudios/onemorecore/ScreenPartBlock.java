package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The space that a TV takes besides its own block: nothing is drawn (the TV's model covers it), but no other block can be put
 * there, and it can be aimed at like the TV. OX, OY, OZ: the way to the TV's block, each 0..2 for -1..1.
 */
public class ScreenPartBlock extends Block {
    public static final IntegerProperty OX = IntegerProperty.create("ox", 0, 2), OY = IntegerProperty.create("oy", 0, 2), OZ = IntegerProperty.create("oz", 0, 2);

    public ScreenPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OX, 1).setValue(OY, 1).setValue(OZ, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OX, OY, OZ);
    }

    static BlockState towards(BlockPos part, BlockPos main) {
        return OneMoreCore.SCREEN_PART.get().defaultBlockState().setValue(OX, main.getX() - part.getX() + 1)
                .setValue(OY, main.getY() - part.getY() + 1).setValue(OZ, main.getZ() - part.getZ() + 1);
    }

    static BlockPos main(BlockPos part, BlockState state) {
        return part.offset(state.getValue(OX) - 1, state.getValue(OY) - 1, state.getValue(OZ) - 1);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** As thin as the TV, on the same side of the block. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockState main = level.getBlockState(main(pos, state));
        BlockPos mainPos = main(pos, state);
        if (main.getBlock() instanceof ScreenBlock) return ScreenBlock.shapeIn(main, pos.subtract(mainPos));
        if (main.getBlock() instanceof OpenSignBlock && OpenSignBlock.hasPart(main)) return OpenSignBlock.bladeTop(main);
        return main.getBlock() instanceof MenuBoardBlock board ? board.getShape(main, level, pos, context) : Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockState main = level.getBlockState(main(pos, state));
        if (main.getBlock() instanceof ScreenBlock) return ScreenBlock.shapeIn(main, pos.subtract(main(pos, state)));
        return Shapes.empty();
    }

    /** A click on the blade's upper part is a click on the sign. */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        BlockPos main = main(pos, state);
        BlockState owner = level.getBlockState(main);
        if (owner.getBlock() instanceof OpenSignBlock) return owner.useWithoutItem(level, player, hit.withPosition(main));
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        BlockPos main = main(pos, state);
        BlockState owner = level.getBlockState(main);
        if (owner.getBlock() instanceof OpenSignBlock) return owner.useItemOn(stack, level, player, hand, hit.withPosition(main));
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** A part broken by a player breaks the TV (which drops itself and removes its other parts). */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        BlockPos main = main(pos, state);
        Block owner = level.getBlockState(main).getBlock();
        if (!level.isClientSide && !newState.is(this) && (owner instanceof MenuBoardBlock || owner instanceof OpenSignBlock)) level.destroyBlock(main, true);
        super.onRemove(state, level, pos, newState, moved);
    }
}
