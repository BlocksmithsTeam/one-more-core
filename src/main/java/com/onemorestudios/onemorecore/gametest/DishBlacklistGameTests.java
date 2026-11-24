package com.onemorestudios.onemorecore.gametest;

import java.util.List;

import com.onemorestudios.onemorecore.DishBlacklist;
import com.onemorestudios.onemorecore.OneMoreCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Everything eaten or drunk can be a dish, except what the dish blacklist names by id or tag. */
@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class DishBlacklistGameTests {
    @GameTest(template = "empty")
    public static void eatenOrDrunkUnlessBlacklisted(GameTestHelper helper) {
        try {
            DishBlacklist.set(List.of("minecraft:splash_potion"));
            helper.assertTrue(DishBlacklist.isDish(new ItemStack(Items.MILK_BUCKET)), "a drink without a food component is a dish");
            helper.assertTrue(DishBlacklist.isDish(new ItemStack(Items.COOKED_COD)), "food is a dish");
            helper.assertFalse(DishBlacklist.isDish(new ItemStack(Items.SPLASH_POTION)), "a blacklisted id is no dish");
            helper.assertFalse(DishBlacklist.isDish(new ItemStack(Items.STONE)), "stone is no dish");
            // A tag entry, added later, takes out the whole tag.
            DishBlacklist.set(List.of("#minecraft:fishes", "not an id!"));
            helper.assertFalse(DishBlacklist.isDish(new ItemStack(Items.COOKED_COD)), "a blacklisted tag is no dish");
            helper.assertTrue(DishBlacklist.isDish(new ItemStack(Items.SPLASH_POTION)), "an id that left the list is a dish again");
        } finally {
            DishBlacklist.reload();
        }
        helper.succeed();
    }
}
