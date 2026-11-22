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

    /**
     * The sides of a table block that a click on one of its faces can split or join, like Create's crafters
     * (EdgeInteractionHandler, MIT): the horizontal sides across the face, towards another serving surface, unless a solid
     * block covers that face of the neighbour.
     */
    public static java.util.List<Direction> connectiveSides(net.minecraft.world.level.BlockGetter level, BlockPos pos, Direction face) {
        java.util.List<Direction> sides = new java.util.ArrayList<>(4);
        if (covered(level, pos.relative(face), face)) return sides;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d.getAxis() == face.getAxis()) continue;
            BlockPos n = pos.relative(d);
            if (!level.getBlockState(n).is(OneMoreCore.SURFACES) || covered(level, n.relative(face), face)) continue;
            sides.add(d);
        }
        return sides;
    }

    private static boolean covered(net.minecraft.world.level.BlockGetter level, BlockPos pos, Direction face) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, face.getOpposite());
    }

    /** The click zone of a side: the strip of the block, 6/16 wide, along that side (as Create's crafters). */
    public static net.minecraft.world.phys.AABB zone(BlockPos pos, Direction d) {
        double margin = 10 / 16.0;
        double ax = Math.abs(d.getStepX()) * margin, az = Math.abs(d.getStepZ()) * margin;
        return new net.minecraft.world.phys.AABB(pos).contract(ax, 0, az).move(ax / 2 + d.getStepX() / 2.0, 0, az / 2 + d.getStepZ() / 2.0)
                .inflate(1 / 256.0);
    }

    /** The side that a click at hit (on face of the table block at pos) splits or joins, or null. */
    public static Direction edge(net.minecraft.world.level.BlockGetter level, BlockPos pos, Direction face, net.minecraft.world.phys.Vec3 hit) {
        for (Direction d : connectiveSides(level, pos, face)) if (zone(pos, d).contains(hit)) return d;
        return null;
    }

    public static Direction edge(net.minecraft.world.level.BlockGetter level, BlockPos pos, net.minecraft.world.phys.BlockHitResult hit) {
        return edge(level, pos, hit.getDirection(), hit.getLocation());
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
