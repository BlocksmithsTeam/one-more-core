package com.onemorestudios.onemoreclient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/** The orderable items, read from config/onemoreclient/menu.json. */
public class Menu {
    /** Exactly one of stack and tag is set. */
    public record Entry(String id, ItemStack stack, TagKey<Item> tag, int price, boolean exact, int day, int cost) {
        ItemStack pick(RandomSource random) {
            if (tag == null) return stack.copy();
            return BuiltInRegistries.ITEM.getTag(tag).flatMap(set -> set.getRandomElement(random))
                    .map(ItemStack::new).orElse(ItemStack.EMPTY);
        }
    }

    public static List<Entry> entries = List.of();

    private static final String DEFAULT = """
            [
              {"id": "bread", "item": "minecraft:bread", "price": 2},
              {"id": "baked_potato", "item": "minecraft:baked_potato", "price": 2},
              {"id": "cooked_beef", "item": "minecraft:cooked_beef", "price": 4, "unlock": {"day": 2}},
              {"id": "pumpkin_pie", "item": "minecraft:pumpkin_pie", "price": 6, "unlock": {"day": 3, "cost": 10}},
              {"id": "cake", "item": "minecraft:cake", "price": 10, "unlock": {"cost": 20}}
            ]
            """;

    public static Entry byId(String id) {
        for (Entry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    static void load(HolderLookup.Provider registries) {
        Path path = FMLPaths.CONFIGDIR.get().resolve(OneMoreClient.MODID).resolve("menu.json");
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT);
            }
            entries = parse(Files.readString(path), registries);
        } catch (Exception e) {
            OneMoreClient.LOGGER.error("Could not read {}. The menu is empty.", path, e);
            entries = List.of();
        }
    }

    /**
     * "item" is an item id, a "#tag", or a full item stack object ({"id": ..., "components": {...}}).
     * A bad entry is logged and skipped.
     */
    static List<Entry> parse(String json, HolderLookup.Provider registries) {
        List<Entry> out = new ArrayList<>();
        for (JsonElement el : JsonParser.parseString(json).getAsJsonArray()) {
            try {
                JsonObject o = el.getAsJsonObject();
                String id = GsonHelper.getAsString(o, "id");
                JsonElement item = o.get("item");
                ItemStack stack = null;
                TagKey<Item> tag = null;
                if (item != null && item.isJsonPrimitive() && item.getAsString().startsWith("#")) {
                    tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(item.getAsString().substring(1)));
                } else {
                    stack = stack(item, registries);
                }
                JsonObject unlock = GsonHelper.getAsJsonObject(o, "unlock", new JsonObject());
                String match = GsonHelper.getAsString(o, "match", "ignore");
                if (!match.equals("ignore") && !match.equals("exact"))
                    throw new IllegalArgumentException("match must be \"ignore\" or \"exact\"");
                if (byIdIn(out, id)) throw new IllegalArgumentException("duplicate id");
                out.add(new Entry(id, stack, tag,
                        money(o.get("price")),
                        match.equals("exact"),
                        Math.max(1, integer(unlock, "day", 1)),
                        unlock.has("cost") ? money(unlock.get("cost")) : 0));
            } catch (Exception e) {
                OneMoreClient.LOGGER.error("Skipped bad menu entry {}: {}", el, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /** An item id or a full item stack object ({"id": ..., "components": {...}}). */
    static ItemStack stack(JsonElement item, HolderLookup.Provider registries) {
        if (item == null) throw new IllegalArgumentException("missing item");
        // The vanilla codec silently replaces an invalid count with 1.
        if (item.isJsonObject() && integer(item.getAsJsonObject(), "count", 1) <= 0)
            throw new IllegalArgumentException("empty item stack");
        ItemStack stack = item.isJsonObject() ? ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), item).getOrThrow()
                : new ItemStack(BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(item.getAsString()))
                        .orElseThrow(() -> new IllegalArgumentException("unknown item " + item.getAsString())));
        if (stack.isEmpty()) throw new IllegalArgumentException("empty item stack");
        return stack;
    }

    static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsBigDecimal().intValueExact() : fallback;
    }

    static int money(JsonElement value) {
        BigDecimal amount = value.getAsBigDecimal();
        if (amount.compareTo(BigDecimal.valueOf(1_000_000)) > 0) OneMoreClient.LOGGER.warn("Capped money value {} at 1000000", value);
        return amount.max(BigDecimal.ZERO).min(BigDecimal.valueOf(1_000_000)).intValue();
    }

    private static boolean byIdIn(List<Entry> list, String id) {
        return list.stream().anyMatch(e -> e.id.equals(id));
    }
}
