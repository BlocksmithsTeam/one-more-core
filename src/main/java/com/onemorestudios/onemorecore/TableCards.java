package com.onemorestudios.onemorecore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Table number cards (TableNumberBlock): a player puts one on a table like a block and types its text (at most 2 characters,
 * "7", "A", "12"). The text names the table on the order screens and the tickets. Click the card with an empty hand to change
 * the text; break it to take it back.
 */
public final class TableCards {
    private TableCards() {}

    /** At most this many characters. */
    public static final int MAX = 2;
    /** A card on a surface: its text, its place on the block (0..1) and the way it faces (degrees, as an entity's yaw). */
    public record Card(String text, float x, float z, float yaw) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("text", text);
            t.putFloat("x", x);
            t.putFloat("z", z);
            t.putFloat("yaw", yaw);
            return t;
        }

        static Card load(CompoundTag t) {
            return new Card(clean(t.getString("text")), clamp(t.getFloat("x")), clamp(t.getFloat("z")), t.getFloat("yaw"));
        }
    }

    static float clamp(float v) {
        return Float.isFinite(v) ? Math.max(0.2f, Math.min(0.8f, v)) : 0.5f;
    }

    /** The text as typed, without control characters, at most MAX characters (code points). */
    public static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        text.strip().codePoints().filter(c -> !Character.isISOControl(c) && c != '§').limit(MAX).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Server to client: open the text box of the Table Number block at pos. */
    public record Edit(BlockPos pos, String text) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "card_edit"));
        static final StreamCodec<ByteBuf, Edit> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Edit::pos,
                ByteBufCodecs.stringUtf8(16), Edit::text, Edit::new);
        /** Client: opens the text box (set by the client setup). */
        public static java.util.function.Consumer<Edit> handler = e -> {};

        @Override
        public Type<Edit> type() { return TYPE; }
    }

    /** Client to server: the text typed on the Table Number block at pos. */
    public record Text(BlockPos pos, String text) implements CustomPacketPayload {
        public static final Type<Text> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "card_text"));
        static final StreamCodec<ByteBuf, Text> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Text::pos,
                ByteBufCodecs.stringUtf8(16), Text::text, Text::new);

        static void handle(Text t, ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            if (!level.isLoaded(t.pos) || player.distanceToSqr(Vec3.atCenterOf(t.pos)) > 8 * 8 || !level.mayInteract(player, t.pos)
                    || !(level.getBlockEntity(t.pos) instanceof TableNumberBlockEntity be)) return;
            String text = clean(t.text);
            be.setText(text);
            Restaurant r = Restaurants.get(level).containing(t.pos.below());
            if (r != null) r.placeCard(level, t.pos.below(), TableNumberBlock.card(be.getBlockState(), text));
        }

        @Override
        public Type<Text> type() { return TYPE; }
    }
}
