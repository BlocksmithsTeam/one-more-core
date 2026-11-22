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
 * A TV on a wall (the Menu Screen; the Order Screen and the Monitor build on it). The TV is 3 blocks wide and 2 high, its block
 * the middle one at the bottom; it keeps the other 5 cells with parts (ScreenPartBlock). The Monitor is one block. What it shows
 * is the restaurant's (ScreenSettings): a new screen shows the menu (the Order Screen: the orders).
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
        BlockState state = defaultBlockState().setValue(FACING, face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getOpposite())
                .setValue(TILT, tiltFor(pitch));
        // A TV needs its whole space.
        return roomFor(context, state) ? state : null;
    }

    /** Whether the cells of the screen besides its own block are free. */
    static boolean roomFor(BlockPlaceContext context, BlockState state) {
        for (BlockPos cell : ((MenuBoardBlock) state.getBlock()).parts(context.getClickedPos(), state))
            if (context.getLevel().isOutsideBuildHeight(cell) || !context.getLevel().getWorldBorder().isWithinBounds(cell)
                    || !context.getLevel().getBlockState(cell).canBeReplaced(context)) return false;
        return true;
    }

    /** 3 x 2 blocks; the Monitor is one. */
    public boolean wide() {
        return true;
    }

    /** The cells this screen keeps besides its own block (ScreenPartBlock). */
    public java.util.List<BlockPos> parts(BlockPos pos, BlockState state) {
        return wide() ? partCells(pos, state.getValue(FACING)) : java.util.List.of();
    }

    /** Whether the screen in this state can be turned down (TILT). */
    public boolean tilts(BlockState state) {
        return true;
    }

    /** Whether a block state is a screen that can be turned down. */
    public static boolean canTilt(BlockState state) {
        return state.getBlock() instanceof MenuBoardBlock board && board.tilts(state);
    }

    /** What a new screen of this block shows. */
    public int defaultMode() {
        return Restaurant.SCREEN_MENU;
    }

    /** The cells of a wide screen besides its own block: one to each side along the wall, and the row above. */
    public static java.util.List<BlockPos> partCells(BlockPos pos, Direction facing) {
        Direction side = facing.getClockWise();
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        for (int up = 0; up <= 1; up++)
            for (int k = -1; k <= 1; k++) if (up != 0 || k != 0) cells.add(pos.relative(side, k).above(up));
        return cells;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        if (level.isClientSide) return;
        for (BlockPos cell : parts(pos, state)) level.setBlock(cell, ScreenPartBlock.towards(cell, pos), 3);
    }

    /** The TV goes with its parts. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !newState.is(this))
            for (BlockPos cell : parts(pos, state))
                if (level.getBlockState(cell).is(OneMoreCore.SCREEN_PART.get())
                        && ScreenPartBlock.main(cell, level.getBlockState(cell)).equals(pos)) level.removeBlock(cell, false);
        super.onRemove(state, level, pos, newState, moved);
    }

    /** Looking up more than 35 degrees: 45; more than 12: 22.5; else straight. */
    static int tiltFor(float pitch) {
        return pitch < -35 ? 2 : pitch < -12 ? 1 : 0;
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
