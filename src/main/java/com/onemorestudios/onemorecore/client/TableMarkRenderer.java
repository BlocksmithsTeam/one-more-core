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

    /** The table edge that a click with the tool would split or join now (client), or null. */
    private static Direction hoveredEdge;
    private static java.util.Set<BlockPos> hoveredTable = java.util.Set.of();
    private static BlockPos hoveredPos;

    /**
     * With a screwdriver or a wrench in the hand, pointing at a table: the outline of the table on its top (green), the edges
     * where it is split from a touching table (red), and the edge a click would change (white), like Create's crafters.
     */
    private static void renderTool(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        hoveredEdge = null;
        hoveredTable = java.util.Set.of();
        hoveredPos = null;
        if (mc.level == null || mc.player == null || !mc.player.getMainHandItem().is(OneMoreCore.TABLE_TOOLS)
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !mc.level.getBlockState(hit.getBlockPos()).is(OneMoreCore.SURFACES)) return;
        BlockPos at = hit.getBlockPos();
        java.util.Set<BlockPos> table = Dirt.tableOf(mc.level, at);
        TableCuts cuts = TableCuts.client();
        hoveredTable = table;
        hoveredPos = at;
        hoveredEdge = TableCuts.edge(mc.level, at, hit);
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        float t = 0.03f;
        for (BlockPos p : table) {
            float y = (float) (p.getY() + Dirt.top(mc.level, p)) + 0.01f;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                boolean hovered = p.equals(at) && d == hoveredEdge;
                if (table.contains(n) && !hovered) continue;
                boolean split = !table.contains(n) && mc.level.getBlockState(n).is(OneMoreCore.SURFACES) && cuts.cut(p, d);
                int argb = hovered ? 0xFFFFFFFF : split ? 0xF0E04545 : 0xF04DE066;
                float w = hovered ? t * 1.6f : t;
                // The edge on the side d of the block's top.
                float x0 = p.getX(), z0 = p.getZ(), x1 = x0 + 1, z1 = z0 + 1;
                switch (d) {
                    case NORTH -> SelectionBox.bar(pose, buffers, argb, x0 - w, y - w, z0 - w, x1 + w, y + w, z0 + w);
                    case SOUTH -> SelectionBox.bar(pose, buffers, argb, x0 - w, y - w, z1 - w, x1 + w, y + w, z1 + w);
                    case WEST -> SelectionBox.bar(pose, buffers, argb, x0 - w, y - w, z0 - w, x0 + w, y + w, z1 + w);
                    default -> SelectionBox.bar(pose, buffers, argb, x1 - w, y - w, z0 - w, x1 + w, y + w, z1 + w);
                }
            }
        }
        pose.popPose();
        buffers.endBatch();
    }

    /** Under the crosshair, with the tool on a table: its size, and what a click does. */
    private static void renderToolText(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (hoveredPos == null || mc.screen != null || mc.level == null) return;
        var g = event.getGuiGraphics();
        int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + 14;
        g.drawCenteredString(mc.font, Component.translatable("onemorecore.tables.size", hoveredTable.size()), cx, y, 0xFFFFFFFF);
        Component hint;
        if (hoveredEdge == null) hint = Component.translatable("onemorecore.tables.point");
        else hint = Component.translatable(TableCuts.client().cut(hoveredPos, hoveredEdge) ? "onemorecore.tables.join" : "onemorecore.tables.split");
        g.drawCenteredString(mc.font, hint, cx, y + 11, 0xFFB8F5C2);
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
