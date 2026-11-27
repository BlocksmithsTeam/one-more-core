package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluids;

/** The server's recipe index. Each recipe is a list of slots, each with alternative item IDs, and its machines. */
final class Ingredients {
    /** One way to make an item. One of the machines must be in the area; none means no machine is needed. */
    record Recipe(List<Set<ResourceLocation>> slots, Set<ResourceLocation> machines) {}

    private static final int DEPTH = 3;
    // Keep core ingredients whose harmful effects are unlikely, such as raw chicken.
    private static final float HARMFUL_EFFECT_PROBABILITY = 0.5f;
    private static Ingredients current = new Ingredients(List.of(), IngredientConfig.parse("{}"), List.of(), null);

    private final IngredientConfig config;
    private final Set<ResourceLocation> ignored;
    private final Set<ResourceLocation> blocked = new HashSet<>();
    private final Set<ResourceLocation> rawIngredients = new HashSet<>();
    private final Map<ResourceLocation, ResourceLocation> representatives = new HashMap<>();
    private final Set<ResourceLocation> foodDependencies = new HashSet<>();
    private final Map<ResourceLocation, List<Recipe>> recipes = new HashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> dishesByIngredient = new HashMap<>();
    private final Map<ResourceLocation, Integer> unlockCosts = new HashMap<>();
    private final Map<ResourceLocation, Integer> dishWorth = new HashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> portions = new HashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> recyclingInputs = new HashMap<>();
    final List<ResourceLocation> raw;
    final List<Menu.Entry> automatic;
    /** Every machine block a recipe can need: what a restaurant looks for in its area. */
    final Set<ResourceLocation> machines = new TreeSet<>();

    static Ingredients current() { return current; }

    static void rebuild(MinecraftServer server) {
        Machines.reload(true);
        current = new Ingredients(server.getRecipeManager().getRecipes(), IngredientConfig.load(), Menu.entries,
                server.registryAccess());
    }

    private Ingredients(Collection<RecipeHolder<?>> holders, IngredientConfig config, List<Menu.Entry> configured,
            HolderLookup.Provider registries) {
        this.config = config;
        ignored = new HashSet<>(config.ignore());
        int skipped = 0;
        Map<ResourceLocation, ItemStack> foodResults = new HashMap<>();
        for (RecipeHolder<?> holder : holders.stream().sorted(Comparator.comparing(RecipeHolder::id)).toList()) {
            try {
                var recipe = holder.value();
                ItemStack result = recipe.getResultItem(registries);
                ResourceLocation id = result == null || result.isEmpty() ? fluidResult(recipe, registries) : id(result);
                if (id == null) {
                    skipped++;
                    continue;
                }
                var alternatives = recipes.computeIfAbsent(id, key -> new ArrayList<>());
                if (result != null && DishBlacklist.isFoodCandidate(result)) foodResults.putIfAbsent(id, result.copyWithCount(1));
                List<Set<ResourceLocation>> slots = new ArrayList<>(recipe.getIngredients().stream()
                        // An empty ingredient ("[]", Brewin's fermenting) is no slot.
                        .filter(ingredient -> !ingredient.isEmpty())
                        .map(ingredient -> {
                            Set<ResourceLocation> ids = new TreeSet<>();
                            Arrays.stream(ingredient.getItems()).filter(Ingredients::realIngredient)
                                    .map(Ingredients::id).forEach(ids::add);
                            return ids;
                        }).toList());
                slots.addAll(hidden(recipe, registries, slots, id));
                var machines = Machines.forType(Machines.kind(recipe));
                if (addRecipe(alternatives, slots, machines)) this.machines.addAll(machines);
                String type = Machines.kind(recipe).getPath();
                if (result != null && result.has(DataComponents.FOOD) && slots.size() == 1
                        && (type.contains("cutting") || type.contains("chopping") || type.contains("slicing")))
                    portions.computeIfAbsent(id, key -> new TreeSet<>()).addAll(slots.getFirst());
            } catch (RuntimeException e) {
                skipped++;
                OneMoreCore.LOGGER.debug("Recipe {} does not expose its result or ingredients", holder.id(), e);
            }
        }
        config.dishes().forEach((id, slots) -> {
            var alternatives = new ArrayList<Recipe>();
            addRecipe(alternatives, slots, Set.of());
            recipes.put(id, alternatives);
            foodResults.computeIfAbsent(id, Ingredients::stack);
        });

        configured.forEach(entry -> variants(entry).forEach(stack -> dishWorth.putIfAbsent(id(stack), Restaurant.worth(stack))));
        List<Menu.Entry> discovered = new ArrayList<>();
        for (ResourceLocation id : new TreeSet<>(foodResults.keySet())) {
            ItemStack stack = foodResults.get(id);
            if (stack.isEmpty() || dishWorth.containsKey(id)) continue;
            int worth = Restaurant.worth(stack);
            dishWorth.put(id, worth);
            discovered.add(new Menu.Entry(id.toString(), stack, null, worth, false));
        }

        portions.forEach((portion, parents) -> {
            if (config.dishes().containsKey(portion)) parents.clear();
            parents.removeIf(parent -> !dishWorth.containsKey(parent) || !recipes.containsKey(parent) || parent.equals(portion));
            if (!parents.isEmpty())
                recipes.put(portion, List.of(new Recipe(List.of(Set.copyOf(parents)), Set.of())));
        });
        portions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        blockIgnoredRecipes();
        // Unpacking storage or processing only non-food must not make its food result free.
        recipes.entrySet().removeIf(entry -> {
            if ((!food(entry.getKey()) && !dishWorth.containsKey(entry.getKey())) || entry.getValue().isEmpty()) return false;
            var usable = entry.getValue().stream().filter(recipe -> recipe.slots().stream().anyMatch(slot ->
                    slot.stream().anyMatch(item -> config.free().contains(item) || (!excluded(stack(item))
                            && (food(item) || isFluidKey(item)))))).toList();
            entry.setValue(usable);
            return usable.isEmpty();
        });

        recipes.forEach((result, alternatives) -> {
            foodDependencies.add(result);
            alternatives.forEach(recipe -> recipe.slots().forEach(foodDependencies::addAll));
        });
        foodDependencies.removeIf(item -> !food(item) && !isFluidKey(item)
                && !(dishWorth.containsKey(item) && !excluded(stack(item))));
        recipes.forEach((result, alternatives) -> {
            if (alternatives.isEmpty()) foodDependencies.add(result);
        });
        int count;
        do {
            count = foodDependencies.size();
            recipes.forEach((result, alternatives) -> {
                if (alternatives.stream().anyMatch(recipe -> recipe.slots().stream()
                        .anyMatch(slot -> slot.stream().anyMatch(foodDependencies::contains)))) foodDependencies.add(result);
            });
        } while (count != foodDependencies.size());

        Set<ResourceLocation> rawIds = new TreeSet<>();
        int withIngredients = 0;
        int unreadable = 0;
        for (ResourceLocation dish : dishWorth.keySet()) {
            var alternatives = recipes.get(dish);
            if (alternatives == null) continue;
            if (alternatives.isEmpty()) unreadable++;
            else withIngredients++;
            Map<ResourceLocation, Integer> seen = new HashMap<>();
            for (var recipe : alternatives) {
                for (var slot : recipe.slots()) {
                    for (ResourceLocation item : slot) {
                        if (config.free().contains(item) || ignored.contains(item)) continue;
                        Set<ResourceLocation> sources = sources(item, DEPTH, new HashSet<>());
                        if (sources == null) {
                            if (food(item) && !portions.containsKey(item) && !blocked.contains(item)) rawIds.add(item);
                        }
                        else rawIds.addAll(sources);
                        indexDependencies(item, dish, DEPTH, seen);
                    }
                }
            }
        }
        rawIds.removeIf(item -> !food(item) || config.free().contains(item) || blocked.contains(item));
        rawIngredients.addAll(rawIds);
        for (var group : config.groups()) {
            List<ResourceLocation> members = rawIds.stream().filter(group.members()::contains).toList();
            if (members.isEmpty()) continue;
            ResourceLocation representative = members.stream().min(Comparator
                    .<ResourceLocation>comparingInt(item -> dishesHelped(item).size()).reversed()
                    .thenComparing(Comparator.naturalOrder())).orElseThrow();
            Set<ResourceLocation> dishes = new HashSet<>();
            members.forEach(member -> dishes.addAll(dishesHelped(member)));
            members.forEach(member -> {
                representatives.put(member, representative);
                dishesByIngredient.put(member, Set.copyOf(dishes));
            });
        }
        rawIds.removeIf(item -> {
            if (dishesHelped(item).size() >= config.minDishes()) return false;
            ignored.add(item);
            return true;
        });
        blockIgnoredRecipes();
        rawIds.removeAll(blocked);
        raw = rawIds.stream().map(this::representative).distinct().sorted().toList();
        automatic = discovered.stream().filter(entry -> DishBlacklist.isDishCandidate(entry.stack())
                && !excludesDish(id(entry.stack()))).toList();
        raw.forEach(id -> unlockCosts.put(id, automaticCost(id)));
        if (registries != null) OneMoreCore.LOGGER.info(
                "Ingredient index: {} dishes with readable recipes, {} locked without a readable recipe, {} without a recipe; {} recipes could not be read",
                withIngredients, unreadable, dishWorth.size() - withIngredients - unreadable, skipped);
    }

    /** Propagate ignored inputs through every recipe, but keep dishes with another usable alternative. */
    private void blockIgnoredRecipes() {
        blocked.addAll(ignored);
        blocked.removeAll(config.free());
        int count;
        do {
            count = blocked.size();
            recipes.forEach((result, alternatives) -> {
                if (!config.free().contains(result) && !alternatives.isEmpty() && alternatives.stream().allMatch(recipe ->
                        recipe.slots().stream().anyMatch(slot -> slot.stream().allMatch(blocked::contains))))
                    blocked.add(result);
            });
        } while (count != blocked.size());
    }

    ResourceLocation representative(ResourceLocation item) { return representatives.getOrDefault(item, item); }

    boolean purchased(ResourceLocation item, Set<ResourceLocation> unlocked) {
        ResourceLocation representative = representative(item);
        return unlocked.stream().anyMatch(member -> representative(member).equals(representative));
    }

    private static boolean excluded(ItemStack stack) {
        return stack.getTags().map(tag -> tag.location()).anyMatch(tag -> (tag.getNamespace().equals("c")
                && (tag.getPath().equals("storage_blocks") || tag.getPath().startsWith("storage_blocks/")))
                || tag.getPath().equals("wild_crops") || tag.getPath().startsWith("wild_crops/"));
    }

    private boolean food(ResourceLocation id) {
        ItemStack stack = stack(id);
        var food = stack.get(DataComponents.FOOD);
        if (harmful(stack)) return false;
        return !stack.isEmpty() && !excluded(stack) && (food != null
                || stack.getTags().map(tag -> tag.location()).anyMatch(tag -> config.foodTags().contains(tag)
                        && !(tag.getNamespace().equals("c")
                            && (tag.getPath().equals("seeds") || tag.getPath().startsWith("seeds/")))));
    }

    static boolean harmful(ItemStack stack) {
        var food = stack.get(DataComponents.FOOD);
        return food != null && food.effects().stream().anyMatch(effect -> effect.probability() >= HARMFUL_EFFECT_PROBABILITY
                && effect.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
    }

    boolean excludesDish(ResourceLocation id) {
        return rawIngredients.contains(id) || ignored.contains(id) || blocked.contains(id);
    }

    private boolean free(ResourceLocation item) {
        return config.free().contains(item) || (!blocked.contains(item) && !isFluidKey(item)
                && !food(item) && !dishWorth.containsKey(item) && !foodDependencies.contains(item));
    }

    // An empty recipe list marks a known result with no usable alternative. An absent result has no indexed recipe.
    private boolean addRecipe(List<Recipe> alternatives, List<Set<ResourceLocation>> slots, Set<ResourceLocation> machines) {
        if (slots.isEmpty()) return false;
        if (slots.stream().anyMatch(Set::isEmpty)) return false;
        alternatives.add(new Recipe(slots, machines));
        return true;
    }

    /** Recipe fields that are no input: the result, and names, numbers, sounds and looks. */
    private static final Set<String> NOT_INPUTS = Set.of("type", "group", "category",
            "texture", "finished_texture", "model_id", "sound", "render", "secondaries", "neoforge:conditions", "conditions");

    /**
     * Inputs a recipe keeps outside getIngredients(), like a Kaleidoscope soup base or a Tavern barrel's fluid: every item, item
     * tag or fluid (as its bucket) in the recipe's data that no visible slot has. Vanilla recipes show all their inputs.
     */
    static List<Set<ResourceLocation>> hidden(net.minecraft.world.item.crafting.Recipe<?> recipe, HolderLookup.Provider registries,
            List<Set<ResourceLocation>> visible, ResourceLocation result) {
        if ("minecraft".equals(Machines.kind(recipe).getNamespace())) return List.of();
        var json = data(recipe, registries);
        return json == null ? List.of() : hidden(json, visible, result);
    }

    /** A recipe with no item result makes a fluid (a pressing tub's grape juice): its key, or null. */
    private static ResourceLocation fluidResult(net.minecraft.world.item.crafting.Recipe<?> recipe, HolderLookup.Provider registries) {
        var json = data(recipe, registries);
        return json == null ? null : fluidResult(json);
    }

    /** The key of the fluid a result-less recipe's data names: a field named like an output first, else the first fluid. */
    public static ResourceLocation fluidResult(com.google.gson.JsonElement json) {
        if (!json.isJsonObject()) return null;
        ResourceLocation first = null;
        for (var e : json.getAsJsonObject().entrySet()) {
            String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
            var value = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("id") : e.getValue();
            if (key.contains("input") || value == null || !value.isJsonPrimitive()) continue;
            ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
            if (id == null || !BuiltInRegistries.FLUID.containsKey(id) || BuiltInRegistries.FLUID.get(id) == Fluids.EMPTY) continue;
            if (key.contains("output") || key.contains("result")) return fluidKey(BuiltInRegistries.FLUID.get(id));
            if (first == null) first = fluidKey(BuiltInRegistries.FLUID.get(id));
        }
        return first;
    }

    /**
     * A fluid in the index: its bucket, or for a fluid with no bucket (Brewin's beer in a keg) a "fluid/" key that only
     * a recipe can make; it is never bought.
     */
    static ResourceLocation fluidKey(net.minecraft.world.level.material.Fluid fluid) {
        var bucket = fluid.getBucket();
        if (bucket != Items.AIR) return BuiltInRegistries.ITEM.getKey(bucket);
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), FLUID + id.getPath());
    }

    private static final String FLUID = "fluid/";

    static boolean isFluidKey(ResourceLocation id) { return id.getPath().startsWith(FLUID); }

    private static com.google.gson.JsonElement data(net.minecraft.world.item.crafting.Recipe<?> recipe, HolderLookup.Provider registries) {
        if (registries == null) return null;
        @SuppressWarnings("unchecked")
        var codec = (com.mojang.serialization.MapCodec<net.minecraft.world.item.crafting.Recipe<?>>) recipe.getSerializer().codec();
        return codec.codec().encodeStart(registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), recipe)
                .result().orElse(null);
    }

    /** The inputs in a recipe's data that no visible slot has, one slot each. */
    public static List<Set<ResourceLocation>> hidden(com.google.gson.JsonElement json, List<Set<ResourceLocation>> visible,
            ResourceLocation result) {
        Set<String> names = new java.util.LinkedHashSet<>();
        collect(json, names, false);
        Set<ResourceLocation> seen = new HashSet<>();
        visible.forEach(seen::addAll);
        seen.add(result);
        List<Set<ResourceLocation>> slots = new ArrayList<>();
        for (String name : names) {
            Set<ResourceLocation> items = items(name);
            if (!items.isEmpty() && java.util.Collections.disjoint(items, seen)) {
                slots.add(items);
                seen.addAll(items);
            }
        }
        return slots;
    }

    /** Names under a field named like a fluid start with "~": the fluid of that id comes before an item of the same id. */
    private static void collect(com.google.gson.JsonElement json, Set<String> names, boolean fluid) {
        if (json.isJsonArray()) json.getAsJsonArray().forEach(e -> collect(e, names, fluid));
        else if (json.isJsonObject()) json.getAsJsonObject().entrySet().forEach(e -> {
            String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
            // A tool (a cutting board's knife or axe) is not used up.
            if (NOT_INPUTS.contains(key) || key.contains("output") || key.contains("result") || key.contains("tool")) return;
            boolean inFluid = fluid || key.contains("fluid");
            if (key.equals("tag") && e.getValue().isJsonPrimitive()) names.add((inFluid ? "~#" : "#") + e.getValue().getAsString());
            else collect(e.getValue(), names, inFluid);
        });
        else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) names.add((fluid ? "~" : "") + json.getAsString());
    }

    /** An item, an item tag, a fluid or a fluid tag (as fluid keys); empty when the name is none of them. */
    private static Set<ResourceLocation> items(String name) {
        boolean fluidFirst = name.startsWith("~");
        String rest = fluidFirst ? name.substring(1) : name;
        boolean tag = rest.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(rest.replaceFirst("^#+", ""));
        if (id == null) return Set.of();
        Set<ResourceLocation> items = new TreeSet<>();
        if (fluidFirst) fluids(id, tag, items);
        if (items.isEmpty()) {
            if (tag) BuiltInRegistries.ITEM.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, id))
                    .ifPresent(set -> set.forEach(item -> items.add(BuiltInRegistries.ITEM.getKey(item.value()))));
            else if (BuiltInRegistries.ITEM.containsKey(id)) items.add(id);
        }
        if (items.isEmpty() && !fluidFirst) fluids(id, tag, items);
        items.remove(BuiltInRegistries.ITEM.getDefaultKey());
        return items;
    }

    private static void fluids(ResourceLocation id, boolean tag, Set<ResourceLocation> items) {
        if (tag) BuiltInRegistries.FLUID.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.FLUID, id))
                .ifPresent(set -> set.forEach(fluid -> { if (fluid.value() != Fluids.EMPTY) items.add(fluidKey(fluid.value())); }));
        else if (BuiltInRegistries.FLUID.containsKey(id) && BuiltInRegistries.FLUID.get(id) != Fluids.EMPTY)
            items.add(fluidKey(BuiltInRegistries.FLUID.get(id)));
    }

    private static boolean realIngredient(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // Vanilla represents an empty tag with a placeholder barrier in getItems().
        var name = stack.get(DataComponents.CUSTOM_NAME);
        return !stack.is(Items.BARRIER) || name == null || !name.getString().startsWith("Empty Tag: ");
    }

    private void indexDependencies(ResourceLocation item, ResourceLocation dish, int depth, Map<ResourceLocation, Integer> seen) {
        if (seen.getOrDefault(item, -1) >= depth) return;
        seen.put(item, depth);
        dishesByIngredient.computeIfAbsent(item, key -> new HashSet<>()).add(dish);
        if (config.free().contains(item) || ignored.contains(item)) return;
        if (portions.containsKey(item)) {
            for (var parent : portions.get(item)) indexDependencies(parent, dish, DEPTH, seen);
            return;
        }
        if (depth == 0 && (food(item) || isFluidKey(item))) return;
        for (var recipe : recipes.getOrDefault(item, List.of())) {
            for (var slot : recipe.slots()) {
                for (ResourceLocation input : slot) indexDependencies(input, dish, Math.max(0, depth - 1), seen);
            }
        }
    }

    private boolean recycles(ResourceLocation candidate, ResourceLocation item) {
        return recyclingInputs.computeIfAbsent(candidate, key -> {
            Set<ResourceLocation> direct = new HashSet<>();
            for (var recipe : recipes.getOrDefault(key, List.of())) {
                for (var slot : recipe.slots()) direct.addAll(slot);
            }
            Set<ResourceLocation> inputs = new HashSet<>(direct);
            for (var input : direct) {
                for (var recipe : recipes.getOrDefault(input, List.of())) {
                    for (var slot : recipe.slots()) inputs.addAll(slot);
                }
            }
            return inputs;
        }).contains(item);
    }

    /** Null means no path to a source. An empty set means the item can be made for free. */
    private Set<ResourceLocation> sources(ResourceLocation item, int depth, Set<ResourceLocation> path) {
        if (free(item)) return Set.of();
        if (blocked.contains(item) || path.contains(item)) return null;
        if (portions.containsKey(item)) {
            path.add(item);
            Set<ResourceLocation> result = new TreeSet<>();
            boolean possible = false;
            for (var parent : portions.get(item)) {
                var inputs = sources(parent, DEPTH, path);
                if (inputs == null) continue;
                possible = true;
                result.addAll(inputs);
            }
            path.remove(item);
            return possible ? result : null;
        }
        var alternatives = recipes.get(item);
        if ((depth == 0 || alternatives == null) && isFluidKey(item)) return null;
        if ((depth == 0 && food(item)) || alternatives == null) return food(item) ? Set.of(item) : Set.of();
        path.add(item);
        Set<ResourceLocation> result = null;
        boolean onlyRecycling = !alternatives.isEmpty();
        for (var recipe : alternatives) {
            Set<ResourceLocation> inputs = new HashSet<>();
            boolean possible = true;
            for (var slot : recipe.slots()) {
                boolean satisfied = false;
                boolean onlySkippedRecycling = true;
                for (ResourceLocation candidate : slot) {
                    if (recycles(candidate, item)) continue;
                    onlySkippedRecycling = false;
                    Set<ResourceLocation> sources = sources(candidate, Math.max(0, depth - 1), path);
                    if (sources == null) continue;
                    satisfied = true;
                    inputs.addAll(sources);
                }
                if (!satisfied) {
                    possible = false;
                    if (!onlySkippedRecycling) onlyRecycling = false;
                }
            }
            if (!possible) continue;
            if (inputs.isEmpty()) {
                result = Set.of();
                break;
            }
            if (result == null) result = new HashSet<>();
            result.addAll(inputs);
        }
        path.remove(item);
        // Keep a closed conversion cycle buyable, but not an unusable recipe or a bucketless fluid.
        if (result == null && onlyRecycling && !isFluidKey(item)) return Set.of(item);
        return result;
    }

    Set<ResourceLocation> dishesHelped(ResourceLocation item) { return dishesByIngredient.getOrDefault(item, Set.of()); }

    int unlockCost(ResourceLocation item) { return item == null ? -1 : unlockCosts.getOrDefault(representative(item), -1); }

    int shopPrice(ResourceLocation item) {
        item = representative(item);
        return config.shopPrice(item, unlockCosts.containsKey(item) ? unlockCosts.get(item) : automaticCost(item));
    }

    private int automaticCost(ResourceLocation item) {
        Set<ResourceLocation> dishes = dishesHelped(item);
        long worth = dishes.stream().mapToLong(dishWorth::get).sum();
        return config.unlockCost(item, dishes.size(), worth);
    }

    static ResourceLocation id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()); }

    static ItemStack stack(ResourceLocation id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    static Stream<ItemStack> variants(Menu.Entry entry) {
        return entry.tag() == null ? Stream.of(entry.stack())
                : BuiltInRegistries.ITEM.getTag(entry.tag()).stream().flatMap(tag -> tag.stream()).map(ItemStack::new);
    }

    /** Preserve each formerly available dish variant, including alternative recipes and tags. */
    void grantLegacy(Menu.Entry dish, Set<ResourceLocation> unlocked) {
        variants(dish).forEach(stack -> {
            var alternatives = recipes.getOrDefault(id(stack), List.of());
            for (var recipe : alternatives) {
                for (var slot : recipe.slots()) {
                    for (var item : slot) if (!config.free().contains(item) && !ignored.contains(item)) unlocked.add(item);
                }
            }
        });
    }

    Availability availability(Set<ResourceLocation> unlocked) { return new Availability(unlocked, null); }

    /** With machines null, recipes need no machine; otherwise one of a recipe's machines must be in the set. */
    Availability availability(Set<ResourceLocation> unlocked, Set<ResourceLocation> machines) { return new Availability(unlocked, machines); }

    final class Availability {
        private record AtDepth(ResourceLocation item, int depth) {}
        private final Set<ResourceLocation> unlocked;
        private final Set<ResourceLocation> present;
        private final Map<AtDepth, Boolean> available = new HashMap<>();

        private Availability(Set<ResourceLocation> unlocked, Set<ResourceLocation> present) {
            this.unlocked = unlocked.stream().map(Ingredients.this::representative).collect(java.util.stream.Collectors.toSet());
            this.present = present;
        }

        private boolean machine(Recipe recipe) {
            return present == null || recipe.machines().isEmpty() || recipe.machines().stream().anyMatch(present::contains);
        }

        boolean dish(ResourceLocation id) {
            if (excludesDish(id) || !DishBlacklist.isDishCandidate(stack(id))) return false;
            var alternatives = recipes.get(id);
            return alternatives == null || alternatives.stream().anyMatch(recipe -> satisfied(recipe, DEPTH));
        }

        boolean item(ResourceLocation id) { return item(id, DEPTH); }

        private boolean item(ResourceLocation item, int depth) {
            if (free(item)) return true;
            if (blocked.contains(item)) return false;
            if (unlocked.contains(representative(item))) return true;
            AtDepth key = new AtDepth(item, depth);
            Boolean known = available.get(key);
            if (known != null) return known;
            available.put(key, false);
            if (portions.containsKey(item)) {
                boolean result = portions.get(item).stream().anyMatch(parent -> item(parent, DEPTH));
                available.put(key, result);
                return result;
            }
            if (depth == 0 && (food(item) || isFluidKey(item))) return false;
            // The in-progress entry stops cycles when a non-food intermediate keeps the same depth.
            boolean result = recipes.getOrDefault(item, List.of()).stream().anyMatch(recipe -> satisfied(recipe, Math.max(0, depth - 1)));
            available.put(key, result);
            return result;
        }

        private boolean satisfied(Recipe recipe, int depth) {
            return machine(recipe) && recipe.slots().stream().allMatch(slot -> slot.stream().anyMatch(item -> item(item, depth)));
        }

        List<ResourceLocation> missing(Menu.Entry entry) {
            return variants(entry).map(Ingredients::id).map(id -> missing(id, DEPTH, new HashSet<>()))
                    .filter(java.util.Objects::nonNull).min(java.util.Comparator.comparingInt(Set::size))
                    .map(ids -> ids.stream().limit(6).toList()).orElse(List.of());
        }

        private Set<ResourceLocation> missing(ResourceLocation id, int depth, Set<ResourceLocation> path) {
            if (!path.add(id)) return null;
            Set<ResourceLocation> best = null;
            int nextDepth = portions.containsKey(id) ? DEPTH : depth - 1;
            for (var recipe : recipes.getOrDefault(id, List.of())) {
                Set<ResourceLocation> requirements = new LinkedHashSet<>();
                // A missing machine shows as its item, before the ingredients.
                if (!machine(recipe)) recipe.machines().stream().sorted().findFirst()
                        .map(block -> BuiltInRegistries.ITEM.getKey(BuiltInRegistries.BLOCK.get(block).asItem()))
                        .filter(item -> !item.equals(BuiltInRegistries.ITEM.getDefaultKey())).ifPresent(requirements::add);
                boolean possible = true;
                for (var slot : recipe.slots()) {
                    Set<ResourceLocation> fewest = null;
                    for (ResourceLocation candidate : slot) {
                        int candidateDepth = portions.containsKey(candidate) ? DEPTH
                                : !food(candidate) && !isFluidKey(candidate) ? Math.max(0, nextDepth) : nextDepth;
                        Set<ResourceLocation> needed = item(candidate, depth) ? Set.of()
                                : unlockCosts.containsKey(representative(candidate)) ? Set.of(representative(candidate))
                                : candidateDepth >= 0 && !ignored.contains(candidate) ? missing(candidate, candidateDepth, path) : null;
                        if (needed != null && (fewest == null || needed.size() < fewest.size())) fewest = needed;
                    }
                    if (fewest == null) {
                        possible = false;
                        break;
                    }
                    requirements.addAll(fewest);
                }
                if (possible && (best == null || requirements.size() < best.size())) best = requirements;
            }
            path.remove(id);
            return best;
        }
    }
}
