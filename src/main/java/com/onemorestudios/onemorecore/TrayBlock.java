package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

/**
 * A fast food tray on a counter. Click it with a dish to put the dish on it, with an order ticket to stick the ticket
 * (the table number) to its edge. Sneak-click with an empty hand to take the tray with everything on it; then click the
 * customer (or the table in front of the customer) to serve all of that customer's dishes at once. A click with an empty
 * hand takes the last dish back (with Diegesis it opens the tray up close instead).
 */
public class TrayBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(new double[] {1, 0, 3, 15, 1.5, 13});

    public TrayBlock(Properties properties) {
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
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrayBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof TrayBlockEntity tray) || stack.is(OneMoreCore.TRAY_ITEM.get()))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (stack.is(OneMoreCore.TICKET.get())) {
            tray.label = TableCards.clean(stack.getOrDefault(OneMoreCore.LABEL.get(), ""));
            tray.changed();
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8f, 1.3f);
            return ItemInteractionResult.SUCCESS;
        }
        if (!put(tray, stack.copyWithCount(1))) {
            player.displayClientMessage(Component.translatable("onemorecore.tray.full"), true);
            return ItemInteractionResult.SUCCESS;
        }
        stack.consume(1, player);
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7f, 1.2f);
        return ItemInteractionResult.SUCCESS;
    }

    /** Into the empty main hand, else into the inventory, else on the ground. */
    static void give(Player player, ItemStack stack) {
        if (player.getMainHandItem().isEmpty()) player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        else if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** Puts one dish in the first free place; false when the tray is full. */
    static boolean put(TrayBlockEntity tray, ItemStack one) {
        for (int i = 0; i < TrayBlockEntity.SIZE; i++)
            if (tray.items.getItem(i).isEmpty()) { tray.items.setItem(i, one); return true; }
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof TrayBlockEntity tray)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player.isShiftKeyDown()) {
            // The whole tray goes into the hand, with its dishes and label.
            ItemStack item = new ItemStack(OneMoreCore.TRAY_ITEM.get());
            item.applyComponents(tray.collectComponents());
            level.removeBlock(pos, false);
            give(player, item);
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6f, 1);
            return InteractionResult.SUCCESS;
        }
        for (int i = TrayBlockEntity.SIZE - 1; i >= 0; i--) {
            ItemStack dish = tray.items.getItem(i);
            if (dish.isEmpty()) continue;
            tray.items.setItem(i, ItemStack.EMPTY);
            if (!player.getInventory().add(dish)) player.drop(dish, false);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
