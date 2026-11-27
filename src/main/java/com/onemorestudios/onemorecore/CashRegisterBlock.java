package com.onemorestudios.onemorecore;

import java.util.function.IntUnaryOperator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class CashRegisterBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(new double[] {1, 0, 2, 15, 3, 13}, new double[] {2, 3, 3, 14, 7, 13});

    public CashRegisterBlock(Properties properties) {
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
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CashRegisterBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (Money.pieceValue(stack) == 0) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel sl) {
            Restaurant restaurant = Restaurants.get(sl).containing(pos);
            if (restaurant != null) depositMoney(stack, player, restaurant.register(), restaurant::deposit);
            else if (level.getBlockEntity(pos) instanceof CashRegisterBlockEntity cash) depositMoney(stack, player, cash.balance(), cash::deposit);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player.isShiftKeyDown()) {
            Restaurant restaurant = Restaurants.get(sl).containing(pos);
            if (restaurant != null) withdrawMoney(player, restaurant::take);
            else if (level.getBlockEntity(pos) instanceof CashRegisterBlockEntity cash) withdrawMoney(player, cash::take);
        }
        return InteractionResult.SUCCESS;
    }

    static void depositMoney(ItemStack stack, Player player, int balance, IntUnaryOperator deposit) {
        int n = Money.deposit(stack, balance, deposit);
        player.displayClientMessage(Component.translatable("onemorecore.register.deposit", n), true);
    }

    static void withdrawMoney(Player player, IntUnaryOperator take) {
        int n = take.applyAsInt(new ItemStack(Money.coin()).getMaxStackSize());
        Money.items(n, player.getInventory()::placeItemBackInInventory);
        player.displayClientMessage(n == 0 ? Component.translatable("onemorecore.register.empty")
                : Component.translatable("onemorecore.register.withdraw", n), true);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!replacement.is(this) && level instanceof ServerLevel
                && level.getBlockEntity(pos) instanceof CashRegisterBlockEntity cash) {
            Money.items(cash.take(Integer.MAX_VALUE), money -> popResource(level, pos, money));
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
