package com.onemorestudios.onemoreclient;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Where the picture of a television is, in the pixels of its model when the screen faces south (+z): the rectangle
 * x0..x1, y0..y1 on the plane z. flip: the block's facing is the back of the screen (the screen faces the other way).
 * The mod's own screens use the Yuushya wall TV model; other mods' televisions come from the config (screenBlocks).
 */
public record ScreenRect(float x0, float y0, float x1, float y1, float z, boolean flip) {
    /** The mod's own screens: the Yuushya wall TV, 2.5 blocks wide, its picture inside a 1 px bezel. */
    public static final ScreenRect OWN = new ScreenRect(-11, 3, 27, 23, 2.6f, false);

    /** The rectangle of a block, or null when it is no screen. */
    public static ScreenRect of(BlockState state) {
        if (state.is(OneMoreClient.MENU_BOARD.get()) || state.is(OneMoreClient.ORDER_BOARD.get())) return OWN;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String value = Config.lookup(Config.SCREEN_BLOCKS.get(), net.minecraft.resources.ResourceLocation.parse(id));
        return value == null ? null : parse(value);
    }

    static ScreenRect parse(String value) {
        try {
            String[] p = value.split(",");
            return new ScreenRect(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                    Float.parseFloat(p[4]), p.length > 5 && p[5].strip().equals("1"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The direction the screen faces in the world. */
    public Direction normal(BlockState state) {
        Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.SOUTH;
        return flip ? facing.getOpposite() : facing;
    }

    /** Degrees the south-facing model is turned (clockwise from above, as a blockstate "y"). */
    public float turn(BlockState state) {
        return normal(state).toYRot();
    }

    /** The world point of model pixel (x, y, z) of a south-facing model turned to the block's state. */
    public Vec3 world(BlockPos pos, BlockState state, float x, float y, float z) {
        double dx = x - 8, dz = z - 8;
        // Clockwise turns: south (+z) goes to west (-x) for 90 degrees.
        int turns = Math.round(turn(state) / 90) & 3;
        for (int i = 0; i < turns; i++) { double t = dx; dx = -dz; dz = t; }
        return new Vec3(pos.getX() + (dx + 8) / 16, pos.getY() + y / 16, pos.getZ() + (dz + 8) / 16);
    }

    /** A point of the screen plane in canvas units {x, y} (x right, y down), or null when it is off the picture. */
    public float[] canvas(BlockPos pos, BlockState state, Vec3 point) {
        Vec3 o = world(pos, state, x0, y1, z), ux = world(pos, state, x1, y1, z).subtract(o), vy = world(pos, state, x0, y0, z).subtract(o);
        Vec3 p = point.subtract(o);
        double u = p.dot(ux) / ux.lengthSqr(), v = p.dot(vy) / vy.lengthSqr();
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        return new float[] {(float) (u * width()), (float) (v * height())};
    }

    /** Order cards on one page of a canvas, and the page shown at a game time (a page every 6 seconds). */
    public static int ordersPerPage(float w, float h) {
        float u = h / 200;
        return Math.max(1, (int) ((w - 6 * u) / (88 * u)));
    }

    public static int page(int pages, long gameTime) {
        return pages <= 1 ? 0 : (int) (gameTime / 120 % pages);
    }

    /** The order card under canvas point x (oldest first over all pages), or -1. */
    public static int orderAt(float w, float h, int count, long gameTime, float x, float y) {
        float u = h / 200, gap = 6 * u;
        if (y < 30 * u) return -1;
        int per = ordersPerPage(w, h), pages = (count + per - 1) / per;
        float cardW = (w - gap * (per + 1)) / per;
        int i = (int) Math.floor((x - gap / 2) / (cardW + gap));
        int index = page(pages, gameTime) * per + i;
        return i >= 0 && i < per && index < count ? index : -1;
    }

    /** Canvas size in units (10 per model pixel). */
    public float width() { return (x1 - x0) * 10; }

    public float height() { return (y1 - y0) * 10; }

    public static List<String> defaults() {
        // MrCrayfish's Refurbished Furniture television: the picture on its south face when it faces north.
        return List.of("refurbished_furniture:television=3,2,13,11,14.02,1");
    }
}
