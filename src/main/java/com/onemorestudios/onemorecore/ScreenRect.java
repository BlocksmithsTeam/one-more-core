package com.onemorestudios.onemorecore;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where the picture of a television is, in the pixels of its model when the screen faces south (+z): the rectangle
 * x0..x1, y0..y1 on the plane z. flip: the block's facing is the back of the screen (the screen faces the other way).
 * The mod's own screens use their own models; other mods' televisions come from the config (screenBlocks).
 */
public record ScreenRect(float x0, float y0, float x1, float y1, float z, boolean flip) {
    /** The Order Screen: a kitchen display 3 x 2 blocks (x -16..32, y 0..32), its picture inside the bezel (art/order_screen.py). */
    public static final ScreenRect OWN = new ScreenRect(-15f, 6f, 31f, 30f, 1.0f, false);
    /** The Menu Screen: the billboard (1 x 2 blocks), its picture inside the frame; the back shows it too (turned half round). */
    public static final ScreenRect BILLBOARD = new ScreenRect(1f, 8f, 15f, 30f, 9.0f, false);
    /** The Monitor: the Yuushya wall TV made thin, one block. */
    public static final ScreenRect MONITOR = new ScreenRect(0.4f, 0.7273f, 15.6f, 15.2727f, 1.0f, false);
    /** The bottom back edge of the mod's own screens, model pixels: a tilted screen turns about it, its top coming out. */
    static final float PIVOT_Y = 0, PIVOT_Z = 0;

    /** The rectangle of a block, or null when it is no screen. */
    public static ScreenRect of(BlockState state) {
        if (state.getBlock() instanceof ScreenBlock) return ScreenLayout.of(state).rect();
        if (state.is(OneMoreCore.ORDER_BOARD.get())) return OWN;
        if (state.is(OneMoreCore.MONITOR.get())) return MONITOR;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var key = net.minecraft.resources.ResourceLocation.parse(id);
        String value = Config.lookup(Config.SCREEN_BLOCKS.get(), key);
        if (value == null) value = Config.lookup(BUILT_IN, key);
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

    /** Degrees the screen is turned down about its top back edge (the mod's own screens only). */
    public static float tilt(BlockState state) {
        return state.hasProperty(MenuBoardBlock.TILT) ? state.getValue(MenuBoardBlock.TILT) * 22.5f : 0;
    }

    /** The edge a screen turns down about, model pixels {y, z}: the bottom back edge, or the Screen's own (ScreenLayout). */
    public static float[] pivot(BlockState state) {
        if (state.getBlock() instanceof ScreenBlock) {
            ScreenLayout l = ScreenLayout.of(state);
            return new float[] {l.pivotY(), l.pivotZ()};
        }
        return new float[] {PIVOT_Y, PIVOT_Z};
    }

    /** The world point of model pixel (x, y, z) of a south-facing model turned to the block's state. */
    public Vec3 world(BlockPos pos, BlockState state, float x, float y, float z) {
        float tilt = tilt(state);
        if (tilt != 0) {
            // Turned down: the top comes out towards +z (the side the picture faces), so the picture looks down.
            float[] pv = pivot(state);
            double a = Math.toRadians(tilt), dy = y - pv[0], dz0 = z - pv[1];
            y = (float) (pv[0] + dy * Math.cos(a) - dz0 * Math.sin(a));
            z = (float) (pv[1] + dy * Math.sin(a) + dz0 * Math.cos(a));
        }
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

    /** The world point of a card click in canvas units (x right, y down). */
    Vec3 cardPoint(BlockPos pos, BlockState state, float x, float y) {
        return world(pos, state, x0 + x / 10, y1 - y / 10, z);
    }

    /** The back of a billboard as a screen of its own: the block state turned half round (its model is the same both ways). */
    public static BlockState back(BlockState state) {
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
    }

    boolean faces(BlockState state, Vec3 towardViewer) {
        double angle = Math.toRadians(tilt(state));
        return Vec3.atLowerCornerOf(normal(state).getNormal()).scale(Math.cos(angle))
                .add(0, -Math.sin(angle), 0).dot(towardViewer) > 0;
    }

    /** The screens themselves (a group's screens and their TV parts) can enclose the picture, but foreground blocks must leave it visible. */
    public static boolean visible(Level level, Entity viewer, java.util.Collection<BlockPos> screens, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer) {
            @Override
            public VoxelShape getBlockShape(BlockState state, BlockGetter getter, BlockPos pos) {
                return screens.contains(ScreenGroups.main(getter, pos)) ? Shapes.empty() : super.getBlockShape(state, getter, pos);
            }
        }).getType() == HitResult.Type.MISS;
    }

    /** Order cards on one page of a canvas w wide with u canvas units a layout unit, and the page shown at a game time (a page every 6 seconds). */
    public static int ordersPerPage(float w, float u) {
        return Math.max(1, (int) ((w - 6 * u) / (88 * u)));
    }

    public static int page(int pages, long gameTime) {
        return pages <= 1 ? 0 : (int) (gameTime / 120 % pages);
    }

    /** The order card under canvas point x (oldest first over all pages), or -1. */
    public static int orderAt(float w, float h, float u, int count, long gameTime, float x, float y) {
        int i = cardSlot(w, h, u, x, y);
        if (i < 0) return -1;
        int per = ordersPerPage(w, u), pages = (count + per - 1) / per;
        int index = page(pages, gameTime) * per + i;
        return index < count ? index : -1;
    }

    /** The order card slot on the page under canvas point (x, y) with u canvas units a layout unit, or -1. */
    int cardSlot(float u, float x, float y) {
        return cardSlot(width(), height(), u, x, y);
    }

    private static int cardSlot(float w, float h, float u, float x, float y) {
        float gap = 6 * u;
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < gap || x > w - gap || y < 30 * u || y > h - gap) return -1;
        int per = ordersPerPage(w, u);
        float cardW = (w - gap * (per + 1)) / per;
        int i = (int) ((x - gap) / (cardW + gap));
        return i < per && x <= gap + i * (cardW + gap) + cardW ? i : -1;
    }

    /** The layout unit of a screen's picture (its single screen's rectangle) with its settings (ScreenSettings, packed): canvas units. */
    public static float unit(ScreenRect single, int settings) {
        return (single.y1() - single.y0()) * 10 / 200 * ScreenSettings.ZOOM[ScreenSettings.size(settings)];
    }

    /** A rectangle grown by whole cells: cols to the right and rows down of this one, tile w x h model pixels. */
    public ScreenRect grown(int cols, int rows, float w, float h) {
        return new ScreenRect(x0, y0 - (rows - 1) * h, x1 + (cols - 1) * w, y1, z, flip);
    }

    /** Canvas size in units (10 per model pixel). */
    public float width() { return (x1 - x0) * 10; }

    public float height() { return (y1 - y0) * 10; }

    /**
     * Televisions known without a config line: MrCrayfish's Refurbished Furniture television (the picture on its south face when
     * it faces north), and Yuushya Townscape's wall televisions (the model of the mod's own screens, before it was made thin).
     */
    static final List<String> BUILT_IN = List.of("refurbished_furniture:television=3,2,13,11,14.02,1",
            "yuushya:mounted_tv=-11,3,27,23,2.52,1", "yuushya:mounted_tv_online=-11,3,27,23,2.52,1");

    /** The config's default: none, the built-in ones apply anyway. */
    public static List<String> defaults() {
        return List.of();
    }
}
