package com.onemorestudios.onemorecore.client.book;

import java.util.ArrayList;
import java.util.List;

import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.MenuBookBlockEntity;
import com.onemorestudios.onemorecore.Status;
import com.pockyl.diegesis.api.client.StationView;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * The menu book's pages: a page for each line of the restaurant's menu. The dish is the page's item; a combo shows its dishes as what
 * goes in. At the foot, the price in the restaurant's currency. The tabs are the kinds of the menu.
 */
final class MenuBookSource implements RecipeBook.Source {
    static final String[] KINDS = {"main", "starter", "drink", "dessert", "combo"};

    @Override
    public String memory() {
        return "menu_book";
    }

    static List<MenuBoardBlockEntity.Line> lines(StationView view) {
        return view.level().getBlockEntity(view.pos()) instanceof MenuBookBlockEntity book ? book.lines : List.of();
    }

    @Override
    public List<RecipeBook.Page> pages(StationView view) {
        List<RecipeBook.Page> pages = new ArrayList<>();
        ItemStack currency = currency();
        // In menu order: the kinds one after another, as a printed menu has them.
        List<MenuBoardBlockEntity.Line> sorted = new ArrayList<>(lines(view));
        sorted.sort(java.util.Comparator.comparingInt(l -> { int i = java.util.Arrays.asList(KINDS).indexOf(l.kind()); return i < 0 ? KINDS.length : i; }));
        for (MenuBoardBlockEntity.Line line : sorted) {
            if (line.items().isEmpty()) continue;
            boolean combo = line.kind().equals("combo");
            String key = (combo ? "combo/" : "dish/") + line.id();
            ItemStack dish = line.items().get(0).copyWithCount(1);
            if (combo) dish.set(DataComponents.CUSTOM_NAME, Component.translatable("onemorecore.book.combo"));
            List<Ingredient> parts = combo ? line.items().stream().map(s -> Ingredient.of(s.copyWithCount(1))).toList() : List.of();
            pages.add(new RecipeBook.Page(key, dish, parts, List.of(currency), Component.literal(String.valueOf(line.price())), false, line.kind()));
        }
        return pages;
    }

    @Override
    public List<ItemStack> inStation(StationView view) {
        return List.of();
    }

    /** "All", then a tab for each kind, its icon the first dish of that kind. */
    @Override
    public List<RecipeBook.Tab> tabs(StationView view) {
        List<RecipeBook.Tab> tabs = new ArrayList<>();
        tabs.add(new RecipeBook.Tab(null, new ItemStack(net.minecraft.world.item.Items.COMPASS), Component.translatable("onemorecore.book.tab.all")));
        List<MenuBoardBlockEntity.Line> lines = lines(view);
        for (String kind : KINDS) {
            lines.stream().filter(l -> l.kind().equals(kind) && !l.items().isEmpty()).findFirst().ifPresent(l ->
                    tabs.add(new RecipeBook.Tab(kind, l.items().get(0).copyWithCount(1), Component.translatable("onemorecore.board." + kind))));
        }
        return tabs;
    }

    static ItemStack currency() {
        Status s = Status.client;
        return s == null ? new ItemStack(com.onemorestudios.onemorecore.OneMoreCore.COIN.get()) : new ItemStack(BuiltInRegistries.ITEM.get(s.currency()));
    }
}
