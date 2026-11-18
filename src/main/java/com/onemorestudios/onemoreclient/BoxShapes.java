package com.onemorestudios.onemoreclient;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block shapes for the four horizontal facings, made from boxes of the north model, like the blockstate y rotation. */
final class BoxShapes {
    private BoxShapes() {}

    /**
     * Index: Direction.get2DDataValue (south 0, west 1, north 2, east 3). Each box is {x1, y1, z1, x2, y2, z2} in pixels of
     * the model that faces north. A clockwise turn seen from above maps (x, z) to (16 - z, x): north to east.
     */
    static VoxelShape[] byFacing(double[]... boxes) {
        VoxelShape[] out = new VoxelShape[4];
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int turns = switch (d) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
            VoxelShape shape = Shapes.empty();
            for (double[] b : boxes) {
                double x1 = b[0], z1 = b[2], x2 = b[3], z2 = b[5];
                for (int i = 0; i < turns; i++) {
                    double nx1 = 16 - z2, nx2 = 16 - z1, nz1 = x1, nz2 = x2;
                    x1 = nx1; x2 = nx2; z1 = nz1; z2 = nz2;
                }
                shape = Shapes.or(shape, Block.box(x1, b[1], z1, x2, b[4], z2));
            }
            out[d.get2DDataValue()] = shape.optimize();
        }
        return out;
    }
}
