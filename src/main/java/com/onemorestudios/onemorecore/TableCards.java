package com.onemorestudios.onemorecore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Table number cards: a player puts a card on a table where they click, and types its text (at most 2 characters, "7", "A",
 * "12"). The text names the table on the order screens and the tickets. One card per table: a second one moves the first.
 * Click the card with an empty hand to change the text; sneak-click it to take it back.
 */
public final class TableCards {
    private TableCards() {}

    /** At most this many characters. */
    public static final int MAX = 2;
    /** A click this near the card (blocks, on its surface) is a click on the card. */
    static final double NEAR = 0.3;

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

    /** Server to client: open the text box of the card on a surface. */
    public record Edit(BlockPos surface, String text) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "card_edit"));
        static final StreamCodec<ByteBuf, Edit> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Edit::surface,
                ByteBufCodecs.stringUtf8(16), Edit::text, Edit::new);
        /** Client: opens the text box (set by the client setup). */
        public static java.util.function.Consumer<Edit> handler = e -> {};

        @Override
        public Type<Edit> type() { return TYPE; }
    }

    /** Client to server: the text typed on the card of a surface. */
    public record Text(BlockPos surface, String text) implements CustomPacketPayload {
        public static final Type<Text> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "card_text"));
        static final StreamCodec<ByteBuf, Text> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Text::surface,
                ByteBufCodecs.stringUtf8(16), Text::text, Text::new);

        static void handle(Text t, ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            if (!level.isLoaded(t.surface) || player.distanceToSqr(Vec3.atCenterOf(t.surface)) > 8 * 8) return;
            Restaurant r = Restaurants.get(level).containing(t.surface);
            if (r != null) r.setCardText(level, t.surface, clean(t.text));
        }

        @Override
        public Type<Text> type() { return TYPE; }
    }

    /**
     * A right click on a table: with a card, it goes there; with an empty hand on a card, the text box opens (sneaking: the card
     * comes back). True when the click was the card's; the event is cancelled then, on both sides.
     */
    static boolean onUse(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !e.getLevel().getBlockState(e.getPos()).is(OneMoreCore.SURFACES)) return false;
        ItemStack held = e.getItemStack();
        Player player = e.getEntity();
        BlockPos pos = e.getPos();
        Vec3 hit = e.getHitVec().getLocation();
        float x = (float) (hit.x - pos.getX()), z = (float) (hit.z - pos.getZ());
        boolean card = held.is(OneMoreCore.TABLE_NUMBER.get());
        if (!card && !held.isEmpty()) return false;
        if (e.getLevel() instanceof ServerLevel level) {
            Restaurant r = Restaurants.get(level).containing(pos);
            if (card) {
                if (r == null) {
                    player.displayClientMessage(Component.translatable("onemorecore.card.no_restaurant"), true);
                } else {
                    boolean moved = r.placeCard(level, pos, new Card("", clamp(x), clamp(z), player.getYRot() + 180));
                    if (!moved && !player.getAbilities().instabuild) held.shrink(1);
                    if (player instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new Edit(pos, r.cards.get(pos).text()));
                }
            } else {
                Card c = r == null ? null : r.cards.get(pos);
                if (c == null || !near(c, x, z)) return false;
                if (player.isShiftKeyDown()) {
                    r.removeCard(level, pos);
                    TrayBlock.give(player, new ItemStack(OneMoreCore.TABLE_NUMBER.get()));
                } else if (player instanceof ServerPlayer sp) {
                    PacketDistributor.sendToPlayer(sp, new Edit(pos, c.text()));
                }
            }
        } else if (!card && !TableMarks.near(pos, x, z)) {
            return false;
        }
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        return true;
    }

    static boolean near(Card c, float x, float z) {
        return (c.x() - x) * (c.x() - x) + (c.z() - z) * (c.z() - z) < NEAR * NEAR;
    }
}
