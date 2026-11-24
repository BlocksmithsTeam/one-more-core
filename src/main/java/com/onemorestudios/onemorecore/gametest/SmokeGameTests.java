package com.onemorestudios.onemorecore.gametest;

import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Menu;
import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.MenuBookBlockEntity;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Tickets;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.Shop;
import com.onemorestudios.onemorecore.TrayBlockEntity;
import com.onemorestudios.onemorecore.gametest.GameTestSupport.TestPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class SmokeGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    /** Placing each block that owns a block entity creates the corresponding registered type. */
    @GameTest(template = "empty")
    public static void blocksCreateTheirBlockEntities(GameTestHelper helper) {
        var sign = GameTestSupport.place(helper, POS, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        var menu = GameTestSupport.place(helper, POS.north(), OneMoreCore.MENU_BOARD.get(), MenuBoardBlockEntity.class);
        var tray = GameTestSupport.place(helper, POS.west(), OneMoreCore.TRAY.get(), TrayBlockEntity.class);
        var ironTray = GameTestSupport.place(helper, POS.above(), OneMoreCore.IRON_TRAY.get(), TrayBlockEntity.class);
        var book = GameTestSupport.place(helper, POS.east(), OneMoreCore.MENU_BOOK.get(), MenuBookBlockEntity.class);

        helper.assertValueEqual(sign.getType(), OneMoreCore.OPEN_SIGN_ENTITY.get(), "open sign block entity type");
        helper.assertValueEqual(menu.getType(), OneMoreCore.MENU_BOARD_ENTITY.get(), "menu board block entity type");
        helper.assertValueEqual(tray.getType(), OneMoreCore.TRAY_ENTITY.get(), "tray block entity type");
        helper.assertValueEqual(ironTray.getType(), OneMoreCore.TRAY_ENTITY.get(), "iron tray block entity type");
        helper.assertValueEqual(book.getType(), OneMoreCore.MENU_BOOK_ENTITY.get(), "menu book block entity type");
        helper.succeed();
    }

    /** Clicking the open sign with coins deposits the full held stack and empties the hand. */
    @GameTest(template = "empty")
    public static void registerAcceptsCoins(GameTestHelper helper) {
        Restaurant restaurant = placeRegister(helper);
        int initialBalance = restaurant.register();
        Item coin = defaultCoin(helper);
        TestPlayer player = GameTestSupport.player(helper);

        GameTestSupport.give(player, new ItemStack(coin, 7));
        helper.assertTrue(GameTestSupport.click(helper, player, POS).consumesAction(), "Deposit click was handled");
        helper.assertValueEqual(restaurant.register(), initialBalance + 7, "register balance");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Deposit emptied the main hand");
        helper.succeed();
    }

    /** Sneak-clicking the register with an empty hand withdraws at most one stack of coins. */
    @GameTest(template = "empty")
    public static void registerReturnsOneStack(GameTestHelper helper) {
        Restaurant restaurant = placeRegister(helper);
        int initialBalance = restaurant.register();
        Item coin = defaultCoin(helper);
        TestPlayer player = GameTestSupport.player(helper);
        ItemStack fullStack = new ItemStack(coin);
        int stackSize = fullStack.getMaxStackSize();
        fullStack.setCount(stackSize);

        GameTestSupport.give(player, fullStack);
        GameTestSupport.click(helper, player, POS);
        GameTestSupport.give(player, new ItemStack(coin, 7));
        GameTestSupport.click(helper, player, POS);
        helper.assertValueEqual(restaurant.register(), initialBalance + stackSize + 7, "balance before withdrawal");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Main hand was empty before withdrawal");

        player.setShiftKeyDown(true);
        helper.assertTrue(GameTestSupport.click(helper, player, POS).consumesAction(), "Withdrawal click was handled");
        helper.assertValueEqual(restaurant.register(), initialBalance + 7, "remaining register balance");
        helper.assertValueEqual(player.getInventory().countItem(coin), stackSize, "number of coins returned");
        helper.assertTrue(player.getInventory().items.stream()
                .anyMatch(stack -> stack.is(coin) && stack.getCount() == stackSize), "The returned coins form one full stack");
        helper.succeed();
    }

    /** Clicking a tray with bread consumes one item and leaves it in the first slot after a tick. */
    @GameTest(template = "empty")
    public static void trayStoresOneFoodItem(GameTestHelper helper) {
        TrayBlockEntity tray = GameTestSupport.place(helper, POS, OneMoreCore.TRAY.get(), TrayBlockEntity.class);
        TestPlayer player = GameTestSupport.player(helper);
        GameTestSupport.give(player, new ItemStack(Items.BREAD, 3));

        helper.assertTrue(GameTestSupport.click(helper, player, POS).consumesAction(), "Tray click was handled");
        helper.assertTrue(ItemStack.matches(player.getMainHandItem(), new ItemStack(Items.BREAD, 2)),
                "Two bread remain in the main hand");
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(helper.getBlockEntity(POS) == tray, "The tray is still placed");
            helper.assertTrue(ItemStack.matches(tray.items.getItem(0), new ItemStack(Items.BREAD)),
                    "The first tray slot contains one bread");
            helper.succeed();
        });
    }

    /** A server started with fresh config loads every default entry and the expected bread and wheat listings. */
    @GameTest(template = "empty")
    public static void serverLoadsDefaultCatalogs(GameTestHelper helper) {
        helper.assertValueEqual(Menu.entries.size(), 5, "default menu entry count");
        Menu.Entry bread = Menu.byId("bread");
        helper.assertTrue(bread != null, "Server startup loaded the default bread entry");
        helper.assertTrue(ItemStack.matches(bread.stack(), new ItemStack(Items.BREAD)), "Default bread item");
        helper.assertValueEqual(bread.price(), 2, "default bread price");

        helper.assertValueEqual(Shop.entries.size(), 4, "default shop entry count");
        Shop.Entry wheat = Shop.byId("wheat");
        helper.assertTrue(wheat != null, "Server startup loaded the default wheat entry");
        helper.assertTrue(ItemStack.matches(wheat.stack(), new ItemStack(Items.WHEAT, 16)), "Default wheat bundle");
        helper.assertValueEqual(wheat.price(), 4, "default wheat price");
        helper.succeed();
    }

    /** An empty-hand menu book click sends one open-book payload to the clicking player. */
    @GameTest(template = "empty")
    public static void menuBookSendsOpenPayload(GameTestHelper helper) {
        GameTestSupport.place(helper, POS, OneMoreCore.MENU_BOOK.get(), MenuBookBlockEntity.class);
        TestPlayer player = GameTestSupport.player(helper);

        helper.assertTrue(GameTestSupport.click(helper, player, POS).consumesAction(), "Menu book click was handled");
        long opens = player.packets.stream().filter(MenuBookBlockEntity.Open.class::isInstance).count();
        helper.assertValueEqual(opens, 1L, "open-book payload count");
        helper.succeed();
    }

    private static Restaurant placeRegister(GameTestHelper helper) {
        GameTestSupport.place(helper, POS, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        Restaurant restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(POS));
        helper.assertTrue(restaurant != null, "Placing the open sign creates its restaurant");
        return restaurant;
    }

    private static Item defaultCoin(GameTestHelper helper) {
        Item coin = OneMoreCore.COIN.get();
        helper.assertValueEqual(Config.currency(), coin, "configured currency on a fresh server");
        return coin;
    }
}
