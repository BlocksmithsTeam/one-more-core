package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A stack of trays: a click with an empty hand takes a tray; a click with an empty tray puts it back. */
public class TrayRackBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final int LOWER_SHELF = 2;
    private static final int MIDDLE_SHELF = 7;
    private static final int UPPER_SHELF = 12;
    private static final VoxelShape[] SHAPES = makeShapes();

    private static VoxelShape[] makeShapes() {
        VoxelShape[] shapes = BoxShapes.byFacing(
                new double[] {0, 0, 1, 1, 16, 2}, new double[] {15, 0, 1, 16, 16, 2},
                new double[] {0, 0, 14, 1, 16, 15}, new double[] {15, 0, 14, 16, 16, 15},
                new double[] {1, 15, 1, 15, 16, 2}, new double[] {1, 15, 14, 15, 16, 15},
                new double[] {1, 0, 1, 15, 1, 2}, new double[] {1, 0, 14, 15, 1, 15},
                new double[] {0, 1, 2, 2, 2, 14}, new double[] {14, 1, 2, 16, 2, 14},
                new double[] {0, 6, 2, 2, 7, 14}, new double[] {14, 6, 2, 16, 7, 14},
                new double[] {0, 11, 2, 2, 12, 14}, new double[] {14, 11, 2, 16, 12, 14});
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            int index = facing.get2DDataValue();
            VoxelShape tray = TrayBlock.shapeFor(facing);
            shapes[index] = Shapes.or(shapes[index], tray.move(0, LOWER_SHELF / 16.0, 0),
                    tray.move(0, MIDDLE_SHELF / 16.0, 0), tray.move(0, UPPER_SHELF / 16.0, 0)).optimize();
        }
        return shapes;
    }

    public TrayRackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            double y = (hit.getLocation().y - pos.getY()) * 16;
            // Boundaries halfway between the centres of the three trays (3, 8 and 13 pixels).
            boolean middle = y >= 5.5 && y < 10.5;
            TrayBlock.give(player, new ItemStack(middle ? OneMoreCore.IRON_TRAY_ITEM.get() : OneMoreCore.TRAY_ITEM.get()));
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6f, 0.9f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof TrayItem) || stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems().iterator().hasNext())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) {
            stack.shrink(1);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6f, 0.9f);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
}
