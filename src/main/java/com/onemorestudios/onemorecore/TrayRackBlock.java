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

/**
 * A rack of up to six trays that players put in: a click with an empty tray puts it on the free level nearest the click, a click
 * with an empty hand takes the tray nearest the click. An empty rack gives nothing. Breaking the rack drops its trays.
 */
public class TrayRackBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** What lies on a level of the rack. */
    public enum Slot implements net.minecraft.util.StringRepresentable {
        EMPTY("empty"), OAK("oak"), IRON("iron");

        private final String name;

        Slot(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }

        ItemStack stack() {
            return this == EMPTY ? ItemStack.EMPTY : new ItemStack(this == IRON ? OneMoreCore.IRON_TRAY_ITEM.get() : OneMoreCore.TRAY_ITEM.get());
        }

        static Slot of(ItemStack stack) {
            return stack.is(OneMoreCore.IRON_TRAY_ITEM.get()) ? IRON : stack.is(OneMoreCore.TRAY_ITEM.get()) ? OAK : EMPTY;
        }
    }

    /** The levels, bottom first; the base of level i is at y = 1 + 2i pixels (art: the tray_rack_* models). */
    public static final int LEVELS = 6;
    @SuppressWarnings("unchecked")
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Slot>[] SLOTS = new net.minecraft.world.level.block.state.properties.EnumProperty[LEVELS];
    static {
        for (int i = 0; i < LEVELS; i++) SLOTS[i] = net.minecraft.world.level.block.state.properties.EnumProperty.create("slot" + i, Slot.class);
    }

    static double levelY(int i) {
        return 1 + 2 * i;
    }

    private static final VoxelShape[] FRAMES = BoxShapes.byFacing(
            new double[] {0, 0, 1, 1, 16, 2}, new double[] {15, 0, 1, 16, 16, 2},
            new double[] {0, 0, 14, 1, 16, 15}, new double[] {15, 0, 14, 16, 16, 15},
            new double[] {1, 15, 1, 15, 16, 2}, new double[] {1, 15, 14, 15, 16, 15},
            new double[] {1, 0, 1, 15, 1, 2}, new double[] {1, 0, 14, 15, 1, 15},
            new double[] {0, 1, 2, 1, 12, 14}, new double[] {15, 1, 2, 16, 12, 14});
    private static final java.util.Map<BlockState, VoxelShape> SHAPES = new java.util.concurrent.ConcurrentHashMap<>();

    public TrayRackBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(FACING, Direction.NORTH);
        for (var slot : SLOTS) state = state.setValue(slot, Slot.EMPTY);
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(SLOTS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** The frame, and a box for each tray in it. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.computeIfAbsent(state, s -> {
            VoxelShape shape = FRAMES[s.getValue(FACING).get2DDataValue()];
            for (int i = 0; i < LEVELS; i++)
                if (s.getValue(SLOTS[i]) != Slot.EMPTY) shape = Shapes.or(shape, Block.box(1, levelY(i), 1, 15, levelY(i) + 2, 15));
            return shape.optimize();
        });
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** The trays in the rack, bottom first. */
    public static int count(BlockState state) {
        int n = 0;
        for (var slot : SLOTS) if (state.getValue(slot) != Slot.EMPTY) n++;
        return n;
    }

    /** The level with (full) or without a tray whose middle is nearest a height in pixels, or -1. */
    static int nearest(BlockState state, double y, boolean full) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < LEVELS; i++) {
            if ((state.getValue(SLOTS[i]) != Slot.EMPTY) != full) continue;
            double d = Math.abs(levelY(i) + 1 - y);
            if (d < bestDistance) { bestDistance = d; best = i; }
        }
        return best;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        int i = nearest(state, (hit.getLocation().y - pos.getY()) * 16, true);
        if (i < 0) {
            if (!level.isClientSide) player.displayClientMessage(net.minecraft.network.chat.Component.translatable("onemorecore.tray_rack.empty"), true);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            TrayBlock.give(player, state.getValue(SLOTS[i]).stack());
            level.setBlock(pos, state.setValue(SLOTS[i], Slot.EMPTY), 3);
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6f, 0.9f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** An empty tray goes on the free level nearest the click; a full rack or a tray with dishes on it is refused. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        Slot kind = Slot.of(stack);
        if (kind == Slot.EMPTY || stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems().iterator().hasNext())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        int i = nearest(state, (hit.getLocation().y - pos.getY()) * 16, false);
        if (i < 0) {
            if (!level.isClientSide) player.displayClientMessage(net.minecraft.network.chat.Component.translatable("onemorecore.tray_rack.full"), true);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            stack.shrink(1);
            level.setBlock(pos, state.setValue(SLOTS[i], kind), 3);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6f, 0.9f);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /** The trays fall out when the rack goes (not when one of its trays is taken). */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !newState.is(this))
            for (var slot : SLOTS) if (state.getValue(slot) != Slot.EMPTY) popResource(level, pos, state.getValue(slot).stack());
        super.onRemove(state, level, pos, newState, moved);
    }
}
