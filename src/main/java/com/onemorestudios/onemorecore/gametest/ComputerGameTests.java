package com.onemorestudios.onemorecore.gametest;

import java.util.List;

import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.Shop;
import com.onemorestudios.onemorecore.gametest.GameTestSupport.TestPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class ComputerGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1);
    private static final BlockPos COMPUTER = new BlockPos(1, 1, 2);
    private static final BlockPos CHEST = new BlockPos(2, 1, 1);
    private static final BlockPos SEAT = new BlockPos(2, 1, 2);
    private static final BlockPos TABLE = new BlockPos(2, 1, 3);

    /** ECONOMY-5: A spectator with a previously opened computer cannot spend the register or queue a delivery. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void spectatorCannotBuy(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = setup(helper);
        TestPlayer player = open(helper);
        try {
            player.setGameMode(GameType.SPECTATOR);
            helper.assertTrue(player.isSpectator(), "fixture became a spectator");
            helper.assertTrue(Computer.handle(player, request(helper, Computer.BUY, Shop.entries.getFirst().id())) == null,
                    "spectator purchase is rejected");
            assertRegister(helper, restaurant, 20, 0);
        } finally {
            close(helper, player);
        }
        helper.succeed();
    }

    /** CLIENT-3: A computer outside the world border rejects requests even from a player who previously opened it. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void computerChecksBlockPermission(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = setup(helper);
        TestPlayer player = open(helper);
        var border = helper.getLevel().getWorldBorder();
        double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        BlockPos pos = helper.absolutePos(COMPUTER);
        try {
            border.setCenter(pos.getX() + 64, pos.getZ());
            border.setSize(16);
            helper.assertFalse(helper.getLevel().mayInteract(player, pos), "fixture denies block interaction");
            helper.assertTrue(Computer.handle(player, request(helper, Computer.BUY, Shop.entries.getFirst().id())) == null,
                    "protected computer rejects purchase");
            assertRegister(helper, restaurant, 20, 0);
        } finally {
            border.setCenter(x, z);
            border.setSize(size);
            close(helper, player);
        }
        helper.succeed();
    }

    /** ECONOMY-5 / CLIENT-3: Manager edits require a session, and closing or walking away ends it. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void managerRequiresLiveSession(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = setup(helper);
        BlockPos secondChest = TABLE;
        helper.setBlock(secondChest, Blocks.CHEST);
        BlockPos delivery = helper.absolutePos(secondChest);
        Computer.Request edit = request(helper, Computer.SET_DELIVERY, coordinates(delivery));
        TestPlayer player = GameTestSupport.player(helper);
        try {
            helper.assertTrue(Computer.handle(player, edit) == null, "delivery edit requires a session");
            helper.assertValueEqual(restaurant.delivery(), helper.absolutePos(CHEST), "unchanged delivery point");
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "computer opens");
            helper.assertTrue(Computer.handle(player, edit) != null, "delivery edit succeeds with the session");
            helper.assertValueEqual(restaurant.delivery(), delivery, "new delivery point");
            close(helper, player);
            helper.assertTrue(Computer.handle(player, edit) == null, "closing ends the session");

            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "computer reopens");
            var start = player.position();
            player.setPos(start.add(32, 0, 0));
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(player));
            player.setPos(start);
            helper.assertTrue(Computer.handle(player, edit) == null, "returning to reach does not reopen the session");
        } finally {
            close(helper, player);
        }
        helper.succeed();
    }

    /** CORE-8 / ECONOMY-3: Shop catalog quantities are clamped at both ends, including counts inside item objects. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void shopClampsListingQuantities(GameTestHelper helper) throws ReflectiveOperationException {
        var parser = Shop.class.getDeclaredMethod("parse", String.class, HolderLookup.Provider.class);
        parser.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Shop.Entry> listings = (List<Shop.Entry>) parser.invoke(null, """
                [
                  {"id":"oversized","item":"minecraft:bread","count":2147483648,"price":3},
                  {"id":"zero","item":"minecraft:bread","count":0,"price":3},
                  {"id":"nested","item":{"id":"minecraft:bread","count":2000},"price":3},
                  {"id":"override","item":{"id":"minecraft:bread","count":2000},"count":16,"price":3}
                ]
                """, helper.getLevel().registryAccess());
        helper.assertValueEqual(listings.stream().map(e -> e.stack().getCount()).toList(), List.of(1728, 1, 1728, 16), "listing quantities");
        helper.succeed();
    }

    /** ECONOMY-4: A tray from the rack cannot fund the register, and both free items fall back to coin. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void freeItemsAreNotCurrency(GameTestHelper helper) throws ReflectiveOperationException {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        Restaurant restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
        helper.setBlock(COMPUTER, OneMoreCore.TRAY_RACK.get());
        TestPlayer player = GameTestSupport.player(helper);
        var field = Config.class.getDeclaredField("CURRENCY");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var currency = (ModConfigSpec.ConfigValue<String>) field.get(null);
        String previous = currency.get();
        try {
            currency.set("onemorecore:tray");
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "rack gives a tray");
            ItemStack tray = player.getInventory().items.stream().filter(s -> s.is(OneMoreCore.TRAY_ITEM.get())).findFirst().orElseThrow();
            GameTestSupport.give(player, tray.copy());
            GameTestSupport.click(helper, player, SIGN);
            helper.assertValueEqual(restaurant.register(), 0, "the tray did not credit the register");
            helper.assertValueEqual(Config.currency(), OneMoreCore.COIN.get(), "fallback for trays");

            currency.set("onemorecore:ticket");
            helper.assertValueEqual(Config.currency(), OneMoreCore.COIN.get(), "fallback for tickets");
        } finally {
            currency.set(previous);
        }
        helper.succeed();
    }

    /** CLIENT-13: A stale total queues and charges nothing, and retrying the refreshed quote buys exactly once. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void checkoutRejectsStaleQuote(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = setup(helper);
        List<Shop.Entry> original = Shop.entries;
        ItemStack stack = new ItemStack(Items.BREAD);
        TestPlayer player = GameTestSupport.player(helper);
        try {
            Shop.entries = List.of(new Shop.Entry("quote", stack, stack, 4));
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "computer opens");
            var quote = (Computer.State) Computer.handle(player, request(helper, Computer.REFRESH, ""));
            int displayed = quote.shop().getFirst().price();
            Shop.entries = List.of(new Shop.Entry("quote", stack, stack, 7));
            BlockPos pos = helper.absolutePos(COMPUTER);

            var reply = Computer.handle(player, new Computer.Request(pos, Computer.BUY_CART, "quote:1", displayed, 1));
            assertRegister(helper, restaurant, 20, 0);
            var stale = (Computer.CheckoutResult) reply;
            helper.assertFalse(stale.success(), "stale checkout was rejected");
            helper.assertValueEqual(stale.checkoutId(), 1L, "reply identifies the original request");
            helper.assertValueEqual(((TranslatableContents) stale.message().getContents()).getKey(),
                    "onemorecore.computer.cart_repriced", "rejection reason");
            int updated = stale.state().orElseThrow().shop().getFirst().price();
            helper.assertValueEqual(updated, 7, "reply supplies the new price");
            var retry = (Computer.CheckoutResult) Computer.handle(player, new Computer.Request(pos, Computer.BUY_CART, "quote:1", updated, 2));
            helper.assertTrue(retry.success(), "checkout succeeded at the updated price");
            assertRegister(helper, restaurant, 13, 1);
        } finally {
            Shop.entries = original;
            close(helper, player);
        }
        helper.succeed();
    }

    /** ECONOMY-8 / CLIENT-14: Shop reads no seat neighbors, while Manager builds geometry for the same area. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void onlyManagerScansForTables(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = setup(helper);
        helper.setBlock(SEAT, Blocks.OAK_STAIRS);
        helper.setBlock(TABLE, Blocks.OAK_SLAB);
        BlockPos computer = helper.absolutePos(COMPUTER);
        var area = new CountingArea(BoundingBox.fromCorners(helper.absolutePos(SIGN), helper.absolutePos(TABLE)), computer);
        var field = Restaurant.class.getDeclaredField("area");
        field.setAccessible(true);
        field.set(restaurant, area);

        TestPlayer player = open(helper);
        try {
            helper.assertValueEqual(area.otherPositions, 0, "opening the desktop skips the seat scan");
            var shop = (Computer.State) Computer.handle(player, request(helper, Computer.REFRESH, ""));
            helper.assertValueEqual(area.otherPositions, 0, "Shop refresh skips the seat scan");
            helper.assertTrue(shop.surfaces().isEmpty(), "Shop state omits table geometry");
            var manager = (Computer.State) Computer.handle(player, request(helper, Computer.REFRESH_MANAGER, ""));
            helper.assertTrue(area.otherPositions > 0, "Manager inspects neighboring blocks");
            helper.assertValueEqual(manager.surfaces(), List.of(helper.absolutePos(TABLE)), "Manager has the table surface");
        } finally {
            close(helper, player);
        }
        helper.succeed();
    }

    private static Restaurant setup(GameTestHelper helper) throws ReflectiveOperationException {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        helper.setBlock(CHEST, Blocks.CHEST);
        Restaurant restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
        restaurant.addMoney(20);
        var setDelivery = Restaurant.class.getDeclaredMethod("setDelivery", BlockPos.class);
        setDelivery.setAccessible(true);
        setDelivery.invoke(restaurant, helper.absolutePos(CHEST));
        return restaurant;
    }

    private static TestPlayer open(GameTestHelper helper) {
        TestPlayer player = GameTestSupport.player(helper);
        helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "computer opens");
        return player;
    }

    private static void close(GameTestHelper helper, TestPlayer player) {
        Computer.handle(player, request(helper, Computer.CLOSE, ""));
    }

    private static Computer.Request request(GameTestHelper helper, int action, String id) {
        return new Computer.Request(helper.absolutePos(COMPUTER), action, id);
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void assertRegister(GameTestHelper helper, Restaurant restaurant, int money, int pending) throws ReflectiveOperationException {
        var deliveries = Restaurant.class.getDeclaredField("deliveries");
        deliveries.setAccessible(true);
        helper.assertValueEqual(restaurant.register(), money, "register balance");
        helper.assertValueEqual(((List<?>) deliveries.get(restaurant)).size(), pending, "queued deliveries");
    }

    private static final class CountingArea extends BoundingBox {
        private final BlockPos computer;
        private int otherPositions;

        private CountingArea(BoundingBox box, BlockPos computer) {
            super(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            this.computer = computer;
        }

        @Override
        public boolean isInside(Vec3i pos) {
            if (!pos.equals(computer)) otherPositions++;
            return super.isInside(pos);
        }
    }
}
