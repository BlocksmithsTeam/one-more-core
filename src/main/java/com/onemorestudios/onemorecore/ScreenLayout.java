package com.onemorestudios.onemorecore;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * The shape of a Screen (ScreenBlock) for the way it stands, in model pixels of its block when it faces south (the front glass
 * faces +z): the panel (frame and glass), the glass core (its front face at gz1, the back face at gz0 when both sides show),
 * the edge about which it turns down, and the mount. Keep in step with art/screen/screen_models.py, which makes the models.
 */
public record ScreenLayout(float x0, float x1, float y0, float y1, float z0, float z1, int top, int bottom, float gz0, float gz1,
        boolean twoSided, float pivotY, float pivotZ, List<float[]> mounts, boolean landscape, AttachFace attach) {
    private static final ScreenLayout WALL_P = new ScreenLayout(0, 16, 0, 32, 2, 6, 2, 2, 2, 5, false, 0, 2,
            List.of(new float[] {6, 14, 0, 10, 18, 2}), false, AttachFace.WALL);
    private static final ScreenLayout WALL_L = new ScreenLayout(0, 32, 0, 16, 2, 6, 1, 2, 2, 5, false, 0, 2,
            List.of(new float[] {14, 6, 0, 18, 10, 2}), true, AttachFace.WALL);
    private static final ScreenLayout FLOOR_P = new ScreenLayout(0, 16, 6, 32, 6, 10, 2, 2, 7, 9, true, 0, 0,
            List.of(new float[] {5, 0, 7, 11, 6, 9}), false, AttachFace.FLOOR);
    private static final ScreenLayout FLOOR_L = new ScreenLayout(0, 32, 2, 16, 6, 10, 1, 2, 7, 9, true, 0, 0,
            List.of(new float[] {3, 0, 7, 5, 2, 9}, new float[] {27, 0, 7, 29, 2, 9}), true, AttachFace.FLOOR);
    private static final ScreenLayout CEILING_P = new ScreenLayout(0, 16, -16, 12, 6, 10, 2, 2, 7, 9, true, 12, 8,
            List.of(new float[] {3, 12, 7, 5, 16, 9}, new float[] {11, 12, 7, 13, 16, 9}), false, AttachFace.CEILING);
    private static final ScreenLayout CEILING_L = new ScreenLayout(0, 32, 0, 13, 6, 10, 1, 2, 7, 9, true, 13, 8,
            List.of(new float[] {3, 13, 7, 5, 16, 9}, new float[] {27, 13, 7, 29, 16, 9}), true, AttachFace.CEILING);

    public static ScreenLayout of(BlockState state) {
        boolean landscape = state.getValue(ScreenBlock.LANDSCAPE);
        return switch (state.getValue(ScreenBlock.ATTACH)) {
            case FLOOR -> landscape ? FLOOR_L : FLOOR_P;
            case CEILING -> landscape ? CEILING_L : CEILING_P;
            default -> landscape ? WALL_L : WALL_P;
        };
    }

    /** The front glass as a ScreenRect (the back is the same rectangle seen from behind). */
    public ScreenRect rect() {
        return new ScreenRect(x0 + 1, y0 + bottom, x1 - 1, y1 - top, gz1, false);
    }

    /** Blocks the screen takes to the viewer's right (landscape) and up (portrait; down when it hangs), for joining. */
    public int stepX() { return landscape ? 2 : 1; }

    public int stepY() { return landscape ? 1 : 2; }

    /** Screens join side by side; on a wall also one over the other. */
    public boolean joinsUp() { return attach == AttachFace.WALL; }

    /** The cells besides the screen's own block. */
    public List<BlockPos> parts(BlockPos pos, Direction facing) {
        if (landscape) return List.of(pos.relative(facing.getCounterClockWise()));
        return List.of(attach == AttachFace.CEILING ? pos.below() : pos.above());
    }

    /** Model pixels of the panel's middle (for cameras): {x, y, z of the front glass}. */
    public float[] middle() {
        return new float[] {(x0 + x1) / 2, (y0 + bottom + y1 - top) / 2f, gz1};
    }
}
