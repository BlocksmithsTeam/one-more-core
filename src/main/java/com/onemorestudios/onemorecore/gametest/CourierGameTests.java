package com.onemorestudios.onemorecore.gametest;

import java.util.List;
import java.util.Map;

import com.onemorestudios.onemorecore.CardboardBoxBlockEntity;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Courier;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.Shop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class CourierGameTests {
    private static final String FLOOR = "courier_floor";
    private static final BlockPos START = new BlockPos(2, 1, 5);
    private static final BlockPos POINT = new BlockPos(10, 1, 5);
    private static final BlockPos SIGN = new BlockPos(6, 1, 2);

    @GameTest(template = FLOOR, timeoutTicks = 240)
    public static void courierFollowsDelayAndReturnsAfterFillingChest(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT);
        var chest = GameTestSupport.place(helper, POINT, Blocks.CHEST, ChestBlockEntity.class);
        buy(helper, restaurant, 10, Map.of(entry(Items.WHEAT, 5), 2, entry(Items.CARROT, 3), 1));
        helper.runAfterDelay(4, () -> helper.assertTrue(couriers(helper).isEmpty(), "Courier appeared before the delay"));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(couriers(helper).size(), 1, "Couriers for the entire cart");
            Courier courier = couriers(helper).getFirst();
            helper.assertTrue(courier.carrying() && courier.blockPosition().equals(helper.absolutePos(START)), "Courier spawns with the parcel");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(chest.countItem(Items.WHEAT), 10, "Wheat in the destination chest");
            helper.assertValueEqual(chest.countItem(Items.CARROT), 3, "Carrots in the destination chest");
        }).thenWaitUntil(() -> {
            helper.assertTrue(couriers(helper).isEmpty(), "Courier returned and vanished");
            helper.assertValueEqual(boxes(helper, POINT).size(), 0, "Boxes at the container that accepted the whole order");
        }).thenSucceed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 240)
    public static void unsetSpawnUsesFreeSpotOutsideRestaurant(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT);
        method(Restaurant.class, "setSpawn", BlockPos.class).invoke(restaurant, (BlockPos) null);
        method(Restaurant.class, "setArea", BoundingBox.class).invoke(restaurant,
                BoundingBox.fromCorners(helper.absolutePos(new BlockPos(5, 0, 1)), helper.absolutePos(new BlockPos(11, 4, 9))));
        buy(helper, restaurant, 0, Map.of(entry(Items.POTATO, 4), 1));
        method(Restaurant.class, "tickDeliveries", ServerLevel.class).invoke(restaurant, helper.getLevel());
        helper.assertValueEqual(couriers(helper).size(), 1, "Dispatched courier");
        BlockPos spawn = couriers(helper).getFirst().blockPosition();
        helper.assertFalse(restaurant.area().isInside(spawn), "Fallback spawn is outside the restaurant");
        helper.assertValueEqual(spawn.distSqr(helper.absolutePos(POINT)), 64.0, "Squared distance to the delivery point");
        helper.succeedWhen(() -> assertBoxes(helper, POINT, Items.POTATO, 1, 4));
    }

    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void openPointGetsTwoBoxesForTwentyEightStacks(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT);
        buy(helper, restaurant, 0, Map.of(entry(Items.IRON_SWORD, 28), 1));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(OneMoreCore.CARDBOARD_BOX.get(), POINT);
            assertBoxes(helper, POINT, Items.IRON_SWORD, 2, 28);
        });
    }

    /** A saturated container keeps its original contents and sends the remainder into parcels. */
    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void fullChestKeepsOrderInBoxes(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT);
        var chest = GameTestSupport.place(helper, POINT, Blocks.CHEST, ChestBlockEntity.class);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(0, new ItemStack(Items.WHEAT, 63));
        buy(helper, restaurant, 0, Map.of(entry(Items.WHEAT, 7), 1));
        helper.succeedWhen(() -> {
            helper.assertValueEqual(chest.countItem(Items.WHEAT), 64, "The chest accepted what fit");
            helper.assertValueEqual(chest.countItem(Items.COBBLESTONE), 26 * 64, "Existing chest contents");
            assertBoxes(helper, POINT, Items.WHEAT, 1, 6);
        });
    }

    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void blockedRouteLeavesBoxAtCourierAndVanishes(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT);
        for (BlockPos pos : BlockPos.betweenClosed(START.offset(-1, 0, -1), START.offset(1, 2, 1))) {
            if (pos.getX() != START.getX() || pos.getZ() != START.getZ()) helper.setBlock(pos, Blocks.BEDROCK);
        }
        buy(helper, restaurant, 0, Map.of(entry(Items.POTATO, 11), 1));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(OneMoreCore.CARDBOARD_BOX.get(), START);
            assertBoxes(helper, START, Items.POTATO, 1, 11);
            helper.assertTrue(couriers(helper).isEmpty(), "The blocked courier vanished");
        });
    }

    @GameTest(template = FLOOR)
    public static void pickupAndPlacementPreserveContentsAndEmptyCloseRemovesBox(GameTestHelper helper) {
        floor(helper);
        var player = GameTestSupport.player(helper);
        var box = box(helper, START);
        var menu = box.createMenu(1, player.getInventory(), player);
        menu.getSlot(4).set(new ItemStack(Items.WHEAT, 13));
        helper.assertTrue(menu.stillValid(player), "Box menu is usable in reach");
        Vec3 away = helper.absoluteVec(new Vec3(22.5, 1, 5.5));
        player.setPos(away.x, away.y, away.z);
        helper.assertFalse(menu.stillValid(player), "Box menu is unusable out of reach");
        Vec3 near = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        player.setPos(near.x, near.y, near.z);
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(START), null) == null,
                "No hopper capability");
        player.setShiftKeyDown(true);
        GameTestSupport.click(helper, player, START);
        helper.assertBlockNotPresent(OneMoreCore.CARDBOARD_BOX.get(), START);
        helper.assertFalse(menu.stillValid(player), "Box menu is unusable after pickup");
        helper.assertTrue(player.getMainHandItem().is(OneMoreCore.CARDBOARD_BOX_ITEM.get()), "Player received the box");
        ItemStack parcel = player.getMainHandItem();
        helper.assertValueEqual(count(parcel, Items.WHEAT), 13, "Items inside the picked-up box");
        player.setShiftKeyDown(false);
        GameTestSupport.click(helper, player, START.below());
        var restored = (CardboardBoxBlockEntity) helper.getBlockEntity(START);
        var reopened = restored.createMenu(2, player.getInventory(), player);
        helper.assertValueEqual(reopened.getSlot(4).getItem().getCount(), 13, "Original slot after placement");
        reopened.getSlot(4).set(ItemStack.EMPTY);
        reopened.removed(player);
        helper.assertBlockNotPresent(OneMoreCore.CARDBOARD_BOX.get(), START);
        helper.succeed();
    }

    @GameTest(template = FLOOR)
    public static void breakingBoxDropsContentsButNoBox(GameTestHelper helper) {
        floor(helper);
        var player = GameTestSupport.player(helper);
        box(helper, START).createMenu(1, player.getInventory(), player).getSlot(0).set(new ItemStack(Items.BREAD, 7));
        helper.destroyBlock(START);
        helper.assertValueEqual(drops(helper).stream().map(ItemEntity::getItem)
                .filter(stack -> stack.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum(), 7, "Bread dropped from the box");
        helper.assertTrue(drops(helper).stream().noneMatch(drop -> drop.getItem().is(OneMoreCore.CARDBOARD_BOX_ITEM.get())), "No box item dropped");
        helper.succeed();
    }

    /** Legacy entries retain their checkout point, with the sign as the fallback for a missing destination tag. */
    @GameTest(template = FLOOR, timeoutTicks = 200)
    public static void legacyOrdersKeepCheckoutPointAndUseSignWhenMissing(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT.south(2));
        var original = GameTestSupport.place(helper, POINT, Blocks.CHEST, ChestBlockEntity.class);
        var current = GameTestSupport.place(helper, POINT.south(2), Blocks.CHEST, ChestBlockEntity.class);
        CompoundTag saved = (CompoundTag) method(Restaurant.class, "save").invoke(restaurant);
        ListTag legacy = new ListTag();
        CompoundTag wheat = legacyLine(helper, Items.WHEAT, 6);
        wheat.putLong("destination", helper.absolutePos(POINT).asLong());
        legacy.add(wheat);
        legacy.add(legacyLine(helper, Items.CARROT, 3));
        saved.put("deliveries", legacy);
        var restored = (Restaurant) method(Restaurant.class, "load", Restaurants.class, CompoundTag.class)
                .invoke(null, Restaurants.get(helper.getLevel()), saved);
        method(Restaurant.class, "tickDeliveries", ServerLevel.class).invoke(restored, helper.getLevel());
        helper.assertValueEqual(couriers(helper).size(), 2, "Each legacy order dispatched once");
        helper.succeedWhen(() -> {
            helper.assertValueEqual(original.countItem(Items.WHEAT), 6, "Wheat at the checkout chest");
            helper.assertTrue(current.isEmpty(), "The newly configured chest stayed empty");
            assertBoxes(helper, SIGN, Items.CARROT, 1, 3);
        });
    }

    @GameTest(template = FLOOR, timeoutTicks = 240)
    public static void unloadedCourierReloadsMidRouteAndDeliversOnce(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT);
        var chest = GameTestSupport.place(helper, POINT, Blocks.CHEST, ChestBlockEntity.class);
        buy(helper, restaurant, 0, Map.of(entry(Items.WHEAT, 6), 1));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(couriers(helper).size(), 1, "Dispatched courier");
            Courier courier = couriers(helper).getFirst();
            helper.assertTrue(!courier.blockPosition().equals(helper.absolutePos(START)) && courier.carrying(), "Courier is en route");
        }).thenExecute(() -> {
            Courier courier = couriers(helper).getFirst();
            CompoundTag saved = courier.saveWithoutId(new CompoundTag());
            courier.remove(RemovalReason.UNLOADED_TO_CHUNK);
            helper.assertValueEqual(boxes(helper, START).size(), 0, "Unloading leaves the parcel with the saved courier");
            Courier restored = OneMoreCore.COURIER.get().create(helper.getLevel());
            restored.load(saved);
            helper.assertTrue(restored.carrying() && helper.getLevel().addFreshEntity(restored), "Saved courier rejoined with its parcel");
        }).thenWaitUntil(() -> {
            helper.assertTrue(couriers(helper).isEmpty(), "Reloaded courier delivered and returned");
            helper.assertValueEqual(chest.countItem(Items.WHEAT), 6, "Reloaded order arrived exactly once");
        }).thenSucceed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 120)
    public static void killedCourierDropsPackedOverflowWhenBoxesCannotFit(GameTestHelper helper) throws Exception {
        Restaurant restaurant = restaurant(helper, POINT);
        for (BlockPos pos : BlockPos.betweenClosed(START.offset(-1, -1, -1), START.offset(1, -1, 1)))
            helper.setBlock(pos, Blocks.AIR);
        buy(helper, restaurant, 0, Map.of(entry(Items.IRON_SWORD, 28), 1));
        method(Restaurant.class, "tickDeliveries", ServerLevel.class).invoke(restaurant, helper.getLevel());
        helper.assertValueEqual(couriers(helper).size(), 1, "Courier ready for removal");
        couriers(helper).getFirst().kill();
        var parcels = drops(helper).stream().map(ItemEntity::getItem)
                .filter(stack -> stack.is(OneMoreCore.CARDBOARD_BOX_ITEM.get())).toList();
        helper.assertValueEqual(parcels.size(), 2, "Overflow parcel count");
        helper.assertValueEqual(parcels.stream().mapToInt(stack -> count(stack, Items.IRON_SWORD)).sum(), 28, "Items in overflow parcels");
        helper.succeed();
    }

    private static void floor(GameTestHelper helper) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(12, 0, 10))) helper.setBlock(pos, Blocks.STONE);
    }

    private static Restaurant restaurant(GameTestHelper helper, BlockPos point) throws Exception {
        floor(helper);
        helper.setBlock(SIGN, OneMoreCore.OPEN_SIGN.get());
        Restaurant restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
        helper.assertTrue(restaurant != null, "Restaurant registered");
        method(Restaurant.class, "setArea", BoundingBox.class).invoke(restaurant,
                BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, 0, 1)), helper.absolutePos(new BlockPos(11, 4, 9))));
        method(Restaurant.class, "setSpawn", BlockPos.class).invoke(restaurant, helper.absolutePos(START));
        method(Restaurant.class, "setDelivery", BlockPos.class).invoke(restaurant, helper.absolutePos(point));
        return restaurant;
    }

    private static void buy(GameTestHelper helper, Restaurant restaurant, int delay, Map<Shop.Entry, Integer> cart) throws Exception {
        var field = Config.class.getDeclaredField("SHOP_DELIVERY");
        field.setAccessible(true);
        var config = (net.neoforged.neoforge.common.ModConfigSpec.IntValue) field.get(null);
        int previous = config.get();
        try {
            config.set(delay);
            helper.assertValueEqual(restaurant.buyAll(cart), Restaurant.Buy.OK, "Shop order accepted");
        } finally {
            config.set(previous);
        }
    }

    private static Shop.Entry entry(Item item, int count) {
        return new Shop.Entry(item.toString(), new ItemStack(item, count), new ItemStack(item), 0);
    }

    private static java.lang.reflect.Method method(Class<?> owner, String name, Class<?>... types) throws Exception {
        var method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static CompoundTag legacyLine(GameTestHelper helper, Item item, int count) {
        CompoundTag line = new CompoundTag();
        line.put("stack", new ItemStack(item).save(helper.getLevel().registryAccess()));
        line.putInt("count", count);
        line.putInt("ticks", 0);
        return line;
    }

    private static List<Courier> couriers(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(Courier.class, helper.getBounds());
    }

    private static List<ItemEntity> drops(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds());
    }

    private static CardboardBoxBlockEntity box(GameTestHelper helper, BlockPos pos) {
        return GameTestSupport.place(helper, pos, OneMoreCore.CARDBOARD_BOX.get(), CardboardBoxBlockEntity.class);
    }

    private static List<CardboardBoxBlockEntity> boxes(GameTestHelper helper, BlockPos center) {
        return BlockPos.betweenClosedStream(center.offset(-1, 0, -1), center.offset(1, 0, 1))
                .map(helper::absolutePos).map(helper.getLevel()::getBlockEntity).filter(CardboardBoxBlockEntity.class::isInstance)
                .map(CardboardBoxBlockEntity.class::cast).toList();
    }

    private static void assertBoxes(GameTestHelper helper, BlockPos at, Item item, int parcels, int amount) {
        var boxes = boxes(helper, at);
        helper.assertValueEqual(boxes.size(), parcels, "Box count");
        var player = GameTestSupport.player(helper);
        int found = 0;
        for (var box : boxes) {
            Container items = box.createMenu(1, player.getInventory(), player).getContainer();
            found += items.countItem(item);
            helper.assertTrue(items.hasAnyMatching(stack -> stack.is(item)), "Box has the ordered item");
            helper.assertFalse(items.hasAnyMatching(stack -> !stack.isEmpty() && !stack.is(item)), "Box has only the ordered item");
        }
        helper.assertValueEqual(found, amount, "Packed items");
    }

    private static int count(ItemStack parcel, Item item) {
        int count = 0;
        for (ItemStack stack : parcel.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemsCopy())
            if (stack.is(item)) count += stack.getCount();
        return count;
    }
}
