package com.onemorestudios.onemorecore.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OrderBoardBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.ScreenClick;
import com.onemorestudios.onemorecore.ScreenData;
import com.onemorestudios.onemorecore.ScreenGroups;
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
 * Draws what the restaurants' screens show (ScreenData): the mod's TVs and Monitors and other mods' televisions. Screens that
 * join (ScreenGroups) are drawn as one picture by their anchor. The pointer is the crosshair, or the mouse while Diegesis shows
 * a station up close; the item under it is lit and its tooltip shows, and a use click on an order card gives its ticket.
 */
public final class ScreenRenderer {
    private ScreenRenderer() {}

    /** The item under the pointer on any screen in this frame. */
    static ItemStack stack = ItemStack.EMPTY;
    private static double best = Double.MAX_VALUE;
    /** The order card under the pointer in this frame, for a click; null: none. */
    private static ScreenClick card;
    private static double cardBest = Double.MAX_VALUE;
    /** The view of the last frame, camera-relative, for the mouse ray. */
    private static final Matrix4f INVERSE = new Matrix4f();
    private static boolean haveMatrix;
    /** The menu and tickets of each restaurant's last ScreenData, read once per packet. */
    private static final Map<BlockPos, Display> DISPLAYS = new HashMap<>();

    private record Display(ScreenData data, List<MenuBoardBlockEntity.Line> lines, List<OrderBoardBlockEntity.Ticket> tickets) {}

    /** Draws one screen (or group) with the pose at the anchor block's origin. */
    static void draw(PoseStack pose, MultiBufferSource buffers, BlockPos pos, BlockState state, ScreenGroups.Group g, int settings, float unit,
            List<MenuBoardBlockEntity.Line> lines, List<OrderBoardBlockEntity.Ticket> tickets) {
        int mode = ScreenSettings.mode(settings);
        if (mode == Restaurant.SCREEN_OFF) return;
        ScreenRect r = g.rect();
        float[] hover = pointer(pos, state, r, g.members());
        pose.pushPose();
        ScreenPainter.place(pose, r, r.turn(state), ScreenRect.tilt(state));
        ScreenPainter.Canvas c = new ScreenPainter.Canvas(pose, buffers, r.width(), r.height(), hover[0], hover[1], unit, settings);
        ItemStack over = mode == Restaurant.SCREEN_ORDERS ? ScreenPainter.orders(c, tickets) : ScreenPainter.menu(c, lines);
        pose.popPose();
        if (!over.isEmpty() && hover[2] < best) {
            best = hover[2];
            stack = over;
        }
        if (mode == Restaurant.SCREEN_ORDERS && !Float.isNaN(hover[0]) && hover[2] < cardBest) {
            int i = ScreenRect.orderAt(r.width(), r.height(), unit, tickets.size(), ScreenPainter.gameTime(settings), hover[0], hover[1]);
            if (i >= 0 && tickets.get(i).orderId() != null) {
                cardBest = hover[2];
                card = new ScreenClick(pos.immutable(), tickets.get(i).orderId(), hover[0], hover[1]);
            }
        }
    }

    /**
     * The pointer on the screen plane in canvas units and its distance: {x, y, distance}; NaN when it misses. Within 8
     * blocks, as the bubbles of the customers; the mouse while Diegesis shows a station up close. Blocks in front of the
     * picture (other than the screens themselves) hide it from the pointer.
     */
    static float[] pointer(BlockPos pos, BlockState state, ScreenRect r, List<BlockPos> screens) {
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
        Vec3 o = r.world(pos, state, r.x0(), r.y1(), r.z()), ux = r.world(pos, state, r.x1(), r.y1(), r.z()).subtract(o),
                vy = r.world(pos, state, r.x0(), r.y0(), r.z()).subtract(o);
        Vec3 n = ux.cross(vy);
        double denom = dir.dot(n);
        if (denom < 1e-6) return none;
        double t = o.subtract(from).dot(n) / denom;
        if (t <= 0 || t > (close ? 48 : 8)) return none;
        Vec3 point = from.add(dir.scale(t));
        Vec3 p = point.subtract(o);
        double u = p.dot(ux) / ux.lengthSqr(), v = p.dot(vy) / vy.lengthSqr();
        if (u < 0 || u > 1 || v < 0 || v > 1 || !ScreenRect.visible(mc.level, mc.player, screens, cam, point)) return none;
        return new float[] {(float) (u * r.width()), (float) (v * r.height()), (float) t};
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> stage(e));
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> {
            stack = ItemStack.EMPTY;
            best = Double.MAX_VALUE;
            card = null;
            cardBest = Double.MAX_VALUE;
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> DISPLAYS.clear());
        // A use click on an order card gives its ticket, also where the screen is wider than its block.
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!e.isUseItem() || e.getHand() != InteractionHand.MAIN_HAND || card == null || mc.screen != null || mc.level == null
                    || mc.player == null || mc.player.isSpectator() || mc.player.isShiftKeyDown()) return;
            // With Diegesis, an empty-hand click on one of the mod's screens opens the close-up (the screen station) instead.
            if (DiegesisHook.LOADED && mc.player.getMainHandItem().isEmpty() && mc.hitResult instanceof BlockHitResult hit
                    && isStation(mc.level.getBlockState(hit.getBlockPos()))) return;
            PacketDistributor.sendToServer(card);
            e.setSwingHand(false);
            e.setCanceled(true);
        });
        // In the Diegesis view, a click on a card does the same.
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (e.getButton() != 0 || card == null || !DiegesisHook.focused()) return;
            PacketDistributor.sendToServer(card);
            e.setCanceled(true);
        });
    }

    /** The blocks of the Diegesis screen station (KitchenStations.SCREEN). */
    private static boolean isStation(BlockState state) {
        return state.is(OneMoreCore.MENU_BOARD.get()) || state.is(OneMoreCore.ORDER_BOARD.get()) || state.is(OneMoreCore.MONITOR.get());
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
                                OrderBoardBlockEntity.decode(d.data().getCompound("tickets"), registries));
                        DISPLAYS.put(d.sign(), display);
                    }
                }
                BlockState state = mc.level.getBlockState(pos);
                float unit = ScreenRect.unit(ScreenRect.of(state), e.getValue());
                pose.pushPose();
                pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
                draw(pose, buffers, pos, state, g, e.getValue(), unit, display.lines(), display.tickets());
                // A billboard shows the picture on its back too: the same model turned half round.
                if (state.getBlock() instanceof com.onemorestudios.onemorecore.BillboardBlock)
                    draw(pose, buffers, pos, ScreenRect.back(state), g, e.getValue(), unit, display.lines(), display.tickets());
                pose.popPose();
            }
        }
        buffers.endBatch();
    }
}
