package com.onemorestudios.onemoreclient.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemoreclient.Computer;
import com.onemorestudios.onemoreclient.OneMoreClient;

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
    /** Part of the view height that the monitor's screen fills at the end of the flight. */
    static final double FILL = 0.9;
    /** The monitor's screen in pixels of the north-facing model: x 4..12, y 3..9, front face at z 3.9. */
    static final double SCREEN_HEIGHT = 6 / 16.0;

    static Mode mode = Mode.NONE;
    static Computer.State state;
    private static Marker camera;
    private static Vec3 from, to;
    private static float fromYaw, fromPitch, toYaw, toPitch;
    private static int tick;
    private static boolean hideGuiBefore;
    /** The app that the desktop opens with, after the free camera. */
    static int reopenApp;

    // Free camera.
    enum Tool { AREA, SPAWN, DELIVERY }
    static Tool tool = Tool.AREA;
    static BlockPos corner;
    static Component message = Component.empty();
    static long messageTime;

    static void register() {
        NeoForge.EVENT_BUS.addListener(ComputerCamera::tick);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::angles);
        NeoForge.EVENT_BUS.addListener((RenderHandEvent e) -> { if (mode != Mode.NONE) e.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener(ComputerCamera::movement);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::click);
        NeoForge.EVENT_BUS.addListener(ComputerCamera::key);
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
        camera = new Marker(EntityType.MARKER, mc.level);
        Vec3 eye = mc.player.getEyePosition();
        place(eye, mc.player.getYRot(), mc.player.getXRot());
        hideGuiBefore = mc.options.hideGui;
        mc.options.hideGui = true;
        mc.setCameraEntity(camera);
        flyTo(Mode.TO_SCREEN, screenView(s.pos()));
    }

    /** Camera position and angles in front of the monitor, so that the screen fills FILL of the view height. */
    static Object[] screenView(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        BlockState block = mc.level.getBlockState(pos);
        Direction facing = block.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? block.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        // The screen centre of the north model is (8, 6, 3.9) px; turn it with the block.
        Vec3 local = new Vec3(0, 6 / 16.0, 3.9 / 16 - 0.5);
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

    /** Leaves the desktop: the camera flies back to the eyes of the player. */
    static void leave() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || camera == null) { finish(); return; }
        if (mc.screen instanceof ComputerScreen) mc.setScreen(null);
        flyTo(Mode.TO_PLAYER, new Object[] {mc.player.getEyePosition(), mc.player.getYRot(), mc.player.getXRot()});
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
        state = null;
        corner = null;
        mc.options.hideGui = hideGuiBefore;
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.NONE) return;
        if (mc.player == null || mc.level == null || camera == null || camera.level() != mc.level) { finish(); return; }
        // The player walked away (pushed, teleported): the computer closes.
        if (!mc.player.canInteractWithBlock(state.pos(), 4) || !mc.level.getBlockState(state.pos()).is(OneMoreClient.COMPUTER)) {
            if (mc.screen instanceof ComputerScreen) mc.setScreen(null);
            finish();
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

    /** The block that the free camera looks at, up to 64 blocks away, or null. */
    static BlockHitResult target() {
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || mc.player == null) return null;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 eye = camera.getPosition(partial);
        Vec3 look = Vec3.directionFromRotation(mc.player.getViewXRot(partial), mc.player.getViewYRot(partial));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(64)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** In the free camera: use places a point with the tool, attack forgets the first corner. Nothing reaches the world. */
    private static void click(InputEvent.InteractionKeyMappingTriggered event) {
        if (mode == Mode.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (mode != Mode.FREE) return;
        if (event.isAttack()) { corner = null; return; }
        if (!event.isUseItem()) return;
        BlockHitResult hit = target();
        if (hit == null) return;
        BlockPos pos = hit.getBlockPos();
        switch (tool) {
            case AREA -> {
                if (corner == null) { corner = pos; return; }
                send(Computer.SET_AREA, List.of(corner.getX(), corner.getY(), corner.getZ(), pos.getX(), pos.getY(), pos.getZ()));
                corner = null;
            }
            case SPAWN -> {
                BlockPos at = pos.relative(hit.getDirection());
                send(Computer.SET_SPAWN, List.of(at.getX(), at.getY(), at.getZ()));
            }
            case DELIVERY -> send(Computer.SET_DELIVERY, List.of(pos.getX(), pos.getY(), pos.getZ()));
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
        int k = event.getKey();
        if (k >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && k <= org.lwjgl.glfw.GLFW.GLFW_KEY_3) {
            tool = Tool.values()[k - org.lwjgl.glfw.GLFW.GLFW_KEY_1];
            corner = null;
        }
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
        var lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        List<Integer> a = state.area();
        if (a.size() == 6) LevelRenderer.renderLineBox(pose, lines, new AABB(a.get(0), a.get(1), a.get(2), a.get(3) + 1, a.get(4) + 1, a.get(5) + 1), 0.3f, 1f, 0.4f, 1f);
        state.sign().ifPresent(p -> LevelRenderer.renderLineBox(pose, lines, new AABB(p).inflate(0.02), 1f, 1f, 1f, 1f));
        state.spawn().ifPresent(p -> LevelRenderer.renderLineBox(pose, lines, new AABB(p), 0.3f, 0.6f, 1f, 1f));
        state.delivery().ifPresent(p -> LevelRenderer.renderLineBox(pose, lines, new AABB(p).inflate(0.02), 1f, 0.55f, 0.1f, 1f));
        BlockHitResult hit = target();
        if (hit != null) {
            BlockPos p = hit.getBlockPos();
            if (tool == Tool.AREA && corner != null) {
                LevelRenderer.renderLineBox(pose, lines, AABB.encapsulatingFullBlocks(corner, p).inflate(0.01), 1f, 1f, 0.3f, 1f);
            } else {
                BlockPos shown = tool == Tool.SPAWN ? p.relative(hit.getDirection()) : p;
                LevelRenderer.renderLineBox(pose, lines, new AABB(shown).inflate(0.005), 1f, 1f, 1f, 0.8f);
            }
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void renderHud(RenderGuiEvent.Post event) {
        if (mode != Mode.FREE) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        var font = mc.font;
        Component title = Component.translatable("onemoreclient.free.title");
        Component[] tools = {Component.translatable("onemoreclient.free.area"), Component.translatable("onemoreclient.free.spawn"),
                Component.translatable("onemoreclient.free.delivery")};
        Component help = Component.translatable(tool == Tool.AREA ? (corner == null ? "onemoreclient.free.help_area1" : "onemoreclient.free.help_area2")
                : tool == Tool.SPAWN ? "onemoreclient.free.help_spawn" : "onemoreclient.free.help_delivery");
        Component keys = Component.translatable("onemoreclient.free.keys");
        int toolsW = 0;
        for (int i = 0; i < 3; i++) toolsW += font.width(tools[i]) + 22 + (i > 0 ? 6 : 0);
        // The panel is as wide as the tool row, at most the screen width; longer lines wrap.
        int w = Math.min(g.guiWidth() - 12, Math.max(toolsW, font.width(title)) + 16);
        var helpLines = font.split(help, w - 12);
        var keyLines = font.split(keys, w - 12);
        boolean showMessage = System.currentTimeMillis() - messageTime < 4000 && !message.getString().isEmpty();
        var messageLines = showMessage ? font.split(message, w - 12) : java.util.List.<net.minecraft.util.FormattedCharSequence>of();
        int h = 6 + 11 + 18 + 10 * (helpLines.size() + keyLines.size() + messageLines.size()) + 6;
        int x = (g.guiWidth() - w) / 2, y = 6;
        g.fill(x + 1, y, x + w - 1, y + h, 0x8C0C0C0C);
        g.fill(x, y + 1, x + w, y + h - 1, 0x8C0C0C0C);
        g.drawString(font, title, x + (w - font.width(title)) / 2, y + 6, 0xFFFFFFFF, false);
        int tx = x + (w - toolsW) / 2, ty = y + 19;
        int[] colors = {0xFF4DE066, 0xFF4D99FF, 0xFFFF8C1A};
        for (int i = 0; i < 3; i++) {
            int tw = font.width(tools[i]) + 22;
            boolean on = tool.ordinal() == i;
            g.fill(tx, ty, tx + tw, ty + 14, on ? 0x66FFFFFF : 0x22FFFFFF);
            g.fill(tx + 3, ty + 4, tx + 9, ty + 10, colors[i]);
            g.drawString(font, (i + 1) + " ", tx + 11, ty + 3, 0xFFB0B0B0, false);
            g.drawString(font, tools[i], tx + 19, ty + 3, on ? 0xFFFFFFFF : 0xFFB0B0B0, false);
            tx += tw + 6;
        }
        int ly = ty + 19;
        for (var l : helpLines) { g.drawString(font, l, x + (w - font.width(l)) / 2, ly, 0xFFFFFFFF, false); ly += 10; }
        for (var l : keyLines) { g.drawString(font, l, x + (w - font.width(l)) / 2, ly, 0xFFB0B0B0, false); ly += 10; }
        for (var l : messageLines) { g.drawString(font, l, x + (w - font.width(l)) / 2, ly, 0xFFFFE27A, false); ly += 10; }
    }
}
