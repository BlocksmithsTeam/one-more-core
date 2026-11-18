package com.onemorestudios.onemoreclient;

import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
                for (BlockPos surface : List.copyOf(r.dirt.keySet())) r.setDirt(sl, surface, 0);
                // Like a filled shulker box: the sign item keeps the money, the day, the unlocks and the paid orders.
                popResource(level, pos, signItem(r));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    static ItemStack signItem(Restaurant r) {
        ItemStack item = new ItemStack(OneMoreClient.OPEN_SIGN_ITEM.get());
        if (r.hasProgress()) {
            CompoundTag data = new CompoundTag();
            data.put("restaurant", r.progress());
            item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
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
        if (stack.is(Config.currency())) {
            if (level instanceof ServerLevel sl) {
                int n = Restaurants.get(sl).create(pos).deposit(stack.getCount());
                stack.shrink(n);
                player.displayClientMessage(Component.translatable("onemoreclient.register.deposit", n), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!stack.is(OneMoreClient.AREA_TOOL)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel sl) {
            Restaurant r = Restaurants.get(sl).create(pos);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.remove("c1");
                tag.putLong("sign", pos.asLong());
                tag.putString("dimension", level.dimension().location().toString());
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
            ItemStack money = new ItemStack(Config.currency());
            int n = r.take(money.getMaxStackSize());
            if (n > 0) player.getInventory().placeItemBackInInventory(money.copyWithCount(n));
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
