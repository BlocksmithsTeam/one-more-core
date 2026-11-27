package com.onemorestudios.onemorecore.gametest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.Menu;
import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.TrayBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class TrayCapacityGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    private static final List<Item> LEGACY_DISHES = List.of(Items.BREAD, Items.APPLE, Items.COOKIE,
            Items.CARROT, Items.MELON_SLICE, Items.BAKED_POTATO);
    private static final List<String> COMBO_DISHES = List.of(
            "custom:minecraft:bread", "custom:minecraft:potion", "custom:minecraft:baked_potato", "custom:minecraft:cookie");

    @GameTest(template = "empty")
    public static void trayRefusesFourthDish(GameTestHelper helper) {
        var tray = GameTestSupport.place(helper, POS, OneMoreCore.TRAY.get(), TrayBlockEntity.class);
        var player = GameTestSupport.player(helper);
        GameTestSupport.give(player, new ItemStack(Items.BREAD, 4));
        for (int i = 0; i < 4; i++) GameTestSupport.click(helper, player, POS);

        helper.assertValueEqual(tray.items.getContainerSize(), 3, "tray capacity");
        helper.assertValueEqual(tray.items.countItem(Items.BREAD), 3, "bread on the tray");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "fourth dish stays in hand");
        for (int i = 0; i < TrayBlockEntity.SIZE; i++) {
            var pose = tray.getPose(i);
            for (int j = 0; j < i; j++) {
                var other = tray.getPose(j);
                helper.assertTrue(Math.hypot(pose.x() - other.x(), pose.z() - other.z()) >= 4,
                        "Repeated clicks give each of the first three dishes its own space");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyTrayBlockDropsOverflow(GameTestHelper helper) {
        var original = GameTestSupport.place(helper, POS, OneMoreCore.TRAY.get(), TrayBlockEntity.class);
        var level = helper.getLevel();
        var tag = original.saveWithFullMetadata(level.registryAccess());
        var dishes = NonNullList.<ItemStack>create();
        LEGACY_DISHES.forEach(item -> dishes.add(new ItemStack(item)));
        ContainerHelper.saveAllItems(tag, dishes, level.registryAccess());

        BlockPos pos = original.getBlockPos();
        var loaded = (TrayBlockEntity) BlockEntity.loadStatic(pos, original.getBlockState(), tag, level.registryAccess());
        level.setBlockEntity(loaded);
        helper.runAfterDelay(2, () -> {
            assertFirstThree(helper, loaded);
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1));
            helper.assertValueEqual(drops.size(), 3, "extra dish entities");
            for (Item dish : LEGACY_DISHES.subList(3, 6)) {
                helper.assertTrue(drops.stream().anyMatch(drop -> drop.getItem().is(dish) && drop.getItem().getCount() == 1),
                        "Dropped extra dish " + dish);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void placingLegacyTrayReturnsOverflow(GameTestHelper helper) {
        helper.setBlock(POS.below(), Blocks.STONE);
        var player = GameTestSupport.player(helper);
        var stack = new ItemStack(OneMoreCore.TRAY_ITEM.get());
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(LEGACY_DISHES.stream().map(ItemStack::new).toList()));
        List<Component> tooltip = new ArrayList<>();
        stack.getItem().appendHoverText(stack, Item.TooltipContext.EMPTY, tooltip, TooltipFlag.NORMAL);
        helper.assertValueEqual(tooltip.size(), 6, "all legacy dishes appear in the tooltip before placement");
        GameTestSupport.give(player, stack);

        helper.assertTrue(GameTestSupport.click(helper, player, POS.below(), 1).consumesAction(), "The legacy tray was placed");
        assertFirstThree(helper, (TrayBlockEntity) helper.getBlockEntity(POS));
        for (Item dish : LEGACY_DISHES.subList(3, 6)) {
            helper.assertValueEqual(player.getInventory().countItem(dish), 1, "returned dish " + dish);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void familyAndComboOrdersFitOneTray(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = readMenu(helper, 3);
        var roll = orderGenerator();
        var rand = RandomSource.create(72051);
        var maximum = (ModConfigSpec.IntValue) config("MAX_ORDER_ITEMS");
        var comboChance = (ModConfigSpec.DoubleValue) config("COMBO_CHANCE");
        int previousMax = maximum.get();
        double previousCombo = comboChance.get();
        try {
            maximum.set(6);
            comboChance.set(0.5);
            int combos = 0, familySingles = 0;
            for (int i = 0; i < 256; i++) {
                Customer.Type type = Customer.Type.values()[i % Customer.Type.values().length];
                List<Customer.Line> order = generate(roll, restaurant, rand, type);
                helper.assertTrue(!order.isEmpty() && order.size() <= 3, "Order fits a tray for " + type);
                if (order.stream().mapToInt(Customer.Line::price).sum() == 25) {
                    helper.assertValueEqual(order.size(), 3, "three-place combo order size");
                    combos++;
                } else if (type == Customer.Type.FAMILY) {
                    helper.assertTrue(order.stream().anyMatch(line -> Items.COOKIE.equals(line.stack().getItem())),
                            "Family single-dish order includes dessert");
                    familySingles++;
                }
            }
            helper.assertTrue(combos > 0 && familySingles > 0, "The rolls covered both combos and family preferences");
        } finally {
            maximum.set(previousMax);
            comboChance.set(previousCombo);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyFourPlaceComboIsNeverOrdered(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = readMenu(helper, 4);
        var combo = Restaurant.class.getDeclaredMethod("combo", String.class);
        combo.setAccessible(true);
        var savedCombo = (Restaurant.Combo) combo.invoke(restaurant, "combo:test");
        helper.assertTrue(savedCombo != null && savedCombo.dishes().size() == 4, "The legacy combo remains saved for editing");
        var buildMenu = MenuBoardBlockEntity.class.getDeclaredMethod("build", Restaurant.class, HolderLookup.Provider.class);
        buildMenu.setAccessible(true);
        var registries = helper.getLevel().registryAccess();
        var display = MenuBoardBlockEntity.parse((CompoundTag) buildMenu.invoke(null, restaurant, registries), registries);
        helper.assertTrue(display.stream().noneMatch(line -> line.id().equals(savedCombo.id())), "The customer menu hides the four-place combo");

        var roll = orderGenerator();
        var rand = RandomSource.create(9137);
        var comboChance = (ModConfigSpec.DoubleValue) config("COMBO_CHANCE");
        double previous = comboChance.get();
        try {
            comboChance.set(1.0);
            for (int i = 0; i < 64; i++) {
                List<Customer.Line> order = generate(roll, restaurant, rand, Customer.Type.FAMILY);
                helper.assertTrue(!order.isEmpty() && order.size() <= 3 && order.stream().mapToInt(Customer.Line::price).sum() != 25,
                        "The legacy combo is skipped even at 100% combo chance");
            }
        } finally {
            comboChance.set(previous);
        }
        var save = Restaurant.class.getDeclaredMethod("saveCombo", String.class, List.class, int.class);
        save.setAccessible(true);
        helper.assertValueEqual(save.invoke(restaurant, savedCombo.id(), savedCombo.dishes(), savedCombo.price()),
                Restaurant.AddCombo.DISHES, "saving four places is rejected");
        helper.assertValueEqual(save.invoke(restaurant, savedCombo.id(), savedCombo.dishes().subList(0, 3), savedCombo.price()),
                Restaurant.AddCombo.OK, "the legacy combo can be edited down to three places");
        var editedDisplay = MenuBoardBlockEntity.parse((CompoundTag) buildMenu.invoke(null, restaurant, registries), registries);
        helper.assertTrue(editedDisplay.stream().anyMatch(line -> line.id().equals(savedCombo.id()) && line.items().size() == 3),
                "The edited combo appears on the customer menu");
        helper.succeed();
    }

    private static void assertFirstThree(GameTestHelper helper, TrayBlockEntity tray) {
        for (int i = 0; i < 3; i++) {
            helper.assertTrue(tray.items.getItem(i).is(LEGACY_DISHES.get(i)), "Original dish in slot " + i);
        }
    }

    private static Restaurant readMenu(GameTestHelper helper, int places) throws ReflectiveOperationException {
        CompoundTag data = new CompoundTag();
        data.putLong("sign", helper.absolutePos(POS).asLong());

        var off = new ListTag();
        var load = Restaurant.class.getDeclaredMethod("load", Restaurants.class, CompoundTag.class);
        load.setAccessible(true);
        Restaurants restaurants = Restaurants.get(helper.getLevel());
        data.put("unlockedIngredients", new ListTag());
        Restaurant base = (Restaurant) load.invoke(null, restaurants, data);
        for (Menu.Entry entry : base.dishes()) off.add(StringTag.valueOf(entry.id()));
        data.put("off", off);

        var custom = new ListTag();
        var kinds = new CompoundTag();
        var comboDishes = new ListTag();
        for (int i = 0; i < COMBO_DISHES.size(); i++) {
            String id = COMBO_DISHES.get(i);
            var dish = new CompoundTag();
            dish.putString("item", id.substring("custom:".length()));
            dish.putInt("price", 2);
            custom.add(dish);
            kinds.putString(id, List.of("main", "drink", "starter", "dessert").get(i));
            if (i < places) comboDishes.add(StringTag.valueOf(id));
        }
        data.put("custom", custom);
        data.put("kinds", kinds);
        var combo = new CompoundTag();
        combo.putString("id", "combo:test");
        combo.putInt("price", 25);
        combo.put("dishes", comboDishes);
        var combos = new ListTag();
        combos.add(combo);
        data.put("combos", combos);

        Restaurant restaurant = (Restaurant) load.invoke(null, restaurants, data);
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat", "cocoa_beans", "potato");
        return restaurant;
    }

    private static Method orderGenerator() throws ReflectiveOperationException {
        Method method = Restaurant.class.getDeclaredMethod("makeOrder", RandomSource.class, Customer.Type.class, List.class);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    private static List<Customer.Line> generate(Method roll, Restaurant restaurant, RandomSource random, Customer.Type type)
            throws ReflectiveOperationException {
        return (List<Customer.Line>) roll.invoke(restaurant, random, type, new ArrayList<String>());
    }

    private static Object config(String name) throws ReflectiveOperationException {
        var field = Config.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
