package com.onemorestudios.onemorecore.gametest;

import java.util.List;

import com.onemorestudios.onemorecore.DishBlacklist;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Restaurant;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
            current.set(null, IngredientGameTests.ingredientIndex(helper, "{}", List.of()));
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
            // A broken entry in the file is left out; the other entries stay (the list does not fall back to the defaults).
            helper.assertValueEqual(DishBlacklist.parse("[\"minecraft:stone\", null, {}, 3, \"#minecraft:fishes\"]"),
                    List.of("minecraft:stone", "#minecraft:fishes"), "entries kept around broken ones");
        } finally {
            current.set(null, before);
            DishBlacklist.reload();
        }
        helper.succeed();
    }
}
