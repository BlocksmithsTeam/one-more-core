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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

/** The server's recipe index. Each recipe is a list of slots, each with alternative item IDs. */
final class Ingredients {
    private static final int DEPTH = 3;
    private static Ingredients current = new Ingredients(List.of(), IngredientConfig.parse("{}"), List.of(), null);

    private final IngredientConfig config;
    private final Map<ResourceLocation, List<List<Set<ResourceLocation>>>> recipes = new HashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> dishesByIngredient = new HashMap<>();
    private final Map<ResourceLocation, Integer> unlockCosts = new HashMap<>();
    private final Map<ResourceLocation, Integer> dishWorth = new HashMap<>();
    final List<ResourceLocation> raw;
    final List<Menu.Entry> automatic;

    static Ingredients current() { return current; }

    static void rebuild(MinecraftServer server) {
        current = new Ingredients(server.getRecipeManager().getRecipes(), IngredientConfig.load(), Menu.entries,
                server.registryAccess());
    }

    private Ingredients(Collection<RecipeHolder<?>> holders, IngredientConfig config, List<Menu.Entry> configured,
            HolderLookup.Provider registries) {
        this.config = config;
        int skipped = 0;
        Map<ResourceLocation, ItemStack> foodResults = new HashMap<>();
        for (RecipeHolder<?> holder : holders.stream().sorted(Comparator.comparing(RecipeHolder::id)).toList()) {
            try {
                var recipe = holder.value();
                ItemStack result = recipe.getResultItem(registries);
                if (result == null || result.isEmpty()) {
                    skipped++;
                    continue;
                }
                List<Set<ResourceLocation>> slots = recipe.getIngredients().stream()
                        .filter(ingredient -> !ingredient.isEmpty())
                        .map(ingredient -> {
                            Set<ResourceLocation> ids = new TreeSet<>();
                            Arrays.stream(ingredient.getItems()).filter(Ingredients::realIngredient)
                                    .map(Ingredients::id).forEach(ids::add);
                            return ids;
                        }).toList();
                ResourceLocation id = id(result);
                recipes.computeIfAbsent(id, key -> new ArrayList<>()).add(slots);
                if (Restaurant.isFood(result)) foodResults.putIfAbsent(id, result.copyWithCount(1));
            } catch (RuntimeException e) {
                skipped++;
                OneMoreCore.LOGGER.debug("Recipe {} does not expose its result or ingredients", holder.id(), e);
            }
        }
        config.dishes().forEach((id, slots) -> {
            recipes.put(id, List.of(slots));
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
        automatic = List.copyOf(discovered);

        Set<ResourceLocation> rawIds = new TreeSet<>();
        int withIngredients = 0;
        for (ResourceLocation dish : dishWorth.keySet()) {
            var alternatives = recipes.get(dish);
            if (alternatives == null) continue;
            if (alternatives.stream().anyMatch(recipe -> !recipe.isEmpty())) withIngredients++;
            Map<ResourceLocation, Integer> seen = new HashMap<>();
            for (var recipe : alternatives) {
                for (var slot : recipe) {
                    for (ResourceLocation item : slot) {
                        if (config.free().contains(item) || config.ignore().contains(item)) continue;
                        Set<ResourceLocation> sources = sources(item, DEPTH, new HashSet<>());
                        if (sources == null) rawIds.add(item);
                        else rawIds.addAll(sources);
                        indexDependencies(item, dish, DEPTH, seen);
                    }
                }
            }
        }
        raw = List.copyOf(rawIds);
        raw.forEach(id -> unlockCosts.put(id, automaticCost(id)));
        if (registries != null) OneMoreCore.LOGGER.info(
                "Ingredient index: {} dishes with ingredients, {} without; {} recipes without readable results or ingredients",
                withIngredients, dishWorth.size() - withIngredients, skipped);
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
        if (depth == 0 || config.free().contains(item) || config.ignore().contains(item)) return;
        for (var recipe : recipes.getOrDefault(item, List.of())) {
            for (var slot : recipe) {
                for (ResourceLocation input : slot) indexDependencies(input, dish, depth - 1, seen);
            }
        }
    }

    /** Null means no path to a source. An empty set means the item can be made for free. */
    private Set<ResourceLocation> sources(ResourceLocation item, int depth, Set<ResourceLocation> path) {
        if (config.free().contains(item)) return Set.of();
        if (config.ignore().contains(item) || path.contains(item)) return null;
        var alternatives = recipes.get(item);
        if (depth == 0 || alternatives == null) return Set.of(item);
        path.add(item);
        Set<ResourceLocation> result = null;
        for (var recipe : alternatives) {
            Set<ResourceLocation> inputs = new HashSet<>();
            boolean possible = true;
            for (var slot : recipe) {
                boolean satisfied = false;
                for (ResourceLocation candidate : slot) {
                    Set<ResourceLocation> sources = sources(candidate, depth - 1, path);
                    if (sources == null) continue;
                    satisfied = true;
                    inputs.addAll(sources);
                }
                if (!satisfied) {
                    possible = false;
                    break;
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
        return result;
    }

    Set<ResourceLocation> dishesHelped(ResourceLocation item) { return dishesByIngredient.getOrDefault(item, Set.of()); }

    int unlockCost(ResourceLocation item) { return item == null ? -1 : unlockCosts.getOrDefault(item, -1); }

    int shopPrice(ResourceLocation item) {
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
                for (var slot : recipe) {
                    for (var item : slot) if (!config.free().contains(item) && !config.ignore().contains(item)) unlocked.add(item);
                }
            }
        });
    }

    Availability availability(Set<ResourceLocation> unlocked) { return new Availability(unlocked); }

    final class Availability {
        private record AtDepth(ResourceLocation item, int depth) {}
        private final Set<ResourceLocation> unlocked;
        private final Map<AtDepth, Boolean> available = new HashMap<>();

        private Availability(Set<ResourceLocation> unlocked) { this.unlocked = unlocked; }

        boolean dish(ResourceLocation id) {
            var alternatives = recipes.get(id);
            return alternatives == null || alternatives.stream().anyMatch(recipe -> satisfied(recipe, DEPTH));
        }

        boolean item(ResourceLocation id) { return item(id, DEPTH); }

        private boolean item(ResourceLocation item, int depth) {
            if (config.free().contains(item) || unlocked.contains(item)) return true;
            if (depth == 0 || config.ignore().contains(item)) return false;
            AtDepth key = new AtDepth(item, depth);
            Boolean known = available.get(key);
            if (known != null) return known;
            // Each edge consumes a level, so a cycle can only succeed if it reaches an unlocked or free item.
            boolean result = recipes.getOrDefault(item, List.of()).stream().anyMatch(recipe -> satisfied(recipe, depth - 1));
            available.put(key, result);
            return result;
        }

        private boolean satisfied(List<Set<ResourceLocation>> recipe, int depth) {
            return recipe.stream().allMatch(slot -> slot.stream().anyMatch(item -> item(item, depth)));
        }

        List<ResourceLocation> missing(Menu.Entry entry) {
            return variants(entry).map(Ingredients::id).map(id -> missing(id, DEPTH, new HashSet<>()))
                    .filter(java.util.Objects::nonNull).min(java.util.Comparator.comparingInt(Set::size))
                    .map(ids -> ids.stream().limit(6).toList()).orElse(List.of());
        }

        private Set<ResourceLocation> missing(ResourceLocation id, int depth, Set<ResourceLocation> path) {
            if (!path.add(id)) return null;
            Set<ResourceLocation> best = null;
            for (var recipe : recipes.getOrDefault(id, List.of())) {
                Set<ResourceLocation> requirements = new LinkedHashSet<>();
                boolean possible = true;
                for (var slot : recipe) {
                    Set<ResourceLocation> fewest = null;
                    for (ResourceLocation candidate : slot) {
                        Set<ResourceLocation> needed = item(candidate, depth) ? Set.of()
                                : unlockCosts.containsKey(candidate) ? Set.of(candidate)
                                : depth > 0 && !config.ignore().contains(candidate) ? missing(candidate, depth - 1, path) : null;
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
