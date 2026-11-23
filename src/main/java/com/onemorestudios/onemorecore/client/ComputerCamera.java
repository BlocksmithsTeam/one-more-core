package com.onemorestudios.onemorecore.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.OneMoreCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The camera of the Restaurant Computer. Using the computer flies the camera from the eyes of the player to the front of
 * the monitor; then the desktop opens. The manager app can let the camera fly freely through walls (the player stays at
 * the computer) to set the area, the customer spawn point and the delivery container with clicks.
 */
public final class ComputerCamera {
    private ComputerCamera() {}

    enum Mode { NONE, TO_SCREEN, DESKTOP, FREE, TO_PLAYER }

    /** Ticks of one camera flight. */
    static final int FLIGHT = 12;
    /** The monitor's screen fills this share of the view height: the room stays visible round the monitor. */
    static final double FILL = 0.75;
    /** Height of the visible CRT glass, from y=3 to y=8 in model pixels. */
    static final double SCREEN_HEIGHT = 5 / 16.0;
    static final double SCREEN_ASPECT = 8 / 5.0;

    static Mode mode = Mode.NONE;
    static Computer.State state;
    private static Marker camera;
    private static Vec3 from, to;
    private static float fromYaw, fromPitch, toYaw, toPitch;
    private static int tick;
    private static boolean hideGuiBefore;
    /** The app that the desktop opens with, after the free camera. */
    static int reopenApp;
    /** The monitor booted in this opening of the computer: the desktop that opens again after the free camera does not boot. */
    static boolean booted;

    // Free camera.
    enum Tool { AREA, SPAWN, DELIVERY, TABLES, SCREENS }
    static Tool tool = Tool.AREA;
    static BlockPos corner;
    static Component message = Component.empty();
    static long messageTime;

    static void register() {
        NeoForge.EVENT_BUS.addListener(ComputerCamera::tick);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::angles);
        NeoForge.EVENT_BUS.addListener((RenderHandEvent e) -> { if (mode != Mode.NONE) e.setCanceled(true); });
        // On the way to the monitor and at the desktop the camera is near the player's head (a seated player sits close to
        // the desk): the player's own model would fill the view. The free camera shows the player.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre e) -> {
            if (mode != Mode.NONE && mode != Mode.FREE && e.getEntity() == Minecraft.getInstance().player) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener(ComputerCamera::movement);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::click);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::key);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::mouseButton);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::scroll);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::pause);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::renderWorld);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::renderHud);
    }

    static boolean active() {
        return mode != Mode.NONE;
    }

    /** A state with "open" from the server: fly to the monitor, or update the open desktop. */
    public static void receive(Computer.State s) {
        Minecraft mc = Minecraft.getInstance();
        boolean same = state != null && state.pos().equals(s.pos());
        if (!s.message().getString().isEmpty()) { message = s.message(); messageTime = System.currentTimeMillis(); }
        if (active() && same) {
            state = s;
            if (mc.screen instanceof ComputerScreen screen) screen.update(s);
            return;
        }
        if (!s.open() || mc.player == null || mc.level == null || active()) return;
        state = s;
        booted = false;
        camera = new Marker(EntityType.MARKER, mc.level);
        Vec3 eye = mc.player.getEyePosition();
        place(eye, mc.player.getYRot(), mc.player.getXRot());
        hideGuiBefore = mc.options.hideGui;
        mc.options.hideGui = true;
        mc.setCameraEntity(camera);
        flyTo(Mode.TO_SCREEN, screenView(s.pos()));
    }

    public static void receive(Computer.CheckoutResult result) {
        if (state != null && state.pos().equals(result.pos()) && Minecraft.getInstance().screen instanceof ComputerScreen screen
                && screen.checkoutResult(result)) {
            state = result.state().orElse(state);
        }
    }

    /** Camera position and angles in front of the monitor, so that the screen fills FILL of the view height. */
    static Object[] screenView(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        BlockState block = mc.level.getBlockState(pos);
        Direction facing = block.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? block.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        // The front of the recessed CRT glass is centred at (8, 5.5, 7) px in the north model.
        Vec3 local = new Vec3(0, 5.5 / 16, 7 / 16.0 - 0.5);
        Vec3 turned = switch (facing) {
            case EAST -> new Vec3(-local.z, local.y, local.x);
            case SOUTH -> new Vec3(-local.x, local.y, -local.z);
            case WEST -> new Vec3(local.z, local.y, -local.x);
            default -> local;
        };
        Vec3 centre = Vec3.atBottomCenterOf(pos).add(turned);
        double fov = mc.options.fov().get();
        double distance = SCREEN_HEIGHT / 2 / FILL / Math.tan(Math.toRadians(fov / 2));
        Vec3 eye = centre.add(Vec3.atLowerCornerOf(facing.getNormal()).scale(distance));
        return new Object[] {eye, facing.getOpposite().toYRot(), 0f};
    }

    private static void place(Vec3 p, float yaw, float pitch) {
        camera.setPos(p.x, p.y, p.z);
        camera.xo = camera.xOld = p.x;
        camera.yo = camera.yOld = p.y;
        camera.zo = camera.zOld = p.z;
        camera.setYRot(yaw);
        camera.setXRot(pitch);
        camera.yRotO = yaw;
        camera.xRotO = pitch;
    }

    private static void flyTo(Mode m, Object[] view) {
        mode = m;
        tick = 0;
        from = camera.position();
        fromYaw = camera.getYRot();
        fromPitch = camera.getXRot();
        to = (Vec3) view[0];
        toYaw = fromYaw + Mth.wrapDegrees((float) view[1] - fromYaw);
        toPitch = (float) view[2];
    }

    /**
     * Leaves the desktop: the camera flies back to the eyes of the player. Escape on the desktop first turns the CRT off
     * (ComputerScreen); the other ways out (walked away, another screen) leave at once.
     */
    static void leave() {
        Minecraft mc = Minecraft.getInstance();
        closeSession();
        if (mc.player == null || camera == null) { finish(); return; }
        if (mc.screen instanceof ComputerScreen) mc.setScreen(null);
        flyTo(Mode.TO_PLAYER, new Object[] {mc.player.getEyePosition(), mc.player.getYRot(), mc.player.getXRot()});
    }

    private static void closeSession() {
        if (mode != Mode.TO_PLAYER && state != null && Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new Computer.Request(state.pos(), Computer.CLOSE, ""));
        }
    }

    /** From the manager app: the free camera starts at the player's eyes. */
    static void startFree() {
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || mc.player == null) return;
        mc.setScreen(null);
        mc.options.hideGui = false;
        place(mc.player.getEyePosition().add(0, 1, 0), mc.player.getYRot(), mc.player.getXRot());
        mode = Mode.FREE;
        tool = Tool.AREA;
        corner = null;
    }

    private static void finish() {
        Minecraft mc = Minecraft.getInstance();
        mode = Mode.NONE;
        camera = null;
        booted = false;
        state = null;
        corner = null;
        mc.options.hideGui = hideGuiBefore;
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.NONE) return;
        if (mc.player == null || mc.level == null || camera == null || camera.level() != mc.level) {
            if (mc.screen instanceof ComputerScreen) mc.setScreen(null);
            finish();
            return;
        }
        // The player walked away (pushed, teleported): the computer closes.
        if (mc.player.isSpectator() || !mc.player.canInteractWithBlock(state.pos(), 0)
                || !mc.level.getBlockState(state.pos()).is(OneMoreCore.COMPUTER)) {
            closeSession();
            if (mc.screen instanceof ComputerScreen) mc.setScreen(null);
            finish();
            return;
        }
        if ((mode == Mode.TO_SCREEN || mode == Mode.FREE) && mc.screen != null) {
            leave();
            return;
        }
        camera.xo = camera.xOld = camera.getX();
        camera.yo = camera.yOld = camera.getY();
        camera.zo = camera.zOld = camera.getZ();
        camera.yRotO = camera.getYRot();
        camera.xRotO = camera.getXRot();
        if (mode == Mode.TO_SCREEN || mode == Mode.TO_PLAYER) {
            float t = Math.min(1, ++tick / (float) FLIGHT);
            float e = t * t * (3 - 2 * t);
            Vec3 p = from.lerp(to, e);
            camera.setPos(p.x, p.y, p.z);
            camera.setYRot(Mth.lerp(e, fromYaw, toYaw));
            camera.setXRot(Mth.lerp(e, fromPitch, toPitch));
            if (t < 1) return;
            if (mode == Mode.TO_PLAYER) { finish(); return; }
            mode = Mode.DESKTOP;
            mc.setScreen(new ComputerScreen(state, reopenApp));
            reopenApp = 0;
        } else if (mode == Mode.DESKTOP && !(mc.screen instanceof ComputerScreen)) {
            // Another screen replaced the desktop (death, a server screen): leave.
            leave();
        } else if (mode == Mode.FREE) {
            freeMove(mc);
        }
    }

    /** Flies with the movement keys in the look direction of the player, through blocks, within the computer's range. */
    private static void freeMove(Minecraft mc) {
        var o = mc.options;
        double forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double side = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double up = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
        double speed = o.keySprint.isDown() ? 0.9 : 0.35;
        float yaw = mc.player.getYRot() * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
        Vec3 move = new Vec3(side * cos - forward * sin, up, forward * cos + side * sin);
        if (move.lengthSqr() > 1) move = move.normalize();
        Vec3 p = camera.position().add(move.scale(speed));
        BlockPos c = state.pos();
        int r = Computer.PLACE_RANGE;
        p = new Vec3(Mth.clamp(p.x, c.getX() - r + 0.5, c.getX() + r + 0.5), Mth.clamp(p.y, c.getY() - r + 0.5, c.getY() + r + 0.5),
                Mth.clamp(p.z, c.getZ() - r + 0.5, c.getZ() + r + 0.5));
        camera.setPos(p.x, p.y, p.z);
        camera.setYRot(mc.player.getYRot());
        camera.setXRot(mc.player.getXRot());
    }

    /** In the free camera the view turns with the mouse at full frame rate, like the player's own view. */
    private static void angles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mode != Mode.FREE || mc.player == null) return;
        event.setYaw(mc.player.getViewYRot((float) event.getPartialTick()));
        event.setPitch(mc.player.getViewXRot((float) event.getPartialTick()));
    }

    /** The player does not walk while the camera is away. */
    private static void movement(MovementInputUpdateEvent event) {
        if (mode == Mode.NONE) return;
        var in = event.getInput();
        in.forwardImpulse = in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
    }

    /** Distance of the point in the air while Alt is held, in blocks. Alt and the mouse wheel change it. */
    static double reach = 6;

    static boolean altDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT);
    }

    /**
     * The position that a click of the current tool sets, or null. Like the selection of Create's Super Glue: without
     * Alt the block that the camera looks at (for the spawn point, the block on its clicked face); with Alt held, the
     * block at {@link #reach} blocks along the view, in the air too.
     */
    static BlockPos target() {
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || mc.player == null) return null;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 eye = camera.getPosition(partial);
        Vec3 look = Vec3.directionFromRotation(mc.player.getViewXRot(partial), mc.player.getViewYRot(partial));
        if (altDown()) return BlockPos.containing(eye.add(look.scale(reach)));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(64)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return tool == Tool.SPAWN ? hit.getBlockPos().relative(hit.getDirection()) : hit.getBlockPos();
    }

    /** The wheel does not change the hotbar in the free camera; with Alt it changes the distance of the point in the air. */
    private static void scroll(InputEvent.MouseScrollingEvent event) {
        if (mode != Mode.FREE) return;
        event.setCanceled(true);
        if (altDown()) reach = Mth.clamp(reach + Math.signum(event.getScrollDeltaY()), 1, Computer.PLACE_RANGE);
    }

    /** In the free camera attack forgets the first corner. Nothing reaches the world. */
    private static void click(InputEvent.InteractionKeyMappingTriggered event) {
        if (mode == Mode.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (mode == Mode.FREE && event.isAttack()) corner = null;
    }

    /** Edits are driven by the mapping's press, since the interaction hook also fires while use is held. */
    private static void useTool() {
        BlockPos pos = target();
        if (pos == null) return;
        switch (tool) {
            case AREA -> {
                if (corner == null) { corner = pos; return; }
                send(Computer.SET_AREA, List.of(corner.getX(), corner.getY(), corner.getZ(), pos.getX(), pos.getY(), pos.getZ()));
                corner = null;
            }
            case SPAWN -> send(Computer.SET_SPAWN, List.of(pos.getX(), pos.getY(), pos.getZ()));
            case DELIVERY -> send(Computer.SET_DELIVERY, List.of(pos.getX(), pos.getY(), pos.getZ()));
            case TABLES -> send(Computer.TOGGLE_TABLE, List.of(pos.getX(), pos.getY(), pos.getZ()));
            case SCREENS -> send(Computer.SET_SCREEN, List.of(pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    private static void send(int action, List<Integer> numbers) {
        StringBuilder id = new StringBuilder();
        for (int n : numbers) id.append(id.isEmpty() ? "" : ",").append(n);
        PacketDistributor.sendToServer(new Computer.Request(state.pos(), action, id.toString()));
    }

    /** Keys 1, 2 and 3 choose the tool in the free camera. */
    private static void key(InputEvent.Key event) {
        if (mode != Mode.FREE || event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS || Minecraft.getInstance().screen != null) return;
        if (Minecraft.getInstance().options.keyUse.isActiveAndMatches(InputConstants.getKey(event.getKey(), event.getScanCode()))) {
            useTool();
            return;
        }
        int k = event.getKey();
        if (k >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && k <= org.lwjgl.glfw.GLFW.GLFW_KEY_5) {
            tool = Tool.values()[k - org.lwjgl.glfw.GLFW.GLFW_KEY_1];
            corner = null;
        }
    }

    private static void mouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.FREE && mc.screen == null && mc.mouseHandler.isMouseGrabbed()
                && event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS
                && mc.options.keyUse.isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) useTool();
    }

    /** Escape in the free camera ends the editing: the camera flies back to the monitor and the manager app opens. */
    private static void pause(ScreenEvent.Opening event) {
        if (mode != Mode.FREE || !(event.getNewScreen() instanceof PauseScreen)) return;
        event.setCanceled(true);
        Minecraft.getInstance().options.hideGui = true;
        reopenApp = Computer.APP_MANAGER;
        flyTo(Mode.TO_SCREEN, screenView(state.pos()));
        corner = null;
    }

    private static void renderWorld(RenderLevelStageEvent event) {
        if (mode != Mode.FREE || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || state == null) return;
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        // Boxes like Create's Super Glue: see-through faces with a grid, thick edges.
        List<Integer> a = state.area();
        if (a.size() == 6) SelectionBox.draw(pose, buffers, new AABB(a.get(0), a.get(1), a.get(2), a.get(3) + 1, a.get(4) + 1, a.get(5) + 1), 0x4DE066, 0.12f);
        state.sign().ifPresent(p -> SelectionBox.draw(pose, buffers, new AABB(p).inflate(0.02), 0xFFFFFF, 0.2f));
        state.spawn().ifPresent(p -> SelectionBox.draw(pose, buffers, new AABB(p), 0x4D99FF, 0.3f));
        state.delivery().ifPresent(p -> SelectionBox.draw(pose, buffers, new AABB(p).inflate(0.02), 0xFF8C1A, 0.3f));
        if (tool == Tool.TABLES) {
            // Tables: green when customers may use them, red when they are off.
            java.util.Set<BlockPos> off = new java.util.HashSet<>(state.offSurfaces());
            for (BlockPos p : state.surfaces()) SelectionBox.draw(pose, buffers, new AABB(p).deflate(0.03), off.contains(p) ? 0xE05555 : 0x4DE066, 0.25f);
        }
        BlockPos p = target();
        if (p != null) {
            // The box grows from the first corner to the looked-at point, like Create's Super Glue.
            if (tool == Tool.AREA && corner != null) SelectionBox.draw(pose, buffers, AABB.encapsulatingFullBlocks(corner, p).inflate(0.01), 0xFFE04D, 0.18f);
            SelectionBox.draw(pose, buffers, new AABB(p).inflate(0.005), 0xFFFFFF, 0.15f);
        }
        pose.popPose();
        buffers.endBatch();
    }

    private static void renderHud(RenderGuiEvent.Post event) {
        if (mode != Mode.FREE) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        var font = mc.font;
        var o = mc.options;
        Component[] tools = {Component.translatable("onemorecore.free.area"), Component.translatable("onemorecore.free.spawn"),
                Component.translatable("onemorecore.free.delivery"), Component.translatable("onemorecore.free.tables"),
                Component.translatable("onemorecore.free.screens")};
        int[] colors = {0xFF4DE066, 0xFF4D99FF, 0xFFFF8C1A, 0xFFE05555, 0xFFB57BFF};
        // One prompt row for the current tool: the mouse buttons and what they do.
        List<Object> help = new java.util.ArrayList<>();
        if (altDown()) {
            help.add(KeyGlyphs.Mouse.MIDDLE);
            help.add(Component.translatable("onemorecore.free.distance", (int) reach));
        } else if (tool == Tool.AREA && corner != null) {
            help.add(o.keyUse); help.add(Component.translatable("onemorecore.free.corner2"));
            help.add(o.keyAttack); help.add(Component.translatable("onemorecore.free.cancel"));
        } else {
            help.add(o.keyUse);
            help.add(Component.translatable(switch (tool) {
                case AREA -> "onemorecore.free.corner1";
                case SPAWN -> "onemorecore.free.place_spawn";
                case DELIVERY -> "onemorecore.free.place_delivery";
                case TABLES -> "onemorecore.free.toggle_table";
                case SCREENS -> "onemorecore.free.cycle_screen";
            }));
            if (tool == Tool.AREA) { help.add("Alt"); help.add(Component.translatable("onemorecore.free.in_air")); }
        }
        List<Object> keys = List.of(o.keyUp, o.keyLeft, o.keyDown, o.keyRight, Component.translatable("onemorecore.free.fly"),
                o.keyJump, o.keyShift, Component.translatable("onemorecore.free.up_down"), o.keySprint, Component.translatable("onemorecore.free.fast"),
                "Esc", Component.translatable("onemorecore.free.done"));
        // Tabs: a key cap with the number, a colour mark and the name.
        int toolsW = -4;
        for (Component t : tools) toolsW += 16 + 4 + 8 + font.width(t) + 6 + 4;
        int helpW = KeyGlyphs.width(font, help), keysW = KeyGlyphs.width(font, keys);
        int w = Math.min(g.guiWidth() - 12, Math.max(toolsW, Math.max(helpW, keysW)) + 12);
        boolean showMessage = System.currentTimeMillis() - messageTime < 4000 && !message.getString().isEmpty();
        int h = 4 + 16 + 4 + KeyGlyphs.HEIGHT + 4 + KeyGlyphs.HEIGHT + 4 + (showMessage ? 12 : 0);
        // At the bottom, above the hotbar: the top of the screen belongs to block tooltips of other mods (Jade).
        int x = (g.guiWidth() - w) / 2, y = g.guiHeight() - 60 - h;
        g.fill(x + 1, y, x + w - 1, y + h, 0x8C0C0C0C);
        g.fill(x, y + 1, x + w, y + h - 1, 0x8C0C0C0C);
        int tx = x + (w - toolsW) / 2, ty = y + 4;
        for (int i = 0; i < tools.length; i++) {
            int tw = 16 + 4 + 8 + font.width(tools[i]) + 6;
            boolean on = tool.ordinal() == i;
            g.fill(tx, ty, tx + tw, ty + 16, on ? 0x55FFFFFF : 0x1AFFFFFF);
            KeyGlyphs.draw(g, font, tx + 1, ty + 1, String.valueOf(i + 1), 0xFFFFFFFF);
            g.fill(tx + 18, ty + 5, tx + 24, ty + 11, colors[i]);
            g.drawString(font, tools[i], tx + 28, ty + 4, on ? 0xFFFFFFFF : 0xFF9AA3AD, false);
            tx += tw + 4;
        }
        int ry = ty + 20;
        KeyGlyphs.draw(g, font, x + (w - helpW) / 2, ry, help, 0xFFFFFFFF);
        KeyGlyphs.draw(g, font, x + (w - keysW) / 2, ry + KeyGlyphs.HEIGHT + 4, keys, 0xFFB8BEC6);
        if (showMessage) g.drawString(font, message, x + (w - font.width(message)) / 2, ry + 2 * KeyGlyphs.HEIGHT + 8, 0xFFFFE27A, false);
    }
}
