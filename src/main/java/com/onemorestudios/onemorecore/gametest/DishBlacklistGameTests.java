package com.onemorestudios.onemorecore.gametest;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.onemorestudios.onemorecore.DishBlacklist;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Restaurant;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dish blacklist rules with an empty ingredient index, so no test food is a raw purchase. */
@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class DishBlacklistGameTests {
    @GameTest(template = "empty")
    public static void eatenOrDrunkUnlessBlacklisted(GameTestHelper helper) throws ReflectiveOperationException {
        var current = Class.forName("com.onemorestudios.onemorecore.Ingredients").getDeclaredField("current");
        current.setAccessible(true);
        Object before = current.get(null);
        try {
            current.set(null, IngredientGameTests.ingredientIndex(helper, "{\"minDishes\":0}", List.of()));
            DishBlacklist.set(List.of("minecraft:splash_potion"));
            helper.assertTrue(DishBlacklist.isDish(new ItemStack(Items.MILK_BUCKET)), "a drink without a food component is a dish");
            helper.assertTrue(DishBlacklist.isDish(new ItemStack(Items.COOKED_COD)), "food is a dish");
            helper.assertFalse(DishBlacklist.isDish(new ItemStack(Items.SPLASH_POTION)), "a blacklisted id is no dish");
            helper.assertFalse(DishBlacklist.isDish(new ItemStack(Items.STONE)), "stone is no dish");
            // The menu uses the same rule.
            DishBlacklist.set(List.of("minecraft:bread"));
            helper.assertFalse(Restaurant.isFood(new ItemStack(Items.BREAD)), "a blacklisted food is no menu dish");
            helper.assertTrue(Restaurant.isFood(new ItemStack(Items.MILK_BUCKET)), "a drink is a menu dish");
            // A tag entry, added later, takes out the whole tag.
            DishBlacklist.set(List.of("#minecraft:fishes", "not an id!"));
            helper.assertFalse(DishBlacklist.isDish(new ItemStack(Items.COOKED_COD)), "a blacklisted tag is no dish");
            helper.assertTrue(DishBlacklist.isDish(new ItemStack(Items.SPLASH_POTION)), "an id that left the list is a dish again");
            DishBlacklist.set(List.of("mine*:cooked_*", "minecraft:*_bread", "bad namespace:*", "*:bad?*", "#c:foods/*"));
            helper.assertTrue(DishBlacklist.blocked(new ItemStack(Items.COOKED_COD)), "A pattern can match namespace and path");
            helper.assertFalse(DishBlacklist.blocked(new ItemStack(Items.COD)), "A pattern leaves other ids alone");
            helper.assertFalse(DishBlacklist.blocked(new ItemStack(Items.BREAD)), "A suffix pattern needs its literal prefix");
            helper.assertFalse(matchesPattern("other:cooked_cod"), "The namespace is part of the match");
            DishBlacklist.set(List.of("*:raw_*"));
            for (String id : List.of("farmersdelight:raw_pasta", "other:raw_", "minecraft:raw_beef"))
                helper.assertTrue(matchesPattern(id), "A raw path prefix matches: " + id);
            for (String id : List.of("farmersdelight:pasta", "farmersdelight:cooked_raw_pasta", "raw_mod:pasta"))
                helper.assertFalse(matchesPattern(id), "A raw prefix elsewhere does not match: " + id);
            DishBlacklist.set(List.of("mine*:cooked_**", "minecraft:cooked.cod*"));
            helper.assertTrue(matchesPattern("minecraft:cooked_cod"), "Repeated stars work");
            helper.assertFalse(matchesPattern("minecraft:cookedXcod"), "Regex punctuation remains literal");
            DishBlacklist.set(List.of("raw_*"));
            helper.assertTrue(matchesPattern("minecraft:raw_beef"), "An omitted namespace uses minecraft");
            helper.assertFalse(matchesPattern("other:raw_beef"), "An omitted namespace is not a wildcard");
            DishBlacklist.set(List.of("bad namespace:*", "*:bad?*", "#c:foods/*", "minecraft:bread"));
            helper.assertFalse(matchesPattern("minecraft:raw_beef"), "Bad patterns are skipped");
            helper.assertTrue(DishBlacklist.blocked(new ItemStack(Items.BREAD)), "Valid entries survive bad patterns");
            var defaults = DishBlacklist.class.getDeclaredField("DEFAULTS");
            defaults.setAccessible(true);
            @SuppressWarnings("unchecked")
            var entries = (List<String>) defaults.get(null);
            DishBlacklist.set(entries);
            for (var item : List.of(Items.BEEF, Items.COD, Items.PORKCHOP))
                helper.assertFalse(DishBlacklist.isDish(new ItemStack(item)), "Default tags exclude raw food: " + item);
            helper.assertTrue(DishBlacklist.isDish(new ItemStack(Items.COOKED_BEEF)), "Default tags keep cooked beef");
            helper.assertTrue(DishBlacklist.blocked(new ItemStack(Items.GOLDEN_CARROT)), "Default ids exclude special consumables");
            // A broken entry in the file is left out; the other entries stay (the list does not fall back to the defaults).
            helper.assertValueEqual(DishBlacklist.parse("[\"minecraft:stone\", null, {}, 3, \"#minecraft:fishes\"]"),
                    List.of("minecraft:stone", "#minecraft:fishes"), "entries kept around broken ones");
        } finally {
            current.set(null, before);
            DishBlacklist.reload();
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesPattern(String id) throws ReflectiveOperationException {
        var field = DishBlacklist.class.getDeclaredField("patterns");
        field.setAccessible(true);
        return ((List<Pattern>) field.get(null)).stream().anyMatch(pattern -> pattern.matcher(id).matches());
    }

    @GameTest(template = "empty")
    public static void blacklistedIntermediatesRemainIngredients(GameTestHelper helper) throws ReflectiveOperationException {
        try {
            DishBlacklist.set(List.of("minecraft:potato", "minecraft:baked_*", "minecraft:pumpkin_*"));
            var index = IngredientGameTests.ingredientIndex(helper, "{\"minDishes\":0}", List.of(
                    recipe("cook", Items.BAKED_POTATO, Items.POTATO),
                    recipe("dish", Items.BREAD, Items.BAKED_POTATO)));
            assertIngredientChain(helper, index, "potato", "baked_potato");
            var portion = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.COOKIE),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.PUMPKIN_PIE))) {
                @Override
                public net.minecraft.world.item.crafting.RecipeType<net.minecraft.world.item.crafting.CraftingRecipe> getType() {
                    return net.minecraft.world.item.crafting.RecipeType.simple(ResourceLocation.parse("onemorecore:test_cutting"));
                }
            };
            index = IngredientGameTests.ingredientIndex(helper, "{\"minDishes\":0}", List.of(
                    new RecipeHolder<>(ResourceLocation.parse("onemorecore:portion"), portion),
                    recipe("whole", Items.PUMPKIN_PIE, Items.WHEAT),
                    recipe("first", Items.COOKED_BEEF, Items.COOKIE),
                    recipe("second", Items.COOKED_COD, Items.COOKED_BEEF),
                    recipe("third", Items.COOKED_CHICKEN, Items.COOKED_COD),
                    recipe("dish", Items.BREAD, Items.COOKED_CHICKEN)));
            assertIngredientChain(helper, index, "wheat", "pumpkin_pie");
        } finally {
            DishBlacklist.reload();
        }
        helper.succeed();
    }

    private static void assertIngredientChain(GameTestHelper helper, Object index, String ingredient, String intermediate)
            throws ReflectiveOperationException {
        var id = ResourceLocation.withDefaultNamespace(ingredient);
        var raw = index.getClass().getDeclaredField("raw");
        raw.setAccessible(true);
        helper.assertValueEqual(raw.get(index), List.of(id), "Blacklisted intermediates keep the raw purchase source");
        var availability = index.getClass().getDeclaredMethod("availability", Set.class);
        availability.setAccessible(true);
        var access = availability.invoke(index, Set.of(id));
        var dish = access.getClass().getDeclaredMethod("dish", ResourceLocation.class);
        dish.setAccessible(true);
        helper.assertTrue((boolean) dish.invoke(access, ResourceLocation.withDefaultNamespace("bread")),
                "Buying the raw ingredient unlocks a dish through blacklisted intermediates");
        helper.assertFalse((boolean) dish.invoke(availability.invoke(index, Set.of()), ResourceLocation.withDefaultNamespace("bread")),
                "The dish still needs the raw purchase");
        helper.assertFalse((boolean) dish.invoke(access, ResourceLocation.withDefaultNamespace(intermediate)),
                "Purchases do not turn a blacklisted intermediate into a dish");
    }

    private static RecipeHolder<?> recipe(String name, Item result, Item input) {
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, name),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(result),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(input))));
    }

}
