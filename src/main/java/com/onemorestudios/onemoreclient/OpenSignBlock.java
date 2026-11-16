package com.onemorestudios.onemoreclient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
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

/** The core block of a restaurant. Click to start the day; sneak-click to empty the register. */
public class OpenSignBlock extends Block {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    public OpenSignBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPEN);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level instanceof ServerLevel sl && !oldState.is(this)) Restaurants.get(sl).create(pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel sl && !newState.is(this)) {
            Restaurant r = Restaurants.get(sl).remove(pos);
            if (r != null) {
                r.open = false; // the customers remove themselves
                for (int left = r.register; left > 0; left -= 64) popResource(level, pos, new ItemStack(Config.currency(), Math.min(64, left)));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(OneMoreClient.AREA_TOOL)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel sl) {
            Restaurant r = Restaurants.get(sl).create(pos);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.remove("c1");
                tag.putLong("sign", pos.asLong());
            });
            AreaToolItem.showArea(stack, r);
            player.displayClientMessage(Component.translatable("onemoreclient.tool.bound"), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
        Restaurant r = Restaurants.get(sl).create(pos);
        if (player.isShiftKeyDown()) {
            int n = r.takeMoney();
            ItemStack money = new ItemStack(Config.currency());
            for (int left = n; left > 0; left -= 64) player.getInventory().placeItemBackInInventory(money.copyWithCount(Math.min(64, left)));
            player.displayClientMessage(n == 0 ? Component.translatable("onemoreclient.register.empty")
                    : Component.translatable("onemoreclient.register.withdraw", n), true);
        } else if (r.isOpen()) {
            player.displayClientMessage(Component.translatable("onemoreclient.open.status", r.day, r.toSpawn + r.customers.size(), r.register), true);
        } else {
            r.requestOpen(sl, player);
        }
        return InteractionResult.SUCCESS;
    }
}
