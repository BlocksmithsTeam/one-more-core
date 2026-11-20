package com.onemorestudios.onemoreclient.client;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.onemorestudios.onemoreclient.MenuBoardBlockEntity;
import com.onemorestudios.onemoreclient.OrderBoardBlockEntity;
import com.onemorestudios.onemoreclient.Restaurant;
import com.onemorestudios.onemoreclient.ScreenData;
import com.onemorestudios.onemoreclient.ScreenRect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Draws the menu and the orders on televisions: the mod's own Menu Screen and Order Screen (block entities), and the
 * televisions of other mods that a restaurant set to a mode (ScreenData). The pointer is the crosshair, or the mouse
 * while Diegesis shows the television up close; the item under it is lit and its tooltip shows.
 */
public class ScreenRenderer<T extends net.minecraft.world.level.block.entity.BlockEntity> implements BlockEntityRenderer<T> {
    /** The item under the pointer on any television in this frame. */
    static ItemStack stack = ItemStack.EMPTY;
    private static double best = Double.MAX_VALUE;
    /** The order card under the crosshair in this frame (its screen and index), for a click; index -1: none. */
    private static BlockPos cardPos = BlockPos.ZERO;
    private static int cardIndex = -1;
    private static double cardBest = Double.MAX_VALUE;
    /** The view of the last frame, camera-relative, for the mouse ray. */
    private static final Matrix4f INVERSE = new Matrix4f();
    private static boolean haveMatrix;

    public ScreenRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public boolean shouldRenderOffScreen(T be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 48;
    }

    @Override
    public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        boolean orders = be instanceof OrderBoardBlockEntity;
        List<MenuBoardBlockEntity.Line> lines = be instanceof MenuBoardBlockEntity m ? m.lines : List.of();
        List<OrderBoardBlockEntity.Ticket> tickets = orders ? ((OrderBoardBlockEntity) be).tickets : List.of();
        draw(pose, buffers, be.getBlockPos(), state, ScreenRect.OWN, orders ? Restaurant.SCREEN_ORDERS : Restaurant.SCREEN_MENU, lines, tickets);
    }

    /** Draws one television with the pose at the block's origin. */
    static void draw(PoseStack pose, MultiBufferSource buffers, BlockPos pos, BlockState state, ScreenRect r, int mode,
            List<MenuBoardBlockEntity.Line> lines, List<OrderBoardBlockEntity.Ticket> tickets) {
        float[] hover = pointer(pos, state, r);
        pose.pushPose();
        ScreenPainter.place(pose, r, r.turn(state), ScreenRect.tilt(state));
        ScreenPainter.Canvas c = new ScreenPainter.Canvas(pose, buffers, r.width(), r.height(), hover[0], hover[1]);
        ItemStack over = mode == Restaurant.SCREEN_ORDERS ? ScreenPainter.orders(c, tickets) : ScreenPainter.menu(c, lines);
        pose.popPose();
        if (!over.isEmpty() && hover[2] < best) {
            best = hover[2];
            stack = over;
        }
        if (mode == Restaurant.SCREEN_ORDERS && !Float.isNaN(hover[0]) && hover[2] < cardBest && Minecraft.getInstance().level != null) {
            int i = ScreenRect.orderAt(r.width(), r.height(), tickets.size(), Minecraft.getInstance().level.getGameTime(), hover[0], hover[1]);
            if (i >= 0) {
                cardBest = hover[2];
                cardPos = pos.immutable();
                cardIndex = i;
            }
        }
    }

    /**
     * The pointer on the screen plane in canvas units and its distance: {x, y, distance}; NaN when it misses. Within 8
     * blocks, as the bubbles of the customers.
     */
    static float[] pointer(BlockPos pos, BlockState state, ScreenRect r) {
        float[] none = {Float.NaN, Float.NaN, Float.MAX_VALUE};
        Minecraft mc = Minecraft.getInstance();
        boolean close = DiegesisHook.focusedAt(pos);
        if (mc.screen != null && !close || ComputerCamera.active() || !haveMatrix) return none;
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
        if (Math.abs(denom) < 1e-6) return none;
        double t = o.subtract(from).dot(n) / denom;
        if (t <= 0 || t > (close ? 32 : 8)) return none;
        Vec3 p = from.add(dir.scale(t)).subtract(o);
        double u = p.dot(ux) / ux.lengthSqr(), v = p.dot(vy) / vy.lengthSqr();
        if (u < 0 || u > 1 || v < 0 || v > 1) return none;
        return new float[] {(float) (u * r.width()), (float) (v * r.height()), (float) t};
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> stage(e));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Pre e) -> {
            stack = ItemStack.EMPTY;
            best = Double.MAX_VALUE;
            cardIndex = -1;
            cardBest = Double.MAX_VALUE;
        });
        // A use click on an order card gives its ticket, also where the television is wider than its block.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered e) -> {
            if (!e.isUseItem() || cardIndex < 0 || Minecraft.getInstance().screen != null || Minecraft.getInstance().player.isShiftKeyDown()) return;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.onemorestudios.onemoreclient.ScreenClick(cardPos, cardIndex));
            e.setSwingHand(false);
            e.setCanceled(true);
        });
    }

    /** Keeps the view for the pointer, and draws the televisions of other mods. */
    private static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert(INVERSE);
            haveMatrix = true;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || mc.level == null || ScreenData.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (ScreenData d : ScreenData.CLIENT.values()) {
            long[] at = d.data().getLongArray("pos");
            int[] modes = d.data().getIntArray("mode");
            List<MenuBoardBlockEntity.Line> lines = MenuBoardBlockEntity.parse(d.data().getCompound("menu"));
            List<OrderBoardBlockEntity.Ticket> tickets = OrderBoardBlockEntity.decode(d.data().getCompound("tickets"), mc.level.registryAccess());
            for (int i = 0; i < at.length && i < modes.length; i++) {
                BlockPos pos = BlockPos.of(at[i]);
                if (pos.distToCenterSqr(cam) > 48 * 48) continue;
                BlockState state = mc.level.getBlockState(pos);
                ScreenRect r = ScreenRect.of(state);
                if (r == null) continue;
                pose.pushPose();
                pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
                draw(pose, buffers, pos, state, r, modes[i], lines, tickets);
                pose.popPose();
            }
        }
        buffers.endBatch();
    }
}
