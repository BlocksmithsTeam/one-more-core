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
 * of MenuBoardBlockEntity order; none set: all), the size (bits 7-8: normal, small, large) and the scroll (bit 9 set: none, the
 * start stays; clear: what does not fit scrolls by itself, down, or sideways when bit 10 is set). Sneak-click a screen with an
 * empty hand to set it. A Screen (ScreenBlock) has two sides: bits 0-10 are its front; when bit 26 is set, bits 16-25 and bit 27
 * are its back (else the back shows what the front shows).
 */
public final class ScreenSettings {
    private ScreenSettings() {}

    public static final String[] KINDS = {"main", "starter", "drink", "dessert", "combo"};
    /** Text size by the size setting. */
    public static final float[] ZOOM = {1f, 0.75f, 1.35f};

    /** The scroll settings: none (the start stays), down, sideways. */
    public static final int SCROLL_OFF = 0, SCROLL_DOWN = 1, SCROLL_SIDEWAYS = 2;
    /** The bits of one side's settings. */
    public static final int FACE = 0x7FF;

    public static int pack(int mode, int kinds, int size, int scroll) {
        return (mode & 3) | (kinds & 31) << 2 | (size & 3) << 7 | (scroll == SCROLL_OFF ? 1 : 0) << 9 | (scroll == SCROLL_SIDEWAYS ? 1 : 0) << 10;
    }

    /** True: scrolls down by itself; false: no scroll. */
    public static int pack(int mode, int kinds, int size, boolean autoScroll) {
        return pack(mode, kinds, size, autoScroll ? SCROLL_DOWN : SCROLL_OFF);
    }

    public static int mode(int v) { return Math.min(2, v & 3); }

    public static int kinds(int v) { return v >> 2 & 31; }

    public static int size(int v) { return Math.min(2, v >> 7 & 3); }

    /** SCROLL_OFF, SCROLL_DOWN or SCROLL_SIDEWAYS (saves from before the scroll had "pages turn by themselves": down). */
    public static int scroll(int v) { return (v >> 9 & 1) == 1 ? SCROLL_OFF : (v >> 10 & 1) == 1 ? SCROLL_SIDEWAYS : SCROLL_DOWN; }

    public static int withMode(int v, int mode) { return pack(mode, kinds(v), size(v), scroll(v)); }

    private static int clean(int face) {
        return pack(mode(face), kinds(face), size(face), scroll(face));
    }

    /** A value from a save or a packet, cut to the known fields. */
    public static int valid(int v) {
        int front = clean(v);
        if ((v >> 26 & 1) == 0) return front;
        return both(front, clean(face(v, 1)));
    }

    /** The settings of one side (0: front, 1: back) of a screen. */
    public static int face(int v, int side) {
        return side == 0 || (v >> 26 & 1) == 0 ? v & FACE : (v >>> 16 & 0x3FF) | (v >>> 27 & 1) << 10;
    }

    /** Both sides packed: the back's bits 0-9 at 16-25 and its bit 10 at 27, and bit 26 set. */
    private static int both(int front, int back) {
        return front & FACE | (back & 0x3FF) << 16 | 1 << 26 | (back >> 10 & 1) << 27;
    }

    /** The settings with one side changed; the other side keeps what it shows. */
    public static int withFace(int v, int side, int settings) {
        settings &= FACE;
        return both(side == 0 ? settings : face(v, 0), side == 1 ? settings : face(v, 1));
    }

    /** Whether no side of the screen shows anything. */
    public static boolean allOff(int v) {
        return mode(face(v, 0)) == 0 && mode(face(v, 1)) == 0;
    }

    /** Whether the menu shows a kind (KINDS order). */
    public static boolean showsKind(int v, String kind) {
        int k = kinds(v);
        int i = java.util.Arrays.asList(KINDS).indexOf(kind);
        return k == 0 || i >= 0 && (k >> i & 1) == 1;
    }

    /**
     * Server to client: open the settings of a screen at the side the player looks at. settings: both sides (see the class);
     * tilt -1 when the screen cannot tilt; landscape -1 when it cannot turn wide (else 0 or 1); sides 1 or 2.
     */
    public record Open(BlockPos pos, int settings, int tilt, int side, int landscape, int sides) implements CustomPacketPayload {
        public Open(BlockPos pos, int settings, int tilt) {
            this(pos, settings, tilt, 0, -1, 1);
        }

        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_settings_open"));
        static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.of((b, o) -> {
            BlockPos.STREAM_CODEC.encode(b, o.pos);
            net.minecraft.network.VarInt.write(b, o.settings);
            net.minecraft.network.VarInt.write(b, o.tilt + 1);
            b.writeByte(o.side);
            b.writeByte(o.landscape + 1);
            b.writeByte(o.sides);
        }, b -> new Open(BlockPos.STREAM_CODEC.decode(b), net.minecraft.network.VarInt.read(b), net.minecraft.network.VarInt.read(b) - 1, b.readByte(), b.readByte() - 1, b.readByte()));
        public static java.util.function.Consumer<Open> handler = o -> {};

        @Override
        public Type<Open> type() { return TYPE; }
    }

    /**
     * Client to server: new settings of one side of a screen (-1: unchanged), its tilt (-1: unchanged), landscape (-1: unchanged,
     * 0 upright, 1 wide), or only the mode of the side (-1: unchanged; from the tabs on the picture).
     */
    public record Save(BlockPos pos, int settings, int tilt, int side, int landscape, int mode) implements CustomPacketPayload {
        public Save(BlockPos pos, int settings, int tilt) {
            this(pos, settings, tilt, 0, -1, -1);
        }

        public static final Type<Save> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_settings_save"));
        static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.of((b, s) -> {
            BlockPos.STREAM_CODEC.encode(b, s.pos);
            net.minecraft.network.VarInt.write(b, s.settings + 1);
            net.minecraft.network.VarInt.write(b, s.tilt + 1);
            b.writeByte(s.side);
            b.writeByte(s.landscape + 1);
            b.writeByte(s.mode + 1);
        }, b -> new Save(BlockPos.STREAM_CODEC.decode(b), net.minecraft.network.VarInt.read(b) - 1, net.minecraft.network.VarInt.read(b) - 1, b.readByte(), b.readByte() - 1, b.readByte() - 1));

        static void handle(Save s, ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            if (!level.isLoaded(s.pos) || player.distanceToSqr(Vec3.atCenterOf(s.pos)) > 10 * 10 || !level.mayInteract(player, s.pos)) return;
            BlockState state = level.getBlockState(s.pos);
            if (ScreenRect.of(state) == null || s.side < 0 || s.side >= ScreenPicture.sides(state)) return;
            Restaurant r = owner(level, s.pos);
            if (r == null) return;
            ScreenGroups.Group group = ScreenGroups.of(level, s.pos);
            BlockPos anchor = group.anchor();
            int now = r.screens.getOrDefault(anchor, pack(((MenuBoardBlock) state.getBlock()).defaultMode(), 0, 0, true));
            int face = face(now, s.side);
            if (s.settings >= 0) face = valid(s.settings) & FACE;
            if (s.mode >= 0 && s.mode <= 2) face = withMode(face, s.mode);
            if (s.settings >= 0 || s.mode >= 0) r.setScreen(anchor, ScreenPicture.sides(state) > 1 ? withFace(now, s.side, face) : face);
            if (s.tilt >= 0 && s.tilt <= 2 && MenuBoardBlock.canTilt(state)) {
                for (BlockPos member : group.members()) {
                    BlockState m = level.getBlockState(member);
                    if (m.getBlock() instanceof MenuBoardBlock) level.setBlockAndUpdate(member, m.setValue(MenuBoardBlock.TILT, s.tilt));
                }
                if (state.getBlock() instanceof ScreenBlock) for (BlockPos member : group.members()) ScreenBlock.refreshAround(level, member);
            }
            if (s.landscape >= 0 && state.getBlock() instanceof ScreenBlock)
                for (BlockPos member : group.members())
                    if (!ScreenBlock.setLandscape(level, member, s.landscape == 1))
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("onemorecore.screen.no_room"), true);
        }

        @Override
        public Type<Save> type() { return TYPE; }
    }

    /**
     * The restaurant of a screen: the one that knows it, else the one whose computer range holds it. A billboard stands out on
     * the street: it also joins the nearest restaurant whose area is within the computer range.
     */
    static Restaurant owner(ServerLevel level, BlockPos pos) {
        Restaurant r = Restaurants.get(level).withScreen(pos);
        if (r == null) r = Restaurants.get(level).forComputer(pos);
        if (r == null && level.getBlockState(pos).getBlock() instanceof ScreenBlock) r = Restaurants.get(level).nearArea(pos, Restaurants.COMPUTER_RANGE);
        return r;
    }

    /**
     * Sneak-click with an empty hand (or a screwdriver) on a screen or its part: its settings open at the side the player looks
     * at. A click with a screwdriver on a Screen changes that side: off, menu, orders. True when it was a screen.
     */
    static boolean onUse(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND) return false;
        boolean tool = e.getItemStack().is(OneMoreCore.TABLE_TOOLS);
        if (!tool && !(e.getItemStack().isEmpty() && e.getEntity().isShiftKeyDown())) return false;
        BlockPos pos = ScreenGroups.main(e.getLevel(), e.getPos());
        ScreenGroups.Group group = ScreenGroups.of(e.getLevel(), pos);
        if (group == null) return false;
        BlockPos clicked = pos;
        pos = group.anchor();
        BlockState state = e.getLevel().getBlockState(pos);
        if (tool && !e.getEntity().isShiftKeyDown() && !(state.getBlock() instanceof ScreenBlock)) return false;
        int side = ScreenPicture.sideFacing(pos, state, group.rect(), e.getEntity().getEyePosition());
        if (e.getLevel() instanceof ServerLevel level && e.getEntity() instanceof ServerPlayer player) {
            Restaurant r = owner(level, pos);
            if (r == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("onemorecore.screen.no_restaurant"), true);
            } else {
                int v = r.screens.getOrDefault(pos, pack(state.getBlock() instanceof MenuBoardBlock b ? b.defaultMode() : Restaurant.SCREEN_MENU, 0, 0, true));
                if (tool && !player.isShiftKeyDown()) {
                    // The screwdriver: the side the player looks at shows the next thing.
                    int face = face(v, side), mode = (mode(face) + 1) % 3;
                    r.setScreen(pos, withFace(v, side, withMode(face, mode)));
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("onemorecore.screen.side_" + side + "_" + mode), true);
                    level.playSound(null, clicked, net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), net.minecraft.sounds.SoundSource.BLOCKS, 0.4f, 1.6f);
                } else {
                    PacketDistributor.sendToPlayer(player, new Open(clicked, v, MenuBoardBlock.canTilt(state) ? state.getValue(MenuBoardBlock.TILT) : -1, side,
                            state.getBlock() instanceof ScreenBlock ? (state.getValue(ScreenBlock.LANDSCAPE) ? 1 : 0) : -1, ScreenPicture.sides(state)));
                }
            }
        }
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }
}
