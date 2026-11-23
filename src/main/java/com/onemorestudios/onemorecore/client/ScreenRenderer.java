package com.onemorestudios.onemorecore.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Tickets;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.ScreenData;
import com.onemorestudios.onemorecore.ScreenGroups;
import com.onemorestudios.onemorecore.ScreenPicture;
import com.onemorestudios.onemorecore.ScreenRect;
import com.onemorestudios.onemorecore.ScreenSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws what the restaurants' screens show (ScreenData): the mod's Screens (both sides), Order Screens and Monitors, and other
 * mods' televisions. Screens that join (ScreenGroups) are drawn as one picture by their anchor. The pointer is the crosshair, or
 * the mouse while Diegesis shows a station up close: the item under it is lit and its tooltip shows, and a use click (or a mouse
 * click up close) runs what is under it: a card gives its ticket, a tab changes what the side shows, a summary line marks the
 * tickets of a dish. Up close the mouse wheel scrolls the side the camera looks at.
 */
public final class ScreenRenderer {
    private ScreenRenderer() {}

    /** The item under the pointer on any screen in this frame. */
    static ItemStack stack = ItemStack.EMPTY;
    private static double best = Double.MAX_VALUE;
    /** What a click runs in this frame (the nearest screen's region under the pointer); null: nothing. */
    private static Runnable action;
    private static double actionBest = Double.MAX_VALUE;
    /** The view of the last frame, camera-relative, for the mouse ray. */
    private static final Matrix4f INVERSE = new Matrix4f();
    private static boolean haveMatrix;
    /** The menu and tickets of each restaurant's last ScreenData, read once per packet. */
    private static final Map<BlockPos, Display> DISPLAYS = new HashMap<>();
    /** What a player at one side of a screen chose there: by anchor and side. */
    private static final Map<Long, View> VIEWS = new HashMap<>();
    /** The side scrolled by the mouse wheel up close: the anchor and side last drawn under the camera's look. */
    private static long scrollTarget = Long.MIN_VALUE;

    private record Display(ScreenData data, List<MenuBoardBlockEntity.Line> lines, List<Tickets.Ticket> tickets) {}

    /** Scrolled canvas units, the highest scroll the content allows, and the dish whose tickets are marked. */
    static final class View {
        float scroll, maxScroll;
        net.minecraft.world.item.Item marked;
    }

    static View view(BlockPos anchor, int side) {
        return VIEWS.computeIfAbsent(anchor.asLong() * 2 + side, k -> new View());
    }

    /** Draws one side of a screen (or group) with the pose at the anchor block's origin. */
    static void draw(PoseStack pose, MultiBufferSource buffers, BlockPos pos, BlockState state, ScreenGroups.Group g, int side, int settings, float unit,
            List<MenuBoardBlockEntity.Line> lines, List<Tickets.Ticket> tickets) {
        int mode = ScreenSettings.mode(settings);
        if (mode == Restaurant.SCREEN_OFF) return;
        ScreenPicture picture = ScreenPicture.of(pos, state, g.rect(), side);
        float[] hover = pointer(picture, g.members());
        boolean focused = g.members().stream().anyMatch(DiegesisHook::focusedAt);
        View view = view(pos, side);
        // Up close, the side that faces the camera scrolls.
        if (focused) {
            Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            if (picture.faces(eye.subtract(picture.world(picture.width() / 2, picture.height() / 2)))) scrollTarget = pos.asLong() * 2 + side;
        }
        boolean scrolling = focused && scrollTarget == pos.asLong() * 2 + side;
        if (!scrolling) view.scroll = 0;
        pose.pushPose();
        pose.pushTransformation(new Transformation(picture.matrix()));
        ScreenPainter.Canvas c = new ScreenPainter.Canvas(pose, buffers, picture.width(), picture.height(), hover[0], hover[1], unit, settings);
        c.anchor = pos;
        c.side = side;
        c.view = view;
        c.scrolling = scrolling;
        ItemStack over = mode == Restaurant.SCREEN_ORDERS ? ScreenPainter.orders(c, tickets) : ScreenPainter.menu(c, lines);
        pose.popPose();
        pose.popPose();
        if (!over.isEmpty() && hover[2] < best) {
            best = hover[2];
            stack = over;
        }
        if (c.action != null && hover[2] < actionBest) {
            actionBest = hover[2];
            action = c.action;
        }
    }

    /**
     * The pointer on the picture in canvas units and its distance: {x, y, distance}; NaN when it misses. Within 8 blocks, as the
     * bubbles of the customers; the mouse while Diegesis shows a station up close. Blocks in front of the picture (other than the
     * screens themselves) hide it from the pointer.
     */
    static float[] pointer(ScreenPicture picture, List<BlockPos> screens) {
        float[] none = {Float.NaN, Float.NaN, Float.MAX_VALUE};
        Minecraft mc = Minecraft.getInstance();
        boolean close = DiegesisHook.focused();
        if (mc.level == null || mc.player == null || mc.screen != null && !close || ComputerCamera.active() || !haveMatrix) return none;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double nx = 0, ny = 0;
        if (close) {
            var w = mc.getWindow();
            nx = 2 * mc.mouseHandler.xpos() / w.getScreenWidth() - 1;
            ny = 1 - 2 * mc.mouseHandler.ypos() / w.getScreenHeight();
        }
        Vector4f a = INVERSE.transform(new Vector4f((float) nx, (float) ny, -1, 1)), b = INVERSE.transform(new Vector4f((float) nx, (float) ny, 1, 1));
        Vec3 from = cam.add(a.x / a.w, a.y / a.w, a.z / a.w), to = cam.add(b.x / b.w, b.y / b.w, b.z / b.w);
        Vec3 dir = to.subtract(from).normalize();
        Vec3 o = picture.world(0, 0), ux = picture.world(picture.width(), 0).subtract(o), vy = picture.world(0, picture.height()).subtract(o);
        Vec3 n = ux.cross(vy);
        double denom = dir.dot(n);
        if (denom < 1e-6) return none;
        double t = o.subtract(from).dot(n) / denom;
        if (t <= 0 || t > (close ? 48 : 8)) return none;
        Vec3 point = from.add(dir.scale(t));
        Vec3 p = point.subtract(o);
        double u = p.dot(ux) / ux.lengthSqr(), v = p.dot(vy) / vy.lengthSqr();
        if (u < 0 || u > 1 || v < 0 || v > 1 || !ScreenRect.visible(mc.level, mc.player, screens, cam, point)) return none;
        return new float[] {(float) (u * picture.width()), (float) (v * picture.height()), (float) t};
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> stage(e));
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> {
            stack = ItemStack.EMPTY;
            best = Double.MAX_VALUE;
            action = null;
            actionBest = Double.MAX_VALUE;
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
            DISPLAYS.clear();
            VIEWS.clear();
        });
        // A use click on a screen runs what is under the crosshair, also where the screen is wider than its block.
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!e.isUseItem() || e.getHand() != InteractionHand.MAIN_HAND || action == null || mc.screen != null || mc.level == null
                    || mc.player == null || mc.player.isSpectator() || mc.player.isShiftKeyDown()) return;
            // With Diegesis, an empty-hand click on one of the mod's screens opens the close-up instead.
            if (DiegesisHook.LOADED && mc.player.getMainHandItem().isEmpty() && mc.hitResult instanceof BlockHitResult hit
                    && isStation(mc.level.getBlockState(hit.getBlockPos()))) return;
            // A held tool (the screwdriver) works on the screen itself.
            if (mc.player.getMainHandItem().is(OneMoreCore.TABLE_TOOLS)) return;
            action.run();
            e.setSwingHand(false);
            e.setCanceled(true);
        });
        // Up close (Diegesis), a mouse click does the same, and the wheel scrolls the side the camera looks at.
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (e.getButton() != 0 || action == null || !DiegesisHook.focused()) return;
            action.run();
            e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseScrolled.Pre e) -> {
            if (DiegesisHook.focused() && scrollBy(e.getScrollDeltaY())) e.setCanceled(true);
        });
    }

    /** Scrolls the side of a screen that the close-up looks at (a wheel step is 40 canvas units); false when none. */
    static boolean scrollBy(double wheel) {
        if (scrollTarget == Long.MIN_VALUE) return false;
        View v = VIEWS.get(scrollTarget);
        if (v == null) return false;
        v.scroll = Math.max(0, Math.min(v.maxScroll, v.scroll - (float) wheel * 40));
        return true;
    }

    /** The blocks of the Diegesis screen stations (KitchenStations). */
    private static boolean isStation(BlockState state) {
        return state.is(OneMoreCore.MENU_BOARD.get());
    }

    /** Keeps the view for the pointer, and draws every screen that a restaurant sent. */
    private static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert(INVERSE);
            haveMatrix = true;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || mc.level == null) return;
        DISPLAYS.keySet().retainAll(ScreenData.CLIENT.keySet());
        if (ScreenData.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Map<BlockPos, ScreenGroups.Group> groups = new HashMap<>();
        for (ScreenData d : ScreenData.CLIENT.values()) {
            long[] at = d.data().getLongArray("pos");
            int[] modes = d.data().getIntArray("mode");
            Display display = null;
            Map<BlockPos, Integer> settings = new HashMap<>();
            for (int i = 0; i < at.length && i < modes.length; i++) settings.put(BlockPos.of(at[i]), modes[i]);
            for (var e : settings.entrySet()) {
                BlockPos pos = e.getKey();
                if (pos.distToCenterSqr(cam) > 64 * 64) continue;
                ScreenGroups.Group g = groups.computeIfAbsent(pos, p -> ScreenGroups.of(mc.level, p));
                if (g == null) continue;
                for (BlockPos m : g.members()) groups.putIfAbsent(m, g);
                // The anchor draws the group, with its own settings; a group whose anchor shows nothing stays dark.
                if (!g.anchor().equals(pos)) continue;
                if (display == null) {
                    display = DISPLAYS.get(d.sign());
                    if (display == null || display.data() != d) {
                        var registries = mc.level.registryAccess();
                        display = new Display(d, MenuBoardBlockEntity.parse(d.data().getCompound("menu"), registries),
                                Tickets.decode(d.data().getCompound("tickets"), registries));
                        DISPLAYS.put(d.sign(), display);
                    }
                }
                BlockState state = mc.level.getBlockState(pos);
                for (int side = 0; side < ScreenPicture.sides(state); side++) {
                    int face = ScreenSettings.face(e.getValue(), side);
                    float unit = ScreenRect.unit(ScreenRect.of(state), face);
                    pose.pushPose();
                    pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
                    draw(pose, buffers, pos, state, g, side, face, unit, display.lines(), display.tickets());
                    pose.popPose();
                }
            }
        }
        buffers.endBatch();
    }

    /**
     * Draws a Screen turned between the four directions (ScreenBlock.TURN): the blockstate shows only straight screens, so this
     * draws the straight model turned about the block's middle.
     */
    static final class Turned implements net.minecraft.client.renderer.blockentity.BlockEntityRenderer<MenuBoardBlockEntity> {
        Turned(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context) {}

        @Override
        public void render(MenuBoardBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            BlockState state = be.getBlockState();
            if (!(state.getBlock() instanceof com.onemorestudios.onemorecore.ScreenBlock) || state.getValue(com.onemorestudios.onemorecore.ScreenBlock.TURN) == 0) return;
            pose.pushPose();
            pose.translate(0.5, 0, 0.5);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-com.onemorestudios.onemorecore.ScreenBlock.turnDegrees(state)));
            pose.translate(-0.5, 0, -0.5);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state.setValue(com.onemorestudios.onemorecore.ScreenBlock.TURN, 0), pose, buffers, light, overlay);
            pose.popPose();
        }

        /** The panel reaches into the blocks around (above, below or beside). */
        @Override
        public net.minecraft.world.phys.AABB getRenderBoundingBox(MenuBoardBlockEntity be) {
            return new net.minecraft.world.phys.AABB(be.getBlockPos()).inflate(1.5);
        }
    }
}
