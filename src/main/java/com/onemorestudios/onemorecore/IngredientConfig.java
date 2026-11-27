package com.onemorestudios.onemorecore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.neoforged.fml.loading.FMLPaths;

/** Ingredient overrides and prices from config/onemorecore/ingredients.json. */
record IngredientConfig(Set<ResourceLocation> free, Set<ResourceLocation> ignore,
        Map<ResourceLocation, List<Set<ResourceLocation>>> dishes, Map<ResourceLocation, Price> prices,
        Set<ResourceLocation> foodTags, List<Group> groups, int minDishes) {
    private static final int BASE = 4, PER_DISH = 2, MIN = 5, MAX = 40;
    private static final double VALUE_SHARE = 0.15;
    record Price(Integer unlock, Integer shop) {}
    /** show: the member whose card the Shop shows (a plain id listed first), or null for the one helping most dishes. */
    record Group(String name, Set<ResourceLocation> members, ResourceLocation show) {
        Group(String name, Set<ResourceLocation> members) { this(name, members, null); }
    }

    private static final List<String> FOOD_TAGS = List.of("c:foods", "c:crops", "c:eggs", "c:milks",
            "c:drinks", "c:mushrooms", "c:flours", "c:flour", "c:dusts/salt");

    private static final String DEFAULT = """
            {
              "food_tags": ["c:foods", "c:crops", "c:eggs", "c:milks", "c:drinks", "c:mushrooms", "c:flours", "c:flour", "c:dusts/salt"],
              "minDishes": 0,
              "groups": [
                {"name": "tomato", "members": ["#c:crops/tomato"]},
                {"name": "onion", "members": ["#c:crops/onion"]},
                {"name": "rice", "members": ["farmersdelight:rice", "#c:crops/rice"]},
                {"name": "flour", "members": ["#c:flour", "refurbished_furniture:wheat_flour"]},
                {"name": "chili", "members": ["#c:crops/chilipepper"]},
                {"name": "grape", "members": ["#c:foods/fruits/grape", "#c:fruits/grapes", "kaleidoscope_tavern:green_grape"]},
                {"name": "milk", "members": ["#c:drinks/milk"]},
                {"name": "cod", "members": ["#c:foods/raw_cod"]},
                {"name": "chicken", "members": ["#c:foods/raw_chicken"]},
                {"name": "beef", "members": ["#c:foods/raw_beef"]},
                {"name": "pumpkin", "members": ["minecraft:pumpkin", "farmersdelight:pumpkin_slice"]},
                {"name": "melon", "members": ["minecraft:melon_slice", "minecraft:melon"]}
              ],
              "prices": {},
              "free": ["minecraft:bowl", "minecraft:glass_bottle", "minecraft:bucket", "minecraft:water_bucket",
                "minecraft:stick", "#c:tools/knife", "#c:tools/knives", "#farmersdelight:tools/knives",
                "#kaleidoscope_cookery:kitchen_knives"],
              "ignore": ["minecraft:cactus", "minecraft:nether_wart", "minecraft:ominous_bottle",
                "minecraft:chorus_fruit", "minecraft:turtle_egg"],
              "dishes": {}
            }
            """;

    static IngredientConfig load() {
        return load(FMLPaths.CONFIGDIR.get().resolve(OneMoreCore.MODID).resolve("ingredients.json"));
    }

    private static IngredientConfig load(Path path) {
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT);
            }
            return parse(Files.readString(path));
        } catch (Exception e) {
            OneMoreCore.LOGGER.error("Could not read {}. Using default ingredient rules.", path, e);
            return parse(DEFAULT);
        }
    }

    private static Set<ResourceLocation> items(String name) {
        ResourceLocation id = ResourceLocation.parse(name.startsWith("#") ? name.substring(1) : name);
        Set<ResourceLocation> result = new LinkedHashSet<>();
        if (name.startsWith("#")) {
            BuiltInRegistries.ITEM.getTag(ItemTags.create(id)).ifPresent(tag ->
                    tag.forEach(holder -> result.add(BuiltInRegistries.ITEM.getKey(holder.value()))));
        } else {
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> result.add(id));
        }
        return result;
    }

    private static void readList(JsonElement list, Consumer<String> add) {
        if (list == null) return;
        for (JsonElement el : list.getAsJsonArray()) {
            try {
                add.accept(el.getAsString());
            } catch (RuntimeException e) {
                OneMoreCore.LOGGER.warn("Skipped invalid ingredient reference {}: {}", el, e.getMessage());
            }
        }
    }

    private static JsonObject object(JsonObject root, String key) {
        return root.has(key) ? root.getAsJsonObject(key) : new JsonObject();
    }

    static IngredientConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Set<ResourceLocation> free = new LinkedHashSet<>(), ignore = new LinkedHashSet<>();
        readList(root.get("free"), name -> free.addAll(items(name)));
        readList(root.get("ignore"), name -> ignore.addAll(items(name)));

        Set<ResourceLocation> foodTags = new LinkedHashSet<>();
        if (root.has("food_tags")) readList(root.get("food_tags"), name ->
                foodTags.add(ResourceLocation.parse(name.startsWith("#") ? name.substring(1) : name)));
        else FOOD_TAGS.stream().map(ResourceLocation::parse).forEach(foodTags::add);

        int minDishes = root.has("minDishes") ? root.get("minDishes").getAsBigDecimal().intValueExact() : 0;
        if (minDishes < 0) throw new IllegalArgumentException("minDishes must be non-negative");
        List<Group> groups = new ArrayList<>();
        Set<ResourceLocation> grouped = new LinkedHashSet<>();
        if (root.has("groups")) for (JsonElement value : root.getAsJsonArray("groups")) {
            try {
                JsonObject group = value.getAsJsonObject();
                String name = group.get("name").getAsString();
                if (name.isBlank()) throw new IllegalArgumentException("group name is empty");
                Set<ResourceLocation> members = new LinkedHashSet<>();
                List<String> listed = new ArrayList<>();
                readList(group.get("members"), member -> {
                    members.addAll(items(member));
                    listed.add(member);
                });
                // A plain id listed first is the member the Shop shows; a tag first lets the most useful member show.
                ResourceLocation show = listed.isEmpty() || listed.getFirst().startsWith("#") ? null : ResourceLocation.tryParse(listed.getFirst());
                if (!java.util.Collections.disjoint(grouped, members))
                    throw new IllegalArgumentException("group members overlap an earlier group");
                groups.add(new Group(name, Set.copyOf(members), show));
                grouped.addAll(members);
            } catch (RuntimeException e) {
                OneMoreCore.LOGGER.warn("Skipped invalid ingredient group {}: {}", value, e.getMessage());
            }
        }

        Map<ResourceLocation, Price> prices = new HashMap<>();
        JsonObject rules = object(root, "prices");
        // Apply tag prices first so exact item overrides win.
        rules.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> !entry.getKey().startsWith("#")))
                .forEach(entry -> {
                    try {
                        JsonElement value = entry.getValue();
                        Price price = value instanceof JsonObject fields
                                ? new Price(fields.has("unlock") ? Menu.money(fields.get("unlock")) : null,
                                            fields.has("shop") ? Menu.money(fields.get("shop")) : null)
                                : new Price(Menu.money(value), null);
                        for (ResourceLocation id : items(entry.getKey())) {
                            Price old = prices.get(id);
                            prices.put(id, old == null ? price : new Price(
                                    price.unlock != null ? price.unlock : old.unlock,
                                    price.shop != null ? price.shop : old.shop));
                        }
                    } catch (RuntimeException e) {
                        OneMoreCore.LOGGER.warn("Skipped invalid ingredient price {}: {}", entry.getKey(), e.getMessage());
                    }
                });

        Map<ResourceLocation, List<Set<ResourceLocation>>> dishes = new HashMap<>();
        object(root, "dishes").entrySet().forEach(entry -> {
            try {
                ResourceLocation id = ResourceLocation.parse(entry.getKey());
                if (!BuiltInRegistries.ITEM.containsKey(id)) return;
                List<Set<ResourceLocation>> slots = entry.getValue().getAsJsonArray().asList().stream()
                        .map(value -> items(value.getAsString())).toList();
                dishes.put(id, slots);
            } catch (RuntimeException e) {
                OneMoreCore.LOGGER.warn("Skipped invalid dish ingredient override {}: {}", entry.getKey(), e.getMessage());
            }
        });
        return new IngredientConfig(Set.copyOf(free), Set.copyOf(ignore), Map.copyOf(dishes), Map.copyOf(prices),
                Set.copyOf(foodTags), List.copyOf(groups), minDishes);
    }

    int unlockCost(ResourceLocation id, int dishes, long totalWorth) {
        Price price = prices.get(id);
        return price != null && price.unlock != null ? price.unlock
                : (int) Math.clamp(Math.round(BASE + PER_DISH * (double) dishes + VALUE_SHARE * totalWorth), MIN, MAX);
    }

    int shopPrice(ResourceLocation id, int unlockCost) {
        Price price = prices.get(id);
        return price != null && price.shop != null ? price.shop : Math.max(1, Math.round(unlockCost / 5f));
    }
}
