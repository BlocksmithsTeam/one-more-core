package com.onemorestudios.onemorecore.gametest;

import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.TrayBlockEntity;
import com.pockyl.diegesis.api.Diegesis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class ModelGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    @GameTest(template = "empty")
    public static void ironTrayTakesADish(GameTestHelper helper) {
        var iron = OneMoreCore.IRON_TRAY.get();
        var tray = GameTestSupport.place(helper, POS, iron, TrayBlockEntity.class);
        helper.assertValueEqual(tray.getType(), OneMoreCore.TRAY_ENTITY.get(), "iron tray shares the tray block entity type");
        helper.assertTrue(tray.getType().isValid(iron.defaultBlockState()), "the block entity type supports iron trays");

        var player = GameTestSupport.player(helper);
        GameTestSupport.give(player, new ItemStack(Items.BREAD, 2));
        helper.assertTrue(GameTestSupport.click(helper, player, POS, 2 / 16.0).consumesAction(), "the tray handled the dish");
        helper.assertTrue(tray.items.getItem(0).is(Items.BREAD), "the dish was put on the iron tray");
        helper.assertValueEqual(tray.items.getItem(0).getCount(), 1, "dish count on the tray");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "dish count left in the hand");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ironTrayDropsWithContents(GameTestHelper helper) {
        var tray = GameTestSupport.place(helper, POS, OneMoreCore.IRON_TRAY.get(), TrayBlockEntity.class);
        var dish = new ItemStack(Items.APPLE);
        tray.items.setItem(2, dish);
        tray.label = "7";

        var drops = Block.getDrops(tray.getBlockState(), helper.getLevel(), tray.getBlockPos(), tray);
        helper.assertValueEqual(drops.size(), 1, "iron tray drop count");
        var drop = drops.getFirst();
        helper.assertTrue(drop.is(OneMoreCore.IRON_TRAY_ITEM.get()), "the iron tray drops itself");
        var contents = drop.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        var droppedDish = contents.stream().skip(2).findFirst().orElse(ItemStack.EMPTY);
        helper.assertTrue(ItemStack.matches(droppedDish, dish), "the dropped tray retained the dish in its original slot");
        helper.assertValueEqual(drop.getOrDefault(OneMoreCore.LABEL.get(), ""), "7", "label on the dropped tray");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ironTrayIsNotCurrency(GameTestHelper helper) throws ReflectiveOperationException {
        GameTestSupport.place(helper, POS, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        var restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(POS));
        var player = GameTestSupport.player(helper);
        GameTestSupport.give(player, new ItemStack(OneMoreCore.IRON_TRAY_ITEM.get()));

        var field = Config.class.getDeclaredField("CURRENCY");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var currency = (ModConfigSpec.ConfigValue<String>) field.get(null);
        String previous = currency.get();
        try {
            currency.set("onemorecore:iron_tray");
            helper.assertValueEqual(Config.currency(), OneMoreCore.COIN.get(), "fallback currency for the free iron tray");
            GameTestSupport.click(helper, player, POS);
            helper.assertValueEqual(restaurant.register(), 0, "the iron tray did not credit the register");
            helper.assertTrue(player.getMainHandItem().is(OneMoreCore.IRON_TRAY_ITEM.get()), "the player kept the tray");
        } finally {
            currency.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rackGivesTraysByShelf(GameTestHelper helper) {
        helper.setBlock(POS, OneMoreCore.TRAY_RACK.get());
        var player = GameTestSupport.player(helper);
        for (int y : new int[] {3, 8, 13}) {
            var expected = y == 8 ? OneMoreCore.IRON_TRAY_ITEM.get() : OneMoreCore.TRAY_ITEM.get();
            helper.assertTrue(GameTestSupport.click(helper, player, POS, y / 16.0).consumesAction(), "rack click at shelf height " + y);
            helper.assertTrue(player.getMainHandItem().is(expected), "tray type at shelf height " + y);
            helper.assertTrue(GameTestSupport.click(helper, player, POS, y / 16.0).consumesAction(), "rack accepts the empty tray");
            helper.assertTrue(player.getMainHandItem().isEmpty(), "empty tray was returned to the rack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bothTraysUseTheDiegesisStation(GameTestHelper helper) {
        if (!ModList.get().isLoaded("diegesis")) { helper.succeed(); return; }
        for (Block tray : new Block[] {OneMoreCore.TRAY.get(), OneMoreCore.IRON_TRAY.get()}) {
            helper.assertTrue(Diegesis.stationFor(tray.defaultBlockState()).orElse(null) == KitchenStations.TRAY,
                    "Both tray blocks resolve to the shared Diegesis station");
        }
        helper.succeed();
    }
}
