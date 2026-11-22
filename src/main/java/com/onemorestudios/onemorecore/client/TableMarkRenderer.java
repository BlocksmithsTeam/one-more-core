package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.Dirt;
import com.onemorestudios.onemorecore.TableCards;
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
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The text of the Table Number blocks, in black on both faces of the card (models/block/table_card, from
 * art/table_card_v2.bbmodel); the text box that names a card; and, while the player sneaks, the edges where touching tables
 * are split (TableCuts).
 */
final class TableMarkRenderer {
    private TableMarkRenderer() {}

    /**
     * The upright card of the north-facing model: faces at z 7 and 8; inside its grey border a 6 x 3 pixel field whose middle
     * is at y 4.5. The text keeps a little margin: at most 5.6 wide, digits at most 2.6 high.
     */
    private static final float FRONT = 7f, BACK = 8f, MIDDLE_Y = 4.5f, WIDTH = 5.6f, DIGIT_HEIGHT = 2.6f;
    private static final int BLACK = 0xFF1A1A1A;

    static void register() {
        NeoForge.EVENT_BUS.addListener(TableMarkRenderer::renderCuts);
        TableCards.Edit.handler = e -> Minecraft.getInstance().setScreen(new EditScreen(e.pos(), e.text()));
    }

    /** The text on a Table Number block. */
    static final class Text implements BlockEntityRenderer<TableNumberBlockEntity> {
        Text(BlockEntityRendererProvider.Context context) {}

        @Override
        public void render(TableNumberBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            String text = be.text();
            if (text.isEmpty()) return;
            Font font = Minecraft.getInstance().font;
            pose.pushPose();
            // Turned like the blockstate model (y rotation clockwise from above).
            pose.translate(0.5, 0, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(-be.getBlockState().getValue(TableNumberBlock.FACING).toYRot() + 180));
            pose.translate(-0.5, 0, -0.5);
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

    /** While the player sneaks: a red line on the table top where two touching tables are split. */
    private static void renderCuts(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || mc.level == null || mc.player == null
                || !mc.player.isShiftKeyDown() || TableMarks.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        for (TableMarks marks : TableMarks.CLIENT.values()) {
            for (int axis = 0; axis < 2; axis++) {
                for (BlockPos p : axis == 0 ? marks.cutsEast() : marks.cutsSouth()) {
                    if (p.distToCenterSqr(cam) > 24 * 24) continue;
                    double y = p.getY() + Dirt.top(mc.level, p) + 0.01;
                    AABB edge = axis == 0 ? new AABB(p.getX() + 1, y, p.getZ(), p.getX() + 1, y + 0.02, p.getZ() + 1)
                            : new AABB(p.getX(), y, p.getZ() + 1, p.getX() + 1, y + 0.02, p.getZ() + 1);
                    LevelRenderer.renderLineBox(pose, lines, edge, 0.9f, 0.2f, 0.2f, 1f);
                }
            }
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
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
