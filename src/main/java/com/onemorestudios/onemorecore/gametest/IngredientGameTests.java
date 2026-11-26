package com.onemorestudios.onemorecore.gametest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.Menu;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.Shop;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class IngredientGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1);
    private static final BlockPos COMPUTER = SIGN.south();
    private static final ResourceLocation WHEAT = id("wheat");

    @GameTest(template = "empty")
    public static void unreadableRecipesRequireAnOverride(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(
                recipe("hidden", Items.COOKED_COD),
                recipe("hidden_intermediate", Items.SUGAR),
                recipe("with_intermediate", Items.PUMPKIN_PIE, Ingredient.of(Items.PUMPKIN), Ingredient.of(Items.SUGAR)),
                recipe("filtered_slot", Items.BREAD, Ingredient.of(Items.EGG)),
                recipe("free_slot", Items.MUSHROOM_STEW, Ingredient.of(Items.BOWL)),
                recipe("readable_alternate", Items.COOKED_CHICKEN, Ingredient.of(Items.WHEAT)),
                recipe("unreadable_alternate", Items.COOKED_CHICKEN));
        var ingredients = Class.forName("com.onemorestudios.onemorecore.Ingredients");
        var config = Class.forName("com.onemorestudios.onemorecore.IngredientConfig");
        var parse = config.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        var constructor = ingredients.getDeclaredConstructor(Collection.class, config, List.class, HolderLookup.Provider.class);
        constructor.setAccessible(true);
        var availability = ingredients.getDeclaredMethod("availability", Set.class);
        availability.setAccessible(true);
        var rules = parse.invoke(null, """
                {"free":["minecraft:bowl"],"ignore":["minecraft:egg"]}
                """);
        var index = constructor.newInstance(recipes, rules, List.of(), helper.getLevel().registryAccess());
        var access = availability.invoke(index, Set.of(id("egg"), id("pumpkin")));
        var dish = access.getClass().getDeclaredMethod("dish", ResourceLocation.class);
        dish.setAccessible(true);
        helper.assertFalse((boolean) dish.invoke(access, id("cooked_cod")), "An empty ingredient list keeps the dish locked");
        helper.assertFalse((boolean) dish.invoke(access, id("pumpkin_pie")), "An unreadable intermediate is unavailable");
        helper.assertFalse((boolean) dish.invoke(access, id("bread")), "A slot with only ignored candidates stays locked after a config change");
        helper.assertTrue((boolean) dish.invoke(access, id("mushroom_stew")), "A slot with only a free candidate is satisfied");
        helper.assertFalse((boolean) dish.invoke(access, id("cooked_chicken")), "An unreadable alternative cannot bypass a readable recipe");
        helper.assertTrue((boolean) dish.invoke(availability.invoke(index, Set.of(WHEAT)), id("cooked_chicken")),
                "A readable alternative can still unlock the dish");

        var corrected = parse.invoke(null, """
                {"dishes":{"minecraft:cooked_cod":["minecraft:cod"],"minecraft:bread":["#onemorecore:missing"]}}
                """);
        var correctedIndex = constructor.newInstance(recipes, corrected, List.of(), helper.getLevel().registryAccess());
        var correctedAccess = availability.invoke(correctedIndex, Set.of(id("cod")));
        helper.assertTrue((boolean) dish.invoke(correctedAccess, id("cooked_cod")), "An explicit ingredient override unlocks the dish");
        helper.assertFalse((boolean) dish.invoke(correctedAccess, id("bread")), "An empty override tag keeps the dish locked");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void purchaseSourcesSkipRecyclingAndKeepClosedCycles(GameTestHelper helper) throws ReflectiveOperationException {
        var recipes = List.of(
                recipe("planks_from_log", Items.OAK_PLANKS, Ingredient.of(Items.OAK_LOG)),
                recipe("boat_from_planks", Items.OAK_BOAT, Ingredient.of(Items.OAK_PLANKS)),
                recipe("planks_from_boat", Items.OAK_PLANKS, Ingredient.of(Items.OAK_BOAT)),
                recipe("chest_boat_from_boat", Items.OAK_CHEST_BOAT, Ingredient.of(Items.OAK_BOAT)),
                recipe("planks_from_chest_boat", Items.OAK_PLANKS, Ingredient.of(Items.OAK_CHEST_BOAT)),
                // Put planks at depth one so the old search buys boats at depth zero.
                recipe("stick_from_planks", Items.STICK, Ingredient.of(Items.OAK_PLANKS)),
                recipe("bowl_from_stick", Items.BOWL, Ingredient.of(Items.STICK)),
                recipe("stew_from_bowl", Items.MUSHROOM_STEW, Ingredient.of(Items.BOWL)),
                recipe("wheat_from_hay", Items.WHEAT, Ingredient.of(Items.HAY_BLOCK)),
                recipe("hay_from_wheat", Items.HAY_BLOCK, Ingredient.of(Items.WHEAT)),
                recipe("bread_from_wheat", Items.BREAD, Ingredient.of(Items.WHEAT)),
                recipe("unreadable_sugar", Items.SUGAR),
                recipe("emerald_from_diamond", Items.EMERALD, Ingredient.of(Items.DIAMOND)),
                recipe("diamond_from_mixed_slot", Items.DIAMOND, Ingredient.of(Items.EMERALD, Items.SUGAR)));
        var ingredients = Class.forName("com.onemorestudios.onemorecore.Ingredients");
        var config = Class.forName("com.onemorestudios.onemorecore.IngredientConfig");
        var parse = config.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        var constructor = ingredients.getDeclaredConstructor(Collection.class, config, List.class, HolderLookup.Provider.class);
        constructor.setAccessible(true);
        var configured = List.of(new Menu.Entry("stew", new ItemStack(Items.MUSHROOM_STEW), null, 4, false),
                new Menu.Entry("bread", new ItemStack(Items.BREAD), null, 2, false));
        var index = constructor.newInstance(recipes, parse.invoke(null, "{}"), configured, helper.getLevel().registryAccess());
        var rawField = ingredients.getDeclaredField("raw");
        rawField.setAccessible(true);
        helper.assertValueEqual(rawField.get(index), List.of(id("oak_log"), WHEAT),
                "Purchase sources keep logs and wheat, without boats or chest boats");

        var sources = ingredients.getDeclaredMethod("sources", ResourceLocation.class, int.class, Set.class);
        sources.setAccessible(true);
        helper.assertTrue(sources.invoke(index, id("diamond"), 3, new java.util.HashSet<ResourceLocation>()) == null,
                "An unreadable candidate prevents the recycling-only fallback");
        helper.assertValueEqual(sources.invoke(index, WHEAT, 3, new java.util.HashSet<ResourceLocation>()), Set.of(WHEAT),
                "A closed conversion cycle keeps wheat as its source");
        helper.succeed();
    }

    private static RecipeHolder<?> recipe(String name, Item result, Ingredient... slots) {
        var ingredients = NonNullList.<Ingredient>create();
        ingredients.addAll(List.of(slots));
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, name),
                new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(result), ingredients));
    }

    @GameTest(template = "empty")
    public static void breadRequiresWheat(GameTestHelper helper) {
        var restaurant = place(helper);
        var bread = restaurant.dish("bread");
        helper.assertFalse(restaurant.orderable(bread), "Bread starts locked despite the wheat/hay bale recipe cycle");
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat");
        helper.assertTrue(restaurant.orderable(bread), "Wheat unlocks bread");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sugarIsAvailableThroughSugarCane(GameTestHelper helper) {
        var restaurant = place(helper);
        var pie = restaurant.dish("pumpkin_pie");
        GameTestSupport.setRegister(restaurant, 200);
        GameTestSupport.unlock(helper, restaurant, "pumpkin", "egg");
        helper.assertFalse(restaurant.orderable(pie), "Pumpkin and egg alone cannot make pie");
        GameTestSupport.unlock(helper, restaurant, "sugar_cane");
        helper.assertTrue(restaurant.orderable(pie), "Sugar from sugar cane completes the pumpkin pie recipe");
        helper.assertFalse(restaurant.unlockedIngredients().contains(id("sugar")), "Sugar never needed a purchase");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mushroomStewDoesNotRequireABowlUnlock(GameTestHelper helper) {
        var restaurant = place(helper);
        var stew = restaurant.dish("minecraft:mushroom_stew");
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "red_mushroom");
        helper.assertFalse(restaurant.orderable(stew), "Stew needs both mushrooms");
        GameTestSupport.unlock(helper, restaurant, "brown_mushroom");
        helper.assertTrue(restaurant.orderable(stew), "The two mushrooms suffice");
        helper.assertFalse(restaurant.unlockedIngredients().contains(id("bowl")), "The bowl is free");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void computerUnlockChecksFundsAndReturnsOnlyMenuData(GameTestHelper helper) {
        var restaurant = place(helper);
        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        var player = GameTestSupport.player(helper);
        var unlock = new Computer.Request(helper.absolutePos(COMPUTER), Computer.UNLOCK_INGREDIENT, WHEAT.toString());
        helper.assertTrue(Computer.handle(player, unlock) == null, "An unlock requires a computer session");
        helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer opens");
        try {
            var desktop = (Computer.State) player.packets.stream().filter(Computer.State.class::isInstance).findFirst().orElseThrow();
            helper.assertTrue(desktop.ingredientData().isEmpty(), "Opening the desktop omits ingredient data");
            helper.assertTrue(request(helper, player, Computer.REFRESH).ingredientData().isEmpty(), "Shop refresh omits ingredient data");
            helper.assertTrue(request(helper, player, Computer.REFRESH_MANAGER).ingredientData().isEmpty(), "Manager refresh omits ingredient data");
            var menu = request(helper, player, Computer.REFRESH_MENU).ingredientData().orElseThrow();
            var line = menu.ingredients().stream().filter(row -> row.id().equals(WHEAT)).findFirst().orElseThrow();
            helper.assertFalse(line.unlocked(), "Wheat starts locked");
            helper.assertTrue(line.newlyOrderable() > 0, "Wheat adds bread to the menu");
            helper.assertTrue(menu.lockedDishes().stream().anyMatch(row -> row.dishId().equals("bread") && row.missing().equals(List.of(WHEAT))),
                    "Bread lists wheat as its missing ingredient");

            GameTestSupport.setRegister(restaurant, line.unlockCost() - 1);
            helper.assertValueEqual(restaurant.unlockIngredient(WHEAT), Restaurant.Unlock.COST, "Insufficient coins");
            helper.assertValueEqual(restaurant.unlockIngredient(id("not_an_item")), Restaurant.Unlock.UNKNOWN, "Unknown item");
            helper.assertValueEqual(restaurant.register(), line.unlockCost() - 1, "Failed unlocks do not charge");

            GameTestSupport.setRegister(restaurant, line.unlockCost());
            var position = player.position();
            player.setPos(position.add(32, 0, 0));
            helper.assertTrue(Computer.handle(player, unlock) == null, "Unlocks require reach");
            player.setPos(position);
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer reopens");
            var updated = ((Computer.State) Computer.handle(player, unlock)).ingredientData().orElseThrow();
            helper.assertTrue(updated.ingredients().stream().anyMatch(row -> row.id().equals(WHEAT) && row.unlocked()
                    && row.newlyOrderable() == 0), "The reply shows wheat purchased");
            helper.assertValueEqual(restaurant.register(), 0, "Unlock charged exactly its quoted cost");
            helper.assertValueEqual(restaurant.unlockIngredient(WHEAT), Restaurant.Unlock.ALREADY, "A repeated unlock is free");
        } finally {
            request(helper, player, Computer.CLOSE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unlocksSurviveSaveAndSignReplacement(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = place(helper);
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat");
        GameTestSupport.setRegister(restaurant, 0);
        var restored = load(helper, saved(restaurant));
        helper.assertValueEqual(restored.unlockedIngredients(), restaurant.unlockedIngredients(), "Unlocks survived save and load");

        var placedAgain = replaceSign(helper);
        helper.assertTrue(placedAgain.unlockedIngredients().contains(WHEAT), "Unlock survived sign replacement");
        helper.assertTrue(placedAgain.orderable(placedAgain.dish("bread")), "Bread is still orderable");
        helper.assertValueEqual(placedAgain.register(), 0, "Sign replacement did not refill the register");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oldDayAndPaidDishIdsMigrateOnce(GameTestHelper helper) throws ReflectiveOperationException {
        var oldRules = Menu.class.getDeclaredField("legacyUnlocks");
        oldRules.setAccessible(true);
        var beforeRules = oldRules.get(null);
        var beforeEntries = Menu.entries;
        var parse = Menu.class.getDeclaredMethod("parse", String.class, HolderLookup.Provider.class, java.util.Map.class);
        parse.setAccessible(true);
        var rules = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Menu.Entry> entries = (List<Menu.Entry>) parse.invoke(null, """
                [
                  {"id":"bread","item":"minecraft:bread","price":2},
                  {"id":"beef","item":"minecraft:cooked_beef","price":4,"unlock":{"day":2}},
                  {"id":"pie","item":"minecraft:pumpkin_pie","price":6,"unlock":{"day":3,"cost":10}},
                  {"id":"cake","item":"minecraft:cake","price":10,"unlock":{"cost":20}}
                ]
                """, helper.getLevel().registryAccess(), rules);
        try {
            Menu.entries = entries;
            oldRules.set(null, rules);
            var old = new CompoundTag();
            old.putInt("register", 7);
            old.putInt("day", 2);
            var paid = new ListTag();
            paid.add(StringTag.valueOf("pie"));
            old.put("unlocked", paid);
            var early = load(helper, old);
            helper.assertFalse(early.orderable(early.dish("pie")), "Paying before the day gate does not migrate the dish");
            old.putInt("day", 3);
            var migrated = load(helper, old);
            for (String dish : List.of("bread", "beef", "pie"))
                helper.assertTrue(migrated.orderable(migrated.dish(dish)), "Migration preserved " + dish);
            helper.assertFalse(migrated.orderable(migrated.dish("cake")), "The unpaid cake was not granted");
            helper.assertValueEqual(migrated.register(), 7, "Migration did not deposit the starting balance");

            var modern = saved(migrated);
            modern.put("unlockedIngredients", new ListTag());
            helper.assertTrue(load(helper, modern).unlockedIngredients().isEmpty(), "An empty modern set still marks migration complete");
        } finally {
            Menu.entries = beforeEntries;
            oldRules.set(null, beforeRules);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void startingBalanceIsGivenOnce(GameTestHelper helper) throws ReflectiveOperationException {
        var field = Config.class.getDeclaredField("STARTING_BALANCE");
        field.setAccessible(true);
        var config = (ModConfigSpec.IntValue) field.get(null);
        var restaurant = place(helper);
        helper.assertValueEqual(restaurant.register(), config.get(), "New sign receives the configured starting balance");
        helper.assertTrue(restaurant.unlockedIngredients().isEmpty(), "A new restaurant starts without unlocks");
        GameTestSupport.setRegister(restaurant, 0);
        helper.assertValueEqual(replaceSign(helper).register(), 0, "A spent sign does not give another starting balance");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void customersOnlyOrderAvailableDishes(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = place(helper);
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat");
        var roll = Restaurant.class.getDeclaredMethod("makeOrder", RandomSource.class, Customer.Type.class, List.class);
        roll.setAccessible(true);
        var random = RandomSource.create(48307);
        var available = restaurant.dishes().stream().filter(restaurant::orderable).map(dish -> dish.stack().getItem()).toList();
        for (int i = 0; i < 64; i++) {
            @SuppressWarnings("unchecked")
            var order = (List<Customer.Line>) roll.invoke(restaurant, random, Customer.Type.values()[i % Customer.Type.values().length],
                    new ArrayList<String>());
            helper.assertFalse(order.isEmpty(), "Bread is available for customers");
            helper.assertTrue(order.stream().allMatch(line -> available.contains(line.stack().getItem())), "Every order line is orderable");
            helper.assertTrue(order.stream().noneMatch(line -> line.stack().is(Items.PUMPKIN_PIE) || line.stack().is(Items.MUSHROOM_STEW)),
                    "Locked vanilla recipes are never selected");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shopSellsOnlyPurchasedIngredientListings(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = place(helper);
        GameTestSupport.setRegister(restaurant, 100);
        int before = restaurant.register();
        GameTestSupport.unlock(helper, restaurant, "red_mushroom");
        int cost = before - restaurant.register();
        var shop = Shop.catalog(restaurant);
        var line = shop.stream().filter(entry -> entry.id().equals("ingredient/minecraft:red_mushroom")).findFirst().orElseThrow();
        helper.assertTrue(line.stack().is(Items.RED_MUSHROOM), "The shop sells the purchased ingredient");
        helper.assertValueEqual(line.stack().getCount(), 16, "Ingredient bundle size");
        helper.assertValueEqual(line.price(), Math.max(1, Math.round(cost / 5f)), "Ingredient shop price");
        helper.assertTrue(shop.stream().noneMatch(entry -> entry.id().equals("ingredient/minecraft:brown_mushroom")),
                "The locked ingredient is absent from the shop's ingredient listings");

        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        BlockPos chest = SIGN.east();
        helper.setBlock(chest, Blocks.CHEST);
        var setDelivery = Restaurant.class.getDeclaredMethod("setDelivery", BlockPos.class);
        setDelivery.setAccessible(true);
        setDelivery.invoke(restaurant, helper.absolutePos(chest));
        var player = GameTestSupport.player(helper);
        helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer opens");
        try {
            BlockPos pos = helper.absolutePos(COMPUTER);
            int balance = restaurant.register();
            var lockedCart = new Computer.Request(pos, Computer.BUY_CART, "ingredient/minecraft:brown_mushroom:1", line.price(), 1);
            helper.assertFalse(((Computer.CheckoutResult) Computer.handle(player, lockedCart)).success(), "Checkout rejects a locked listing");
            var staleCart = new Computer.Request(pos, Computer.BUY_CART, line.id() + ":2", 0, 2);
            helper.assertFalse(((Computer.CheckoutResult) Computer.handle(player, staleCart)).success(), "Checkout rejects a stale quote");
            helper.assertValueEqual(restaurant.register(), balance, "Neither failed checkout charges the register");
            var cart = new Computer.Request(pos, Computer.BUY_CART, line.id() + ":2", 2L * line.price(), 3);
            helper.assertTrue(((Computer.CheckoutResult) Computer.handle(player, cart)).success(), "Checkout accepts the unlocked listing");
            helper.assertValueEqual(restaurant.register(), balance - 2 * line.price(), "Checkout charges the server price for both bundles");
        } finally {
            request(helper, player, Computer.CLOSE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void largeIngredientPayloadFitsTheBudget(GameTestHelper helper) throws ReflectiveOperationException {
        var ingredients = new ArrayList<Computer.IngredientLine>();
        var locked = new ArrayList<Computer.LockedDish>();
        for (int i = 0; i < 5000; i++) {
            var id = ResourceLocation.fromNamespaceAndPath("example", "ingredient_" + i + "_" + "x".repeat(100));
            ingredients.add(new Computer.IngredientLine(id, 40, false, 200));
            locked.add(new Computer.LockedDish("dish_" + i + "_" + "é".repeat(60), java.util.Collections.nCopies(6, id)));
        }
        var registries = helper.getLevel().registryAccess();
        var cap = Computer.IngredientData.class.getDeclaredMethod("capped", RegistryAccess.class, List.class, List.class);
        cap.setAccessible(true);
        var data = (Computer.IngredientData) cap.invoke(null, registries, ingredients, locked);
        var field = Computer.IngredientData.class.getDeclaredField("CODEC");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var codec = (StreamCodec<RegistryFriendlyByteBuf, Computer.IngredientData>) field.get(null);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            codec.encode(buffer, data);
            helper.assertTrue(buffer.readableBytes() <= 128 * 1024, "The serialized ingredient payload fits its budget");
            helper.assertTrue(data.moreIngredients() > 0 && data.moreLockedDishes() > 0, "Both synthetic lists reach their byte limits");
            helper.assertValueEqual(data.ingredients().size() + data.moreIngredients(), ingredients.size(), "Ingredient omission count");
            helper.assertValueEqual(data.lockedDishes().size() + data.moreLockedDishes(), locked.size(), "Locked dish omission count");
            helper.assertValueEqual(codec.decode(buffer), data, "Ingredient payload round trip");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static Restaurant place(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        return Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
    }

    private static Restaurant replaceSign(GameTestHelper helper) {
        helper.setBlock(SIGN, Blocks.AIR);
        var item = helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds()).stream()
                .filter(drop -> drop.getItem().is(OneMoreCore.OPEN_SIGN_ITEM.get())).findFirst().orElseThrow();
        Restaurant restaurant = place(helper);
        var state = helper.getBlockState(SIGN);
        state.getBlock().setPlacedBy(helper.getLevel(), restaurant.sign, state, GameTestSupport.player(helper), item.getItem());
        return restaurant;
    }

    private static Computer.State request(GameTestHelper helper, GameTestSupport.TestPlayer player, int action) {
        return (Computer.State) Computer.handle(player, new Computer.Request(helper.absolutePos(COMPUTER), action, ""));
    }

    private static CompoundTag saved(Restaurant restaurant) throws ReflectiveOperationException {
        var save = Restaurant.class.getDeclaredMethod("save");
        save.setAccessible(true);
        return (CompoundTag) save.invoke(restaurant);
    }

    private static Restaurant load(GameTestHelper helper, CompoundTag data) throws ReflectiveOperationException {
        var load = Restaurant.class.getDeclaredMethod("load", Restaurants.class, CompoundTag.class);
        load.setAccessible(true);
        return (Restaurant) load.invoke(null, Restaurants.get(helper.getLevel()), data);
    }
}
