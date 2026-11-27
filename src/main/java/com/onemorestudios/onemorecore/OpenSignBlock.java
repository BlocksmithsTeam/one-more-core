package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The core block of a restaurant. Click to start the day. Like a storage drawer for money: click with money to put it in,
 * sneak-click to take one stack out.
 */
public class OpenSignBlock extends Block implements EntityBlock {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    /** A screen on a floor, wall or ceiling. */
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    /** Like a vanilla sign: 0 faces south, 4 west, 8 north, 12 east. A wall sign uses only 0, 4, 8 and 12. */
    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    private static final VoxelShape[] STANDING = new VoxelShape[16];
    static {
        for (int rotation = 0; rotation < 16; rotation++) {
            double angle = Math.toRadians(RotationSegment.convertToDegrees(rotation));
            double x = 8 * Math.abs(Math.cos(angle)) + 2 * Math.abs(Math.sin(angle));
            double z = 8 * Math.abs(Math.sin(angle)) + 2 * Math.abs(Math.cos(angle));
            STANDING[rotation] = box(8 - x, 0, 8 - z, 8 + x, 16, 8 + z);
        }
    }
    /** North-facing box; the south-facing model has its back at z 0. */
    private static final VoxelShape[] WALL = BoxShapes.byFacing(new double[] {0, 2, 10, 16, 16, 16});

    public OpenSignBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OPEN, false).setValue(FACE, AttachFace.FLOOR).setValue(ROTATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPEN, FACE, ROTATION);
    }

    /** Clicked on top of a block: on the floor; under a block: hanging; on a side: on the wall. Floor and ceiling face the player. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        if (clicked.getAxis().isHorizontal()) {
            return defaultBlockState().setValue(FACE, AttachFace.WALL).setValue(ROTATION, RotationSegment.convertToSegment(clicked));
        }
        return defaultBlockState().setValue(FACE, clicked == Direction.UP ? AttachFace.FLOOR : AttachFace.CEILING)
                .setValue(ROTATION, RotationSegment.convertToSegment(context.getRotation() + 180));
    }

    /** The direction that a wall sign faces, away from its wall. */
    public static Direction wallFacing(BlockState state) {
        // Segment 0 faces south (yaw 0), like a vanilla sign. RotationSegment.convertToDirection uses another origin.
        return Direction.fromYRot(RotationSegment.convertToDegrees(state.getValue(ROTATION)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case FLOOR, CEILING -> STANDING[state.getValue(ROTATION)];
            case WALL -> WALL[wallFacing(state).get2DDataValue()];
        };
    }

    /** Like a vanilla sign, players and customers walk through it. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /** The block entity renderer draws the sign, so that the board can turn. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OpenSignBlockEntity(pos, state);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(ROTATION, rotation.rotate(state.getValue(ROTATION), 16));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(ROTATION, mirror.mirror(state.getValue(ROTATION), 16));
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level instanceof ServerLevel sl && !oldState.is(this)) Restaurants.get(sl).create(pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel sl && !newState.is(this)) {
            Restaurants restaurants = Restaurants.get(sl);
            Restaurant r = restaurants.at(pos);
            if (r != null) {
                r.open = false; // the customers remove themselves
                r.settleOnRemoval(sl);
                for (BlockPos surface : List.copyOf(r.dirt.keySet())) r.setDirt(sl, surface, 0);
                restaurants.remove(pos);
                // Like a filled shulker box: the sign item keeps the money, the day, the unlocks and the paid orders.
                popResource(level, pos, signItem(r));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    static ItemStack signItem(Restaurant r) {
        ItemStack item = new ItemStack(OneMoreCore.OPEN_SIGN_ITEM.get());
        // Even an empty register has already received its starting balance.
        CompoundTag data = new CompoundTag();
        data.put("restaurant", r.progress());
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return item;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // A creative player gets no loot. Keep an empty restaurant gone without a drop, as vanilla does with shulker boxes.
        if (level instanceof ServerLevel sl && player.isCreative()) {
            Restaurant r = Restaurants.get(sl).at(pos);
            if (r != null && !r.hasProgress()) {
                r.open = false;
                Restaurants.get(sl).remove(pos);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (level instanceof ServerLevel sl && data.contains("restaurant")) Restaurants.get(sl).create(pos).restore(data.getCompound("restaurant"));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (Money.pieceValue(stack) > 0) {
            if (level instanceof ServerLevel sl) {
                Restaurant restaurant = Restaurants.get(sl).create(pos);
                CashRegisterBlock.depositMoney(stack, player, restaurant.register(), restaurant::deposit);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
        Restaurant r = Restaurants.get(sl).create(pos);
        if (player.isShiftKeyDown()) {
            CashRegisterBlock.withdrawMoney(player, r::take);
        } else if (!r.isOpen()) {
            r.requestOpen(sl, player);
        }
        return InteractionResult.SUCCESS;
    }
}
