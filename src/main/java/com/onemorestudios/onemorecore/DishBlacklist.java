package com.onemorestudios.onemorecore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * What can be a dish: everything that is eaten or drunk (a food component, the eat or drink use, #c:foods, #c:drinks),
 * except the items in config/onemorecore/dish_blacklist.json. That file is a list like JEI's blacklist: item ids
 * ("youkaisfeasts:soy_sauce_bottle") and item tags ("#c:buckets"); add to it at any time, it is read again on /reload and
 * when a world loads. A missing file is written with the sauces, buckets and thrown potions of the pack.
 */
@EventBusSubscriber(modid = OneMoreCore.MODID)
public final class DishBlacklist {
    private DishBlacklist() {}

    static final String FILE = "dish_blacklist.json";
    private static final TagKey<Item> FOODS = ItemTags.create(ResourceLocation.parse("c:foods"));
    private static final TagKey<Item> DRINKS = ItemTags.create(ResourceLocation.parse("c:drinks"));

    /** Eaten or drunk, but no dish: thrown potions, sauces and condiments, buckets of fruit. */
    static final List<String> DEFAULTS = List.of(
            "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:ominous_bottle",
            "youkaisfeasts:soy_sauce_bottle", "youkaisfeasts:soy_sauce_flask",
            "youkaisfeasts:mayonnaise_bottle", "youkaisfeasts:mayonnaise_flask",
            "kaleidoscope_tavern:vinegar",
            "kaleidoscope_tavern:grape_bucket", "kaleidoscope_tavern:ice_grape_bucket", "kaleidoscope_tavern:gold_grape_bucket",
            "kaleidoscope_tavern:green_grape_bucket", "kaleidoscope_tavern:sweet_berries_bucket", "kaleidoscope_tavern:glow_berries_bucket");

    private static volatile Set<ResourceLocation> items;
    private static volatile List<TagKey<Item>> tags = List.of();

    /** Whether an item can be a dish on the menu. */
    public static boolean isDish(ItemStack stack) {
        if (stack.isEmpty() || blocked(stack)) return false;
        UseAnim use = stack.getUseAnimation();
        return stack.has(DataComponents.FOOD) || use == UseAnim.EAT || use == UseAnim.DRINK || stack.is(FOODS) || stack.is(DRINKS);
    }

    /** Whether the blacklist names the item, by its id or one of its tags. */
    public static boolean blocked(ItemStack stack) {
        if (items == null) reload();
        if (items.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return true;
        for (TagKey<Item> tag : tags) if (stack.is(tag)) return true;
        return false;
    }

    /** Reads the file (the defaults when it is missing). Only the server writes a missing file: see tagsLoaded. */
    public static void reload() { reload(false); }

    /** Reads the file again, or (write) writes it with the defaults when it is missing. A broken entry is left out. */
    static void reload(boolean write) {
        Path path = FMLPaths.CONFIGDIR.get().resolve(OneMoreCore.MODID).resolve(FILE);
        List<String> entries = DEFAULTS;
        try {
            if (Files.exists(path)) {
                entries = parse(Files.readString(path));
            } else if (write) {
                Files.createDirectories(path.getParent());
                JsonArray array = new JsonArray();
                DEFAULTS.forEach(array::add);
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(array));
            }
        } catch (IOException | RuntimeException ex) {
            LogUtils.getLogger().warn("{}: {}; the default dish blacklist is used", path, ex.toString());
            entries = DEFAULTS;
        }
        set(entries);
    }

    /** The file's entries: a JSON list of strings. An entry that is no string is left out, the others stay. */
    public static List<String> parse(String json) {
        List<String> entries = new ArrayList<>();
        for (var e : JsonParser.parseString(json).getAsJsonArray()) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) entries.add(e.getAsString());
            else LogUtils.getLogger().warn("{}: {} is no item id or #tag", FILE, e);
        }
        return entries;
    }

    /** Uses these entries: "namespace:item" or "#namespace:tag". */
    public static void set(List<String> entries) {
        Set<ResourceLocation> ids = new HashSet<>();
        List<TagKey<Item>> tagKeys = new ArrayList<>();
        for (String entry : entries) {
            String s = entry.trim();
            ResourceLocation id = ResourceLocation.tryParse(s.startsWith("#") ? s.substring(1) : s);
            if (id == null) {
                LogUtils.getLogger().warn("{}: '{}' is no item id or #tag", FILE, entry);
                continue;
            }
            if (s.startsWith("#")) tagKeys.add(TagKey.create(Registries.ITEM, id));
            else ids.add(id);
        }
        tags = List.copyOf(tagKeys);
        items = Set.copyOf(ids);
    }

    /** The server's data load reads the file again (and writes a missing one); a client that gets tags from a server does not. */
    @SubscribeEvent
    static void tagsLoaded(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) reload(true);
    }
}
