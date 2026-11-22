package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.Dirt;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TableCards;
import com.onemorestudios.onemorecore.TableCuts;
import com.onemorestudios.onemorecore.TableMarks;
import com.onemorestudios.onemorecore.TableNumberBlock;
import com.onemorestudios.onemorecore.TableNumberBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The text of the Table Number blocks, in black on both faces of the card (models/block/table_card, from
 * art/table_card_v2.bbmodel); the text box that names a card; and, with a screwdriver or a wrench in the hand, the table under
 * the crosshair with its splits (TableCuts).
 */
final class TableMarkRenderer {
    private TableMarkRenderer() {}

    /**
     * The upright card of the north-facing model: faces at z 7 and 8; inside its grey border a 6 x 3 pixel field whose middle
     * is at y 4.5. The text keeps a little margin: at most 5.6 wide, digits at most 2.6 high.
     */
    private static final float FRONT = 7f, BACK = 8f, MIDDLE_Y = 4.5f, WIDTH = 5.6f, DIGIT_HEIGHT = 2.6f;
    private static final int BLACK = 0xFF1A1A1A;

    static final ModelResourceLocation CARD = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/table_card"));

    static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(CARD);
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(TableMarkRenderer::renderTool);
        NeoForge.EVENT_BUS.addListener(TableMarkRenderer::renderToolText);
        TableCards.Edit.handler = e -> Minecraft.getInstance().setScreen(new EditScreen(e.pos(), e.text()));
    }

    /** The text on a Table Number block. */
    static final class Text implements BlockEntityRenderer<TableNumberBlockEntity> {
        Text(BlockEntityRendererProvider.Context context) {}

        /** The card can stand up to half a block off its block. */
        @Override
        public net.minecraft.world.phys.AABB getRenderBoundingBox(TableNumberBlockEntity be) {
            return new net.minecraft.world.phys.AABB(be.getBlockPos()).inflate(0.5, 0, 0.5);
        }

        @Override
        public void render(TableNumberBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            Minecraft mc = Minecraft.getInstance();
            pose.pushPose();
            // Where the card stands on the block, turned like a blockstate model (y rotation clockwise from above).
            pose.translate(0.5 + be.offsetX(), 0, 0.5 + be.offsetZ());
            pose.mulPose(Axis.YP.rotationDegrees(-be.getBlockState().getValue(TableNumberBlock.FACING).toYRot() + 180));
            pose.translate(-0.5, 0, -0.5);
            mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), be.getBlockState(),
                    mc.getModelManager().getModel(CARD), 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
            String text = be.text();
            if (text.isEmpty()) {
                pose.popPose();
                return;
            }
            Font font = mc.font;
            float s = Math.min(DIGIT_HEIGHT / 16 / 7, WIDTH / 16 / Math.max(1, font.width(text)));
            for (int side = 0; side < 2; side++) {
                pose.pushPose();
                pose.translate(0.5, MIDDLE_Y / 16, side == 0 ? FRONT / 16 - 0.002 : BACK / 16 + 0.002);
                if (side == 0) pose.mulPose(Axis.YP.rotationDegrees(180));
                pose.scale(s, -s, s);
                font.drawInBatch(text, -font.width(text) / 2f, -3.5f, BLACK, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
                pose.popPose();
            }
            pose.popPose();
        }
    }

    /** The table side under the crosshair that a click with the tool splits or joins (client), or null; its block. */
    private static Direction hoveredEdge;
    private static BlockPos hoveredPos;

    /**
     * With a screwdriver or a wrench in the hand (not sneaking), on a table block: as Create's wrench on mechanical crafters
     * (EdgeInteractionRenderer, MIT), white corner marks on the face where the click zone of the nearest side is, when the
     * crosshair is in it, and a hint below the crosshair. The splits of the table show as red lines on its top.
     */
    private static void renderTool(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        hoveredEdge = null;
        hoveredPos = null;
        if (mc.level == null || mc.player == null || mc.player.isShiftKeyDown() || !mc.player.getMainHandItem().is(OneMoreCore.TABLE_TOOLS)
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !mc.level.getBlockState(hit.getBlockPos()).is(OneMoreCore.SURFACES)) return;
        BlockPos at = hit.getBlockPos();
        Direction face = hit.getDirection();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        // The splits of this table and its neighbours, on the table tops.
        TableCuts cuts = TableCuts.client();
        // One pixel wide, on the edge.
        float t = PIXEL / 2;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-8, 0, -8), at.offset(8, 0, 8))) {
            for (Direction d : new Direction[] {Direction.EAST, Direction.SOUTH}) {
                if (!cuts.cut(p, d)) continue;
                float y = (float) (p.getY() + Dirt.top(mc.level, p)) + 0.01f;
                if (d == Direction.EAST) SelectionBox.bar(pose, buffers, 0xF0E04545, p.getX() + 1 - t, y - t, p.getZ(), p.getX() + 1 + t, y + t, p.getZ() + 1);
                else SelectionBox.bar(pose, buffers, 0xF0E04545, p.getX(), y - t, p.getZ() + 1 - t, p.getX() + 1, y + t, p.getZ() + 1 + t);
            }
        }
        java.util.List<Direction> sides = TableCuts.connectiveSides(mc.level, at, face);
        if (!sides.isEmpty()) {
            // The side nearest the crosshair; marked only when the crosshair is in its click zone.
            Vec3 center = Vec3.atCenterOf(at), point = hit.getLocation();
            Direction closest = sides.getFirst();
            double best = Double.MAX_VALUE;
            for (Direction d : sides) {
                double distance = Vec3.atLowerCornerOf(d.getNormal()).subtract(point.subtract(center)).length();
                if (distance < best) { best = distance; closest = d; }
            }
            if (TableCuts.zone(at, closest).contains(point)) {
                hoveredEdge = closest;
                hoveredPos = at;
                // The marks sit on the face, over the edge between the two blocks; on the top face, at the table's height.
                Vec3 f = Vec3.atLowerCornerOf(face.getNormal()), e = Vec3.atLowerCornerOf(closest.getNormal());
                Vec3 c = center.add(e.scale(0.5)).add(f.scale(0.5 + 1 / 128.0));
                if (face == Direction.UP) c = new Vec3(c.x, at.getY() + Dirt.top(mc.level, at) + 1 / 128.0, c.z);
                corners(pose, buffers, c, face, 3 / 16f);
            }
        }
        pose.popPose();
        buffers.endBatch();
    }

    /** One model pixel (block units): the marks are drawn in whole pixels, like Minecraft textures. */
    private static final float PIXEL = 1 / 16f;

    /**
     * White corner marks of a square (half size h) on a face plane around c: at each corner an L of two pixels by one pixel,
     * inside the square.
     */
    private static void corners(PoseStack pose, MultiBufferSource buffers, Vec3 c, Direction face, float h) {
        // Two axes across the face.
        Direction.Axis[] across = java.util.Arrays.stream(Direction.Axis.values()).filter(a -> a != face.getAxis()).toArray(Direction.Axis[]::new);
        float len = 2 * PIXEL, w = PIXEL;
        for (int su = -1; su <= 1; su += 2) {
            for (int sv = -1; sv <= 1; sv += 2) {
                // Along u, then along v, from the corner inwards.
                for (int k = 0; k < 2; k++) {
                    double[] lo = {c.x, c.y, c.z}, hi = {c.x, c.y, c.z};
                    Direction.Axis along = across[k], other = across[1 - k];
                    int sa = k == 0 ? su : sv, so = k == 0 ? sv : su;
                    double cornerA = sa * h, cornerO = so * h;
                    set(lo, along, Math.min(cornerA, cornerA - sa * len)); set(hi, along, Math.max(cornerA, cornerA - sa * len));
                    set(lo, other, Math.min(cornerO, cornerO - so * w)); set(hi, other, Math.max(cornerO, cornerO - so * w));
                    set(lo, face.getAxis(), -1 / 256.0); set(hi, face.getAxis(), 1 / 256.0);
                    SelectionBox.bar(pose, buffers, 0xFFFFFFFF, (float) lo[0], (float) lo[1], (float) lo[2], (float) hi[0], (float) hi[1], (float) hi[2]);
                }
            }
        }
    }

    private static void set(double[] v, Direction.Axis axis, double offset) {
        int i = axis.ordinal();
        v[i] += offset;
    }

    /** Below the crosshair while a click zone is marked: as Create's crafters, the title and what a click does. */
    private static void renderToolText(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (hoveredPos == null || hoveredEdge == null || mc.screen != null || mc.level == null) return;
        var g = event.getGuiGraphics();
        int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + 20;
        boolean joined = !TableCuts.client().cut(hoveredPos, hoveredEdge);
        g.drawCenteredString(mc.font, Component.translatable("onemorecore.tables.connected"), cx, y, 0xFFFFFFFF);
        g.drawCenteredString(mc.font, Component.translatable(joined ? "onemorecore.tables.separate" : "onemorecore.tables.merge"), cx, y + 11, 0xFFAAAAAA);
    }

    /** The card's text box: at most two characters; Done or Enter sends it. */
    static final class EditScreen extends Screen {
        private final BlockPos pos;
        private final String start;
        private EditBox box;

        EditScreen(BlockPos pos, String text) {
            super(Component.translatable("onemorecore.card.title"));
            this.pos = pos;
            this.start = text;
        }

        @Override
        protected void init() {
            box = new EditBox(font, width / 2 - 20, height / 2 - 10, 40, 20, title);
            box.setMaxLength(TableCards.MAX * Character.charCount(Character.MAX_CODE_POINT));
            box.setFilter(text -> text.codePointCount(0, text.length()) <= TableCards.MAX);
            box.setValue(start);
            addRenderableWidget(box);
            setInitialFocus(box);
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 40, height / 2 + 18, 80, 20).build());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            g.drawCenteredString(font, title, width / 2, height / 2 - 26, 0xFFFFFFFF);
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                onClose();
                return true;
            }
            return super.keyPressed(key, scan, modifiers);
        }

        @Override
        public void onClose() {
            PacketDistributor.sendToServer(new TableCards.Text(pos, TableCards.clean(box.getValue())));
            super.onClose();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
