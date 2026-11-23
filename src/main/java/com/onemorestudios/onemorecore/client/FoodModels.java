package com.onemorestudios.onemorecore.client;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.onemorestudios.onemorecore.OneMoreCore;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The 3D form of a food: a block that shows it. First the plated food blocks of Display Delight (each block knows its
 * food through its public getStackFor() method; read by reflection, so that the mod is not needed), then the block that
 * the food item itself places (cakes, pies, Kaleidoscope dishes, bottles). Null when there is none.
 */
public final class FoodModels {
    private FoodModels() {}

    private static Map<Item, BlockState> plated;

    public static BlockState block(ItemStack stack) {
        if (plated == null) plated = scan();
        BlockState s = plated.get(stack.getItem());
        if (s != null) return s;
        if (stack.getItem() instanceof BlockItem item && item.getBlock().defaultBlockState().getRenderShape() == RenderShape.MODEL)
            return item.getBlock().defaultBlockState();
        return null;
    }

    /** The block a dish shows on trays and tables (CustomerRenderer.display "block"), or null. */
    public static BlockState shownBlock(ItemStack stack) {
        return CustomerRenderer.showsBlock(stack) ? block(stack) : null;
    }

    /** A stackable plate shows several portions by default ("stacks"); one order is one portion. */
    private static BlockState single(BlockState state) {
        for (var property : state.getProperties()) {
            if (property.getName().equals("stacks") && property instanceof net.minecraft.world.level.block.state.properties.IntegerProperty p)
                return state.setValue(p, p.getPossibleValues().stream().min(Integer::compare).orElse(1));
        }
        return state;
    }

    private static Map<Item, BlockState> scan() {
        Map<Item, BlockState> map = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("displaydelight")) continue;
            try {
                Method m = block.getClass().getMethod("getStackFor");
                if (m.invoke(block) instanceof ItemStack food && !food.isEmpty() && block.defaultBlockState().getRenderShape() == RenderShape.MODEL)
                    map.putIfAbsent(food.getItem(), single(block.defaultBlockState()));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Not a food display block (a plate, a decoration): skipped.
            }
        }
        if (!map.isEmpty()) OneMoreCore.LOGGER.info("Display Delight: {} plated foods are shown in 3D", map.size());
        return map;
    }
}
