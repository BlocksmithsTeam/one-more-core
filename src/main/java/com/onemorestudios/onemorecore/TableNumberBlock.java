package com.onemorestudios.onemorecore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
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
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A table number: a card in a wooden holder, placed on a serving surface like any block, where the player clicks (on a grid of
 * 1/8 block, also between two blocks of a table: TableNumberItem). Its text (TableCards.MAX characters)
 * names the table on the order screens and the tickets; a click with an empty hand opens the text box, breaking it takes it.
 */
public class TableNumberBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The holder and the card of models/block/table_card (art/table_card_v2.bbmodel). */
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(new double[] {5, 0, 6, 11, 2, 9}, new double[] {4, 1, 7, 12, 7, 8});

    public TableNumberBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The card's front faces the player who puts it down. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        return canSurvive(state, context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** Only on a serving surface. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(OneMoreCore.SURFACES);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return dir == Direction.DOWN && !canSurvive(state, level, pos) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    /** The shape stands where the card stands (TableNumberBlockEntity offset). */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = SHAPES[state.getValue(FACING).get2DDataValue()];
        return level.getBlockEntity(pos) instanceof TableNumberBlockEntity be && (be.offsetX() != 0 || be.offsetZ() != 0)
                ? shape.move(be.offsetX(), 0, be.offsetZ()) : shape;
    }

    /** Drawn by its block entity renderer, which can move the card off the middle of the block. */
    @Override
    protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.ENTITYBLOCK_ANIMATED;
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
        return new TableNumberBlockEntity(pos, state);
    }

    /** The table learns its card, and the player types the text. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (!(level instanceof ServerLevel sl)) return;
        Restaurant r = Restaurants.get(sl).containing(pos.below());
        if (r == null) {
            if (placer instanceof Player p) p.displayClientMessage(Component.translatable("onemorecore.card.no_restaurant"), true);
        } else {
            r.placeCard(sl, pos.below(), card(state, ""));
        }
        if (placer instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new TableCards.Edit(pos, ""));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof TableNumberBlockEntity be)
            PacketDistributor.sendToPlayer(sp, new TableCards.Edit(pos, be.text()));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl) {
            Restaurant r = Restaurants.get(sl).containing(pos.below());
            if (r != null) r.removeCard(sl, pos.below());
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    static TableCards.Card card(BlockState state, String text) {
        return new TableCards.Card(text, 0.5f, 0.5f, state.getValue(FACING).toYRot());
    }
}
