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
 * Draws the open sign (models in art/models/open_sign.bbmodel, made in Blockbench): on a floor a chalkboard that turns half
 * round when the restaurant opens or closes and shows the new word from the moment it is edge-on; on a wall an Art Deco blade
 * whose word lights up; under a ceiling a plaque on a rope that turns.
 */
public class OpenSignRenderer implements BlockEntityRenderer<OpenSignBlockEntity> {
    static final ModelResourceLocation PLAQUE_OPEN = model("plaque_open"), PLAQUE_CLOSED = model("plaque_closed"), ROPE = model("rope"),
            FRAME = model("frame"), HOOK = model("hook"), CHALKBOARD = model("chalkboard"), BLADE = model("blade");
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
        for (ModelResourceLocation m : new ModelResourceLocation[] {PLAQUE_OPEN, PLAQUE_CLOSED, ROPE, FRAME, HOOK, CHALKBOARD, BLADE}) event.register(m);
    }

    /** The wall blade reaches into the block above. */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(OpenSignBlockEntity sign) {
        return new net.minecraft.world.phys.AABB(sign.getBlockPos()).expandTowards(0, 1, 0);
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
        // On the floor a chalkboard in a wooden frame, written on both sides in chalk: it turns about its middle. On a wall an Art Deco
        // blade with the word down both its faces in lights: lit gold when open, dim red when closed. Under a ceiling a plaque on a
        // rope.
        if (face == AttachFace.FLOOR) {
            chalkboard(font, pose, buffers, light, overlay, turn, showOpen);
            pose.popPose();
            return;
        }
        if (face == AttachFace.WALL) {
            blade(font, pose, buffers, light, overlay, showOpen);
            pose.popPose();
            return;
        }
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

    /** The chalkboard turns half round about its middle; each side has the word in chalk. */
    private static void chalkboard(Font font, PoseStack pose, MultiBufferSource buffers, int light, int overlay, float turn, boolean showOpen) {
        float eased = turn * turn * (3 - 2 * turn);
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180 * eased));
        pose.translate(-0.5, 0, -0.5);
        part(pose, buffers, CHALKBOARD, light, overlay);
        Component word = Component.translatable(showOpen ? "onemorecore.sign.open" : "onemorecore.sign.closed");
        float scale = 12f / 16 / Math.max(font.width(Component.translatable("onemorecore.sign.open")), font.width(Component.translatable("onemorecore.sign.closed")));
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            pose.translate(0.5, 7.5 / 16, 0.5);
            if (side == 1) pose.mulPose(Axis.YP.rotationDegrees(180));
            // The chalk faces are at z 9 (front) and 6 (back): 1.5 px out from the middle.
            pose.translate(0, 0, 1.5 / 16 + 0.002f);
            pose.scale(scale, -scale, scale);
            font.drawInBatch(word, -font.width(word) / 2f, -4, showOpen ? 0xFFF2F2E6 : 0xFFF3B2A8, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
            pose.popPose();
        }
    }

    /** The blade stands out of the wall; its word runs down both faces, a letter under the other. */
    private static void blade(Font font, PoseStack pose, MultiBufferSource buffers, int light, int overlay, boolean showOpen) {
        part(pose, buffers, BLADE, light, overlay);
        String word = Component.translatable(showOpen ? "onemorecore.sign.open" : "onemorecore.sign.closed").getString();
        int color = showOpen ? 0xFFFFD166 : 0xFF8A3A30;
        int textLight = showOpen ? net.minecraft.client.renderer.LightTexture.FULL_BRIGHT : light;
        // The blade's faces: x 7 and x 9; its letters fill y 4..28 and z 3..11.
        int n = Math.max(1, word.length());
        float room = 24f / 16, step = room / n, scale = Math.min(step / 8.5f, 7f / 16 / Math.max(1, font.width("W")));
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            pose.translate(side == 0 ? 9 / 16f + 0.002f : 7 / 16f - 0.002f, 0, 7 / 16f);
            pose.mulPose(Axis.YP.rotationDegrees(side == 0 ? 90 : -90));
            for (int i = 0; i < n; i++) {
                String letter = word.substring(i, i + 1);
                pose.pushPose();
                pose.translate(0, 28f / 16 - step * (i + 0.5f), 0);
                pose.scale(scale, -scale, scale);
                font.drawInBatch(letter, -font.width(letter) / 2f, -4, color, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, textLight);
                pose.popPose();
            }
            pose.popPose();
        }
    }

    private static void part(PoseStack pose, MultiBufferSource buffers, ModelResourceLocation id, int light, int overlay) {
        var model = Minecraft.getInstance().getModelManager().getModel(id);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null,
                model, 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
    }

    /** The sign item: the chalkboard, OPEN. */
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
