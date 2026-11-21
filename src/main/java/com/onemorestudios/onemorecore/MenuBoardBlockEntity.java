package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The menu that a board shows. The server builds it from the restaurant every two seconds and sends it to the clients
 * when it changed; it is not saved with the world.
 */
public class MenuBoardBlockEntity extends BlockEntity {
    public static final int MAX_ROWS = 256;
    /** Leave room in ScreenData for the screen positions and order tickets under the 1 MiB clientbound limit. */
    private static final int MAX_MENU_BYTES = 512 * 1024;

    /** One line of the board: a dish (one item) or a combo (several items), its kind ("combo" for a combo) and price. */
    public record Line(String id, String kind, List<ItemStack> items, int price) {}

    private CompoundTag menu = new CompoundTag();
    /** Client: the lines from the last update. */
    public List<Line> lines = List.of();

    public MenuBoardBlockEntity(BlockPos pos, BlockState state) {
        this(OneMoreCore.MENU_BOARD_ENTITY.get(), pos, state);
    }

    /** Also the menu book, which shows the same menu. */
    protected MenuBoardBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    void serverTick(ServerLevel level) {
        if (level.getGameTime() % 40 != Math.floorMod(worldPosition.asLong(), 40)) return;
        CompoundTag now = build(Restaurants.get(level).forComputer(worldPosition), level.registryAccess());
        if (now.equals(menu)) return;
        menu = now;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** The dishes that customers can order now, and the combos that they can order. */
    static CompoundTag build(Restaurant r, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        ListTag combos = new ListTag();
        int remaining = MAX_MENU_BYTES;
        if (r != null) {
            // Reserve space for orderable combos even when the dish catalog fills the display.
            for (Restaurant.Combo c : r.combos) {
                if (combos.size() >= MAX_ROWS) break;
                if (!r.canOrder(c)) continue;
                List<ItemStack> items = new ArrayList<>();
                for (String id : c.dishes()) {
                    // A kind place shows one dish of that kind.
                    String kind = Restaurant.slotKind(id);
                    Menu.Entry e = kind == null ? r.dish(id) : r.ofKind(kind).stream().findFirst().orElse(null);
                    items.add(icon(e));
                }
                remaining = append(combos, line(c.id(), "combo", items, c.price(), registries), remaining);
            }
            for (Menu.Entry e : r.dishes()) {
                if (list.size() + combos.size() >= MAX_ROWS) break;
                if (!r.canOrder(e)) continue;
                ItemStack icon = icon(e);
                if (icon.isEmpty()) continue;
                remaining = append(list, line(e.id(), r.kind(e), List.of(icon), e.price(), registries), remaining);
            }
        }
        list.addAll(combos);
        tag.put("lines", list);
        return tag;
    }

    private static ItemStack icon(Menu.Entry entry) {
        if (entry == null) return ItemStack.EMPTY;
        return entry.tag() == null ? entry.stack() : BuiltInRegistries.ITEM.getTag(entry.tag())
                .flatMap(set -> set.stream().findFirst()).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static int append(ListTag lines, CompoundTag line, int remaining) {
        int size = ScreenData.wireSize(line);
        if (size > remaining) return remaining;
        lines.add(line);
        return remaining - size;
    }

    private static CompoundTag line(String id, String kind, List<ItemStack> items, int price, HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putString("kind", kind);
        ListTag stacks = new ListTag();
        for (ItemStack stack : items) stacks.add(stack.copyWithCount(1).saveOptional(registries));
        t.put("items", stacks);
        t.putInt("price", price);
        return t;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return menu.copy();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Client: reads the lines of an update. The tag is not trusted beyond its shape. */
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        lines = parse(tag, registries);
    }

    /** The lines of a menu tag (from build). */
    public static List<Line> parse(CompoundTag tag, HolderLookup.Provider registries) {
        List<Line> out = new ArrayList<>();
        for (Tag t : tag.getList("lines", Tag.TAG_COMPOUND)) {
            if (out.size() >= MAX_ROWS) break;
            CompoundTag c = (CompoundTag) t;
            ListTag stacks = c.getList("items", Tag.TAG_COMPOUND);
            if (stacks.size() > Restaurant.MAX_ORDER_ITEMS) continue;
            List<ItemStack> items = new ArrayList<>();
            for (Tag stack : stacks) {
                items.add(ItemStack.parseOptional(registries, (CompoundTag) stack));
            }
            if (!items.isEmpty()) out.add(new Line(c.getString("id"), c.getString("kind"), items, c.getInt("price")));
        }
        return out;
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }
}
