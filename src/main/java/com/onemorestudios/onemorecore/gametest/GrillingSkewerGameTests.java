package com.onemorestudios.onemorecore.gametest;

import java.io.IOException;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.compat.GrillingSkewers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagFile;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class GrillingSkewerGameTests {
    @GameTest(template = "empty")
    public static void grillingSkewersUseOptionalTagsAndScopedIds(GameTestHelper helper) throws IOException {
        ResourceLocation path = GrillingSkewers.LYING_DISHES.location().withPath(name -> "tags/item/" + name + ".json");
        var resources = helper.getLevel().getServer().getResourceManager();
        try (var reader = resources.openAsReader(path)) {
            TagFile file = TagFile.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
            for (String name : new String[] {"grilled_skewers", "raw_skewers"}) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("kaleidoscope_grilling", name);
                helper.assertTrue(file.entries().stream().anyMatch(entry -> entry.isTag() && !entry.isRequired() && entry.getId().equals(id)),
                        "Lying dishes should include the optional tag " + id);
            }
        }

        helper.assertTrue(GrillingSkewers.matches(ResourceLocation.parse("kaleidoscope_grilling:grilled_beef_skewer"), false),
                "A skewer ID should match without tag membership");
        helper.assertTrue(GrillingSkewers.matches(ResourceLocation.parse("kaleidoscope_grilling:tagged_dish"), true),
                "Tagged Kaleidoscope Grilling dishes should match");
        helper.assertTrue(!GrillingSkewers.matches(ResourceLocation.parse("kaleidoscope_grilling:grill"), false),
                "Untagged non-skewers should fall through");
        helper.assertTrue(!GrillingSkewers.matches(ResourceLocation.parse("other_mod:beef_skewer"), true),
                "Tag and suffix should both stay scoped to Kaleidoscope Grilling");
        helper.assertTrue(!GrillingSkewers.matches(Items.BREAD.getDefaultInstance())
                        && !GrillingSkewers.matches(Items.STICK.getDefaultInstance()),
                "Vanilla bread and sticks should fall through");
        helper.succeed();
    }
}
