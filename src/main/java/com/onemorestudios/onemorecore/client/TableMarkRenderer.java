package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.Dirt;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TableCards;
import com.onemorestudios.onemorecore.TableMarks;

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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The table number cards on the tables (the card model, models/block/table_card) with their text in grey on both faces of the
 * upright card; and the text box that names a card.
 */
final class TableMarkRenderer {
    private TableMarkRenderer() {}

    static final ModelResourceLocation CARD = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/table_card"));
    /**
     * The upright card (models/block/table_card, from art/table_card_v2.bbmodel): faces at z 7 and 8; inside its grey border
     * a 6 x 4 pixel field whose middle is at y 5. The text keeps a little margin: at most 5.6 wide, digits at most 3.6 high.
     */
    private static final float FRONT = 7f, BACK = 8f, MIDDLE_Y = 5f, WIDTH = 5.6f, DIGIT_HEIGHT = 3.6f;
    private static final int GREY = 0xFF9EA1A6;

    static void register() {
        NeoForge.EVENT_BUS.addListener(TableMarkRenderer::render);
        TableCards.Edit.handler = e -> Minecraft.getInstance().setScreen(new EditScreen(e.surface(), e.text()));
    }

    static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(CARD);
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || mc.level == null || TableMarks.CLIENT.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        var model = mc.getModelManager().getModel(CARD);
        var renderer = mc.getBlockRenderer().getModelRenderer();
        for (TableMarks marks : TableMarks.CLIENT.values()) {
            for (int i = 0; i < marks.marks().size(); i++) {
                BlockPos pos = marks.marks().get(i);
                if (pos.distToCenterSqr(cam) > 32 * 32 || !mc.level.getBlockState(pos).is(OneMoreCore.SURFACES)) continue;
                int light = LevelRenderer.getLightColor(mc.level, pos.above());
                float x = marks.places().get(3 * i), z = marks.places().get(3 * i + 1), yaw = marks.places().get(3 * i + 2);
                pose.pushPose();
                pose.translate(pos.getX() + x - cam.x, pos.getY() + Dirt.top(mc.level, pos) - cam.y, pos.getZ() + z - cam.z);
                // The card's front (-z of the model) faces the player who put it down.
                pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
                pose.translate(-0.5, 0, -0.5);
                renderer.renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null, model, 1, 1, 1, light,
                        OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.cutout());
                String text = marks.texts().get(i);
                if (!text.isEmpty()) {
                    float s = Math.min(DIGIT_HEIGHT / 16 / 7, WIDTH / 16 / Math.max(1, font.width(text)));
                    for (int side = 0; side < 2; side++) {
                        pose.pushPose();
                        pose.translate(0.5, MIDDLE_Y / 16, side == 0 ? FRONT / 16 - 0.002 : BACK / 16 + 0.002);
                        if (side == 0) pose.mulPose(Axis.YP.rotationDegrees(180));
                        pose.scale(s, -s, s);
                        font.drawInBatch(text, -font.width(text) / 2f, -3.5f, GREY, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
                        pose.popPose();
                    }
                }
                pose.popPose();
            }
        }
        buffers.endBatch();
    }

    /** The card's text box: at most two characters; Done or Enter sends it. */
    static final class EditScreen extends Screen {
        private final BlockPos surface;
        private final String start;
        private EditBox box;

        EditScreen(BlockPos surface, String text) {
            super(Component.translatable("onemorecore.card.title"));
            this.surface = surface;
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
            PacketDistributor.sendToServer(new TableCards.Text(surface, TableCards.clean(box.getValue())));
            super.onClose();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
