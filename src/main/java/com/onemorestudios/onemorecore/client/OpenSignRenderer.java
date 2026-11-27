package com.onemorestudios.onemorecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlock;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/** A one-block screen with an animated atlas picture; floor and ceiling screens turn half round. */
public class OpenSignRenderer implements BlockEntityRenderer<OpenSignBlockEntity> {
    static final ModelResourceLocation FLOOR = model("screen_floor"), WALL = model("screen_wall"), CEILING = model("screen_ceiling");
    static final float TURN_TICKS = 12;
    private static final ResourceLocation OPEN = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/sign_screen_open"),
            CLOSED = ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/sign_screen_closed");

    public OpenSignRenderer(BlockEntityRendererProvider.Context context) {}

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "block/open_sign/" + name));
    }

    static void registerModels(ModelEvent.RegisterAdditional event) {
        for (ModelResourceLocation m : new ModelResourceLocation[] {FLOOR, WALL, CEILING}) event.register(m);
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
        AttachFace face = state.getValue(OpenSignBlock.FACE);
        draw(pose, buffers, light, overlay, face, state.getValue(OpenSignBlock.ROTATION), t,
                face == AttachFace.WALL || t >= 0.5f ? sign.target : !sign.target);
    }

    static void draw(PoseStack pose, MultiBufferSource buffers, int light, int overlay, AttachFace face, int rotation, float turn, boolean showOpen) {
        pose.pushPose();
        // Rotation 0 faces south; both the model and the picture turn about the block centre.
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-RotationSegment.convertToDegrees(rotation)));
        if (face != AttachFace.WALL) pose.mulPose(Axis.YP.rotationDegrees(180 * turn * turn * (3 - 2 * turn)));
        pose.translate(-0.5, 0, -0.5);
        part(pose, buffers, switch (face) { case FLOOR -> FLOOR; case WALL -> WALL; case CEILING -> CEILING; }, light, overlay);
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(showOpen ? OPEN : CLOSED);
        float bottom = (face == AttachFace.CEILING ? 2 : 4) / 16f;
        for (int side = 0; side < (face == AttachFace.WALL ? 1 : 2); side++) {
            pose.pushPose();
            // Turning the back face preserves upright, readable UVs instead of mirroring the picture.
            if (side == 1) {
                pose.translate(0.5, 0, 0.5);
                pose.mulPose(Axis.YP.rotationDegrees(180));
                pose.translate(-0.5, 0, -0.5);
            }
            float z = ((face == AttachFace.WALL ? 5 : 9) + 0.01f) / 16f;
            var out = buffers.getBuffer(RenderType.text(TextureAtlas.LOCATION_BLOCKS));
            var matrix = pose.last().pose();
            out.addVertex(matrix, 1 / 16f, bottom, z).setColor(-1).setUv(sprite.getU0(), sprite.getV1()).setLight(LightTexture.FULL_BRIGHT);
            out.addVertex(matrix, 15 / 16f, bottom, z).setColor(-1).setUv(sprite.getU1(), sprite.getV1()).setLight(LightTexture.FULL_BRIGHT);
            out.addVertex(matrix, 15 / 16f, bottom + 10 / 16f, z).setColor(-1).setUv(sprite.getU1(), sprite.getV0()).setLight(LightTexture.FULL_BRIGHT);
            out.addVertex(matrix, 1 / 16f, bottom + 10 / 16f, z).setColor(-1).setUv(sprite.getU0(), sprite.getV0()).setLight(LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        pose.popPose();
    }

    private static void part(PoseStack pose, MultiBufferSource buffers, ModelResourceLocation id, int light, int overlay) {
        var model = Minecraft.getInstance().getModelManager().getModel(id);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()), null,
                model, 1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
    }

    /** The sign item: the floor screen, OPEN. */
    static class Item extends BlockEntityWithoutLevelRenderer {
        Item(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
            super(dispatcher, models);
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            draw(pose, buffers, light, overlay, AttachFace.FLOOR, 0, 1, true);
        }
    }
}
