package com.onemorestudios.onemorecore;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

/**
 * A fast food tray on a counter. Click it with a dish to put the dish on it, with an order ticket to stick the ticket
 * (the table number) to its edge. Sneak-click with an empty hand to take the tray with everything on it; then click the
 * customer (or the table in front of the customer) to serve all of that customer's dishes at once. A click with an empty
 * hand takes the last dish back (with Diegesis it opens the tray up close instead).
 */
public class TrayBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final float DISH_SPACING = 4;
    private static final float SEARCH_STEP = 0.25f;
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(
            new double[] {0, 0, 2, 16, 1, 14},
            new double[] {0, 1, 2, 16, 2, 3},
            new double[] {0, 1, 13, 16, 2, 14},
            new double[] {0, 1, 3, 0.5, 2, 13},
            new double[] {15.5, 1, 3, 16, 2, 13});

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
        return shapeFor(state.getValue(FACING));
    }

    static VoxelShape shapeFor(Direction facing) {
        return SHAPES[facing.get2DDataValue()];
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
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof TrayBlockEntity tray) || !tray.hasOverflow()) return;
        Player player = placer instanceof Player p ? p : null;
        // Creative keeps the tray item. Its overflow still transfers to the player just once.
        if (player != null && player.hasInfiniteMaterials()) TrayItem.trimOverflow(stack);
        // NeoForge calls onPlace after a captured placement has been accepted.
        if (level.captureBlockSnapshots) tray.placingPlayer = player;
        else tray.releaseOverflow(player);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!level.restoringBlockSnapshots && level.getBlockEntity(pos) instanceof TrayBlockEntity tray
                && tray.placingPlayer != null) {
            Player player = tray.placingPlayer;
            tray.placingPlayer = null;
            tray.releaseOverflow(player);
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty() || stack.getItem() instanceof TrayItem
                || !(level.getBlockEntity(pos) instanceof TrayBlockEntity tray))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (stack.is(OneMoreCore.TICKET.get())) {
            tray.label = TableCards.clean(stack.getOrDefault(OneMoreCore.LABEL.get(), ""));
            tray.changed();
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8f, 1.3f);
            return ItemInteractionResult.SUCCESS;
        }
        if (!put(tray, stack.copyWithCount(1), hit, player)) {
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

    /** The blockstate model's clockwise turn from north, in degrees. */
    public static float bedYaw(Direction facing) {
        return (facing.toYRot() + 180) % 360;
    }

    private static boolean put(TrayBlockEntity tray, ItemStack dish, BlockHitResult hit, Player player) {
        for (int slot = 0; slot < TrayBlockEntity.SIZE; slot++) {
            if (!tray.items.getItem(slot).isEmpty()) continue;
            float yaw = bedYaw(tray.getBlockState().getValue(FACING));
            Vec3 local = hit.getLocation().subtract(Vec3.atCenterOf(tray.getBlockPos())).yRot(yaw * Mth.DEG_TO_RAD);
            float x = Mth.clamp((float) (8 + local.x * 16), TrayPose.MIN_X, TrayPose.MAX_X);
            float z = Mth.clamp((float) (8 + local.z * 16), TrayPose.MIN_Z, TrayPose.MAX_Z);
            Quaternionf turn = new Quaternionf().rotateY((yaw - player.getYRot()) * Mth.DEG_TO_RAD);
            if (!roomAt(tray, x, z)) {
                // Find the nearest clear point on the bed, including when the click is against a rim or between two dishes.
                float nearest = Float.POSITIVE_INFINITY;
                float targetX = x, targetZ = z;
                for (float bx = TrayPose.MIN_X; bx <= TrayPose.MAX_X; bx += SEARCH_STEP) {
                    for (float bz = TrayPose.MIN_Z; bz <= TrayPose.MAX_Z; bz += SEARCH_STEP) {
                        float distance = Mth.square(bx - x) + Mth.square(bz - z);
                        if (distance < nearest && roomAt(tray, bx, bz)) {
                            nearest = distance;
                            targetX = bx;
                            targetZ = bz;
                        }
                    }
                }
                x = targetX;
                z = targetZ;
            }
            tray.putDish(slot, dish, TrayPose.atRest(x, z, turn));
            return true;
        }
        return false;
    }

    private static boolean roomAt(TrayBlockEntity tray, float x, float z) {
        for (int slot = 0; slot < TrayBlockEntity.SIZE; slot++) {
            if (tray.items.getItem(slot).isEmpty()) continue;
            TrayPose pose = tray.getPose(slot);
            if (Mth.square(x - pose.x()) + Mth.square(z - pose.z()) < DISH_SPACING * DISH_SPACING) return false;
        }
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof TrayBlockEntity tray)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player.isShiftKeyDown()) {
            // The whole tray goes into the hand, with its dishes and label.
            ItemStack item = new ItemStack(this);
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
