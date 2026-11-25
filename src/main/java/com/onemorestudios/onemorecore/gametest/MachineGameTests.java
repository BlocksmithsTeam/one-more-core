package com.onemorestudios.onemorecore.gametest;

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
        GameTestSupport.unlock(helper, restaurant, "beef");
        var beef = restaurant.dish("cooked_beef");
        helper.assertTrue(beef != null, "Cooked beef is a dish");
        boolean before = Config.REQUIRE_MACHINES.get();
        try {
            Config.REQUIRE_MACHINES.set(false);
            helper.assertTrue(restaurant.orderable(beef), "Without the rule beef needs no machine");

            Config.REQUIRE_MACHINES.set(true);
            restaurant.refreshMachines(helper.getLevel());
            helper.assertFalse(restaurant.orderable(beef), "No furnace, smoker or campfire: no cooked beef");

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
        helper.assertValueEqual(merged, Map.of(id("smelting"), Set.of(id("furnace"), id("blast_furnace")),
                id("smoking"), Set.of(id("smoker"))), "JEI adds blocks and types; unknown ones are left out");
        helper.assertValueEqual(Machines.forType(id("smoker")), Set.of(id("smoker")), "A type named like a block uses that block");
        helper.assertValueEqual(Machines.forType(ResourceLocation.fromNamespaceAndPath("nomod", "no_type")), Set.of(),
                "An unknown type needs no machine");
        helper.succeed();
    }

    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
}
