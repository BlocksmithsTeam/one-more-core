package com.onemorestudios.onemorecore.gametest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.DaySummary;
import com.onemorestudios.onemorecore.Courier;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Money;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.Shop;
import com.onemorestudios.onemorecore.TableItems;
import com.onemorestudios.onemorecore.gametest.GameTestSupport.TestPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class MoneyGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1);
    private static final BlockPos TABLE = SIGN.south();
    private static final BlockPos OTHER = TABLE.east();
    private static final BlockPos SEAT = TABLE.west();
    private static final Class<?> PARTY = nestedClass(Customer.class, "Party");

    /** CORE-1 / ECONOMY-1: a re-placed sign carries the bills up to the cap and the excess drops as coins. */
    @GameTest(template = "empty")
    public static void breakingSignKeepsBills(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        restaurant.addMoney(Integer.MAX_VALUE - 5);
        addBill(restaurant, helper.absolutePos(TABLE), 8);
        addBill(restaurant, helper.absolutePos(OTHER), 9);

        helper.setBlock(SIGN, Blocks.AIR);
        helper.assertValueEqual(droppedMoney(helper), 12, "overflow at the sign");
        List<ItemEntity> signs = drops(helper, OneMoreCore.OPEN_SIGN_ITEM.get());
        helper.assertValueEqual(signs.size(), 1, "dropped sign count");
        ItemStack item = signs.getFirst().getItem();
        Restaurant restored = placeRestaurant(helper);
        var state = helper.getBlockState(SIGN);
        state.getBlock().setPlacedBy(helper.getLevel(), restored.sign, state, GameTestSupport.player(helper), item);
        helper.assertValueEqual(restored.register(), Integer.MAX_VALUE, "restored register balance");
        helper.succeed();
    }

    /** ECONOMY-2: collecting keeps the uncredited coins on the table and reports the credited amount. */
    @GameTest(template = "empty")
    public static void collectionRetainsOverflow(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        BlockPos table = helper.absolutePos(TABLE);
        TestPlayer player = GameTestSupport.player(helper);
        restaurant.addMoney(Integer.MAX_VALUE - 5);
        addBill(restaurant, table, 20);

        helper.assertValueEqual(collect(helper, restaurant, player, table), 5, "credited coins");
        helper.assertValueEqual(restaurant.register(), Integer.MAX_VALUE, "capped register");
        helper.assertValueEqual(billAt(restaurant, table), 15, "bill remainder");
        helper.assertValueEqual(player.packets.stream().filter(TableItems.Collected.class::isInstance).toList(),
                List.of(new TableItems.Collected(5)), "collection notice");
        helper.assertValueEqual(collect(helper, restaurant, player, table), 0, "credit into a full register");
        helper.assertValueEqual(billAt(restaurant, table), 15, "bill remainder after retry");
        helper.assertValueEqual(droppedMoney(helper), 0, "coins dropped during collection");
        helper.succeed();
    }

    /** ECONOMY-2: direct payments fill the register and drop the remaining currency at the sign. */
    @GameTest(template = "empty")
    public static void directPaymentDropsOverflow(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        restaurant.addMoney(Integer.MAX_VALUE - 5);
        withConfig("COLLECT_PAYMENTS", false, () -> {
            Object party = serveBread(helper, restaurant, 20, false);
            int payment = (int) read(PARTY, party, "bill");
            call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, restaurant, true);
            helper.assertValueEqual(restaurant.register(), Integer.MAX_VALUE, "capped register");
            helper.assertValueEqual(droppedMoney(helper), payment - 5, "currency dropped at the sign");
            helper.assertValueEqual(billAt(restaurant, helper.absolutePos(TABLE)), 0, "table bill after direct payment");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void paidTableStoresAndSplitsMoney(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        Object party = serveBread(helper, restaurant, 23, false);
        // The payment is the price plus the tip.
        int payment = (int) read(PARTY, party, "bill");
        helper.assertTrue(payment >= 23, "Payment includes the price: " + payment);
        call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, restaurant, true);
        BlockPos table = helper.absolutePos(TABLE);
        int amount = billAt(restaurant, table);
        helper.assertValueEqual(amount, payment, "Table stores the party's full payment");
        helper.assertValueEqual(Money.split(amount), new Money.Split(amount / 10, amount % 10), "Table shows bills and coins for the payment");
        helper.assertValueEqual(Money.split(23), new Money.Split(2, 3), "23 shows two bills and three coins");
        Restaurant restored = reload(helper, restaurant, save(restaurant));
        helper.assertValueEqual(billAt(restored, table), payment, "Payment amount survives saving");
        helper.assertValueEqual(collect(helper, restored, GameTestSupport.player(helper), table), payment, "Collection credits the full amount");
        helper.assertValueEqual(restored.register(), payment, "Collection uses the restaurant balance");
        helper.succeed();
    }

    /** CORE-2: a fully served party's dish value becomes a table bill once after reload. */
    @GameTest(template = "empty")
    public static void reloadSettlesServedParty(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        serveBread(helper, restaurant, 13, false);

        Restaurant restored = reload(helper, restaurant, save(restaurant));
        BlockPos table = helper.absolutePos(TABLE);
        helper.assertValueEqual(billAt(restored, table), 13, "recovered dish value on the table");
        helper.assertValueEqual(restored.register(), 0, "register before collecting the recovered bill");
        Restaurant savedAgain = reload(helper, restored, save(restored));
        helper.assertValueEqual(billAt(savedAgain, table), 13, "recovered bill after a second reload");
        helper.succeed();
    }

    /** CORE-2: a partly served party credits only its served dish when the table is gone on reload. */
    @GameTest(template = "empty")
    public static void reloadCreditsPartialServiceWithoutTable(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        serveBread(helper, restaurant, 7, true);
        helper.setBlock(TABLE, Blocks.AIR);
        helper.setBlock(OTHER, Blocks.AIR);

        Restaurant restored = reload(helper, restaurant, save(restaurant));
        helper.assertValueEqual(restored.register(), 7, "register credit for the served dish");
        helper.assertValueEqual(billAt(restored, helper.absolutePos(TABLE)), 0, "bill at the missing table");
        helper.succeed();
    }

    /** CORE-2: a partly served party leaves its bill on a surviving block of a multi-block table. */
    @GameTest(template = "empty")
    public static void reloadSettlesPartialServiceOnSurvivingSurface(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        serveBread(helper, restaurant, 7, true);
        helper.setBlock(TABLE, Blocks.AIR);

        Restaurant restored = reload(helper, restaurant, save(restaurant));
        helper.assertValueEqual(billAt(restored, helper.absolutePos(OTHER)), 7, "bill on the surviving surface");
        helper.assertValueEqual(restored.register(), 0, "register before collecting the recovered bill");
        helper.succeed();
    }

    /** CORE-7 / ECONOMY-7: settling a zero-price party leaves the clean table available after its cooldown. */
    @GameTest(template = "empty")
    public static void zeroBillLeavesTableAvailable(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        withConfig("COLLECT_PAYMENTS", true, () -> {
            Object party = serveBread(helper, restaurant, 0, false);
            call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, restaurant, true);
            Restaurant.Seat seat = new Restaurant.Seat(helper.absolutePos(SEAT), helper.absolutePos(TABLE));
            write(Restaurant.class, restaurant, "tables", List.of(List.of(seat)));
            // The cooldown has elapsed by the time we ask for the next seat.
            write(Restaurant.class, restaurant, "freedAt", Map.of(seat.surface(), Long.MIN_VALUE / 2));
            helper.assertValueEqual(call(Restaurant.class, restaurant, "freeSeats",
                    new Class<?>[] {ServerLevel.class, int.class}, helper.getLevel(), 1), List.of(seat), "next party's seat");
            helper.assertTrue(save(restaurant).getList("table_items", Tag.TAG_COMPOUND).isEmpty(), "A free meal created no bill");
        });
        helper.succeed();
    }

    /** CORE-14: a partly cleaned table clears on the same stroke before and after a reload, including fully wiped surfaces. */
    @GameTest(template = "empty")
    public static void cleaningThresholdSurvivesReload(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        BlockPos a = helper.absolutePos(TABLE);
        BlockPos b = helper.absolutePos(OTHER);
        Set<BlockPos> table = Set.of(a, b);
        withConfig("DIRT_CLEAN_FRACTION", 0.75, () -> {
            dirt(helper, restaurant, a, 0x0707);
            dirt(helper, restaurant, b, 0xE7);
            wipe(helper, restaurant, table, a);
            helper.assertTrue(!dirt(restaurant).containsKey(a) && dirt(restaurant).containsKey(b), "One surface is clean before saving");

            CompoundTag saved = save(restaurant);
            Restaurant restored = reload(helper, restaurant, saved);
            CompoundTag legacy = saved.copy();
            legacy.remove("dirt_start");
            Restaurant oldSave = reload(helper, restaurant, legacy);
            wipe(helper, restaurant, table, b);
            wipe(helper, restored, table, b);
            wipe(helper, oldSave, table, b);

            helper.assertTrue(dirt(restaurant).isEmpty(), "Uninterrupted table cleared");
            helper.assertTrue(dirt(restored).isEmpty(), "Reloaded table cleared on the same stroke");
            helper.assertValueEqual(dirt(oldSave).get(b), 0xE0L, "legacy save uses the remaining dirt as its baseline");
        });
        helper.succeed();
    }

    /**
     * One stroke of the cloth cleans every cell it passes over at once: a stroke across the middle row of a fully dirty surface
     * leaves only the rows more than one cell away from it, with no need to rest the cloth on each spot.
     */
    @GameTest(template = "empty")
    public static void strokeWipesEveryCellOnItsWay(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        BlockPos a = helper.absolutePos(TABLE);
        withConfig("DIRT_CLEAN_FRACTION", 1.0, () -> {
            dirt(helper, restaurant, a, -1L);
            int row = a.getZ() * 8 + 4;
            call(Restaurant.class, restaurant, "wipeCells", new Class<?>[] {ServerLevel.class, Set.class, int.class, int.class, int.class, int.class, Vec3.class},
                    helper.getLevel(), Set.of(a), a.getX() * 8, row, a.getX() * 8 + 7, row, Vec3.atCenterOf(a));
            long left = (Long) dirt(restaurant).get(a);
            // Rows 3, 4 and 5 (the stroke and its neighbours) are clean in all 8 columns; the other 5 rows stay.
            helper.assertValueEqual(left, -1L & ~(0xFFFFFFL << 24), "cells left after the stroke");
        });
        helper.succeed();
    }

    /** CORE-15: removing the sign sends exactly one zero-coin update per bill to a tracking player. */
    @GameTest(template = "empty")
    public static void removingSignClearsTrackedBills(GameTestHelper helper) {
        Restaurant restaurant = placeRestaurant(helper);
        BlockPos a = helper.absolutePos(TABLE);
        BlockPos b = helper.absolutePos(OTHER);
        addBill(restaurant, a, 9);
        addBill(restaurant, b, 11);
        TestPlayer player = GameTestSupport.player(helper);
        ChunkMap chunks = helper.getLevel().getChunkSource().chunkMap;
        PlayerMap players = (PlayerMap) read(ChunkMap.class, chunks, "playerMap");
        player.setChunkTrackingView(ChunkTrackingView.of(new ChunkPos(a), 2));
        players.addPlayer(player, false);
        try {
            helper.assertTrue(chunks.getPlayers(new ChunkPos(a), false).contains(player)
                    && chunks.getPlayers(new ChunkPos(b), false).contains(player), "Fixture player tracks both bills");
            helper.setBlock(SIGN, Blocks.AIR);
            List<Object> updates = player.packets.stream().filter(TableItems.class::isInstance).toList();
            helper.assertValueEqual(updates.size(), 2, "coin clears received");
            helper.assertValueEqual(Set.copyOf(updates), Set.of(new TableItems(a, 0), new TableItems(b, 0)), "cleared table positions");
        } finally {
            players.removePlayer(player);
        }
        helper.succeed();
    }

    @GameTest(template = "courier_floor")
    public static void shopDeliveryUsesSharedTickBudget(GameTestHelper helper) {
        var level = helper.getLevel();
        var restaurant = placeRestaurant(helper);
        var point = new BlockPos(8, 1, 5);
        var spawn = point.west();
        for (BlockPos pos : BlockPos.betweenClosed(point.offset(-1, -1, -1), point.offset(1, -1, 1)))
            helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(point.below(), Blocks.STONE);
        var chest = GameTestSupport.place(helper, point, Blocks.CHEST, ChestBlockEntity.class);
        for (int slot = 8; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        call(Restaurant.class, restaurant, "setDelivery", new Class<?>[] {BlockPos.class}, helper.absolutePos(point));
        call(Restaurant.class, restaurant, "setSpawn", new Class<?>[] {BlockPos.class}, helper.absolutePos(spawn));
        withConfig("SHOP_DELIVERY", 0, () -> {
            for (int amount : List.of(10, 18)) {
                var entry = new Shop.Entry("swords", new ItemStack(Items.IRON_SWORD, amount), new ItemStack(Items.IRON_SWORD), 0);
                helper.assertValueEqual(restaurant.buy(entry), Restaurant.Buy.OK, "Checkout accepted");
            }
        });

        call(Restaurant.class, restaurant, "tickDeliveries", new Class<?>[] {ServerLevel.class}, level);
        var first = level.getEntitiesOfClass(Courier.class, helper.getBounds());
        helper.assertValueEqual(first.size(), 1, "One courier may start per restaurant per tick");
        first.getFirst().setNoAi(true);

        helper.runAfterDelay(1, () -> {
            call(Restaurant.class, restaurant, "tickDeliveries", new Class<?>[] {ServerLevel.class}, level);
            var couriers = level.getEntitiesOfClass(Courier.class, helper.getBounds());
            helper.assertValueEqual(couriers.size(), 2, "The second courier starts on the next tick");
            Courier second = couriers.stream().filter(courier -> courier != first.getFirst()).findFirst().orElseThrow();
            var at = Vec3.atBottomCenterOf(helper.absolutePos(spawn));
            for (Courier courier : List.of(first.getFirst(), second)) {
                courier.setNoAi(true);
                courier.setPos(at.x, at.y, at.z);
                call(Courier.class, courier, "customServerAiStep", new Class<?>[0]);
            }
            int packed = drops(helper, OneMoreCore.CARDBOARD_BOX_ITEM.get()).stream()
                    .map(ItemEntity::getItem).map(stack -> stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY))
                    .flatMap(ItemContainerContents::nonEmptyStream).mapToInt(ItemStack::getCount).sum();
            helper.assertValueEqual(chest.countItem(Items.IRON_SWORD), 8, "The chest accepted eight stacks");
            helper.assertValueEqual(packed, 8, "Packed item drops use the remaining budget");
            var pending = save(restaurant).getList("deliveries", Tag.TAG_COMPOUND);
            helper.assertValueEqual(pending.size(), 1, "A partially delivered order remains saved");
            helper.assertValueEqual(pending.getCompound(0).getList("items", Tag.TAG_COMPOUND).getCompound(0).getInt("count"),
                    12, "Only the undelivered stacks remain");
            helper.runAfterDelay(1, () -> {
                second.setPos(at.x, at.y, at.z);
                call(Courier.class, second, "customServerAiStep", new Class<?>[0]);
                int total = drops(helper, OneMoreCore.CARDBOARD_BOX_ITEM.get()).stream()
                        .map(ItemEntity::getItem).map(stack -> stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY))
                        .flatMap(ItemContainerContents::nonEmptyStream).mapToInt(ItemStack::getCount).sum();
                helper.assertValueEqual(total, 20, "The next tick delivers the rest");
                helper.assertTrue(save(restaurant).getList("deliveries", Tag.TAG_COMPOUND).isEmpty(), "Both orders completed");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void daySummarySeparatesPriceAndTip(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        withConfig("MAX_TIP", 25, () -> withConfig("COLLECT_PAYMENTS", false, () -> {
            Object party = serveBread(helper, r, 100, false);
            int bill = (int) read(PARTY, party, "bill");
            helper.assertTrue(bill > 100, "The fixture includes a tip");
            call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, r, true);
            DaySummary summary = summary(r);
            helper.assertValueEqual(summary.income(), 100L, "Dish price excludes the tip");
            helper.assertValueEqual(summary.tips(), (long) bill - 100, "Tip matches the paid bill");
            helper.assertValueEqual(summary.served(), 1, "Served customers");
            helper.assertValueEqual(summary.register(), bill, "Direct payment balance");
            call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, r, true);
            helper.assertValueEqual(summary(r), summary, "Repeated settlement changes no counters");
        }));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void daySummaryCountsServedDishes(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        serveBread(helper, r, 0, true);
        serveBread(helper, r, 20, true);
        call(Restaurant.class, r, "recordDish", new Class<?>[] {ItemStack.class}, new ItemStack(Items.CARROT));
        DaySummary summary = summary(r);
        helper.assertValueEqual(summary.bestDish(), "minecraft:bread", "Actual served item wins");
        helper.assertValueEqual(summary.bestCount(), 2, "Free and partial-order dishes count");
        call(Restaurant.class, r, "recordDish", new Class<?>[] {ItemStack.class}, new ItemStack(Items.CARROT));
        helper.assertValueEqual(summary(r).bestDish(), "minecraft:bread", "Ties use stable item ID order");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void daySummaryPersistsAndResets(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        Object party = serveBread(helper, r, 100, false);
        call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, r, true);
        write(Restaurant.class, r, "lost", 2);
        write(Restaurant.class, r, "penalties", 7L);
        DaySummary current = summary(r);
        Restaurant restored = reload(helper, r, save(r));
        helper.assertValueEqual(summary(restored), current, "Current counters survive saving");
        write(Restaurant.class, restored, "open", true);
        call(Restaurant.class, restored, "endDay", new Class<?>[] {ServerLevel.class, boolean.class}, helper.getLevel(), false);
        helper.assertValueEqual(restored.lastSummary(), current, "Day end stores the snapshot before advancing the day");
        Restaurant again = reload(helper, restored, save(restored));
        helper.assertValueEqual(again.lastSummary(), current, "Last summary survives saving");
        call(Restaurant.class, again, "startDay", new Class<?>[] {ServerLevel.class}, helper.getLevel());
        DaySummary next = summary(again);
        helper.assertValueEqual(next.income(), 0L, "Next day income resets");
        helper.assertValueEqual(next.tips(), 0L, "Next day tips reset");
        helper.assertValueEqual(next.served(), 0, "Next day served count resets");
        helper.assertValueEqual(next.lost(), 0, "Next day lost count resets");
        helper.assertValueEqual(next.penalties(), 0L, "Next day penalties reset");
        helper.assertValueEqual(next.bestCount(), 0, "Next day dish counts reset");
        helper.assertValueEqual(next.bestDish(), "", "An empty day has no best dish");
        helper.assertValueEqual(again.lastSummary(), current, "Yesterday remains available");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void daySummaryEndDayUsesServerCounters(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        write(Restaurant.class, r, "open", true);
        write(Restaurant.class, r, "earned", 137);
        write(Restaurant.class, r, "tips", 17L);
        write(Restaurant.class, r, "penalties", 9L);
        write(Restaurant.class, r, "served", 3);
        write(Restaurant.class, r, "lost", 2);
        GameTestSupport.setRegister(r, 44);
        call(Restaurant.class, r, "recordDish", new Class<?>[] {ItemStack.class}, new ItemStack(Items.BREAD));
        int day = r.day();
        call(Restaurant.class, r, "endDay", new Class<?>[] {ServerLevel.class, boolean.class}, helper.getLevel(), true);
        DaySummary expected = new DaySummary(day, 120, 17, 9, 3, 2, "minecraft:bread", 1, 44, 0, 3);
        helper.assertValueEqual(r.lastSummary(), expected, "Failed day snapshot uses authoritative counters");
        helper.assertValueEqual(r.lastSummary().net(), 128L, "Net is prices plus tips minus actual penalties");
        helper.assertValueEqual(r.day(), day, "Failed day does not advance");
        write(Restaurant.class, r, "earned", 999);
        call(Restaurant.class, r, "endDay", new Class<?>[] {ServerLevel.class, boolean.class}, helper.getLevel(), false);
        helper.assertValueEqual(r.lastSummary(), expected, "Repeated day end does not replace the summary");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void daySummaryCodecIsBounded(GameTestHelper helper) {
        var buffer = io.netty.buffer.Unpooled.buffer();
        try {
            DaySummary expected = new DaySummary(4, Integer.MAX_VALUE - 20L, 20, 500, 7, 3, "minecraft:bread", 6, 42, 2.5f, 3.75f);
            DaySummary.CODEC.encode(buffer, expected);
            helper.assertValueEqual(DaySummary.CODEC.decode(buffer), expected, "All fields round trip");
            buffer.clear();
            boolean rejected = false;
            try {
                DaySummary.CODEC.encode(buffer, new DaySummary(1, 0, 0, 0, 0, 0, "a".repeat(257), 0, 0, 0, 3));
            } catch (io.netty.handler.codec.EncoderException expectedError) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Dish IDs longer than 256 characters are rejected");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void daySummaryNotifiesEachRestaurantPlayerOnce(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        TestPlayer inside = GameTestSupport.player(helper);
        TestPlayer signUser = GameTestSupport.player(helper);
        TestPlayer outsider = GameTestSupport.player(helper);
        signUser.setPos(r.sign.getX() + 20, r.sign.getY(), r.sign.getZ());
        outsider.setPos(r.sign.getX() + 30, r.sign.getY(), r.sign.getZ());
        // getPlayers() is an unmodifiable view; register fixtures in its backing list.
        var online = (List<ServerPlayer>) read(PlayerList.class, helper.getLevel().getServer().getPlayerList(), "players");
        online.addAll(List.of(inside, signUser, outsider));
        try {
            withConfig("DAY_SUMMARY", true, () -> {
                write(Restaurant.class, r, "readyInitiator", signUser.getUUID());
                call(Restaurant.class, r, "startDay", new Class<?>[] {ServerLevel.class}, helper.getLevel());
                call(Restaurant.class, r, "endDay", new Class<?>[] {ServerLevel.class, boolean.class}, helper.getLevel(), false);
                call(Restaurant.class, r, "endDay", new Class<?>[] {ServerLevel.class, boolean.class}, helper.getLevel(), true);
                helper.assertValueEqual(inside.packets.stream().filter(DaySummary.Open.class::isInstance).count(), 1L, "Area player receives one summary");
                helper.assertValueEqual(signUser.packets.stream().filter(DaySummary.Open.class::isInstance).count(), 1L, "Outside sign user receives one summary");
                helper.assertValueEqual(outsider.packets.stream().filter(DaySummary.Open.class::isInstance).count(), 0L, "Unrelated player receives no summary");
            });
            inside.packets.clear();
            signUser.packets.clear();
            withConfig("DAY_SUMMARY", false, () -> {
                call(Restaurant.class, r, "startDay", new Class<?>[] {ServerLevel.class}, helper.getLevel());
                call(Restaurant.class, r, "endDay", new Class<?>[] {ServerLevel.class, boolean.class}, helper.getLevel(), false);
                helper.assertTrue(inside.packets.stream().noneMatch(DaySummary.Open.class::isInstance), "Config disables the popup");
                helper.assertTrue(r.lastSummary() != null, "Config does not discard the saved summary");
            });
        } finally {
            online.removeAll(List.of(inside, signUser, outsider));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void daySummaryLostLimitEndsDay(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        write(Restaurant.class, r, "open", true);
        GameTestSupport.setRegister(r, 20);
        withConfig("MAX_LOST_PER_DAY", 1, () -> withConfig("LOST_CUSTOMER_PENALTY", 7, () -> {
            Object party = serveBread(helper, r, 10, true);
            call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, r, false);
            helper.assertTrue(!r.isOpen(), "Lost customer limit closes the restaurant");
            helper.assertValueEqual(r.lastSummary(), new DaySummary(r.day(), 0, 0, 7, 0, 1, "minecraft:bread", 1, 13, 1, 1),
                    "Lost customer limit builds the summary after charging the penalty");
        }));
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void reviewsRewardFastServiceOncePerParty(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        withConfig("REVIEWS", true, () -> withConfig("DIRT_ENABLED", false, () -> {
            for (float patience : new float[] {1, 0.25f}) {
                Object party = serveBread(helper, r, 10, false, patience);
                Customer member = ((List<Customer>) read(PARTY, party, "members")).getFirst();
                write(Customer.class, member, "timer", ((ConfigValue<Integer>) read(Config.class, null, "EAT_TICKS")).get() + 1);
                call(PARTY, party, "tick", new Class<?>[] {Restaurant.class}, r);
                helper.assertValueEqual(member.getReview(), patience == 1 ? 5 : 2, "Service speed determines departure stars");
                helper.assertValueEqual(member.getState(), Customer.State.LEAVING, "Review appears on departure");
                call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, r, true);
            }
            helper.assertValueEqual(save(r).getIntArray("reviews").length, 2, "One review per party despite repeated settlement");
            helper.assertValueEqual(summary(r).reviewAverage(), 3.5f, "Today's average includes both parties");
        }));
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void reviewsUseWrongDishesDirtAndDecor(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        withConfig("REVIEWS", true, () -> withConfig("WRONG_PENALTY", 0, () -> {
            for (int scenario = 0; scenario < 4; scenario++) {
                dirt(helper, r, helper.absolutePos(TABLE), 0);
                Map<String, Integer> upgrades = (Map<String, Integer>) read(Restaurant.class, r, "upgrades");
                upgrades.put("decor", scenario == 3 ? 3 : 0);
                Object party = serveBread(helper, r, 10, true, 0.75f);
                Customer member = ((List<Customer>) read(PARTY, party, "members")).getFirst();
                TestPlayer player = GameTestSupport.player(helper);
                if (scenario >= 1) {
                    GameTestSupport.give(player, new ItemStack(Items.STICK));
                    for (int i = 0; i < 2; i++) call(Customer.class, member, "mobInteract",
                            new Class<?>[] {Player.class, InteractionHand.class}, player, InteractionHand.MAIN_HAND);
                }
                if (scenario >= 2) dirt(helper, r, helper.absolutePos(TABLE), com.onemorestudios.onemorecore.Dirt.FULL);
                ItemStack tray = new ItemStack(OneMoreCore.TRAY_ITEM.get());
                tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.CARROT))));
                helper.assertTrue(member.deliver(player, tray), "The party completes its correct order");
                call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, r, true);
                helper.assertValueEqual(member.getReview(), new int[] {4, 3, 2, 3}[scenario],
                        "Wrong attempts and existing dirt lower stars; decor supplies a small bonus");
            }
        }));
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void reviewsOffPreservesHistoryWithoutNewReviews(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        call(Restaurant.class, r, "recordReview", new Class<?>[] {int.class}, 5);
        withConfig("REVIEWS", false, () -> {
            Object party = serveBread(helper, r, 10, false);
            call(PARTY, party, "settle", new Class<?>[] {Restaurant.class, boolean.class}, r, true);
            Customer member = ((List<Customer>) read(PARTY, party, "members")).getFirst();
            helper.assertValueEqual(member.getReview(), 0, "Disabled reviews show no stars");
            helper.assertValueEqual(save(r).getIntArray("reviews").length, 1, "Disabled reviews retain the saved history");
            helper.assertValueEqual(summary(r).rating(), 0f, "Disabled reviews hide summary rating");
            helper.assertValueEqual(call(Restaurant.class, r, "reviewIntervalFactor", new Class<?>[0]), 1.0,
                    "Disabled reviews preserve normal spawn timing");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unservedPartyReviewsOneStar(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        withConfig("REVIEWS", true, () -> withConfig("MAX_LOST_PER_DAY", 0, () -> {
            Object party = serveBread(helper, r, 10, true);
            write(PARTY, party, "patience", 1);
            call(PARTY, party, "tick", new Class<?>[] {Restaurant.class}, r);
            helper.assertValueEqual(r.rating(), 1f, "A party leaving without its complete order gives one star");
            helper.assertValueEqual(summary(r).reviewAverage(), 1f, "Unserved review enters the daily summary");
        }));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reviewsKeepLastTwentyAndSurviveSaving(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        for (int i = 0; i < 30; i++) call(Restaurant.class, r, "recordReview", new Class<?>[] {int.class}, i < 10 ? 1 : 2 + (i % 2) * 2);
        helper.assertValueEqual(r.rating(), 3f, "Only the last twenty reviews count");
        write(Restaurant.class, r, "lastSummary", summary(r));
        CompoundTag saved = save(r);
        helper.assertValueEqual(saved.getIntArray("reviews").length, 20, "History is bounded");
        Restaurant restored = reload(helper, r, saved);
        helper.assertValueEqual(restored.rating(), 3f, "Rating survives saving");
        helper.assertValueEqual(restored.lastSummary(), r.lastSummary(), "Yesterday's review average and rating survive saving");
        helper.assertValueEqual(summary(restored), summary(r), "Daily review counters survive saving");
        call(Restaurant.class, restored, "recordReview", new Class<?>[] {int.class}, 5);
        helper.assertValueEqual(restored.rating(), 3.15f, "Reload preserves review order for eviction");
        call(Restaurant.class, restored, "startDay", new Class<?>[] {ServerLevel.class}, helper.getLevel());
        helper.assertValueEqual(summary(restored).reviewAverage(), 0f, "Starting a day resets only today's average");
        helper.assertValueEqual(restored.rating(), 3.15f, "Starting a day preserves rating");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reviewRatingScalesActualSpawnInterval(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        withConfig("REVIEWS", true, () -> {
            for (int day : new int[] {1, 100}) {
                write(Restaurant.class, r, "day", day);
                for (int stars : new int[] {1, 3, 5}) {
                    for (int i = 0; i < 20; i++) call(Restaurant.class, r, "recordReview", new Class<?>[] {int.class}, stars);
                    double factor = (double) call(Restaurant.class, r, "reviewIntervalFactor", new Class<?>[0]);
                    helper.assertTrue(factor >= 0.7 && factor <= 1.3, "Rating factor stays within its clamp");
                    for (int seed = 0; seed < 16; seed++) {
                        helper.getLevel().random.setSeed(seed);
                        int rated = (int) call(Restaurant.class, r, "nextInterval", new Class<?>[] {ServerLevel.class}, helper.getLevel());
                        int sampleSeed = seed;
                        withConfig("REVIEWS", false, () -> {
                            helper.getLevel().random.setSeed(sampleSeed);
                            int base = (int) call(Restaurant.class, r, "nextInterval", new Class<?>[] {ServerLevel.class}, helper.getLevel());
                            helper.assertTrue(stars == 1 ? rated > base : stars == 5 ? rated < base : rated == base,
                                    "The same spawn timing sample follows the rating");
                        });
                    }
                }
            }
        });
        helper.succeed();
    }

    private static DaySummary summary(Restaurant r) {
        return (DaySummary) call(Restaurant.class, r, "daySummary", new Class<?>[0]);
    }

    @GameTest(template = "empty")
    public static void preferenceDishPreferredWhenAvailable(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        GameTestSupport.setRegister(r, 10000);
        GameTestSupport.unlock(helper, r, "wheat", "cocoa_beans");
        List<com.onemorestudios.onemorecore.Menu.Entry> previous = com.onemorestudios.onemorecore.Menu.entries;
        try {
            com.onemorestudios.onemorecore.Menu.entries = List.of(
                    new com.onemorestudios.onemorecore.Menu.Entry("preference_bread", new ItemStack(Items.BREAD), null, 2, false, "main"),
                    new com.onemorestudios.onemorecore.Menu.Entry("preference_cookie", null, net.minecraft.tags.TagKey.create(
                            net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.ResourceLocation.parse("onemorecore:preferences/test_choices")),
                            2, false, "main"));
            helper.assertTrue(r.orderable(r.dish("preference_cookie")), "The matching cookie is orderable");
            for (int seed = 0; seed < 64; seed++) {
                List<Customer.Line> order = preferenceOrder(r, seed, "test_sweet");
                helper.assertTrue(order.stream().anyMatch(l -> l.stack().is(Items.COOKIE)), "An available matching dish is included");
                helper.assertTrue(order.size() <= 3, "Preferences preserve tray capacity");
                helper.assertTrue(order.stream().allMatch(l -> r.dishes().stream().anyMatch(e -> r.orderable(e)
                        && (e.tag() != null ? l.stack().is(e.tag()) : ItemStack.isSameItem(e.stack(), l.stack())))),
                        "Every preferred order line remains orderable");
            }
        } finally {
            com.onemorestudios.onemorecore.Menu.entries = previous;
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preferenceFallsBackWhenNothingMatches(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        GameTestSupport.setRegister(r, 10000);
        GameTestSupport.unlock(helper, r, "wheat");
        for (int seed = 0; seed < 64; seed++) {
            List<Customer.Line> normal = preferenceOrder(r, seed, "");
            for (String preference : List.of("test_sweet", "missing_tag")) {
                List<Customer.Line> fallback = preferenceOrder(r, seed, preference);
                helper.assertFalse(fallback.isEmpty(), "No match still gives an order");
                helper.assertValueEqual(fallback.stream().map(l -> l.stack().getItem()).toList(),
                        normal.stream().map(l -> l.stack().getItem()).toList(), "Fallback preserves the normal roll");
                helper.assertTrue(fallback.stream().allMatch(l -> r.dishes().stream()
                        .anyMatch(e -> r.orderable(e) && e.stack() != null && ItemStack.isSameItem(e.stack(), l.stack()))),
                        "Fallback orders only available dishes");
            }
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static List<Customer.Line> preferenceOrder(Restaurant r, int seed, String preference) {
        return (List<Customer.Line>) call(Restaurant.class, r, "makeOrder",
                new Class<?>[] {net.minecraft.util.RandomSource.class, Customer.Type.class, List.class, String.class},
                net.minecraft.util.RandomSource.create(seed), Customer.Type.values()[seed % Customer.Type.values().length], new java.util.ArrayList<String>(), preference);
    }

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty")
    public static void preferenceTipAppliedOnce(GameTestHelper helper) {
        Restaurant r = placeRestaurant(helper);
        withConfig("MAX_TIP", 50, () -> withConfig("PREFERENCE_TIP", 1.5, () -> {
            for (float patience : new float[] {0.5f, 1f}) {
                Object party = serveBread(helper, r, 100, false, patience, "test_bread");
                int expected = patience == 0.5f ? 137 : 175;
                helper.assertValueEqual(read(PARTY, party, "bill"), expected, "Matching dish bonus");
                Customer member = ((List<Customer>) read(PARTY, party, "members")).getFirst();
                ItemStack tray = new ItemStack(OneMoreCore.TRAY_ITEM.get());
                tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.BREAD))));
                helper.assertFalse(member.deliver(GameTestSupport.player(helper), tray), "A served dish cannot be delivered again");
                helper.assertValueEqual(read(PARTY, party, "bill"), expected, "Repeated delivery adds no bonus");
                Object normal = serveBread(helper, r, 100, false, patience, "missing_tag");
                helper.assertValueEqual(read(PARTY, normal, "bill"), patience == 0.5f ? 125 : 150, "Unmatched dish has the normal tip");
            }
            Object mixed = serveBread(helper, r, 100, true, 0.5f, "test_bread");
            Customer member = ((List<Customer>) read(PARTY, mixed, "members")).getFirst();
            ItemStack tray = new ItemStack(OneMoreCore.TRAY_ITEM.get());
            tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.CARROT))));
            helper.assertTrue(member.deliver(GameTestSupport.player(helper), tray), "The remaining unmatched dish completes the order");
            helper.assertValueEqual(read(PARTY, mixed, "bill"), 187, "Only the matching dish receives the multiplier");
        }));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preferenceEmptyListDisablesRoll(GameTestHelper helper) {
        withConfig("PREFERENCES", List.of(), () -> {
            for (int seed = 0; seed < 64; seed++) helper.assertValueEqual(call(Customer.class, null, "rollPreference",
                    new Class<?>[] {net.minecraft.util.RandomSource.class}, net.minecraft.util.RandomSource.create(seed)), "", "Empty list disables preferences");
        });
        withConfig("PREFERENCES", List.of("test_bread=1"), () -> {
            for (int seed = 0; seed < 64; seed++) helper.assertValueEqual(call(Customer.class, null, "rollPreference",
                    new Class<?>[] {net.minecraft.util.RandomSource.class}, net.minecraft.util.RandomSource.create(seed)), "test_bread", "Chance one always assigns one preference");
        });
        withConfig("PREFERENCES", List.of("test_bread=0"), () -> helper.assertValueEqual(call(Customer.class, null, "rollPreference",
                new Class<?>[] {net.minecraft.util.RandomSource.class}, net.minecraft.util.RandomSource.create(0)), "", "Chance zero never assigns a preference"));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preferenceSurvivesSaveLoad(GameTestHelper helper) {
        withConfig("PREFERENCES", List.of("test_bread=1"), () -> {
            Customer original = new Customer(OneMoreCore.CUSTOMER.get(), helper.getLevel());
            CompoundTag source = new CompoundTag();
            source.putString("Preference", "test_bread");
            original.readAdditionalSaveData(source);
            CompoundTag saved = new CompoundTag();
            original.addAdditionalSaveData(saved);
            Customer restored = new Customer(OneMoreCore.CUSTOMER.get(), helper.getLevel());
            restored.readAdditionalSaveData(saved);
            helper.assertValueEqual(restored.getPreference(), "test_bread", "Preference survives the entity save hooks");
            for (String invalid : List.of("../test_bread", "unknown", "test_bread=1", "")) {
                saved.putString("Preference", invalid);
                restored.readAdditionalSaveData(saved);
                helper.assertValueEqual(restored.getPreference(), "", "Invalid or removed preference is dropped");
            }
        });
        helper.succeed();
    }

    private static Restaurant placeRestaurant(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        helper.setBlock(TABLE, Blocks.OAK_SLAB);
        helper.setBlock(OTHER, Blocks.OAK_SLAB);
        helper.setBlock(SEAT, Blocks.OAK_STAIRS);
        Restaurant r = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
        GameTestSupport.setRegister(r, 0);
        BoundingBox area = BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(3, 3, 3)));
        call(Restaurant.class, r, "setArea", new Class<?>[] {BoundingBox.class}, area);
        return r;
    }

    /** An old tray can hold a dish past its 3 places: served, it gets no pose (a pose there would be off the tray and not sync). */
    @GameTest(template = "empty")
    public static void dishPastTheTraysPlacesHasNoPose(GameTestHelper helper) throws ReflectiveOperationException {
        ItemStack tray = new ItemStack(OneMoreCore.TRAY_ITEM.get());
        tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, new ItemStack(Items.BREAD))));
        var serve = com.onemorestudios.onemorecore.TrayItem.class.getDeclaredMethod("serveDishes", ItemStack.class,
                java.util.function.Predicate.class, java.util.function.BiConsumer.class);
        serve.setAccessible(true);
        List<Object> poses = new java.util.ArrayList<>();
        serve.invoke(null, tray, (java.util.function.Predicate<ItemStack>) s -> true,
                (java.util.function.BiConsumer<ItemStack, Object>) (dish, at) -> poses.add(at == null ? "none" : at));
        helper.assertValueEqual(poses, List.<Object>of("none"), "the pose of the 4th slot's dish");
        helper.succeed();
    }

    private static Object serveBread(GameTestHelper helper, Restaurant r, int price, boolean partial) {
        return serveBread(helper, r, price, partial, 1);
    }

    private static Object serveBread(GameTestHelper helper, Restaurant r, int price, boolean partial, float patienceLeft) {
        return serveBread(helper, r, price, partial, patienceLeft, "");
    }

    private static Object serveBread(GameTestHelper helper, Restaurant r, int price, boolean partial, float patienceLeft, String preference) {
        Object party;
        try {
            Constructor<?> constructor = PARTY.getDeclaredConstructor();
            constructor.setAccessible(true);
            party = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        write(PARTY, party, "preference", preference);
        Customer customer = new Customer(OneMoreCore.CUSTOMER.get(), helper.getLevel());
        Restaurant.Seat seat = new Restaurant.Seat(helper.absolutePos(SEAT), helper.absolutePos(TABLE));
        Customer.Line bread = new Customer.Line(new ItemStack(Items.BREAD), price, false);
        List<Customer.Line> order = partial ? List.of(bread, new Customer.Line(new ItemStack(Items.CARROT), 40, false)) : List.of(bread);
        call(Customer.class, customer, "setup", new Class<?>[] {Restaurant.class, Restaurant.Seat.class, List.class, PARTY}, r, seat, order, party);
        call(Customer.class, customer, "sit", new Class<?>[0]);
        TestPlayer player = GameTestSupport.player(helper);
        call(Customer.class, customer, "mobInteract", new Class<?>[] {Player.class, InteractionHand.class}, player, InteractionHand.MAIN_HAND);
        write(PARTY, party, "patience", Math.max(1, Math.round((int) read(PARTY, party, "patienceMax") * patienceLeft)));
        ItemStack tray = new ItemStack(OneMoreCore.TRAY_ITEM.get());
        tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.BREAD))));
        // The bread lies turned in a corner of the tray: it reaches the table with that pose.
        var placed = new com.onemorestudios.onemorecore.TrayPose(12.5f, 1, 4.25f, 0, new org.joml.Quaternionf().rotationY(0.9f), 1.1f, -1, 1);
        tray.set(OneMoreCore.TRAY_POSES.get(), List.of(placed));
        helper.assertTrue(customer.deliver(player, tray), "Customer accepted the dish on the tray");
        var shown = customer.getServed().get(0);
        helper.assertTrue(shown.is(Items.BREAD) && List.of(placed).equals(shown.get(OneMoreCore.TRAY_POSES.get())),
                "The served bread keeps its tray pose: " + shown.get(OneMoreCore.TRAY_POSES.get()));
        helper.assertTrue(Customer.State.EATING == customer.getState() || partial && Customer.State.WAITING_FOOD == customer.getState(),
                "Customer reached the expected service state");
        return party;
    }

    private static void addBill(Restaurant r, BlockPos pos, int amount) {
        call(Restaurant.class, r, "addCoins", new Class<?>[] {BlockPos.class, int.class}, pos, amount);
    }

    private static int billAt(Restaurant r, BlockPos pos) {
        for (Tag entry : save(r).getList("table_items", Tag.TAG_COMPOUND)) {
            CompoundTag bill = (CompoundTag) entry;
            if (bill.getLong("pos") == pos.asLong()) return bill.getInt("coins");
        }
        return 0;
    }

    private static int collect(GameTestHelper helper, Restaurant r, Player player, BlockPos pos) {
        return (int) call(Restaurant.class, r, "collect", new Class<?>[] {ServerLevel.class, Player.class, BlockPos.class},
                helper.getLevel(), player, pos);
    }

    private static List<ItemEntity> drops(GameTestHelper helper, Item item) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds(), entity -> entity.getItem().is(item));
    }

    private static int droppedMoney(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds()).stream()
                .mapToInt(entity -> (int) Money.value(entity.getItem())).sum();
    }

    private static CompoundTag save(Restaurant r) {
        return (CompoundTag) call(Restaurant.class, r, "save", new Class<?>[0]);
    }

    private static Restaurant reload(GameTestHelper helper, Restaurant r, CompoundTag saved) {
        Restaurant restored = (Restaurant) call(Restaurant.class, null, "load", new Class<?>[] {Restaurants.class, CompoundTag.class},
                Restaurants.get(helper.getLevel()), saved);
        helper.assertValueEqual(restored.sign, r.sign, "reloaded sign position");
        call(Restaurant.class, restored, "tick", new Class<?>[] {ServerLevel.class}, helper.getLevel());
        return restored;
    }

    private static Map<?, ?> dirt(Restaurant r) {
        return (Map<?, ?>) read(Restaurant.class, r, "dirt");
    }

    private static void dirt(GameTestHelper helper, Restaurant r, BlockPos pos, long cells) {
        call(Restaurant.class, r, "setDirt", new Class<?>[] {ServerLevel.class, BlockPos.class, long.class}, helper.getLevel(), pos, cells);
    }

    private static void wipe(GameTestHelper helper, Restaurant r, Set<BlockPos> table, BlockPos pos) {
        call(Restaurant.class, r, "wipeCells", new Class<?>[] {ServerLevel.class, Set.class, int.class, int.class, Vec3.class},
                helper.getLevel(), table, pos.getX() * 8 + 1, pos.getZ() * 8 + 1, Vec3.atCenterOf(pos));
    }

    @SuppressWarnings("unchecked")
    private static <T> void withConfig(String name, T value, Runnable action) {
        ConfigValue<T> config = (ConfigValue<T>) read(Config.class, null, name);
        T previous = config.get();
        config.set(value);
        try {
            action.run();
        } finally {
            config.set(previous);
        }
    }

    // Fixtures use the package-private server paths without widening their production visibility.
    private static Class<?> nestedClass(Class<?> owner, String name) {
        for (Class<?> nested : owner.getDeclaredClasses()) if (nested.getSimpleName().equals(name)) return nested;
        throw new AssertionError("Missing nested class " + name);
    }

    private static Object call(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object read(Class<?> owner, Object target, String name) {
        try {
            return field(owner, name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Class<?> owner, Object target, String name, Object value) {
        try {
            field(owner, name).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
