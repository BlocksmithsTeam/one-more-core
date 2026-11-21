package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlock;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
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
    static final ModelResourceLocation PLAQUE_OPEN = model("plaque_open"), PLAQUE_CLOSED = model("plaque_closed"), ROPE = model("rope"),
            FRAME = model("frame"), HOOK = model("hook");
    /** Ticks of one half turn. */
    static final float TURN_TICKS = 12;
    /** Plaque width that the longest word may use, in pixels. */
    private static final float TEXT_WIDTH = 11.5f;

    private final Font font;

    public OpenSignRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/open_sign/" + name));
    }

    static void registerModels(ModelEvent.RegisterAdditional event) {
        for (ModelResourceLocation m : new ModelResourceLocation[] {PLAQUE_OPEN, PLAQUE_CLOSED, ROPE, FRAME, HOOK}) event.register(m);
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
        // A plaque on a rope from a nail: in a wooden frame on the floor, from the ceiling, or from a hook in the wall. The plaque's
        // centre in pixels, and the axis that it turns round (on a wall, round its long axis, so that it does not hit the wall).
        float y = 9.25f, z = 8;
        Axis axis = Axis.YP;
        boolean ropeTurns = true;
        switch (face) {
            case FLOOR -> part(pose, buffers, FRAME, light, overlay);
            case CEILING -> y = 7.25f;
            case WALL -> { part(pose, buffers, HOOK, light, overlay); y = 7.75f; z = 4.5f; axis = Axis.XP; ropeTurns = false; }
        }
        pose.translate(0.5, y / 16, z / 16);
        if (!ropeTurns) {
            pose.pushPose();
            pose.translate(-0.5, -0.5, -0.5);
            part(pose, buffers, ROPE, light, overlay);
            pose.popPose();
        }
        float eased = turn * turn * (3 - 2 * turn);
        pose.mulPose(axis.rotationDegrees(180 * eased));
        pose.pushPose();
        pose.translate(-0.5, -0.5, -0.5);
        part(pose, buffers, showOpen ? PLAQUE_OPEN : PLAQUE_CLOSED, light, overlay);
        if (ropeTurns) part(pose, buffers, ROPE, light, overlay);
        pose.popPose();
        Component word = Component.translatable(showOpen ? "onemorecore.sign.open" : "onemorecore.sign.closed");
        // One text size for both words: the longer word fills TEXT_WIDTH pixels.
        float scale = TEXT_WIDTH / 16 / Math.max(font.width(Component.translatable("onemorecore.sign.open")),
                font.width(Component.translatable("onemorecore.sign.closed")));
        int color = 0xFFFFFFFF;
        int textLight = light;
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            // The back text turns round the same axis as the board, so that it is upright after the half turn.
            if (side == 1) pose.mulPose(axis.rotationDegrees(180));
            // The plaque is 1 px thick; the text lies just in front of its face.
            pose.translate(0, 0, 0.5f / 16 + 0.002f);
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

    /** The sign item: the plaque in its floor frame, OPEN. */
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
