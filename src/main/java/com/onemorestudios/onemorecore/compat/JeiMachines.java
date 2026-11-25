package com.onemorestudios.onemorecore.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.onemorestudios.onemorecore.Machines;
import com.onemorestudios.onemorecore.OneMoreCore;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends the server the machines (JEI's catalyst blocks) of each recipe type when JEI is ready. */
@JeiPlugin
public final class JeiMachines implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "machines"); }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getConnection() == null) return;
        var jei = runtime.getRecipeManager();
        RecipeManager recipes = minecraft.level.getRecipeManager();
        kinds = null;
        Map<ResourceLocation, Set<ResourceLocation>> found = new HashMap<>();
        jei.createRecipeCategoryLookup().get().forEach(category -> collect(jei, category, recipes, found));
        Map<ResourceLocation, List<ResourceLocation>> types = new HashMap<>();
        found.forEach((type, blocks) -> types.put(type, new ArrayList<>(blocks).subList(0, Math.min(64, blocks.size()))));
        if (types.isEmpty() || types.size() > 4096) return;
        try {
            PacketDistributor.sendToServer(new Machines.Found(types));
        } catch (RuntimeException e) {
            OneMoreCore.LOGGER.debug("The server does not take the machine list", e);
        }
    }

    private static Set<ResourceLocation> kinds;

    /** The type ids of all recipes the client has. */
    private static Set<ResourceLocation> kinds(RecipeManager recipes) {
        if (kinds == null) kinds = recipes.getRecipes().stream().map(holder -> Machines.kind(holder.value())).collect(Collectors.toSet());
        return kinds;
    }

    private static <T> void collect(IRecipeManager jei, IRecipeCategory<T> category, RecipeManager recipes,
            Map<ResourceLocation, Set<ResourceLocation>> found) {
        Set<ResourceLocation> blocks = jei.createRecipeCatalystLookup(category.getRecipeType()).getItemStack()
                .filter(stack -> stack.getItem() instanceof BlockItem)
                .map(stack -> BuiltInRegistries.BLOCK.getKey(((BlockItem) stack.getItem()).getBlock()))
                .collect(Collectors.toSet());
        if (blocks.isEmpty()) return;
        // The vanilla recipe types this category shows: through the ids of its recipes, else its own id.
        Set<ResourceLocation> types = new HashSet<>();
        jei.createRecipeLookup(category.getRecipeType()).get().forEach(recipe -> {
            for (ResourceLocation id : category.getSourceRecipeIds(recipe)) {
                recipes.byKey(id).map(holder -> Machines.kind(holder.value())).ifPresent(types::add);
            }
        });
        ResourceLocation uid = category.getRecipeType().getUid();
        if (types.isEmpty() && kinds(recipes).contains(uid)) types.add(uid);
        types.forEach(type -> found.computeIfAbsent(type, key -> new HashSet<>()).addAll(blocks));
    }
}
