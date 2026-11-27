package com.onemorestudios.onemorecore.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Machines;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A recipe counts only when one of its machines stands in the restaurant area (requireMachines). */
@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class MachineGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1);
    private static final BlockPos MACHINE = new BlockPos(3, 1, 3);

    @GameTest(template = "empty")
    public static void cookedBeefNeedsACookingBlockInTheArea(GameTestHelper helper) throws ReflectiveOperationException {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        Restaurant restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
        var setArea = Restaurant.class.getDeclaredMethod("setArea", BoundingBox.class);
        setArea.setAccessible(true);
        setArea.invoke(restaurant, BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(4, 3, 4))));
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "beef", "wheat");
        var beef = restaurant.dish("cooked_beef");
        helper.assertTrue(beef != null, "Cooked beef is a dish");
        boolean before = Config.REQUIRE_MACHINES.get();
        try {
            Config.REQUIRE_MACHINES.set(false);
            helper.assertTrue(restaurant.orderable(beef), "Without the rule beef needs no machine");

            Config.REQUIRE_MACHINES.set(true);
            restaurant.refreshMachines(helper.getLevel());
            helper.assertFalse(restaurant.orderable(beef), "No furnace, smoker or campfire: no cooked beef");
            helper.assertTrue(restaurant.orderable(restaurant.dish("bread")), "Crafting needs no block in the area");

            helper.setBlock(MACHINE, Blocks.SMOKER);
            restaurant.refreshMachines(helper.getLevel());
            helper.assertValueEqual(restaurant.machines(), Set.of(id("smoker")), "The scan finds the smoker");
            helper.assertTrue(restaurant.orderable(beef), "Any one of the cooking blocks is enough");

            helper.setBlock(MACHINE, Blocks.AIR);
            helper.setBlock(new BlockPos(4, 4, 4), Blocks.SMOKER);
            restaurant.refreshMachines(helper.getLevel());
            helper.assertFalse(restaurant.orderable(beef), "A smoker outside the area does not count");
        } finally {
            Config.REQUIRE_MACHINES.set(before);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void machineListsMergeAndFallBack(GameTestHelper helper) {
        var current = Machines.parse("""
                {"minecraft:smelting": ["minecraft:furnace"], "broken": 3}
                """);
        helper.assertValueEqual(current, Map.of(id("smelting"), Set.of(id("furnace"))), "A broken entry is left out");
        var merged = Machines.merge(current, Map.of(
                id("smelting"), List.of(id("blast_furnace")),
                id("smoking"), List.of(id("smoker"), ResourceLocation.fromNamespaceAndPath("nomod", "nothing")),
                ResourceLocation.fromNamespaceAndPath("nomod", "no_type"), List.of(id("stone"))));
        helper.assertValueEqual(merged, Map.of(id("smelting"), Set.of(id("furnace")),
                id("smoking"), Set.of(id("smoker")), ResourceLocation.fromNamespaceAndPath("nomod", "no_type"), Set.of(id("stone"))),
                "JEI adds only types the list lacks (also types that are not registered); unknown blocks are left out");
        helper.assertValueEqual(Machines.forType(id("crafting")), Set.of(), "Crafting needs no machine");
        helper.assertValueEqual(Machines.merge(Machines.parse("{\"minecraft:crafting\":[]}"),
                Map.of(id("crafting"), List.of(id("crafter"), id("crafting_table")))),
                Map.of(id("crafting"), Set.of()), "JEI preserves an explicitly empty machine list");
        helper.assertFalse(Machines.forType(ResourceLocation.parse("youkaisfeasts:kettle")).contains(ResourceLocation.parse("farmersdelight:stove")),
                "A stove is no kettle");
        helper.assertValueEqual(Machines.forType(id("smoker")), Set.of(id("smoker")), "A type named like a block uses that block");
        helper.assertValueEqual(Machines.forType(ResourceLocation.fromNamespaceAndPath("nomod", "no_type")), Set.of(),
                "An unknown type needs no machine");
        helper.assertTrue(Machines.forType(ResourceLocation.parse("kaleidoscope_tavern:barrel"))
                .contains(ResourceLocation.parse("kaleidoscope_tavern:barrel")), "The built-in list knows the pack's mods without JEI");
        helper.assertValueEqual(Machines.kind(new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.BREAD),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.WHEAT)))), id("crafting"), "A registered type is its own id");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oldMachineFilesMigrateOnlyOnce(GameTestHelper helper) throws IOException, ReflectiveOperationException {
        Path path = Files.createTempFile("onemorecore-machines-", ".json");
        var load = Machines.class.getDeclaredMethod("load", Path.class);
        load.setAccessible(true);
        try {
            Files.writeString(path, """
                    {"minecraft:crafting":["minecraft:crafter"],"minecraft:smelting":["minecraft:stone"]}
                    """);
            var migrated = (Map<?, ?>) load.invoke(null, path);
            helper.assertValueEqual(migrated.get(id("crafting")), Set.of(), "Old crafting overrides give way to the empty default");
            helper.assertValueEqual(migrated.get(id("smelting")), Set.of(id("stone")), "Other old overrides survive");
            String saved = Files.readString(path);
            var json = com.google.gson.JsonParser.parseString(saved).getAsJsonObject();
            helper.assertValueEqual(json.get("version").getAsInt(), 1, "Migration writes the version");
            helper.assertFalse(json.has("minecraft:crafting"), "Migration removes the obsolete override");
            helper.assertValueEqual(load.invoke(null, path), migrated, "A second load keeps the migrated values");
            helper.assertValueEqual(Files.readString(path), saved, "A second load does not write the file");

            String versioned = "{\"version\":1,\"minecraft:crafting\":[\"minecraft:crafting_table\"]}";
            Files.writeString(path, versioned);
            var kept = (Map<?, ?>) load.invoke(null, path);
            helper.assertValueEqual(kept.get(id("crafting")), Set.of(id("crafting_table")), "Versioned overrides remain intentional");
            helper.assertValueEqual(Files.readString(path), versioned, "Versioned files stay byte for byte unchanged");

            var toJson = Machines.class.getDeclaredMethod("toJson", Map.class);
            toJson.setAccessible(true);
            var written = com.google.gson.JsonParser.parseString((String) toJson.invoke(null, Map.of())).getAsJsonObject();
            helper.assertValueEqual(written.get("version").getAsInt(), 1, "New files also have the version");
        } finally {
            Files.deleteIfExists(path);
        }
        helper.succeed();
    }

    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
}
