package com.onemorestudios.onemorecore.gametest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.DishBlacklist;
import com.onemorestudios.onemorecore.Menu;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.Shop;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class IngredientGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1);
    private static final BlockPos COMPUTER = SIGN.south();
    private static final ResourceLocation WHEAT = id("wheat");

    @GameTest(template = "empty")
    public static void unreadableRecipesRequireAnOverride(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(
                recipe("hidden", Items.COOKED_COD),
                recipe("hidden_intermediate", Items.SUGAR),
                recipe("with_intermediate", Items.PUMPKIN_PIE, Ingredient.of(Items.PUMPKIN), Ingredient.of(Items.SUGAR)),
                recipe("filtered_slot", Items.BREAD, Ingredient.of(Items.EGG)),
                recipe("free_slot", Items.MUSHROOM_STEW, Ingredient.of(Items.BOWL)),
                recipe("readable_alternate", Items.COOKED_CHICKEN, Ingredient.of(Items.WHEAT)),
                recipe("unreadable_alternate", Items.COOKED_CHICKEN));
        var ingredients = Class.forName("com.onemorestudios.onemorecore.Ingredients");
        var config = Class.forName("com.onemorestudios.onemorecore.IngredientConfig");
        var parse = config.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        var constructor = ingredients.getDeclaredConstructor(Collection.class, config, List.class, HolderLookup.Provider.class);
        constructor.setAccessible(true);
        var availability = ingredients.getDeclaredMethod("availability", Set.class);
        availability.setAccessible(true);
        var rules = parse.invoke(null, """
                {"minDishes":0,"free":["minecraft:bowl"],"ignore":["minecraft:egg"]}
                """);
        var index = constructor.newInstance(recipes, rules, List.of(), helper.getLevel().registryAccess());
        var access = availability.invoke(index, Set.of(id("egg"), id("pumpkin")));
        var dish = access.getClass().getDeclaredMethod("dish", ResourceLocation.class);
        dish.setAccessible(true);
        helper.assertFalse((boolean) dish.invoke(access, id("cooked_cod")), "An empty ingredient list keeps the dish locked");
        helper.assertFalse((boolean) dish.invoke(access, id("pumpkin_pie")), "An unreadable intermediate is unavailable");
        helper.assertFalse((boolean) dish.invoke(access, id("bread")), "A slot with only ignored candidates stays locked after a config change");
        helper.assertTrue((boolean) dish.invoke(access, id("mushroom_stew")), "A slot with only a free candidate is satisfied");
        helper.assertFalse((boolean) dish.invoke(access, id("cooked_chicken")), "An unreadable alternative cannot bypass a readable recipe");
        helper.assertTrue((boolean) dish.invoke(availability.invoke(index, Set.of(WHEAT)), id("cooked_chicken")),
                "A readable alternative can still unlock the dish");

        var corrected = parse.invoke(null, """
                {"minDishes":0,"dishes":{"minecraft:cooked_cod":["minecraft:cod"],"minecraft:bread":["#onemorecore:missing"]}}
                """);
        var correctedIndex = constructor.newInstance(recipes, corrected, List.of(), helper.getLevel().registryAccess());
        var correctedAccess = availability.invoke(correctedIndex, Set.of(id("cod")));
        helper.assertTrue((boolean) dish.invoke(correctedAccess, id("cooked_cod")), "An explicit ingredient override unlocks the dish");
        helper.assertFalse((boolean) dish.invoke(correctedAccess, id("bread")), "An empty override tag keeps the dish locked");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void purchaseSourcesSkipRecyclingAndKeepClosedCycles(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(
                recipe("planks_from_log", Items.OAK_PLANKS, Ingredient.of(Items.OAK_LOG)),
                recipe("boat_from_planks", Items.OAK_BOAT, Ingredient.of(Items.OAK_PLANKS)),
                recipe("planks_from_boat", Items.OAK_PLANKS, Ingredient.of(Items.OAK_BOAT)),
                recipe("chest_boat_from_boat", Items.OAK_CHEST_BOAT, Ingredient.of(Items.OAK_BOAT)),
                recipe("planks_from_chest_boat", Items.OAK_PLANKS, Ingredient.of(Items.OAK_CHEST_BOAT)),
                // Put planks at depth one so the old search buys boats at depth zero.
                recipe("stick_from_planks", Items.STICK, Ingredient.of(Items.OAK_PLANKS)),
                recipe("bowl_from_stick", Items.BOWL, Ingredient.of(Items.STICK)),
                recipe("stew_from_bowl", Items.MUSHROOM_STEW, Ingredient.of(Items.BOWL)),
                recipe("wheat_from_hay", Items.WHEAT, Ingredient.of(Items.HAY_BLOCK)),
                recipe("hay_from_wheat", Items.HAY_BLOCK, Ingredient.of(Items.WHEAT)),
                recipe("bread_from_wheat", Items.BREAD, Ingredient.of(Items.WHEAT)),
                recipe("unreadable_sugar", Items.SUGAR),
                recipe("emerald_from_diamond", Items.EMERALD, Ingredient.of(Items.DIAMOND)),
                recipe("diamond_from_mixed_slot", Items.DIAMOND, Ingredient.of(Items.EMERALD, Items.SUGAR)));
        var ingredients = Class.forName("com.onemorestudios.onemorecore.Ingredients");
        var config = Class.forName("com.onemorestudios.onemorecore.IngredientConfig");
        var parse = config.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        var constructor = ingredients.getDeclaredConstructor(Collection.class, config, List.class, HolderLookup.Provider.class);
        constructor.setAccessible(true);
        var configured = List.of(new Menu.Entry("stew", new ItemStack(Items.MUSHROOM_STEW), null, 4, false),
                new Menu.Entry("bread", new ItemStack(Items.BREAD), null, 2, false));
        var index = constructor.newInstance(recipes, parse.invoke(null, "{\"minDishes\":0,\"food_tags\":[\"c:foods\",\"c:crops\",\"c:gems\"]}"), configured, helper.getLevel().registryAccess());
        var rawField = ingredients.getDeclaredField("raw");
        rawField.setAccessible(true);
        helper.assertValueEqual(rawField.get(index), List.of(WHEAT),
                "Purchase sources keep wheat; non-food wood and boats are free");

        var sources = ingredients.getDeclaredMethod("sources", ResourceLocation.class, int.class, Set.class);
        sources.setAccessible(true);
        helper.assertTrue(sources.invoke(index, id("diamond"), 3, new java.util.HashSet<ResourceLocation>()) == null,
                "An unreadable candidate prevents the recycling-only fallback");
        helper.assertValueEqual(sources.invoke(index, WHEAT, 3, new java.util.HashSet<ResourceLocation>()), Set.of(WHEAT),
                "A closed conversion cycle keeps wheat as its source");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nonFoodChainsAreFreeAndNeverRaw(GameTestHelper helper) throws ReflectiveOperationException {
        var index = ingredientIndex(helper, "{\"minDishes\":0}", List.of(
                recipe("wood", Items.OAK_PLANKS, Ingredient.of(Items.OAK_LOG)),
                recipe("wood_cycle", Items.OAK_LOG, Ingredient.of(Items.OAK_PLANKS)),
                recipe("metal", Items.IRON_INGOT, Ingredient.of(Items.IRON_ORE)),
                recipe("bucket", Items.COD_BUCKET, Ingredient.of(Items.IRON_INGOT)),
                recipe("dish", Items.BREAD, Ingredient.of(Items.WHEAT), Ingredient.of(Items.OAK_PLANKS),
                        Ingredient.of(Items.COD_BUCKET), Ingredient.of(Items.OAK_LEAVES))));
        helper.assertValueEqual(raw(index), List.of(WHEAT), "Logs, ore, fish buckets and leaves are not raw ingredients");
        helper.assertFalse(dishAvailable(index, "bread", Set.of()), "The food input still locks the dish");
        helper.assertTrue(dishAvailable(index, "bread", Set.of(WHEAT)), "Non-food leaves do not lock the dish");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void foodFromOnlyNonFoodRemainsRaw(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(
                recipe("non_food", Items.PAPER, Ingredient.of(Items.OAK_LOG)),
                recipe("food", Items.APPLE, Ingredient.of(Items.PAPER)),
                recipe("dish", Items.BREAD, Ingredient.of(Items.APPLE)));
        var index = ingredientIndex(helper, "{\"minDishes\":0}", recipes);
        helper.assertValueEqual(raw(index), List.of(id("apple")), "Food made only from non-food stays raw");
        helper.assertFalse(dishAvailable(index, "bread", Set.of()), "The raw food locks its dish");
        helper.assertTrue(dishAvailable(index, "bread", Set.of(id("apple"))), "Buying the raw food unlocks its dish");
        var free = ingredientIndex(helper, "{\"minDishes\":0,\"free\":[\"minecraft:paper\"]}", recipes);
        helper.assertTrue(raw(free).isEmpty(), "An explicit free input still makes the food free");
        helper.assertTrue(dishAvailable(free, "bread", Set.of()), "An explicit free input still unlocks the dish");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void foodFromFoodResolvesToTheRawFood(GameTestHelper helper) throws ReflectiveOperationException {
        var index = ingredientIndex(helper, "{\"minDishes\":0}", List.of(
                recipe("non_food", Items.PAPER, Ingredient.of(Items.OAK_LOG)),
                recipe("raw_food", Items.APPLE, Ingredient.of(Items.PAPER)),
                recipe("processed_food", Items.COOKED_COD, Ingredient.of(Items.APPLE)),
                recipe("dish", Items.BREAD, Ingredient.of(Items.COOKED_COD))));
        helper.assertValueEqual(raw(index), List.of(id("apple")), "A food chain resolves to its raw food, not its processed food");
        helper.assertFalse(dishAvailable(index, "bread", Set.of()), "The food chain starts locked");
        helper.assertTrue(dishAvailable(index, "bread", Set.of(id("apple"))), "Buying the raw food unlocks the food chain");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storageContentsRemainBuyable(GameTestHelper helper) throws ReflectiveOperationException {
        var index = ingredientIndex(helper, "{\"minDishes\":0}", List.of(
                recipe("unpack", Items.WHEAT, Ingredient.of(Items.HAY_BLOCK)),
                recipe("pack", Items.HAY_BLOCK, Ingredient.of(Items.WHEAT)),
                recipe("first", Items.COOKED_COD, Ingredient.of(Items.HAY_BLOCK), Ingredient.of(Items.WHEAT)),
                recipe("second", Items.COOKED_BEEF, Ingredient.of(Items.COOKED_COD)),
                recipe("third", Items.COOKED_CHICKEN, Ingredient.of(Items.COOKED_BEEF)),
                recipe("dish", Items.BREAD, Ingredient.of(Items.COOKED_CHICKEN))));
        helper.assertValueEqual(raw(index), List.of(WHEAT), "Storage contents remain raw; storage blocks do not");
        helper.assertFalse(dishAvailable(index, "bread", Set.of()), "Unpacking storage does not grant free food");
        helper.assertTrue(dishAvailable(index, "bread", Set.of(WHEAT)), "Buying the contents unlocks the dish");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void foodTagsReplaceDefaultsButNotExclusions(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(recipe("dish", Items.BREAD, Ingredient.of(Items.IRON_INGOT),
                Ingredient.of(Items.WHEAT_SEEDS), Ingredient.of(Items.HAY_BLOCK), Ingredient.of(Items.OAK_SAPLING)));
        var defaults = ingredientIndex(helper, "{\"minDishes\":0}", recipes);
        helper.assertTrue(raw(defaults).isEmpty(), "Metal, seeds, storage and wild crops are not default food");
        var custom = ingredientIndex(helper, "{\"minDishes\":0,\"food_tags\":[\"onemorecore:ingredient_test_foods\"]}", recipes);
        helper.assertValueEqual(raw(custom), List.of(id("iron_ingot")), "Config reads food tags but excludes seeds, storage and wild crops");
        helper.assertFalse(dishAvailable(custom, "bread", Set.of()), "The configured food tag changes availability");
        helper.assertTrue(dishAvailable(custom, "bread", Set.of(id("iron_ingot"))), "The configured ingredient unlocks the dish");
        var noTags = ingredientIndex(helper, "{\"minDishes\":0,\"food_tags\":[]}", List.of(recipe("dish", Items.BREAD, Ingredient.of(Items.WHEAT))));
        helper.assertTrue(raw(noTags).isEmpty(), "An empty tag list replaces default crop tags");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void seedsWithFoodEvidenceRemainBuyable(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(recipe("dish", Items.BREAD, Ingredient.of(Items.WHEAT_SEEDS),
                Ingredient.of(Items.APPLE), Ingredient.of(Items.BEETROOT_SEEDS)));
        var index = ingredientIndex(helper, "{\"minDishes\":0,\"food_tags\":[\"onemorecore:ingredient_test_seed_foods\",\"c:seeds\",\"c:seeds/beetroot\"]}", recipes);
        helper.assertValueEqual(raw(index), List.of(id("apple"), id("wheat_seeds")),
                "Edible seeds and seeds in another food tag remain raw; seed tags alone do not qualify");
        helper.assertFalse(dishAvailable(index, "bread", Set.of()), "Food seeds lock their dish");
        helper.assertTrue(dishAvailable(index, "bread", Set.of(id("apple"), id("wheat_seeds"))),
                "Buying food seeds unlocks their dish");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void singularFlourIsDefaultFood(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(recipe("dish", Items.BREAD, Ingredient.of(Items.QUARTZ)));
        helper.assertValueEqual(raw(ingredientIndex(helper, "{\"minDishes\":0}", recipes)), List.of(id("quartz")),
                "The singular c:flour tag qualifies without a food component");
        helper.assertTrue(raw(ingredientIndex(helper, "{\"minDishes\":0,\"food_tags\":[]}", recipes)).isEmpty(),
                "Explicit food tags still replace defaults");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void likelyHarmfulFoodEffectsExcludeRawPurchases(GameTestHelper helper) throws ReflectiveOperationException {
        var index = ingredientIndex(helper, "{\"minDishes\":0}", List.of(recipe("dish", Items.BREAD,
                Ingredient.of(Items.ROTTEN_FLESH), Ingredient.of(Items.SPIDER_EYE), Ingredient.of(Items.PUFFERFISH),
                Ingredient.of(Items.POISONOUS_POTATO), Ingredient.of(Items.CHICKEN), Ingredient.of(Items.GOLDEN_APPLE), Ingredient.of(Items.POTATO))));
        helper.assertTrue(raw(index).contains(id("chicken")), "A 0.3 hunger food remains raw");
        helper.assertFalse(raw(index).contains(id("spider_eye")), "A 1.0 poison food is excluded");
        helper.assertValueEqual(raw(index), List.of(id("chicken"), id("golden_apple"), id("potato")),
                "Harmful effects exclude food at probability >= 0.5; beneficial effects and safe food remain raw");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void defaultIgnoresOnlyGoIntoNewFiles(GameTestHelper helper) throws Exception {
        var config = Class.forName("com.onemorestudios.onemorecore.IngredientConfig");
        var load = config.getDeclaredMethod("load", java.nio.file.Path.class);
        load.setAccessible(true);
        var directory = java.nio.file.Files.createTempDirectory("ingredient-defaults");
        var path = directory.resolve("ingredients.json");
        try {
            load.invoke(null, path);
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            helper.assertValueEqual(json.getAsJsonArray("ignore").asList().stream().map(com.google.gson.JsonElement::getAsString).toList(),
                    List.of("minecraft:cactus", "minecraft:nether_wart", "minecraft:ominous_bottle",
                            "minecraft:chorus_fruit", "minecraft:turtle_egg"), "New files contain the five default ignores");
            helper.assertTrue(json.getAsJsonArray("food_tags").asList().stream()
                    .anyMatch(tag -> tag.getAsString().equals("c:flour")), "New files include singular flour");
            helper.assertValueEqual(json.get("minDishes").getAsInt(), 0, "New files keep every ingredient (minDishes off)");
            helper.assertValueEqual(json.getAsJsonArray("groups").size(), 12, "New files contain twelve default groups");
            json.addProperty("minDishes", 0);
            var recipes = List.of(recipe("dish", Items.BREAD, Ingredient.of(Items.CHORUS_FRUIT, Items.WHEAT)));
            helper.assertValueEqual(raw(ingredientIndex(helper, json.toString(), recipes)), List.of(WHEAT),
                    "Generated ignores exclude otherwise edible ingredients");
            String existing = "{\"ignore\":[],\"food_tags\":[]}";
            java.nio.file.Files.writeString(path, existing);
            var existingRules = load.invoke(null, path);
            var groups = config.getDeclaredMethod("groups");
            var minimum = config.getDeclaredMethod("minDishes");
            groups.setAccessible(true);
            minimum.setAccessible(true);
            helper.assertTrue(((List<?>) groups.invoke(existingRules)).isEmpty(), "Existing files do not inherit default groups");
            helper.assertValueEqual(minimum.invoke(existingRules), 0, "An omitted threshold is off");
            helper.assertValueEqual(java.nio.file.Files.readString(path), existing, "Existing files remain byte-for-byte unchanged");
            helper.assertValueEqual(raw(ingredientIndex(helper, "{\"minDishes\":0}", recipes)), List.of(id("chorus_fruit"), WHEAT),
                    "Existing files without ignores do not inherit new default ignores");
        } finally {
            java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.deleteIfExists(directory);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cutPortionsFollowTheWholeDishEvenAtTheDepthLimit(GameTestHelper helper) throws ReflectiveOperationException {
        var portion = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.COOKIE),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.PUMPKIN_PIE))) {
            @Override
            public net.minecraft.world.item.crafting.RecipeType<net.minecraft.world.item.crafting.CraftingRecipe> getType() {
                return net.minecraft.world.item.crafting.RecipeType.simple(ResourceLocation.parse("onemorecore:test_cutting"));
            }
        };
        var index = ingredientIndex(helper, "{\"minDishes\":0}", List.of(
                new RecipeHolder<>(ResourceLocation.parse("onemorecore:portion"), portion),
                recipe("whole", Items.PUMPKIN_PIE, Ingredient.of(Items.WHEAT)),
                recipe("reassemble", Items.PUMPKIN_PIE, Ingredient.of(Items.COOKIE)),
                recipe("first", Items.COOKED_BEEF, Ingredient.of(Items.COOKIE)),
                recipe("second", Items.COOKED_COD, Ingredient.of(Items.COOKED_BEEF)),
                recipe("third", Items.COOKED_CHICKEN, Ingredient.of(Items.COOKED_COD)),
                recipe("last", Items.BREAD, Ingredient.of(Items.COOKED_CHICKEN))));
        helper.assertValueEqual(raw(index), List.of(WHEAT), "A portion follows the whole dish instead of becoming raw at depth zero");
        helper.assertFalse(dishAvailable(index, "bread", Set.of()), "The whole dish starts locked");
        helper.assertTrue(dishAvailable(index, "bread", Set.of(WHEAT)), "An orderable whole dish makes its portion available");
        var helped = index.getClass().getDeclaredMethod("dishesHelped", ResourceLocation.class);
        helped.setAccessible(true);
        helper.assertTrue(((Set<?>) helped.invoke(index, WHEAT)).contains(id("bread")), "The ingredient preview follows the whole dish too");
        var availability = index.getClass().getDeclaredMethod("availability", Set.class);
        availability.setAccessible(true);
        var access = availability.invoke(index, Set.of());
        var missing = access.getClass().getDeclaredMethod("missing", Menu.Entry.class);
        missing.setAccessible(true);
        helper.assertValueEqual(missing.invoke(access, new Menu.Entry("bread", new ItemStack(Items.BREAD), null, 4, false)),
                List.of(WHEAT), "The missing ingredient icon follows the whole dish at depth zero");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rawIgnoredAndHarmfulFoodsAreNotDishes(GameTestHelper helper) throws ReflectiveOperationException {
        var current = Class.forName("com.onemorestudios.onemorecore.Ingredients").getDeclaredField("current");
        current.setAccessible(true);
        Object before = current.get(null);
        try {
            DishBlacklist.set(List.of());
            var recipes = List.of(
                    recipe("raw_potato", Items.POTATO, Ingredient.of(Items.DIRT)),
                    recipe("baked_potato", Items.BAKED_POTATO, Ingredient.of(Items.POTATO)),
                    recipe("ignored_apple", Items.APPLE, Ingredient.of(Items.WHEAT)),
                    recipe("harmful_food", Items.ROTTEN_FLESH, Ingredient.of(Items.WHEAT)));
            Object index = ingredientIndex(helper, "{\"minDishes\":0,\"ignore\":[\"minecraft:apple\"]}", recipes);
            current.set(null, index);
            helper.assertTrue(raw(index).contains(id("potato")), "The fixture indexes potato as a buyable raw ingredient");
            for (var item : List.of(Items.POTATO, Items.ROTTEN_FLESH, Items.APPLE)) {
                helper.assertFalse(DishBlacklist.isDish(new ItemStack(item)), "Raw, harmful and ignored foods cannot be dishes: " + item);
                helper.assertFalse(Restaurant.isFood(new ItemStack(item)), "The restaurant uses the same dish rule: " + item);
                helper.assertFalse(dishAvailable(index, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath(),
                        Set.of(id("potato"), WHEAT)), "Purchases cannot make excluded foods orderable: " + item);
            }
            helper.assertTrue(Restaurant.isFood(new ItemStack(Items.BAKED_POTATO)), "Baked potato remains a dish");
            helper.assertFalse(dishAvailable(index, "baked_potato", Set.of()), "Baked potato still needs its ingredient");
            helper.assertTrue(dishAvailable(index, "baked_potato", Set.of(id("potato"))), "Buying potato unlocks baked potato");
            var automatic = index.getClass().getDeclaredField("automatic");
            automatic.setAccessible(true);
            helper.assertValueEqual(((List<?>) automatic.get(index)).stream().map(entry -> ((Menu.Entry) entry).stack().getItem()).toList(),
                    List.of(Items.BAKED_POTATO), "Discovery omits excluded foods");

            var restaurant = place(helper);
            var custom = Restaurant.class.getDeclaredField("custom");
            custom.setAccessible(true);
            @SuppressWarnings("unchecked")
            var entries = (List<Menu.Entry>) custom.get(restaurant);
            for (var item : List.of(Items.POTATO, Items.ROTTEN_FLESH, Items.APPLE))
                entries.add(new Menu.Entry("excluded:" + item, new ItemStack(item), null, 1, false));
            helper.assertTrue(restaurant.dishes().stream().filter(entry -> entry.tag() == null).map(Menu.Entry::stack)
                    .noneMatch(stack -> stack.is(Items.POTATO) || stack.is(Items.ROTTEN_FLESH) || stack.is(Items.APPLE)),
                    "Saved custom and configured dishes cannot bypass the catalogue filter");

            Object rebuilt = ingredientIndex(helper, "{\"minDishes\":0,\"free\":[\"minecraft:potato\"]}", recipes);
            current.set(null, rebuilt);
            helper.assertTrue(Restaurant.isFood(new ItemStack(Items.POTATO)), "The rebuilt index releases a former raw ingredient");
            helper.assertTrue(((List<?>) automatic.get(rebuilt)).stream().anyMatch(entry -> ((Menu.Entry) entry).stack().is(Items.APPLE)),
                    "Discovery does not inherit the previous index's ignores");
            DishBlacklist.set(List.of("minecraft:baked_potato"));
            helper.assertFalse(Restaurant.isFood(new ItemStack(Items.BAKED_POTATO)), "The blacklist still excludes cooked dishes");
            helper.assertFalse(dishAvailable(rebuilt, "baked_potato", Set.of()), "The blacklist also excludes order variants");
        } finally {
            current.set(null, before);
            DishBlacklist.reload();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void groupedIngredientsShareUnlockCardAndShop(GameTestHelper helper) throws ReflectiveOperationException {
        var current = Class.forName("com.onemorestudios.onemorecore.Ingredients").getDeclaredField("current");
        current.setAccessible(true);
        Object before = current.get(null);
        var menuBefore = Menu.entries;
        var shopBefore = Shop.entries;
        try {
            Menu.entries = List.of();
            Shop.entries = List.of();
            DishBlacklist.set(List.of());
            var index = ingredientIndex(helper, """
                    {"minDishes":4,"groups":[{"name":"test","members":["minecraft:wheat","#c:crops/carrot"]}],
                     "prices":{"minecraft:wheat":{"unlock":13,"shop":7},"minecraft:carrot":{"unlock":31,"shop":9}}}
                    """, List.of(
                            recipe("bread", Items.BREAD, Ingredient.of(Items.WHEAT)),
                            recipe("cookie", Items.COOKIE, Ingredient.of(Items.WHEAT)),
                            recipe("pie", Items.PUMPKIN_PIE, Ingredient.of(Items.CARROT)),
                            recipe("stew", Items.RABBIT_STEW, Ingredient.of(Items.WHEAT, Items.CARROT))));
            current.set(null, index);
            helper.assertValueEqual(raw(index), List.of(WHEAT), "The union has four dishes; wheat helps three and carrot two");
            for (var member : List.of(WHEAT, id("carrot"))) {
                for (var dish : List.of("bread", "cookie", "pumpkin_pie", "rabbit_stew")) {
                    helper.assertFalse(dishAvailable(index, dish, Set.of()), "Grouped dishes start locked");
                    helper.assertTrue(dishAvailable(index, dish, Set.of(member)), "Either saved member unlocks every group dish");
                }
            }
            var restaurant = place(helper);
            helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
            var player = GameTestSupport.player(helper);
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer opens");
            try {
                var data = request(helper, player, Computer.REFRESH_MENU).ingredientData().orElseThrow();
                helper.assertValueEqual(data.ingredients().size(), 1, "The group has one card");
                var card = data.ingredients().getFirst();
                helper.assertValueEqual(card.id(), WHEAT, "The card uses the member with most dishes");
                helper.assertValueEqual(card.unlockCost(), 13, "The representative supplies the single unlock price");
                helper.assertValueEqual(card.newlyOrderable(), 4, "The preview counts the union without duplicate dishes");
                helper.assertTrue(Shop.catalog(restaurant).isEmpty(), "A locked group has no Shop listing");
                GameTestSupport.setRegister(restaurant, 13);
                helper.assertValueEqual(restaurant.unlockIngredient(id("carrot")), Restaurant.Unlock.OK, "Buying either member buys the group");
                helper.assertValueEqual(restaurant.register(), 0, "The group charges only the representative price");
                helper.assertValueEqual(restaurant.unlockIngredient(WHEAT), Restaurant.Unlock.ALREADY, "The group cannot be charged twice");
                var updated = request(helper, player, Computer.REFRESH_MENU).ingredientData().orElseThrow();
                helper.assertTrue(updated.ingredients().size() == 1 && updated.ingredients().getFirst().unlocked(),
                        "A saved non-representative purchase marks the single card bought");
                var restored = load(helper, saved(restaurant));
                helper.assertTrue(restored.unlockedIngredients().contains(id("carrot")), "Saving preserves the original member ID");
                var shop = Shop.catalog(restored);
                helper.assertValueEqual(shop.size(), 1, "A saved member gives one Shop listing");
                helper.assertTrue(shop.getFirst().stack().is(Items.WHEAT), "The Shop delivers the representative");
                helper.assertValueEqual(shop.getFirst().price(), 7, "The Shop uses the representative price");
                var byId = Shop.class.getDeclaredMethod("byId", Restaurant.class, String.class);
                byId.setAccessible(true);
                helper.assertTrue(byId.invoke(null, restored, shop.getFirst().id()) != null, "Checkout accepts the representative listing");
                helper.assertTrue(byId.invoke(null, restored, "ingredient/minecraft:carrot") == null, "Checkout rejects a second member listing");
            } finally {
                request(helper, player, Computer.CLOSE);
            }
        } finally {
            current.set(null, before);
            Menu.entries = menuBefore;
            Shop.entries = shopBefore;
            DishBlacklist.reload();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rareIngredientsAndTheirOnlyDishesLeaveTheCatalog(GameTestHelper helper) throws ReflectiveOperationException {
        var current = Class.forName("com.onemorestudios.onemorecore.Ingredients").getDeclaredField("current");
        current.setAccessible(true);
        Object before = current.get(null);
        var menuBefore = Menu.entries;
        try {
            DishBlacklist.set(List.of());
            // Include configured dishes too, so discovery cannot hide a catalogue regression.
            Menu.entries = List.of(new Menu.Entry("rare", new ItemStack(Items.MUSHROOM_STEW), null, 4, false));
            var recipes = List.of(
                    recipe("bread", Items.BREAD, Ingredient.of(Items.WHEAT)),
                    recipe("cookie", Items.COOKIE, Ingredient.of(Items.WHEAT)),
                    recipe("pie", Items.PUMPKIN_PIE, Ingredient.of(Items.WHEAT, Items.CARROT)),
                    recipe("rare", Items.MUSHROOM_STEW, Ingredient.of(Items.CARROT)));
            var index = ingredientIndex(helper, "{\"minDishes\":3}", recipes);
            current.set(null, index);
            helper.assertValueEqual(raw(index), List.of(WHEAT), "Three distinct dishes keep wheat; two remove carrot");
            helper.assertFalse(dishAvailable(index, "mushroom_stew", Set.of(id("carrot"))), "A saved rare purchase cannot restore the dish");
            helper.assertTrue(dishAvailable(index, "pumpkin_pie", Set.of(WHEAT)), "An alternative candidate keeps a dish available");
            var restaurant = place(helper);
            helper.assertTrue(restaurant.dishes().stream().noneMatch(entry -> entry.stack().is(Items.MUSHROOM_STEW)), "Configured and discovered rare dishes leave the catalog");
            helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
            var player = GameTestSupport.player(helper);
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer opens");
            try {
                var state = request(helper, player, Computer.REFRESH_MENU);
                helper.assertTrue(state.menu().stream().noneMatch(line -> line.icon().is(Items.MUSHROOM_STEW)), "Rare dishes leave the Menu app list");
                var data = state.ingredientData().orElseThrow();
                helper.assertTrue(data.ingredients().stream().noneMatch(line -> line.id().equals(id("carrot"))), "Rare ingredients have no card");
                helper.assertTrue(data.lockedDishes().stream().noneMatch(line -> line.dishId().equals("rare") || line.dishId().equals("minecraft:mushroom_stew")), "Rare dishes do not stay locked forever");
            } finally {
                request(helper, player, Computer.CLOSE);
            }
            var disabled = ingredientIndex(helper, "{\"minDishes\":0,\"groups\":[]}", recipes);
            helper.assertValueEqual(raw(disabled), List.of(id("carrot"), WHEAT), "Disabling both rules preserves separate purchases");
            helper.assertTrue(dishAvailable(disabled, "mushroom_stew", Set.of(id("carrot"))), "Disabling the threshold restores the rare dish");
            helper.assertFalse(dishAvailable(disabled, "bread", Set.of(id("carrot"))), "Separate ingredients do not unlock each other");
            var ignored = ingredientIndex(helper, "{\"minDishes\":0,\"ignore\":[\"minecraft:carrot\"]}", recipes);
            current.set(null, ignored);
            helper.assertFalse(Restaurant.isFood(new ItemStack(Items.MUSHROOM_STEW)), "Explicit ignores use the same catalog rule");
            helper.assertTrue(Restaurant.isFood(new ItemStack(Items.PUMPKIN_PIE)), "Explicit ignores preserve an alternative candidate");
        } finally {
            current.set(null, before);
            Menu.entries = menuBefore;
            DishBlacklist.reload();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void groupCountsAreDistinctAndTiesAreStable(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(
                recipe("bread", Items.BREAD, Ingredient.of(Items.WHEAT, Items.CARROT)),
                recipe("pie", Items.PUMPKIN_PIE, Ingredient.of(Items.WHEAT, Items.CARROT)));
        String groups = "\"groups\":[{\"name\":\"test\",\"members\":[\"#c:crops/wheat\",\"#c:crops/carrot\"]}]";
        var rare = ingredientIndex(helper, "{\"minDishes\":3," + groups + "}", recipes);
        helper.assertTrue(raw(rare).isEmpty(), "Two shared dishes count twice, not four times");
        helper.assertFalse(dishAvailable(rare, "bread", Set.of(WHEAT)), "A saved member cannot bypass the group threshold");
        var enabled = ingredientIndex(helper, "{\"minDishes\":0," + groups + "}", recipes);
        helper.assertValueEqual(raw(enabled), List.of(id("carrot")), "Equal counts use stable item ID order");
        var reversed = ingredientIndex(helper,
                "{\"minDishes\":0,\"groups\":[{\"name\":\"test\",\"members\":[\"#c:crops/carrot\",\"#c:crops/wheat\"]}]}", recipes);
        helper.assertValueEqual(raw(reversed), raw(enabled), "Member order does not change the representative");
        helper.assertTrue(dishAvailable(enabled, "pumpkin_pie", Set.of(WHEAT)), "Disabling the threshold keeps the shared unlock");
        helper.succeed();
    }

    static Object ingredientIndex(GameTestHelper helper, String rules, Collection<? extends RecipeHolder<?>> recipes)
            throws ReflectiveOperationException {
        var ingredients = Class.forName("com.onemorestudios.onemorecore.Ingredients");
        var config = Class.forName("com.onemorestudios.onemorecore.IngredientConfig");
        var parse = config.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        var constructor = ingredients.getDeclaredConstructor(Collection.class, config, List.class, HolderLookup.Provider.class);
        constructor.setAccessible(true);
        return constructor.newInstance(recipes, parse.invoke(null, rules), List.of(), helper.getLevel().registryAccess());
    }

    private static List<?> raw(Object index) throws ReflectiveOperationException {
        var field = index.getClass().getDeclaredField("raw");
        field.setAccessible(true);
        return (List<?>) field.get(index);
    }

    private static boolean dishAvailable(Object index, String name, Set<ResourceLocation> unlocked) throws ReflectiveOperationException {
        var availability = index.getClass().getDeclaredMethod("availability", Set.class);
        availability.setAccessible(true);
        var access = availability.invoke(index, unlocked);
        var dish = access.getClass().getDeclaredMethod("dish", ResourceLocation.class);
        dish.setAccessible(true);
        return (boolean) dish.invoke(access, id(name));
    }

    private static RecipeHolder<?> recipe(String name, Item result, Ingredient... slots) {
        var ingredients = NonNullList.<Ingredient>create();
        ingredients.addAll(List.of(slots));
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, name),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(result), ingredients));
    }

    @GameTest(template = "empty")
    public static void breadRequiresWheat(GameTestHelper helper) {
        var restaurant = place(helper);
        var bread = restaurant.dish("bread");
        helper.assertFalse(restaurant.orderable(bread), "Bread starts locked despite the wheat/hay bale recipe cycle");
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat");
        helper.assertTrue(restaurant.orderable(bread), "Wheat unlocks bread");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sugarIsAvailableThroughSugarCane(GameTestHelper helper) {
        var restaurant = place(helper);
        var pie = restaurant.dish("pumpkin_pie");
        GameTestSupport.setRegister(restaurant, 200);
        GameTestSupport.unlock(helper, restaurant, "pumpkin", "egg");
        helper.assertFalse(restaurant.orderable(pie), "Pumpkin and egg alone cannot make pie");
        GameTestSupport.unlock(helper, restaurant, "sugar_cane");
        helper.assertTrue(restaurant.orderable(pie), "Sugar from sugar cane completes the pumpkin pie recipe");
        helper.assertFalse(restaurant.unlockedIngredients().contains(id("sugar")), "Sugar never needed a purchase");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mushroomStewDoesNotRequireABowlUnlock(GameTestHelper helper) {
        var restaurant = place(helper);
        var stew = restaurant.dish("minecraft:mushroom_stew");
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "red_mushroom");
        helper.assertFalse(restaurant.orderable(stew), "Stew needs both mushrooms");
        GameTestSupport.unlock(helper, restaurant, "brown_mushroom");
        helper.assertTrue(restaurant.orderable(stew), "The two mushrooms suffice");
        helper.assertFalse(restaurant.unlockedIngredients().contains(id("bowl")), "The bowl is free");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void computerShopUnlockChecksFundsAndReturnsCatalogData(GameTestHelper helper) {
        var restaurant = place(helper);
        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        var player = GameTestSupport.player(helper);
        var unlock = new Computer.Request(helper.absolutePos(COMPUTER), Computer.UNLOCK_INGREDIENT, WHEAT.toString());
        helper.assertTrue(Computer.handle(player, unlock) == null, "An unlock requires a computer session");
        helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer opens");
        try {
            var desktop = (Computer.State) player.packets.stream().filter(Computer.State.class::isInstance).findFirst().orElseThrow();
            helper.assertTrue(desktop.ingredientData().isEmpty(), "Opening the desktop omits ingredient data");
            var shop = request(helper, player, Computer.REFRESH);
            helper.assertFalse(shop.shop().stream().anyMatch(row -> row.id().equals("ingredient/minecraft:wheat")),
                    "Locked wheat has no buyable listing");
            helper.assertTrue(request(helper, player, Computer.REFRESH_MANAGER).ingredientData().isEmpty(), "Manager refresh omits ingredient data");
            var menu = shop.ingredientData().orElseThrow();
            helper.assertValueEqual(request(helper, player, Computer.REFRESH_MENU).ingredientData().orElseThrow(), menu,
                    "Menu keeps the same missing ingredient data for dish cards");
            var line = menu.ingredients().stream().filter(row -> row.id().equals(WHEAT)).findFirst().orElseThrow();
            helper.assertFalse(line.unlocked(), "Wheat starts locked");
            helper.assertTrue(line.newlyOrderable() > 0, "Wheat adds bread to the menu");
            helper.assertTrue(menu.lockedDishes().stream().anyMatch(row -> row.dishId().equals("bread") && row.missing().equals(List.of(WHEAT))),
                    "Bread lists wheat as its missing ingredient");

            GameTestSupport.setRegister(restaurant, line.unlockCost() - 1);
            helper.assertValueEqual(restaurant.unlockIngredient(WHEAT), Restaurant.Unlock.COST, "Insufficient coins");
            helper.assertValueEqual(restaurant.unlockIngredient(id("not_an_item")), Restaurant.Unlock.UNKNOWN, "Unknown item");
            helper.assertValueEqual(restaurant.register(), line.unlockCost() - 1, "Failed unlocks do not charge");

            var appMenu = appSwitch("APP_MENU"); var appShop = appSwitch("APP_SHOP");
            boolean menuEnabled = appMenu.get(), shopEnabled = appShop.get();
            try {
                appShop.set(false);
                helper.assertTrue(Computer.handle(player, unlock) == null, "A disabled Shop rejects ingredient unlocks");
                appShop.set(true);
                appMenu.set(false);
                helper.assertTrue(request(helper, player, Computer.REFRESH).ingredientData().isPresent(),
                        "The Shop supplies ingredient data when Menu is disabled");
                helper.assertTrue(((Computer.State) Computer.handle(player, unlock)).ingredientData().isPresent(),
                        "The Shop returns a denied unlock when Menu is disabled");
                helper.assertValueEqual(restaurant.register(), line.unlockCost() - 1, "Denied unlocks keep the balance");
            } finally {
                appMenu.set(menuEnabled);
                appShop.set(shopEnabled);
            }
            GameTestSupport.setRegister(restaurant, line.unlockCost());
            var position = player.position();
            player.setPos(position.add(32, 0, 0));
            helper.assertTrue(Computer.handle(player, unlock) == null, "Unlocks require reach");
            player.setPos(position);
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer reopens");
            var reply = (Computer.State) Computer.handle(player, unlock);
            var updated = reply.ingredientData().orElseThrow();
            helper.assertTrue(reply.shop().stream().anyMatch(row -> row.stack().is(Items.WHEAT)),
                    "The unlock reply replaces locked wheat with a buyable listing");
            helper.assertTrue(updated.ingredients().stream().anyMatch(row -> row.id().equals(WHEAT) && row.unlocked()
                    && row.newlyOrderable() == 0), "The reply shows wheat purchased");
            helper.assertValueEqual(restaurant.register(), 0, "Unlock charged exactly its quoted cost");
            helper.assertValueEqual(restaurant.unlockIngredient(WHEAT), Restaurant.Unlock.ALREADY, "A repeated unlock is free");
        } finally {
            request(helper, player, Computer.CLOSE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unlocksSurviveSaveAndSignReplacement(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = place(helper);
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat");
        GameTestSupport.setRegister(restaurant, 0);
        var restored = load(helper, saved(restaurant));
        helper.assertValueEqual(restored.unlockedIngredients(), restaurant.unlockedIngredients(), "Unlocks survived save and load");

        var placedAgain = replaceSign(helper);
        helper.assertTrue(placedAgain.unlockedIngredients().contains(WHEAT), "Unlock survived sign replacement");
        helper.assertTrue(placedAgain.orderable(placedAgain.dish("bread")), "Bread is still orderable");
        helper.assertValueEqual(placedAgain.register(), 0, "Sign replacement did not refill the register");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oldDayAndPaidDishIdsMigrateOnce(GameTestHelper helper) throws ReflectiveOperationException {
        var oldRules = Menu.class.getDeclaredField("legacyUnlocks");
        oldRules.setAccessible(true);
        var beforeRules = oldRules.get(null);
        var beforeEntries = Menu.entries;
        var parse = Menu.class.getDeclaredMethod("parse", String.class, HolderLookup.Provider.class, java.util.Map.class);
        parse.setAccessible(true);
        var rules = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Menu.Entry> entries = (List<Menu.Entry>) parse.invoke(null, """
                [
                  {"id":"bread","item":"minecraft:bread","price":2},
                  {"id":"beef","item":"minecraft:cooked_beef","price":4,"unlock":{"day":2}},
                  {"id":"pie","item":"minecraft:pumpkin_pie","price":6,"unlock":{"day":3,"cost":10}},
                  {"id":"cake","item":"minecraft:cake","price":10,"unlock":{"cost":20}}
                ]
                """, helper.getLevel().registryAccess(), rules);
        try {
            Menu.entries = entries;
            oldRules.set(null, rules);
            var old = new CompoundTag();
            old.putInt("register", 7);
            old.putInt("day", 2);
            var paid = new ListTag();
            paid.add(StringTag.valueOf("pie"));
            old.put("unlocked", paid);
            var early = load(helper, old);
            helper.assertFalse(early.orderable(early.dish("pie")), "Paying before the day gate does not migrate the dish");
            old.putInt("day", 3);
            var migrated = load(helper, old);
            for (String dish : List.of("bread", "beef", "pie"))
                helper.assertTrue(migrated.orderable(migrated.dish(dish)), "Migration preserved " + dish);
            helper.assertFalse(migrated.orderable(migrated.dish("cake")), "The unpaid cake was not granted");
            helper.assertValueEqual(migrated.register(), 7, "Migration did not deposit the starting balance");

            var modern = saved(migrated);
            modern.put("unlockedIngredients", new ListTag());
            helper.assertTrue(load(helper, modern).unlockedIngredients().isEmpty(), "An empty modern set still marks migration complete");
        } finally {
            Menu.entries = beforeEntries;
            oldRules.set(null, beforeRules);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void startingBalanceIsGivenOnce(GameTestHelper helper) throws ReflectiveOperationException {
        var field = Config.class.getDeclaredField("STARTING_BALANCE");
        field.setAccessible(true);
        var config = (ModConfigSpec.IntValue) field.get(null);
        var restaurant = place(helper);
        helper.assertValueEqual(restaurant.register(), config.get(), "New sign receives the configured starting balance");
        helper.assertTrue(restaurant.unlockedIngredients().isEmpty(), "A new restaurant starts without unlocks");
        GameTestSupport.setRegister(restaurant, 0);
        helper.assertValueEqual(replaceSign(helper).register(), 0, "A spent sign does not give another starting balance");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void customersOnlyOrderAvailableDishes(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = place(helper);
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat");
        var roll = Restaurant.class.getDeclaredMethod("makeOrder", RandomSource.class, Customer.Type.class, List.class);
        roll.setAccessible(true);
        var random = RandomSource.create(48307);
        var available = restaurant.dishes().stream().filter(restaurant::orderable).map(dish -> dish.stack().getItem()).toList();
        for (int i = 0; i < 64; i++) {
            @SuppressWarnings("unchecked")
            var order = (List<Customer.Line>) roll.invoke(restaurant, random, Customer.Type.values()[i % Customer.Type.values().length],
                    new ArrayList<String>());
            helper.assertFalse(order.isEmpty(), "Bread is available for customers");
            helper.assertTrue(order.stream().allMatch(line -> available.contains(line.stack().getItem())), "Every order line is orderable");
            helper.assertTrue(order.stream().noneMatch(line -> line.stack().is(Items.PUMPKIN_PIE) || line.stack().is(Items.MUSHROOM_STEW)),
                    "Locked vanilla recipes are never selected");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shopSellsOnlyPurchasedIngredientListings(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = place(helper);
        GameTestSupport.setRegister(restaurant, 100);
        int before = restaurant.register();
        GameTestSupport.unlock(helper, restaurant, "red_mushroom");
        int cost = before - restaurant.register();
        var purchased = Restaurant.class.getDeclaredField("unlockedIngredients");
        purchased.setAccessible(true);
        @SuppressWarnings("unchecked")
        var saved = (Set<ResourceLocation>) purchased.get(restaurant);
        saved.add(id("oak_log"));
        var shop = Shop.catalog(restaurant);
        helper.assertTrue(shop.stream().noneMatch(entry -> entry.id().equals("ingredient/minecraft:oak_log")),
                "A legacy non-food purchase has no generated shop listing");
        var byId = Shop.class.getDeclaredMethod("byId", Restaurant.class, String.class);
        byId.setAccessible(true);
        helper.assertTrue(byId.invoke(null, restaurant, "ingredient/minecraft:oak_log") == null,
                "The server rejects a legacy non-food listing too");
        var line = shop.stream().filter(entry -> entry.id().equals("ingredient/minecraft:red_mushroom")).findFirst().orElseThrow();
        helper.assertTrue(line.stack().is(Items.RED_MUSHROOM), "The shop sells the purchased ingredient");
        helper.assertValueEqual(line.stack().getCount(), 16, "Ingredient bundle size");
        helper.assertValueEqual(line.price(), Math.max(1, Math.round(cost / 5f)), "Ingredient shop price");
        helper.assertTrue(shop.stream().noneMatch(entry -> entry.id().equals("ingredient/minecraft:brown_mushroom")),
                "The locked ingredient is absent from the shop's ingredient listings");

        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        BlockPos chest = SIGN.east();
        helper.setBlock(chest, Blocks.CHEST);
        var setDelivery = Restaurant.class.getDeclaredMethod("setDelivery", BlockPos.class);
        setDelivery.setAccessible(true);
        setDelivery.invoke(restaurant, helper.absolutePos(chest));
        var player = GameTestSupport.player(helper);
        helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer opens");
        try {
            BlockPos pos = helper.absolutePos(COMPUTER);
            int balance = restaurant.register();
            var lockedCart = new Computer.Request(pos, Computer.BUY_CART, "ingredient/minecraft:brown_mushroom:1", line.price(), 1);
            helper.assertFalse(((Computer.CheckoutResult) Computer.handle(player, lockedCart)).success(), "Checkout rejects a locked listing");
            var staleCart = new Computer.Request(pos, Computer.BUY_CART, line.id() + ":2", 0, 2);
            helper.assertFalse(((Computer.CheckoutResult) Computer.handle(player, staleCart)).success(), "Checkout rejects a stale quote");
            helper.assertValueEqual(restaurant.register(), balance, "Neither failed checkout charges the register");
            var cart = new Computer.Request(pos, Computer.BUY_CART, line.id() + ":2", 2L * line.price(), 3);
            helper.assertTrue(((Computer.CheckoutResult) Computer.handle(player, cart)).success(), "Checkout accepts the unlocked listing");
            helper.assertValueEqual(restaurant.register(), balance - 2 * line.price(), "Checkout charges the server price for both bundles");
        } finally {
            request(helper, player, Computer.CLOSE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void largeIngredientPayloadFitsTheBudget(GameTestHelper helper) throws ReflectiveOperationException {
        var ingredients = new ArrayList<Computer.IngredientLine>();
        var locked = new ArrayList<Computer.LockedDish>();
        for (int i = 0; i < 5000; i++) {
            var id = ResourceLocation.fromNamespaceAndPath("example", "ingredient_" + i + "_" + "x".repeat(100));
            ingredients.add(new Computer.IngredientLine(id, 40, false, 200));
            locked.add(new Computer.LockedDish("dish_" + i + "_" + "é".repeat(60), java.util.Collections.nCopies(6, id)));
        }
        var registries = helper.getLevel().registryAccess();
        var cap = Computer.IngredientData.class.getDeclaredMethod("capped", RegistryAccess.class, List.class, List.class);
        cap.setAccessible(true);
        var data = (Computer.IngredientData) cap.invoke(null, registries, ingredients, locked);
        var field = Computer.IngredientData.class.getDeclaredField("CODEC");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var codec = (StreamCodec<RegistryFriendlyByteBuf, Computer.IngredientData>) field.get(null);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            codec.encode(buffer, data);
            helper.assertTrue(buffer.readableBytes() <= 128 * 1024, "The serialized ingredient payload fits its budget");
            helper.assertTrue(data.moreIngredients() > 0 && data.moreLockedDishes() > 0, "Both synthetic lists reach their byte limits");
            helper.assertValueEqual(data.ingredients().size() + data.moreIngredients(), ingredients.size(), "Ingredient omission count");
            helper.assertValueEqual(data.lockedDishes().size() + data.moreLockedDishes(), locked.size(), "Locked dish omission count");
            helper.assertValueEqual(codec.decode(buffer), data, "Ingredient payload round trip");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static Restaurant place(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        return Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
    }

    private static Restaurant replaceSign(GameTestHelper helper) {
        helper.setBlock(SIGN, Blocks.AIR);
        var item = helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds()).stream()
                .filter(drop -> drop.getItem().is(OneMoreCore.OPEN_SIGN_ITEM.get())).findFirst().orElseThrow();
        Restaurant restaurant = place(helper);
        var state = helper.getBlockState(SIGN);
        state.getBlock().setPlacedBy(helper.getLevel(), restaurant.sign, state, GameTestSupport.player(helper), item.getItem());
        return restaurant;
    }

    private static Computer.State request(GameTestHelper helper, GameTestSupport.TestPlayer player, int action) {
        return (Computer.State) Computer.handle(player, new Computer.Request(helper.absolutePos(COMPUTER), action, ""));
    }

    private static CompoundTag saved(Restaurant restaurant) throws ReflectiveOperationException {
        var save = Restaurant.class.getDeclaredMethod("save");
        save.setAccessible(true);
        return (CompoundTag) save.invoke(restaurant);
    }

    private static Restaurant load(GameTestHelper helper, CompoundTag data) throws ReflectiveOperationException {
        var load = Restaurant.class.getDeclaredMethod("load", Restaurants.class, CompoundTag.class);
        load.setAccessible(true);
        return (Restaurant) load.invoke(null, Restaurants.get(helper.getLevel()), data);
    }

    @GameTest(template = "empty")
    public static void configuredRawLinesWaitForUnlockAndReplaceGenerated(GameTestHelper helper) throws ReflectiveOperationException {
        var before = Shop.entries;
        try {
            var restaurant = place(helper);
            ItemStack wheat = new ItemStack(Items.WHEAT, 16);
            Shop.entries = List.of(new Shop.Entry("wheat", wheat, wheat, 4));
            helper.assertTrue(Shop.catalog(restaurant).stream().noneMatch(e -> e.stack().is(Items.WHEAT)), "A locked raw line is hidden");
            GameTestSupport.setRegister(restaurant, 1000);
            GameTestSupport.unlock(helper, restaurant, "wheat");
            helper.assertValueEqual(Shop.catalog(restaurant).stream().filter(e -> e.stack().is(Items.WHEAT)).count(), 1L,
                    "After the unlock the configured line is the only wheat card");
        } finally {
            Shop.entries = before;
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void groupShowsItsFirstListedId(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(
                recipe("bread", Items.BREAD, Ingredient.of(Items.WHEAT)),
                recipe("cookie", Items.COOKIE, Ingredient.of(Items.WHEAT)),
                recipe("pie", Items.PUMPKIN_PIE, Ingredient.of(Items.CARROT)));
        var listedFirst = ingredientIndex(helper, "{\"minDishes\":0,\"groups\":[{\"name\":\"g\",\"members\":[\"minecraft:carrot\",\"minecraft:wheat\"]}]}", recipes);
        helper.assertValueEqual(raw(listedFirst), List.of(id("carrot")), "A plain id listed first is the shown member");
        var tagFirst = ingredientIndex(helper, "{\"minDishes\":0,\"groups\":[{\"name\":\"g\",\"members\":[\"#c:crops/carrot\",\"minecraft:wheat\"]}]}", recipes);
        helper.assertValueEqual(raw(tagFirst), List.of(WHEAT), "A tag listed first lets the member with most dishes show");
        helper.succeed();
    }

    /** A computer app switch from Config (package-private there). */
    private static net.neoforged.neoforge.common.ModConfigSpec.BooleanValue appSwitch(String name) {
        try {
            var field = Config.class.getDeclaredField(name);
            field.setAccessible(true);
            return (net.neoforged.neoforge.common.ModConfigSpec.BooleanValue) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
