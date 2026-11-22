package com.onemorestudios.onemorecore;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One side of a screen's picture in the world: the map from canvas units (x right, y down, 10 per model pixel) to the block of its
 * anchor (blocks), with the screen turned to its facing and turned down about its pivot. Side 0 is the front; side 1, a Screen's
 * back, is the same rectangle seen from behind (its canvas starts at the front's top right). The renderer draws with it and the
 * pointer and the server's click check use it.
 */
public record ScreenPicture(BlockPos pos, Matrix4f matrix, float width, float height) {
    public static ScreenPicture of(BlockPos pos, BlockState state, ScreenRect r, int side) {
        Matrix4f m = new Matrix4f().translate(0.5f, 0, 0.5f).rotateY((float) Math.toRadians(-r.turn(state))).translate(-0.5f, 0, -0.5f);
        float tilt = ScreenRect.tilt(state);
        if (tilt != 0) {
            float[] pv = ScreenRect.pivot(state);
            m.translate(0, pv[0] / 16, pv[1] / 16).rotateX((float) Math.toRadians(tilt)).translate(0, -pv[0] / 16, -pv[1] / 16);
        }
        if (side == 0) {
            m.translate(r.x0() / 16, r.y1() / 16, (r.z() + 0.05f) / 16);
        } else {
            float back = state.getBlock() instanceof ScreenBlock ? ScreenLayout.of(state).gz0() : r.z();
            m.translate(r.x1() / 16, r.y1() / 16, (back - 0.05f) / 16).rotateY((float) Math.PI);
        }
        m.scale(1 / 160f, -1 / 160f, 1 / 160f);
        return new ScreenPicture(pos.immutable(), m, r.width(), r.height());
    }

    /** The sides of a screen that can show a picture: 2 for a Screen that is not on a wall. */
    public static int sides(BlockState state) {
        return state.getBlock() instanceof ScreenBlock && ScreenLayout.of(state).twoSided() ? 2 : 1;
    }

    /** The world point of a canvas point. */
    public Vec3 world(float x, float y) {
        Vector3f v = matrix.transformPosition(new Vector3f(x, y, 0));
        return new Vec3(pos.getX() + (double) v.x, pos.getY() + (double) v.y, pos.getZ() + (double) v.z);
    }

    /** The direction the picture faces (towards its viewers). */
    public Vec3 normal() {
        Vector3f n = matrix.transformDirection(new Vector3f(0, 0, 1)).normalize();
        return new Vec3(n.x, n.y, n.z);
    }

    public boolean faces(Vec3 towardViewer) {
        return normal().dot(towardViewer) > 0;
    }

    /** A point of the picture's plane in canvas units {x, y}, or null when it is off the picture. */
    public float[] canvas(Vec3 point) {
        Vec3 o = world(0, 0), ux = world(width, 0).subtract(o), vy = world(0, height).subtract(o);
        Vec3 p = point.subtract(o);
        double u = p.dot(ux) / ux.lengthSqr(), v = p.dot(vy) / vy.lengthSqr();
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        return new float[] {(float) (u * width), (float) (v * height)};
    }

    /** The side of a Screen that faces a point (a player's eye): 0 front, 1 back. */
    public static int sideFacing(BlockPos pos, BlockState state, ScreenRect r, Vec3 eye) {
        if (sides(state) < 2) return 0;
        ScreenPicture front = of(pos, state, r, 0);
        return front.faces(eye.subtract(front.world(front.width / 2, front.height / 2))) ? 0 : 1;
    }
}
