package com.onemorestudios.onemorecore.gametest;

import java.util.List;

import com.onemorestudios.onemorecore.CashRegisterBlockEntity;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Money;
import com.onemorestudios.onemorecore.RegisterTake;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class RegisterGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1), REGISTER = new BlockPos(2, 1, 1);
    private static final BlockPos OTHER = new BlockPos(4, 1, 1);

    @GameTest(template = "empty")
    public static void outsideBalanceSavesAndMergesOnce(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = sign(helper);
        CashRegisterBlockEntity cash = place(helper, REGISTER);
        var player = GameTestSupport.player(helper);
        deposit(helper, player, REGISTER, 23);
        helper.assertValueEqual(cash.balance(), 23, "Outside register stores the deposit");
        helper.assertValueEqual(restaurant.register(), 0, "Unconfigured restaurant receives no deposit");
        var registries = helper.getLevel().registryAccess();
        var loaded = (CashRegisterBlockEntity) BlockEntity.loadStatic(cash.getBlockPos(), cash.getBlockState(),
                cash.saveWithFullMetadata(registries), registries);
        helper.assertTrue(loaded != null && loaded.balance() == 23, "Balance survives saving");
        helper.getLevel().setBlockEntity(loaded);
        setArea(helper, restaurant, REGISTER);
        helper.assertValueEqual(restaurant.register(), 23, "Area assignment credits the saved balance");
        helper.assertValueEqual(loaded.balance(), 0, "Area assignment clears the block balance");
        setArea(helper, restaurant, REGISTER);
        helper.assertValueEqual(restaurant.register(), 23, "Repeated assignment cannot credit twice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insideMoneyAndSignShareTheRestaurant(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = sign(helper);
        CashRegisterBlockEntity cash = place(helper, REGISTER);
        setArea(helper, restaurant, REGISTER);
        var player = GameTestSupport.player(helper);
        int stackSize = new ItemStack(Config.currency()).getMaxStackSize();
        deposit(helper, player, REGISTER, stackSize);
        deposit(helper, player, SIGN, 7);
        helper.assertValueEqual(restaurant.register(), stackSize + 7, "Both blocks deposit to the restaurant");
        helper.assertValueEqual(cash.balance(), 0, "Inside register keeps no local money");
        withdraw(helper, player, REGISTER);
        helper.assertValueEqual(restaurant.register(), 7, "Register withdraws one currency stack");
        helper.assertValueEqual(inventoryMoney(player), stackSize, "Player receives the withdrawn value as banknotes and coins");
        helper.assertValueEqual(player.getInventory().countItem(Config.banknote()), stackSize / 10, "Withdrawal gives banknotes first");
        helper.assertValueEqual(player.getInventory().countItem(Config.currency()), stackSize % 10, "Withdrawal gives coins for the remainder");
        var signPlayer = GameTestSupport.player(helper);
        withdraw(helper, signPlayer, SIGN);
        helper.assertValueEqual(restaurant.register(), 0, "Sign still withdraws the remainder");
        helper.assertValueEqual(signPlayer.getInventory().countItem(Config.currency()), 7, "Sign returns the remainder");
        var emptyPlayer = GameTestSupport.player(helper);
        withdraw(helper, emptyPlayer, REGISTER);
        helper.assertValueEqual(emptyPlayer.getInventory().countItem(Config.currency()), 0, "Empty withdrawal gives nothing");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void outsideWithdrawalAndDepositCap(GameTestHelper helper) {
        CashRegisterBlockEntity cash = place(helper, REGISTER);
        var player = GameTestSupport.player(helper);
        int stackSize = new ItemStack(Config.currency()).getMaxStackSize();
        deposit(helper, player, REGISTER, stackSize);
        deposit(helper, player, REGISTER, 7);
        withdraw(helper, player, REGISTER);
        helper.assertValueEqual(cash.balance(), 7, "Outside withdrawal takes only one stack");
        helper.assertValueEqual(inventoryMoney(player), stackSize, "Withdrawn value reaches the player");
        var registries = helper.getLevel().registryAccess();
        var tag = cash.saveWithFullMetadata(registries);
        tag.putInt("balance", Integer.MAX_VALUE - 2);
        var loaded = (CashRegisterBlockEntity) BlockEntity.loadStatic(cash.getBlockPos(), cash.getBlockState(), tag, registries);
        helper.assertTrue(loaded != null, "Saved register loads");
        helper.getLevel().setBlockEntity(loaded);
        GameTestSupport.give(player, new ItemStack(Config.currency(), 5));
        player.setShiftKeyDown(false);
        GameTestSupport.click(helper, player, REGISTER);
        helper.assertValueEqual(loaded.balance(), Integer.MAX_VALUE, "Deposit stops at the int cap");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "Overflow stays in the hand");
        GameTestSupport.click(helper, player, REGISTER);
        helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "Full register rejects a repeated deposit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void levelGrowthMergesAllBalancesAndClamps(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = sign(helper);
        setArea(helper, restaurant, SIGN);
        CashRegisterBlockEntity first = place(helper, REGISTER), second = place(helper, OTHER);
        var player = GameTestSupport.player(helper);
        deposit(helper, player, REGISTER, 11);
        deposit(helper, player, OTHER, 13);
        GameTestSupport.setRegister(restaurant, Integer.MAX_VALUE - 5);
        var field = Restaurant.class.getDeclaredField("steps");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var steps = (List<Restaurant.Step>) field.get(restaurant);
        steps.add(new Restaurant.Step(0, box(helper, OTHER), null, null));
        var buy = Restaurant.class.getDeclaredMethod("buyLevel", ServerLevel.class);
        buy.setAccessible(true);
        helper.assertValueEqual(buy.invoke(restaurant, helper.getLevel()), "onemorecore.level.bought", "Area level purchase succeeds");
        helper.assertValueEqual(restaurant.register(), Integer.MAX_VALUE, "Merged sum clamps without wrapping");
        helper.assertValueEqual(first.balance(), 6, "First register keeps the overflow");
        helper.assertValueEqual(second.balance(), 13, "Second register keeps its balance at the cap");
        setArea(helper, restaurant, OTHER);
        helper.assertValueEqual(restaurant.register(), Integer.MAX_VALUE, "Repeated merge stays capped");
        helper.assertValueEqual(first.balance(), 6, "Repeated merge keeps the first remainder");
        helper.assertValueEqual(second.balance(), 13, "Repeated merge keeps the second balance");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void retainedRemainderSurvivesBreak(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = sign(helper);
        int dropped = 0;
        for (int retained : new int[] {6, 13, 1}) {
            setArea(helper, restaurant, SIGN);
            CashRegisterBlockEntity cash = place(helper, REGISTER);
            var deposit = CashRegisterBlockEntity.class.getDeclaredMethod("deposit", int.class);
            deposit.setAccessible(true);
            deposit.invoke(cash, retained + 5);
            GameTestSupport.setRegister(restaurant, Integer.MAX_VALUE - 5);
            setArea(helper, restaurant, REGISTER);
            helper.assertValueEqual(cash.balance(), retained, "Area merge retains only the overflow");
            helper.getLevel().destroyBlock(helper.absolutePos(REGISTER), true);
            dropped += retained;
            helper.assertValueEqual(droppedMoney(helper), dropped, "Break drops every retained unit exactly once");
            helper.assertValueEqual(cash.balance(), 0, "Removed block keeps no balance to drop twice");
            helper.assertValueEqual(restaurant.register(), Integer.MAX_VALUE, "Break never withdraws shared money");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void openingRequiresRegisterOnlyWhenEnabled(GameTestHelper helper) throws ReflectiveOperationException {
        boolean previous = Config.REQUIRE_REGISTER.get();
        try {
            Restaurant restaurant = openingRestaurant(helper);
            Config.REQUIRE_REGISTER.set(true);
            var player = GameTestSupport.player(helper);
            GameTestSupport.click(helper, player, SIGN);
            helper.assertFalse(restaurant.isOpen(), "Required register blocks opening");
            helper.assertTrue(missingMessage(player), "Missing register uses the requested message key");
            Config.REQUIRE_REGISTER.set(false);
            GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
            helper.assertTrue(restaurant.isOpen(), "Disabled rule preserves opening without a register");
            var open = Restaurant.class.getDeclaredField("open");
            open.setAccessible(true);
            open.setBoolean(restaurant, false);
            setArea(helper, restaurant, new BlockPos(3, 2, 3));
            Config.REQUIRE_REGISTER.set(true);
            place(helper, OTHER);
            GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
            helper.assertFalse(restaurant.isOpen(), "Register outside the area does not satisfy the rule");
            place(helper, REGISTER);
            GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
            helper.assertTrue(restaurant.isOpen(), "Register inside the area allows opening");
        } finally {
            Config.REQUIRE_REGISTER.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void readyCountdownRechecksRegister(GameTestHelper helper) throws ReflectiveOperationException {
        boolean previous = Config.REQUIRE_REGISTER.get();
        var first = GameTestSupport.player(helper);
        var second = GameTestSupport.player(helper);
        var players = helper.getLevel().players();
        try {
            Restaurant restaurant = openingRestaurant(helper);
            place(helper, REGISTER);
            Config.REQUIRE_REGISTER.set(true);
            players.add(first);
            players.add(second);
            var ready = Restaurant.class.getDeclaredField("ready");
            ready.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ids = (java.util.Set<java.util.UUID>) ready.get(restaurant);
            ids.add(first.getUUID());
            ids.add(second.getUUID());
            var countdown = Restaurant.class.getDeclaredField("countdown");
            countdown.setAccessible(true);
            countdown.setInt(restaurant, 1);
            helper.setBlock(REGISTER, Blocks.AIR);
            var tick = Restaurant.class.getDeclaredMethod("tickReadyCheck", ServerLevel.class);
            tick.setAccessible(true);
            tick.invoke(restaurant, helper.getLevel());
            helper.assertFalse(restaurant.isOpen(), "Removing the register prevents countdown completion");
            helper.assertTrue(missingMessage(first) && missingMessage(second), "Ready players receive the missing message");
        } finally {
            players.remove(first);
            players.remove(second);
            Config.REQUIRE_REGISTER.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void breakingDropsLocalMoneyWithoutTouchingRestaurant(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = sign(helper);
        place(helper, REGISTER);
        var player = GameTestSupport.player(helper);
        int amount = new ItemStack(Config.currency()).getMaxStackSize() + 7;
        deposit(helper, player, REGISTER, amount - 7);
        deposit(helper, player, REGISTER, 7);
        helper.getLevel().destroyBlock(helper.absolutePos(REGISTER), true);
        helper.assertValueEqual(droppedMoney(helper), amount, "Outside break drops the whole balance");
        helper.assertValueEqual(helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds(),
                item -> item.getItem().is(OneMoreCore.CASH_REGISTER_ITEM.get())).size(), 1, "Register drops itself");
        place(helper, REGISTER);
        setArea(helper, restaurant, REGISTER);
        deposit(helper, player, REGISTER, 19);
        helper.getLevel().destroyBlock(helper.absolutePos(REGISTER), true);
        helper.assertValueEqual(droppedMoney(helper), amount, "Inside break adds no currency drops");
        helper.assertValueEqual(restaurant.register(), 19, "Restaurant keeps its money after breaking");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void drawerTakesExactPiecesAndChecksFunds(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = sign(helper);
        place(helper, REGISTER);
        setArea(helper, restaurant, REGISTER);
        GameTestSupport.setRegister(restaurant, 23);
        var player = GameTestSupport.player(helper);
        var requests = new RegisterTake();
        BlockPos pos = helper.absolutePos(REGISTER);
        helper.assertTrue(requests.take(helper.getLevel(), player, pos, "take_banknote"), "Banknote request succeeds");
        helper.assertValueEqual(restaurant.register(), 13, "One banknote costs ten");
        helper.assertValueEqual(player.getInventory().countItem(OneMoreCore.BANKNOTE.get()), 1, "Player receives one banknote");
        helper.assertTrue(!requests.take(helper.getLevel(), player, pos, "take_coin"), "Second request in one tick is refused");
        helper.assertValueEqual(restaurant.register(), 13, "Rate limit preserves the balance");
        var poor = GameTestSupport.player(helper);
        GameTestSupport.setRegister(restaurant, 9);
        helper.assertTrue(!requests.take(helper.getLevel(), poor, pos, "take_banknote"), "Nine cannot buy a banknote");
        helper.assertValueEqual(restaurant.register(), 9, "Failed request preserves money");
        helper.assertValueEqual(inventoryMoney(poor), 0, "Failed request gives nothing");
        var coinPlayer = GameTestSupport.player(helper);
        helper.assertTrue(requests.take(helper.getLevel(), coinPlayer, pos, "take_coin"), "Coin request succeeds");
        helper.assertValueEqual(restaurant.register(), 8, "One coin costs one");
        helper.assertValueEqual(coinPlayer.getInventory().countItem(OneMoreCore.COIN.get()), 1, "Coin request gives a coin");
        var far = GameTestSupport.player(helper);
        far.setPos(pos.getX() + 20, pos.getY(), pos.getZ());
        helper.assertTrue(!requests.take(helper.getLevel(), far, pos, "take_coin"), "Distant request is refused");
        helper.assertValueEqual(inventoryMoney(far), 0, "Distant request gives nothing");
        helper.assertValueEqual(restaurant.register(), 8, "Distant request preserves money");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void banknotesDepositAtSignAndOutsideDrawer(GameTestHelper helper) {
        Restaurant restaurant = sign(helper);
        CashRegisterBlockEntity cash = place(helper, REGISTER);
        var player = GameTestSupport.player(helper);
        GameTestSupport.give(player, new ItemStack(OneMoreCore.BANKNOTE.get()));
        GameTestSupport.click(helper, player, SIGN);
        helper.assertValueEqual(restaurant.register(), 10, "Sign values a banknote at ten");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Sign consumes the banknote");
        GameTestSupport.give(player, new ItemStack(OneMoreCore.BANKNOTE.get()));
        GameTestSupport.click(helper, player, REGISTER);
        helper.assertValueEqual(cash.balance(), 10, "Outside register values a banknote at ten");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Register consumes the banknote");
        var requests = new RegisterTake();
        helper.assertTrue(requests.take(helper.getLevel(), player, helper.absolutePos(REGISTER), "take_banknote"),
                "Outside request uses the local balance");
        helper.assertValueEqual(cash.balance(), 0, "Outside drawer deducts ten locally");
        helper.assertValueEqual(restaurant.register(), 10, "Outside drawer leaves the restaurant balance intact");
        helper.assertValueEqual(player.getInventory().countItem(OneMoreCore.BANKNOTE.get()), 1, "Outside drawer gives a banknote");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void drawerDropsPieceWhenInventoryIsFull(GameTestHelper helper) {
        CashRegisterBlockEntity cash = place(helper, REGISTER);
        var player = GameTestSupport.player(helper);
        GameTestSupport.give(player, new ItemStack(OneMoreCore.BANKNOTE.get()));
        GameTestSupport.click(helper, player, REGISTER);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            player.getInventory().setItem(i, new ItemStack(net.minecraft.world.item.Items.STONE, 64));
        helper.assertTrue(new RegisterTake().take(helper.getLevel(), player, helper.absolutePos(REGISTER), "take_banknote"),
                "Full inventory does not block a valid withdrawal");
        helper.assertValueEqual(cash.balance(), 0, "Dropped banknote costs ten");
        helper.assertValueEqual(droppedMoney(helper), 10, "Full inventory drops the banknote");
        helper.succeed();
    }

    private static int inventoryMoney(GameTestSupport.TestPlayer player) {
        int value = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) value += (int) Money.value(player.getInventory().getItem(i));
        return value;
    }

    private static Restaurant sign(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        Restaurant restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
        GameTestSupport.setRegister(restaurant, 0);
        return restaurant;
    }

    private static CashRegisterBlockEntity place(GameTestHelper helper, BlockPos pos) {
        return GameTestSupport.place(helper, pos, OneMoreCore.CASH_REGISTER.get(), CashRegisterBlockEntity.class);
    }

    private static void deposit(GameTestHelper helper, GameTestSupport.TestPlayer player, BlockPos pos, int amount) {
        player.setShiftKeyDown(false);
        GameTestSupport.give(player, new ItemStack(Config.currency(), amount));
        helper.assertTrue(GameTestSupport.click(helper, player, pos).consumesAction(), "Deposit consumes the click");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Deposit consumes the accepted currency");
    }

    private static void withdraw(GameTestHelper helper, GameTestSupport.TestPlayer player, BlockPos pos) {
        GameTestSupport.give(player, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        helper.assertTrue(GameTestSupport.click(helper, player, pos).consumesAction(), "Withdrawal consumes the click");
    }

    private static BoundingBox box(GameTestHelper helper, BlockPos end) {
        return BoundingBox.fromCorners(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(end));
    }

    private static void setArea(GameTestHelper helper, Restaurant restaurant, BlockPos end) throws ReflectiveOperationException {
        var set = Restaurant.class.getDeclaredMethod("setArea", BoundingBox.class);
        set.setAccessible(true);
        set.invoke(restaurant, box(helper, end));
    }

    private static Restaurant openingRestaurant(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant restaurant = sign(helper);
        GameTestSupport.setRegister(restaurant, 100);
        GameTestSupport.unlock(helper, restaurant, "wheat");
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.OAK_SLAB);
        helper.setBlock(new BlockPos(0, 1, 3), Blocks.OAK_STAIRS);
        setArea(helper, restaurant, new BlockPos(3, 2, 3));
        var spawn = Restaurant.class.getDeclaredMethod("setSpawn", BlockPos.class);
        spawn.setAccessible(true);
        spawn.invoke(restaurant, helper.absolutePos(SIGN.north()));
        return restaurant;
    }

    private static boolean missingMessage(GameTestSupport.TestPlayer player) {
        return player.messages.stream().anyMatch(message -> message.getContents() instanceof TranslatableContents text
                && text.getKey().equals("onemorecore.register.missing"));
    }

    private static int droppedMoney(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds(),
                entity -> Money.value(entity.getItem()) > 0).stream().mapToInt(entity -> (int) Money.value(entity.getItem())).sum();
    }
}
