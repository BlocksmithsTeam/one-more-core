package com.onemorestudios.onemorecore;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/** The items that the Restaurant Computer sells, read from config/onemorecore/shop.json. */
public class Shop {
    private static final int MAX_COUNT = 1728;
    /** The stack is delivered. The icon is shown in the list. */
    public record Entry(String id, ItemStack stack, ItemStack icon, int price) {}

    public static List<Entry> entries = List.of();

    private static final String DEFAULT = """
            [
              {"id": "wheat", "item": "minecraft:wheat", "count": 16, "price": 4},
              {"id": "potatoes", "item": "minecraft:potato", "count": 16, "price": 4},
              {"id": "beef", "item": "minecraft:beef", "count": 8, "price": 6},
              {"id": "baking_box", "icon": "minecraft:cake", "price": 12, "item": {"id": "minecraft:shulker_box", "components": {"minecraft:container": [
                {"slot": 0, "item": {"id": "minecraft:egg", "count": 16}},
                {"slot": 1, "item": {"id": "minecraft:sugar", "count": 16}},
                {"slot": 2, "item": {"id": "minecraft:pumpkin", "count": 8}}
              ]}}}
            ]
            """;

    public static Entry byId(String id) {
        for (Entry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    static void load(HolderLookup.Provider registries) {
        Path path = FMLPaths.CONFIGDIR.get().resolve(OneMoreCore.MODID).resolve("shop.json");
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT);
            }
            entries = parse(Files.readString(path), registries);
        } catch (Exception e) {
            OneMoreCore.LOGGER.error("Could not read {}. The shop is empty.", path, e);
            entries = List.of();
        }
    }

    /**
     * "item" is an item id or a full item stack object, as in menu.json. "count" replaces the count of the stack.
     * A bad entry is logged and skipped.
     */
    static List<Entry> parse(String json, HolderLookup.Provider registries) {
        List<Entry> out = new ArrayList<>();
        for (JsonElement el : JsonParser.parseString(json).getAsJsonArray()) {
            try {
                JsonObject o = el.getAsJsonObject();
                String id = GsonHelper.getAsString(o, "id");
                JsonElement item = o.get("item");
                JsonElement quantity = o.get("count");
                if (item != null && item.isJsonObject() && item.getAsJsonObject().has("count")) {
                    JsonObject stackObject = item.getAsJsonObject().deepCopy();
                    JsonElement stackCount = stackObject.remove("count");
                    if (quantity == null) quantity = stackCount;
                    item = stackObject;
                }
                ItemStack stack = Menu.stack(item, registries);
                if (quantity != null) {
                    BigDecimal count = quantity.getAsBigDecimal();
                    if (count.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("count must be an integer");
                    int clamped = count.max(BigDecimal.ONE).min(BigDecimal.valueOf(MAX_COUNT)).intValueExact();
                    if (count.compareTo(BigDecimal.valueOf(clamped)) != 0) {
                        OneMoreCore.LOGGER.warn("Clamped shop entry {} count from {} to {}", id, count, clamped);
                    }
                    stack.setCount(clamped);
                }
                ItemStack icon = o.has("icon") ? Menu.stack(o.get("icon"), registries) : stack;
                if (out.stream().anyMatch(e -> e.id.equals(id))) throw new IllegalArgumentException("duplicate id");
                out.add(new Entry(id, stack, icon, Menu.money(o.get("price"))));
            } catch (Exception e) {
                OneMoreCore.LOGGER.error("Skipped bad shop entry {}: {}", el, e.getMessage());
            }
        }
        return List.copyOf(out);
    }
}
