package com.onemorestudios.onemorecore.client;

import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.MenuBoardBlock;
import com.onemorestudios.onemorecore.ScreenGroups;
import com.onemorestudios.onemorecore.ScreenPicture;
import com.onemorestudios.onemorecore.ScreenRect;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.phys.Vec3;

/**
 * Moves the camera of a Screen's close-up (KitchenStations.SCREEN_BOARD) to the middle of the picture the player looks at (the
 * side towards the player, of the screen or of its joined group), along the scene's view, as far as the whole picture fits. A
 * very long or tall picture shows a window of it, from its start: the movement keys (or the arrow keys) and a drag with the mouse
 * move the window over the picture. The scene of each tilt looks at the point (8, 16, 8) from 30 pixels away along the glass's
 * normal.
 */
final class ScreenStationBehavior implements StationBehavior {
    private static final float SCENE_DISTANCE = 30, HALF_FOV_TAN = (float) Math.tan(Math.toRadians(21)), ASPECT = 16 / 9f;

    static void register() {
        for (var type : KitchenStations.SCREEN_BOARD) DiegesisClient.registerBehavior(type.id(), view -> new ScreenStationBehavior());
    }

    /** How fast the keys move the window: its own width (or height) in this many seconds. */
    private static final float PAN_TIME = 1.2f;
    /** The room the window has to move in along the picture, canvas units (0: the whole picture shows that way). */
    private float roomX, roomY, viewW, viewH;

    @Override
    public void onOpen(StationView view) {
        ScreenRenderer.panX = ScreenRenderer.panY = 0;
        place(view);
    }

    /** Every frame: the keys held move the window, then the camera goes to it. */
    @Override
    public void frame(StationView view, float dt) {
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        int x = (held(window, mc.options.keyRight, GLFW.GLFW_KEY_RIGHT) ? 1 : 0) - (held(window, mc.options.keyLeft, GLFW.GLFW_KEY_LEFT) ? 1 : 0);
        int y = (held(window, mc.options.keyDown, GLFW.GLFW_KEY_DOWN) ? 1 : 0) - (held(window, mc.options.keyUp, GLFW.GLFW_KEY_UP) ? 1 : 0);
        float step = Math.min(dt, 0.1f) / PAN_TIME;
        if (roomX > 0) ScreenRenderer.panX += x * viewW * step;
        if (roomY > 0) ScreenRenderer.panY += y * viewH * step;
        place(view);
    }

    /** The movement keys move the window where it can move; elsewhere they go on to Diegesis (to the next station). */
    @Override
    public boolean onKey(StationView view, int key, int scanCode, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        boolean sideways = mc.options.keyLeft.matches(key, scanCode) || mc.options.keyRight.matches(key, scanCode) || key == GLFW.GLFW_KEY_LEFT
                || key == GLFW.GLFW_KEY_RIGHT;
        boolean upDown = mc.options.keyUp.matches(key, scanCode) || mc.options.keyDown.matches(key, scanCode) || key == GLFW.GLFW_KEY_UP
                || key == GLFW.GLFW_KEY_DOWN;
        return sideways && roomX > 0 || upDown && roomY > 0;
    }

    private static boolean held(long window, KeyMapping mapping, int arrow) {
        var key = mapping.getKey();
        return InputConstants.isKeyDown(window, arrow)
                || key.getType() == InputConstants.Type.KEYSYM && key.getValue() != InputConstants.UNKNOWN.getValue() && InputConstants.isKeyDown(window, key.getValue());
    }

    @Override
    public void onSceneReady(StationView view) {
        place(view);
    }

    /** The wheel scrolls the picture the camera looks at. */
    @Override
    public boolean onScroll(StationView view, String node, double amount) {
        return ScreenRenderer.scrollBy(amount);
    }

    private void place(StationView view) {
        var level = view.level();
        BlockState state = level.getBlockState(view.pos());
        ScreenGroups.Group g = ScreenGroups.of(level, view.pos());
        if (g == null || !(state.getBlock() instanceof MenuBoardBlock)) return;
        BlockState anchorState = level.getBlockState(g.anchor());
        int side = view.facing() == state.getValue(MenuBoardBlock.FACING) ? 0 : Math.min(1, ScreenPicture.sides(anchorState) - 1);
        ScreenPicture picture = ScreenPicture.of(g.anchor(), anchorState, g.rect(), side);
        // A very long or tall picture is not shown whole (it would be too small to read): the camera shows a window of it, at most
        // 2.4 times as wide as high (or 2 times as high as wide), at its start (left, top), where the menu and the tickets begin.
        float w = picture.width(), h = picture.height();
        viewW = Math.min(w, h * 2.4f);
        viewH = Math.min(h, w * 2f);
        roomX = w - viewW;
        roomY = h - viewH;
        // The window where the player moved it, inside the picture.
        ScreenRenderer.panX = Math.max(0, Math.min(roomX, ScreenRenderer.panX));
        ScreenRenderer.panY = Math.max(0, Math.min(roomY, ScreenRenderer.panY));
        float cx = viewW / 2 + ScreenRenderer.panX, cy = viewH / 2 + ScreenRenderer.panY;
        Vec3 middle = picture.world(cx, cy);
        // The scene's coordinates of the middle, from the station's own map (its axes are orthogonal).
        Vec3 o = view.toWorld(0, 0, 0), ex = view.toWorld(1, 0, 0).subtract(o), ey = view.toWorld(0, 1, 0).subtract(o), ez = view.toWorld(0, 0, 1).subtract(o);
        Vec3 p = middle.subtract(o);
        float sx = (float) (p.dot(ex) / ex.lengthSqr()), sy = (float) (p.dot(ey) / ey.lengthSqr()), sz = (float) (p.dot(ez) / ez.lengthSqr());
        // Far enough for the whole picture (canvas units are tenths of a pixel), a little margin.
        float halfH = viewH / 20, halfW = viewW / 20;
        float distance = Math.max(halfH / HALF_FOV_TAN, halfW / (HALF_FOV_TAN * ASPECT)) * 1.15f + 2;
        // A drag moves the picture with the mouse: canvas units of a GUI pixel at the picture's distance.
        ScreenRenderer.canvasPerPixel = 2 * distance * HALF_FOV_TAN * 10 / Math.max(1, Minecraft.getInstance().getWindow().getGuiScaledHeight());
        double tilt = Math.toRadians(ScreenRect.tilt(state));
        float ny = (float) -Math.sin(tilt), nz = (float) -Math.cos(tilt);
        float back = distance - SCENE_DISTANCE;
        view.moveCamera(sx - 8, sy - 16 + ny * back, sz - 8 + nz * back);
    }
}
