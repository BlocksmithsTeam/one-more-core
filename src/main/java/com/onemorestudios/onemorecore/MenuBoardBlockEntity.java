package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The menu that a board shows. The server builds it from the restaurant every two seconds and sends it to the clients
 * when it changed; it is not saved with the world.
 */
public class MenuBoardBlockEntity extends BlockEntity {
    /** One line of the board: a dish (one item) or a combo (several items), its kind ("combo" for a combo) and price. */
    public record Line(String kind, List<ItemStack> items, int price) {}

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
        if (getBlockState().getBlock() instanceof MenuBoardBlock board) {
            // A screen of the mod joins the restaurant it stands in (or the nearest one), showing its default at first.
            Restaurant r = ScreenSettings.owner(level, worldPosition);
            if (r != null) r.ensureScreen(worldPosition, board.defaultMode());
            return;
        }
        CompoundTag now = build(Restaurants.get(level).forComputer(worldPosition));
        if (now.equals(menu)) return;
        menu = now;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** The dishes that customers can order now, and the combos that they can order. */
    static CompoundTag build(Restaurant r) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        if (r != null) {
            for (Menu.Entry e : r.dishes()) {
                if (!r.canOrder(e)) continue;
                ItemStack icon = e.tag() == null ? e.stack() : BuiltInRegistries.ITEM.getTag(e.tag())
                        .flatMap(set -> set.stream().findFirst()).map(ItemStack::new).orElse(ItemStack.EMPTY);
                if (icon.isEmpty()) continue;
                list.add(line(r.kind(e), List.of(icon), e.price()));
            }
            for (Restaurant.Combo c : r.combos) {
                if (!r.canOrder(c)) continue;
                List<ItemStack> items = new ArrayList<>();
                for (String id : c.dishes()) {
                    // A kind place shows one dish of that kind.
                    String kind = Restaurant.slotKind(id);
                    Menu.Entry e = kind == null ? r.dish(id) : r.ofKind(kind).stream().findFirst().orElse(null);
                    items.add(e == null || e.stack() == null ? ItemStack.EMPTY : e.stack());
                }
                list.add(line("combo", items, c.price()));
            }
        }
        tag.put("lines", list);
        return tag;
    }

    private static CompoundTag line(String kind, List<ItemStack> items, int price) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        ListTag ids = new ListTag();
        for (ItemStack s : items) ids.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));
        t.put("items", ids);
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
        lines = parse(tag);
    }

    /** The lines of a menu tag (from build). */
    public static List<Line> parse(CompoundTag tag) {
        List<Line> out = new ArrayList<>();
        for (Tag t : tag.getList("lines", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            List<ItemStack> items = new ArrayList<>();
            for (Tag id : c.getList("items", Tag.TAG_STRING))
                BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id.getAsString())).ifPresent(i -> items.add(new ItemStack(i)));
            if (!items.isEmpty() && out.size() < 64) out.add(new Line(c.getString("kind"), items, c.getInt("price")));
        }
        return out;
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }
}
