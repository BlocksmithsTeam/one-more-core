package com.onemorestudios.onemorecore.gametest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Courier;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
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
        helper.assertValueEqual(dropped(helper, Config.currency()), 12, "overflow at the sign");
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
        helper.assertValueEqual(dropped(helper, Config.currency()), 0, "coins dropped during collection");
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
            helper.assertValueEqual(dropped(helper, Config.currency()), payment - 5, "currency dropped at the sign");
            helper.assertValueEqual(billAt(restaurant, helper.absolutePos(TABLE)), 0, "table bill after direct payment");
        });
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

    private static Object serveBread(GameTestHelper helper, Restaurant r, int price, boolean partial) {
        Object party;
        try {
            Constructor<?> constructor = PARTY.getDeclaredConstructor();
            constructor.setAccessible(true);
            party = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        Customer customer = new Customer(OneMoreCore.CUSTOMER.get(), helper.getLevel());
        Restaurant.Seat seat = new Restaurant.Seat(helper.absolutePos(SEAT), helper.absolutePos(TABLE));
        Customer.Line bread = new Customer.Line(new ItemStack(Items.BREAD), price, false);
        List<Customer.Line> order = partial ? List.of(bread, new Customer.Line(new ItemStack(Items.CARROT), 40, false)) : List.of(bread);
        call(Customer.class, customer, "setup", new Class<?>[] {Restaurant.class, Restaurant.Seat.class, List.class, PARTY}, r, seat, order, party);
        call(Customer.class, customer, "sit", new Class<?>[0]);
        TestPlayer player = GameTestSupport.player(helper);
        call(Customer.class, customer, "mobInteract", new Class<?>[] {Player.class, InteractionHand.class}, player, InteractionHand.MAIN_HAND);
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

    private static int dropped(GameTestHelper helper, Item item) {
        return drops(helper, item).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
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
