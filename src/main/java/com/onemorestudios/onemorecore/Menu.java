package com.onemorestudios.onemorecore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

/** The orderable items, read from config/onemorecore/menu.json. */
public class Menu {
    /** Kinds of dishes. One order has at most one dish of each kind. */
    public static final List<String> CATEGORIES = List.of("main", "drink", "starter", "dessert");

    /**
     * The kind of a dish from its item: a drink if it is drunk or in c:drinks; a dessert or a starter (soup, salad, meze)
     * from the words in its id; otherwise a main dish.
     */
    public static String classify(ItemStack stack) {
        if (stack.isEmpty()) return "main";
        if (stack.getUseAnimation() == net.minecraft.world.item.UseAnim.DRINK
                || stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse("c:drinks")))) return "drink";
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        // Starters first: a "cream soup" is a soup.
        for (String w : new String[] {"soup", "salad", "porridge", "chowder", "broth", "appetizer", "snack", "fries", "chips"})
            if (path.contains(w)) return "starter";
        for (String w : new String[] {"cake", "pie", "cookie", "ice_cream", "pudding", "candy", "popsicle", "tart", "jelly", "chocolate",
                "donut", "muffin", "dessert"})
            if (path.contains(w)) return "dessert";
        return "main";
    }

    /** Exactly one of stack and tag is set. */
    public record Entry(String id, ItemStack stack, TagKey<Item> tag, int price, boolean exact, String category) {
        /** An entry whose kind comes from its item. */
        public Entry(String id, ItemStack stack, TagKey<Item> tag, int price, boolean exact) {
            this(id, stack, tag, price, exact, classify(stack == null ? ItemStack.EMPTY : stack));
        }

        ItemStack pick(RandomSource random) {
            if (tag == null) return stack.copy();
            return BuiltInRegistries.ITEM.getTag(tag).flatMap(set -> set.getRandomElement(random))
                    .map(ItemStack::new).orElse(ItemStack.EMPTY);
        }
    }

    public static List<Entry> entries = List.of();
    private static Map<String, LegacyUnlock> legacyUnlocks = Map.of();
    private static boolean warnedAboutUnlock;

    // Only read for one-time migration of old restaurant and sign data.
    private record LegacyUnlock(int day, int cost) {}

    static boolean wasAvailable(Entry entry, int day, Set<String> paid) {
        LegacyUnlock unlock = legacyUnlocks.get(entry.id());
        return unlock == null || day >= unlock.day && (unlock.cost == 0 || paid.contains(entry.id()));
    }

    private static final String DEFAULT = """
            [
              {"id": "bread", "item": "minecraft:bread", "price": 2},
              {"id": "baked_potato", "item": "minecraft:baked_potato", "price": 2},
              {"id": "cooked_beef", "item": "minecraft:cooked_beef", "price": 4},
              {"id": "pumpkin_pie", "item": "minecraft:pumpkin_pie", "price": 6},
              {"id": "cake", "item": "minecraft:cake", "price": 10}
            ]
            """;

    public static Entry byId(String id) {
        for (Entry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    static void load(HolderLookup.Provider registries) {
        Path path = FMLPaths.CONFIGDIR.get().resolve(OneMoreCore.MODID).resolve("menu.json");
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT);
            }
            Map<String, LegacyUnlock> old = new HashMap<>();
            entries = parse(Files.readString(path), registries, old);
            legacyUnlocks = Map.copyOf(old);
        } catch (Exception e) {
            OneMoreCore.LOGGER.error("Could not read {}. The menu is empty.", path, e);
            entries = List.of();
            legacyUnlocks = Map.of();
        }
    }

    /**
     * "item" is an item id, a "#tag", or a full item stack object ({"id": ..., "components": {...}}).
     * A bad entry is logged and skipped.
     */
    static List<Entry> parse(String json, HolderLookup.Provider registries) {
        return parse(json, registries, new HashMap<>());
    }

    private static List<Entry> parse(String json, HolderLookup.Provider registries, Map<String, LegacyUnlock> old) {
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
                String match = GsonHelper.getAsString(o, "match", "ignore");
                if (!match.equals("ignore") && !match.equals("exact"))
                    throw new IllegalArgumentException("match must be \"ignore\" or \"exact\"");
                if (byIdIn(out, id)) throw new IllegalArgumentException("duplicate id");
                String category = GsonHelper.getAsString(o, "category", null);
                if (category != null && !CATEGORIES.contains(category)) throw new IllegalArgumentException("category must be one of " + CATEGORIES);
                if (category == null) category = tag == null ? classify(stack)
                        : BuiltInRegistries.ITEM.getTag(tag).flatMap(set -> set.stream().findFirst()).map(h -> classify(new ItemStack(h))).orElse("main");
                out.add(new Entry(id, stack, tag, money(o.get("price")), match.equals("exact"), category));
                if (o.has("unlock")) {
                    if (!warnedAboutUnlock) {
                        OneMoreCore.LOGGER.warn("menu.json: unlock is deprecated and ignored for gameplay. Use ingredients.json.");
                        warnedAboutUnlock = true;
                    }
                    if (o.get("unlock") instanceof JsonObject unlock) {
                        try {
                            old.put(id, new LegacyUnlock(Math.max(1, integer(unlock, "day", 1)),
                                    unlock.has("cost") ? money(unlock.get("cost")) : 0));
                        } catch (RuntimeException ignored) {
                            // Invalid legacy values do not change the current menu entry.
                        }
                    }
                }
            } catch (Exception e) {
                OneMoreCore.LOGGER.error("Skipped bad menu entry {}: {}", el, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /** An item id or a full item stack object ({"id": ..., "components": {...}}). */
    static ItemStack stack(JsonElement item, HolderLookup.Provider registries) {
        if (item == null) throw new IllegalArgumentException("missing item");
        // The vanilla codec silently replaces counts above 99 with 1.
        int count = item.isJsonObject() ? integer(item.getAsJsonObject(), "count", 1) : 1;
        if (count <= 0) throw new IllegalArgumentException("empty item stack");
        ItemStack stack = item.isJsonObject() ? ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), item).getOrThrow()
                : new ItemStack(BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(item.getAsString()))
                        .orElseThrow(() -> new IllegalArgumentException("unknown item " + item.getAsString())));
        if (stack.isEmpty()) throw new IllegalArgumentException("empty item stack");
        stack.setCount(count);
        return stack;
    }

    static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsBigDecimal().intValueExact() : fallback;
    }

    static int money(JsonElement value) {
        BigDecimal amount = value.getAsBigDecimal();
        if (amount.compareTo(BigDecimal.valueOf(1_000_000)) > 0) OneMoreCore.LOGGER.warn("Capped money value {} at 1000000", value);
        return amount.max(BigDecimal.ZERO).min(BigDecimal.valueOf(1_000_000)).intValue();
    }

    private static boolean byIdIn(List<Entry> list, String id) {
        return list.stream().anyMatch(e -> e.id.equals(id));
    }
}
