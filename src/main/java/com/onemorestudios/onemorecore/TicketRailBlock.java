package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class TicketRailBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(new double[] {0, 3, 14, 16, 14, 16});

    public TicketRailBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) return null;
        BlockState state = defaultBlockState().setValue(FACING, face);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction front = state.getValue(FACING);
        return level.getBlockState(pos.relative(front.getOpposite())).isFaceSturdy(level, pos.relative(front.getOpposite()), front);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos other) {
        return side == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, side, neighbour, level, pos, other);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }

    /** Inverse of the blockstate rotation; retain doubles until the local coordinate is known. */
    public static int spot(BlockState state, BlockPos pos, Vec3 hit) {
        double x = hit.x - pos.getX(), z = hit.z - pos.getZ(), y = hit.y - pos.getY();
        double across = switch (state.getValue(FACING)) {
            case EAST -> z;
            case SOUTH -> 1 - x;
            case WEST -> 1 - z;
            default -> x;
        };
        if (y < 3 / 16d || y > 13 / 16d || across < 0 || across >= 1) return -1;
        return (int) (across * TicketRailBlockEntity.SIZE);
    }

    public static boolean mayUse(Player player, BlockPos pos) {
        return !player.isSpectator() && player.canInteractWithBlock(pos, 1)
                && (!(player.level() instanceof ServerLevel level) || level.mayInteract(player, pos));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        // FAIL still lets the held item run useOn; consume the click without using the item.
        if (!stack.is(OneMoreCore.TICKET.get())) return ItemInteractionResult.CONSUME;
        if (!mayUse(player, pos)) return ItemInteractionResult.FAIL;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof TicketRailBlockEntity rail && !rail.hang(stack))
            return ItemInteractionResult.FAIL;
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!mayUse(player, pos) || !player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof TicketRailBlockEntity rail)
            TrayBlock.give(player, rail.take(spot(state, pos, hit.getLocation())));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new TicketRailBlockEntity(pos, state); }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == OneMoreCore.TICKET_RAIL_ENTITY.get()
                ? (world, pos, block, entity) -> TicketRailBlockEntity.tick(world, pos, block, (TicketRailBlockEntity) entity) : null;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!replacement.is(this) && !level.isClientSide && level.getBlockEntity(pos) instanceof TicketRailBlockEntity rail)
            for (int i = 0; i < TicketRailBlockEntity.SIZE; i++) popResource(level, pos, rail.take(i));
        super.onRemove(state, level, pos, replacement, moving);
    }
}
