package com.onemorestudios.onemoreclient.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemoreclient.OneMoreClient;
import com.onemorestudios.onemoreclient.OpenSignBlock;
import com.onemorestudios.onemoreclient.OpenSignBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the open sign: a post, two rods out of a wall or two chains, and a board with OPEN or CLOSED on both faces. When
 * the restaurant opens or closes, the board turns half a turn and shows the new word from the moment it is edge-on to
 * the viewer. A floor or hanging board turns round its vertical axis; a wall board turns round the rods, so that it does
 * not hit the wall.
 */
public class OpenSignRenderer implements BlockEntityRenderer<OpenSignBlockEntity> {
    static final ModelResourceLocation BOARD = model("board"), POST = model("post"), CHAINS = model("chains"), ARM = model("arm");
    /** Ticks of one half turn. */
    static final float TURN_TICKS = 12;
    /** Board width that the longest word may use, in pixels. */
    private static final float TEXT_WIDTH = 13;

    private final Font font;

    public OpenSignRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "block/open_sign/" + name));
    }

    static void registerModels(ModelEvent.RegisterAdditional event) {
        for (ModelResourceLocation m : new ModelResourceLocation[] {BOARD, POST, CHAINS, ARM}) event.register(m);
    }

    @Override
    public void render(OpenSignBlockEntity sign, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = sign.getBlockState();
        boolean open = state.getValue(OpenSignBlock.OPEN);
        double now = sign.getLevel() == null ? 0 : sign.getLevel().getGameTime() + partialTick;
        // The first frame shows the state as it is; a later change starts a turn.
        if (!sign.seen) {
            sign.seen = true;
            sign.target = open;
        } else if (sign.target != open) {
            sign.target = open;
            sign.turnStart = now;
        }
        float t = (float) Mth.clamp((now - sign.turnStart) / TURN_TICKS, 0, 1);
        draw(font, pose, buffers, light, overlay, state.getValue(OpenSignBlock.FACE), state.getValue(OpenSignBlock.ROTATION), t,
                t < 0.5f ? !sign.target : sign.target);
    }

    /**
     * @param turn 0 to 1, the part of the half turn that is done
     * @param showOpen the word on the board
     */
    static void draw(Font font, PoseStack pose, MultiBufferSource buffers, int light, int overlay, AttachFace face, int rotation, float turn, boolean showOpen) {
        pose.pushPose();
        // Like a vanilla sign: rotation 0 faces south, the model's front is +z.
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-RotationSegment.convertToDegrees(rotation)));
        pose.translate(-0.5, 0, -0.5);
        // The board centre in pixels, and the axis that the board turns round.
        float y = 12, z = 8;
        Axis axis = Axis.YP;
        switch (face) {
            case FLOOR -> part(pose, buffers, POST, light, overlay);
            case CEILING -> { part(pose, buffers, CHAINS, light, overlay); y = 6; }
            case WALL -> { part(pose, buffers, ARM, light, overlay); y = 8; z = 5; axis = Axis.XP; }
        }
        pose.translate(0.5, y / 16, z / 16);
        float eased = turn * turn * (3 - 2 * turn);
        pose.mulPose(axis.rotationDegrees(180 * eased));
        pose.pushPose();
        pose.translate(-0.5, -0.5, -0.5);
        part(pose, buffers, BOARD, light, overlay);
        pose.popPose();
        Component word = Component.translatable(showOpen ? "onemoreclient.sign.open" : "onemoreclient.sign.closed");
        // One text size for both words: the longer word fills TEXT_WIDTH pixels.
        float scale = TEXT_WIDTH / 16 / Math.max(font.width(Component.translatable("onemoreclient.sign.open")),
                font.width(Component.translatable("onemoreclient.sign.closed")));
        int color = showOpen ? 0xFF6BE070 : 0xFFE85A50;
        int textLight = showOpen ? LightTexture.FULL_BRIGHT : light;
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            // The back text turns round the same axis as the board, so that it is upright after the half turn.
            if (side == 1) pose.mulPose(axis.rotationDegrees(180));
            // The board is 2 px thick; the text lies just in front of its face.
            pose.translate(0, 0, 1 / 16f + 0.002f);
            pose.scale(scale, -scale, scale);
            font.drawInBatch(word, -font.width(word) / 2f, -4, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, textLight);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void part(PoseStack pose, MultiBufferSource buffers, ModelResourceLocation id, int light, int overlay) {
        var model = Minecraft.getInstance().getModelManager().getModel(id);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null,
                model, 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
    }

    /** The sign item: a sign on a post that shows OPEN. */
    static class Item extends BlockEntityWithoutLevelRenderer {
        Item(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
            super(dispatcher, models);
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            draw(Minecraft.getInstance().font, pose, buffers, light, overlay, AttachFace.FLOOR, 0, 1, true);
        }
    }
}
