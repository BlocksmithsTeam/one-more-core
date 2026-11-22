package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.MenuBoardBlock;
import com.onemorestudios.onemorecore.ScreenGroups;
import com.onemorestudios.onemorecore.ScreenPicture;
import com.onemorestudios.onemorecore.ScreenRect;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Moves the camera of a Screen's close-up (KitchenStations.SCREEN_BOARD) to the middle of the picture the player looks at (the
 * side towards the player, of the screen or of its joined group), along the scene's view, as far as the whole picture fits.
 * The scene of each tilt looks at the point (8, 16, 8) from 30 pixels away along the glass's normal.
 */
final class ScreenStationBehavior implements StationBehavior {
    private static final float SCENE_DISTANCE = 30, HALF_FOV_TAN = (float) Math.tan(Math.toRadians(21)), ASPECT = 16 / 9f;

    static void register() {
        for (var type : KitchenStations.SCREEN_BOARD) DiegesisClient.registerBehavior(type.id(), view -> new ScreenStationBehavior());
    }

    @Override
    public void onOpen(StationView view) {
        place(view);
    }

    @Override
    public void onSceneReady(StationView view) {
        place(view);
    }

    private static void place(StationView view) {
        var level = view.level();
        BlockState state = level.getBlockState(view.pos());
        ScreenGroups.Group g = ScreenGroups.of(level, view.pos());
        if (g == null || !(state.getBlock() instanceof MenuBoardBlock)) return;
        BlockState anchorState = level.getBlockState(g.anchor());
        int side = view.facing() == state.getValue(MenuBoardBlock.FACING) ? 0 : Math.min(1, ScreenPicture.sides(anchorState) - 1);
        ScreenPicture picture = ScreenPicture.of(g.anchor(), anchorState, g.rect(), side);
        Vec3 middle = picture.world(picture.width() / 2, picture.height() / 2);
        // The scene's frame: x along the facing's clockwise turn, z away from the camera's side, origin the block's corner.
        Direction f = view.facing(), cw = f.getClockWise();
        Vec3 local = middle.subtract(view.pos().getX() + 0.5, view.pos().getY(), view.pos().getZ() + 0.5).scale(16);
        float sx = (float) (local.x * cw.getStepX() + local.z * cw.getStepZ()) + 8;
        float sz = (float) -(local.x * f.getStepX() + local.z * f.getStepZ()) + 8;
        float sy = (float) local.y;
        // Far enough for the whole picture (canvas units are tenths of a pixel), a little margin.
        float halfH = picture.height() / 20, halfW = picture.width() / 20;
        float distance = Math.max(halfH / HALF_FOV_TAN, halfW / (HALF_FOV_TAN * ASPECT)) * 1.15f + 2;
        double tilt = Math.toRadians(ScreenRect.tilt(state));
        float ny = (float) -Math.sin(tilt), nz = (float) -Math.cos(tilt);
        float back = distance - SCENE_DISTANCE;
        view.moveCamera(sx - 8, sy - 16 + ny * back, sz - 8 + nz * back);
    }
}
