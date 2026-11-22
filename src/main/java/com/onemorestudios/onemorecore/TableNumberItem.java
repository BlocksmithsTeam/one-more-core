package com.onemorestudios.onemorecore;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Places the Table Number where the player clicks: the card stands at the click point, on the grid of its block entity. */
public class TableNumberItem extends BlockItem {
    public TableNumberItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        var pos = context.getClickedPos();
        var click = context.getClickLocation();
        InteractionResult result = super.place(context);
        if (result.consumesAction() && context.getLevel().getBlockEntity(pos) instanceof TableNumberBlockEntity be)
            be.setOffset(click.x - pos.getX() - 0.5, click.z - pos.getZ() - 0.5);
        return result;
    }
}
