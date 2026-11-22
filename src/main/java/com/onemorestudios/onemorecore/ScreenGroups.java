package com.onemorestudios.onemorecore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Screens that join into one picture: TVs side by side or one over the other (3 x 2 blocks each), or Monitors (1 x 1), with
 * the same facing and tilt (tilted ones only side by side), that fill a whole rectangle. The top left one (as seen from the
 * front) is the anchor: it draws the whole picture with its settings.
 */
public final class ScreenGroups {
    private ScreenGroups() {}

    /** A group: its anchor, the picture of the whole group in the anchor's model pixels, its size, and its screens. */
    public record Group(BlockPos anchor, ScreenRect rect, int cols, int rows, List<BlockPos> members) {}

    /** The screen's block for a TV part, else the position itself. */
    public static BlockPos main(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(OneMoreCore.SCREEN_PART.get()) ? ScreenPartBlock.main(pos, state) : pos;
    }

    /** The group of the screen at pos; a screen that joins nothing (or another mod's) is a group of one. */
    public static Group of(BlockGetter level, BlockPos pos) {
        pos = main(level, pos);
        BlockState state = level.getBlockState(pos);
        ScreenRect rect = ScreenRect.of(state);
        if (rect == null) return null;
        Group alone = new Group(pos, rect, 1, 1, List.of(pos));
        if (!(state.getBlock() instanceof MenuBoardBlock board)) return alone;
        ScreenLayout layout = state.getBlock() instanceof ScreenBlock ? ScreenLayout.of(state) : null;
        int stepX = layout != null ? layout.stepX() : board.wide() ? 3 : 1, stepY = layout != null ? layout.stepY() : board.wide() ? 2 : 1;
        Direction facing = state.getValue(MenuBoardBlock.FACING);
        // Model +x is the viewer's right: facing south (+z), +x; turned with the block.
        Direction right = facing.getCounterClockWise();
        boolean vertical = state.getValue(MenuBoardBlock.TILT) == 0 && (layout == null || layout.joinsUp());
        Map<BlockPos, int[]> cells = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        cells.put(pos, new int[] {0, 0});
        queue.add(pos);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            int[] at = cells.get(p);
            int[][] steps = vertical ? new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}} : new int[][] {{1, 0}, {-1, 0}};
            for (int[] d : steps) {
                BlockPos q = p.relative(right, d[0] * stepX).above(d[1] * stepY);
                if (cells.containsKey(q)) continue;
                BlockState s = level.getBlockState(q);
                if (s.getBlock() != state.getBlock() || s.getValue(MenuBoardBlock.FACING) != facing || !s.getValue(MenuBoardBlock.TILT).equals(state.getValue(MenuBoardBlock.TILT))
                        || layout != null && !ScreenBlock.sameKind(state, s)) continue;
                if (cells.size() == 64) return alone;
                cells.put(q, new int[] {at[0] + d[0], at[1] + d[1]});
                queue.add(q);
            }
        }
        if (cells.size() == 1) return alone;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] c : cells.values()) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]);
            maxY = Math.max(maxY, c[1]);
        }
        int cols = maxX - minX + 1, rows = maxY - minY + 1;
        // Only a full rectangle joins; any other shape shows each screen alone.
        if (cols * rows != cells.size()) return alone;
        BlockPos anchor = null;
        for (var e : cells.entrySet()) if (e.getValue()[0] == minX && e.getValue()[1] == maxY) anchor = e.getKey();
        return new Group(anchor, rect.grown(cols, rows, stepX * 16, stepY * 16), cols, rows, new ArrayList<>(cells.keySet()));
    }
}
