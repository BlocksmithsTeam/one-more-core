package com.onemorestudios.onemorecore.compat;

import com.onemorestudios.onemorecore.OneMoreCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class GrillingSkewers {
    private GrillingSkewers() {}

    public static final TagKey<Item> LYING_DISHES =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "lying_dishes"));

    public static boolean matches(ItemStack stack) {
        return matches(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.is(LYING_DISHES));
    }

    public static boolean matches(ResourceLocation id, boolean tagged) {
        return id.getNamespace().equals("kaleidoscope_grilling") && (tagged || id.getPath().endsWith("_skewer"));
    }
}
