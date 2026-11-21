package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A chalkboard on a wall that shows the menu of the restaurant: the dishes that are on the menu, by kind, with their
 * prices, and the combos. The board is two blocks wide and one and a half high, centred on its block.
 */
public class MenuBoardBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The screen turned down (its top out from the wall): 0, 22.5 or 45 degrees, for a screen hung high. */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty TILT =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("tilt", 0, 2);
    /** The screen that faces north: on the wall at z 16, 1 px thick; turned down, its top comes out from the wall. */
    private static final VoxelShape[][] SHAPES = {BoxShapes.byFacing(new double[] {0, 0, 15, 16, 16, 16}),
            BoxShapes.byFacing(new double[] {0, 0, 7.5, 16, 16, 16}), BoxShapes.byFacing(new double[] {0, 0, 0.5, 16, 16, 16})};

    public MenuBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TILT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TILT);
    }

    /**
     * Placed on the side of a block, facing away from it; on a floor or ceiling, facing the player. A player who looks up to place it
     * hangs it high: it is turned down towards them.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        float pitch = context.getPlayer() == null ? 0 : context.getPlayer().getXRot();
        return defaultBlockState().setValue(FACING, face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getOpposite())
                .setValue(TILT, tiltFor(pitch));
    }

    /** Looking up more than 35 degrees: 45; more than 12: 22.5; else straight. */
    static int tiltFor(float pitch) {
        return pitch < -35 ? 2 : pitch < -12 ? 1 : 0;
    }

    /** Sneak-click with an empty hand: the next tilt. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!level.isClientSide) level.setBlockAndUpdate(pos, state.cycle(TILT));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(TILT)][state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MenuBoardBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != OneMoreCore.MENU_BOARD_ENTITY.get() ? null
                : (l, p, s, be) -> ((MenuBoardBlockEntity) be).serverTick((net.minecraft.server.level.ServerLevel) l);
    }
}
