package com.onemorestudios.onemorecore.gametest;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import com.onemorestudios.onemorecore.OneMoreCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Inputs a recipe keeps outside getIngredients() count too (#12). */
@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class HiddenInputGameTests {
    @GameTest(template = "empty")
    public static void soupBaseFluidAndCarrierAreInputs(GameTestHelper helper) throws ReflectiveOperationException {
        var json = JsonParser.parseString("""
                {"type": "kaleidoscope_cookery:stockpot",
                 "ingredients": [{"item": "minecraft:carrot"}, {"item": "minecraft:carrot"}],
                 "result": {"id": "minecraft:rabbit_stew", "count": 1},
                 "soup_base": "minecraft:pufferfish_bucket",
                 "fluid": "minecraft:lava",
                 "base_fluid": {"ingredient": {"tag": "#minecraft:water"}},
                 "carrier": {"item": "minecraft:bowl"},
                 "model_id": "minecraft:cooked_salmon",
                 "group": "minecraft:cod",
                 "outputFluid": "minecraft:water",
                 "byproducts": {"output": {"item": "minecraft:bone"}}}
                """);
        var hidden = Class.forName("com.onemorestudios.onemorecore.Ingredients")
                .getDeclaredMethod("hidden", com.google.gson.JsonElement.class, List.class, ResourceLocation.class);
        hidden.setAccessible(true);
        @SuppressWarnings("unchecked")
        var slots = (List<Set<ResourceLocation>>) hidden.invoke(null, json, List.of(Set.of(id("carrot"))), id("rabbit_stew"));
        helper.assertValueEqual(slots, List.of(Set.of(id("pufferfish_bucket")), Set.of(id("lava_bucket")), Set.of(id("water_bucket")),
                Set.of(id("bowl"))), "Soup base, fluid (as its bucket), fluid tag and carrier; not the shown carrot, the result, looks or byproducts");
        var fluidResult = Class.forName("com.onemorestudios.onemorecore.Ingredients").getDeclaredMethod("fluidResult", com.google.gson.JsonElement.class);
        fluidResult.setAccessible(true);
        var tub = JsonParser.parseString("""
                {"type": "kaleidoscope_tavern:pressing_tub", "fluid": "minecraft:lava", "ingredient": {"tag": "c:fruits/grapes"}}
                """);
        helper.assertValueEqual(fluidResult.invoke(null, tub), id("lava_bucket"), "A result-less recipe makes its fluid");
        var ferment = JsonParser.parseString("""
                {"inputFluid": {"id": "minecraft:water"}, "inputFluid2": "minecraft:water", "outputFluid": "minecraft:lava"}
                """);
        helper.assertValueEqual(fluidResult.invoke(null, ferment), id("lava_bucket"), "An input fluid is no result");
        helper.succeed();
    }

    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
}
