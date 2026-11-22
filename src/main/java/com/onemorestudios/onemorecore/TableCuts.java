package com.onemorestudios.onemorecore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * Where two touching serving surfaces are split into two tables. Touching surfaces are one table unless the edge between
 * them is cut. An edge is kept by its west or north block: east cuts the edge to the block east of it, south to the south.
 */
public final class TableCuts {
    /** At most this many cuts in a restaurant. */
    static final int MAX = 1024;
    private final Set<BlockPos> east = new HashSet<>(), south = new HashSet<>();

    public TableCuts() {}

    /** Client: the cuts of a packet. */
    public TableCuts(List<BlockPos> east, List<BlockPos> south) {
        this.east.addAll(east);
        this.south.addAll(south);
    }

    /** Whether the edge from pos towards a horizontal direction is cut. */
    public boolean cut(BlockPos pos, Direction dir) {
        return switch (dir) {
            case EAST -> east.contains(pos);
            case WEST -> east.contains(pos.west());
            case SOUTH -> south.contains(pos);
            case NORTH -> south.contains(pos.north());
            default -> false;
        };
    }

    /** Cuts the edge, or joins it again. */
    void toggle(BlockPos pos, Direction dir) {
        Set<BlockPos> set = dir.getAxis() == Direction.Axis.X ? east : south;
        BlockPos key = (dir == Direction.WEST || dir == Direction.NORTH ? pos.relative(dir) : pos).immutable();
        if (!set.remove(key) && east.size() + south.size() < MAX) set.add(key);
    }

    List<BlockPos> east() { return List.copyOf(east); }

    List<BlockPos> south() { return List.copyOf(south); }

    void save(CompoundTag tag) {
        tag.putLongArray("table_cuts_east", east.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("table_cuts_south", south.stream().mapToLong(BlockPos::asLong).toArray());
    }

    void load(CompoundTag tag) {
        for (long p : tag.getLongArray("table_cuts_east")) if (east.size() < MAX) east.add(BlockPos.of(p));
        for (long p : tag.getLongArray("table_cuts_south")) if (east.size() + south.size() < MAX) south.add(BlockPos.of(p));
    }

    /** A click this near an edge of a table top (blocks) is a click on that edge. */
    static final double EDGE = 0.25;

    /**
     * The edge of a table top that a click on its top face is near, towards another serving surface; null for a click
     * elsewhere (the middle of the block, a side face) or an edge with no table behind it.
     */
    public static Direction edge(net.minecraft.world.level.BlockGetter level, BlockPos pos, net.minecraft.world.phys.BlockHitResult hit) {
        if (hit.getDirection() != Direction.UP) return null;
        double x = hit.getLocation().x - pos.getX(), z = hit.getLocation().z - pos.getZ();
        Direction best = null;
        double near = EDGE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            double dist = switch (d) { case EAST -> 1 - x; case WEST -> x; case SOUTH -> 1 - z; default -> z; };
            if (dist < near && level.getBlockState(pos.relative(d)).is(OneMoreCore.SURFACES)) {
                near = dist;
                best = d;
            }
        }
        return best;
    }

    /** Client: the cuts of every restaurant that the player has seen. */
    public static TableCuts client() {
        TableCuts all = new TableCuts();
        for (TableMarks t : TableMarks.CLIENT.values()) {
            all.east.addAll(t.cutsEast());
            all.south.addAll(t.cutsSouth());
        }
        return all;
    }
}
