package com.onemorestudios.onemorecore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What a screen shows, packed in an int: the mode (bits 0-1: off, menu, orders), the kinds of the menu (bits 2-6, one per kind
 * of MenuBoardBlockEntity order; none set: all), the size (bits 7-8: normal, small, large) and paging (bit 9 set: the first page
 * only; clear: the pages change every 6 seconds). Sneak-click a screen with an empty hand to set it.
 */
public final class ScreenSettings {
    private ScreenSettings() {}

    public static final String[] KINDS = {"main", "starter", "drink", "dessert", "combo"};
    /** Text size by the size setting. */
    public static final float[] ZOOM = {1f, 0.75f, 1.35f};

    public static int pack(int mode, int kinds, int size, boolean autoPages) {
        return (mode & 3) | (kinds & 31) << 2 | (size & 3) << 7 | (autoPages ? 0 : 1) << 9;
    }

    public static int mode(int v) { return Math.min(2, v & 3); }

    public static int kinds(int v) { return v >> 2 & 31; }

    public static int size(int v) { return Math.min(2, v >> 7 & 3); }

    public static boolean autoPages(int v) { return (v >> 9 & 1) == 0; }

    public static int withMode(int v, int mode) { return pack(mode, kinds(v), size(v), autoPages(v)); }

    /** A value from a save or a packet, cut to the known fields. */
    public static int valid(int v) { return pack(mode(v), kinds(v), size(v), autoPages(v)); }

    /** Whether the menu shows a kind (KINDS order). */
    public static boolean showsKind(int v, String kind) {
        int k = kinds(v);
        int i = java.util.Arrays.asList(KINDS).indexOf(kind);
        return k == 0 || i >= 0 && (k >> i & 1) == 1;
    }

    /** Server to client: open the settings of a screen; tilt -1 when the screen cannot tilt. */
    public record Open(BlockPos pos, int settings, int tilt) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_settings_open"));
        static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Open::pos, ByteBufCodecs.VAR_INT, Open::settings,
                ByteBufCodecs.VAR_INT, Open::tilt, Open::new);
        public static java.util.function.Consumer<Open> handler = o -> {};

        @Override
        public Type<Open> type() { return TYPE; }
    }

    /** Client to server: the new settings (and tilt, -1: unchanged) of a screen. */
    public record Save(BlockPos pos, int settings, int tilt) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_settings_save"));
        static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Save::pos, ByteBufCodecs.VAR_INT, Save::settings,
                ByteBufCodecs.VAR_INT, Save::tilt, Save::new);

        static void handle(Save s, ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            if (!level.isLoaded(s.pos) || player.distanceToSqr(Vec3.atCenterOf(s.pos)) > 10 * 10) return;
            BlockState state = level.getBlockState(s.pos);
            if (ScreenRect.of(state) == null) return;
            Restaurant r = owner(level, s.pos);
            if (r == null) return;
            ScreenGroups.Group group = ScreenGroups.of(level, s.pos);
            r.setScreen(group.anchor(), valid(s.settings));
            if (s.tilt >= 0 && s.tilt <= 2 && state.hasProperty(MenuBoardBlock.TILT)) {
                for (BlockPos member : group.members()) level.setBlockAndUpdate(member, level.getBlockState(member).setValue(MenuBoardBlock.TILT, s.tilt));
            }
        }

        @Override
        public Type<Save> type() { return TYPE; }
    }

    /** The restaurant of a screen: the one that knows it, else the one whose computer range holds it. */
    static Restaurant owner(ServerLevel level, BlockPos pos) {
        Restaurant r = Restaurants.get(level).withScreen(pos);
        return r != null ? r : Restaurants.get(level).forComputer(pos);
    }

    /** Sneak-click with an empty hand on a screen (or a part of a TV): its settings open. True when it was a screen. */
    static boolean onUse(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !e.getItemStack().isEmpty() || !e.getEntity().isShiftKeyDown()) return false;
        BlockPos pos = ScreenGroups.main(e.getLevel(), e.getPos());
        ScreenGroups.Group group = ScreenGroups.of(e.getLevel(), pos);
        if (group == null) return false;
        BlockPos clicked = pos;
        pos = group.anchor();
        BlockState state = e.getLevel().getBlockState(pos);
        if (e.getLevel() instanceof ServerLevel level && e.getEntity() instanceof ServerPlayer player) {
            Restaurant r = owner(level, pos);
            if (r == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("onemorecore.screen.no_restaurant"), true);
            } else {
                int v = r.screens.getOrDefault(pos, pack(state.getBlock() instanceof OrderBoardBlock ? Restaurant.SCREEN_ORDERS : Restaurant.SCREEN_MENU, 0, 0, true));
                PacketDistributor.sendToPlayer(player, new Open(clicked, v, state.hasProperty(MenuBoardBlock.TILT) ? state.getValue(MenuBoardBlock.TILT) : -1));
            }
        }
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }
}
