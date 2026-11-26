package com.onemorestudios.onemorecore.gametest;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.onemorestudios.onemorecore.Money;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.OneMoreCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class MoneyPiecesGameTests {
    @GameTest(template = "empty")
    public static void piecesPreserveValueAndRejectInsufficientFunds(GameTestHelper helper) {
        var pieces = new ArrayList<ItemStack>();
        Money.items(23, pieces::add);
        helper.assertValueEqual(pieces.size(), 2, "Two denominations");
        helper.assertTrue(pieces.get(0).is(OneMoreCore.BANKNOTE.get()) && pieces.get(0).getCount() == 2,
                "Two banknotes come first");
        helper.assertTrue(pieces.get(1).is(OneMoreCore.COIN.get()) && pieces.get(1).getCount() == 3,
                "Three coins are the remainder");
        AtomicInteger balance = new AtomicInteger();
        var bill = new ItemStack(OneMoreCore.BANKNOTE.get());
        var coins = new ItemStack(OneMoreCore.COIN.get(), 2);
        helper.assertValueEqual(Money.deposit(bill, balance.get(), n -> { balance.addAndGet(n); return n; }), 10, "Banknote deposits ten");
        Money.deposit(coins, balance.get(), n -> { balance.addAndGet(n); return n; });
        helper.assertValueEqual(balance.get(), 12, "Banknote plus two coins deposits twelve");
        helper.assertTrue(bill.isEmpty() && coins.isEmpty(), "Deposits consume the pieces");
        balance.set(4);
        pieces.clear();
        helper.assertTrue(!Money.take(5, balance.get(), n -> { balance.addAndGet(-n); return n; }, pieces::add),
                "Withdrawing five from four fails");
        helper.assertValueEqual(balance.get(), 4, "Failed withdrawal keeps money");
        helper.assertTrue(pieces.isEmpty(), "Failed withdrawal gives nothing");
        helper.assertTrue(Money.take(4, balance.get(), n -> { balance.addAndGet(-n); return n; }, pieces::add), "Exact balance can be taken");
        helper.assertValueEqual(balance.get(), 0, "Exact withdrawal empties the balance");
        helper.assertValueEqual(Money.value(pieces.getFirst()), 4L, "Exact withdrawal preserves value");
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void configuredPiecesUseConfiguredValues(GameTestHelper helper) throws ReflectiveOperationException {
        var itemField = Config.class.getDeclaredField("BANKNOTE");
        var valueField = Config.class.getDeclaredField("BANKNOTE_VALUE");
        itemField.setAccessible(true);
        valueField.setAccessible(true);
        var item = (net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String>) itemField.get(null);
        var value = (net.neoforged.neoforge.common.ModConfigSpec.IntValue) valueField.get(null);
        String oldItem = item.get();
        int oldValue = value.get();
        try {
            item.set("minecraft:paper");
            value.set(7);
            helper.assertValueEqual(Money.value(new ItemStack(Items.PAPER, 2)), 14L, "Configured banknotes use the configured value");
            var pieces = new ArrayList<ItemStack>();
            Money.items(23, pieces::add);
            helper.assertTrue(pieces.getFirst().is(Items.PAPER) && pieces.getFirst().getCount() == 3, "Configured bills come first");
            helper.assertValueEqual(Money.value(pieces.getLast()), 2L, "Configured payout keeps the remainder");
            AtomicInteger balance = new AtomicInteger();
            var paper = new ItemStack(Items.PAPER);
            Money.deposit(paper, balance.get(), n -> { balance.addAndGet(n); return n; });
            helper.assertValueEqual(balance.get(), 7, "Configured bill deposits seven");
            item.set(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Money.coin()).toString());
            helper.assertTrue(!Money.hasBanknote(), "One item cannot have two values");
            helper.assertValueEqual(Money.value(new ItemStack(Money.coin())), 1L, "Matching items keep the coin value");
            pieces.clear();
            Money.items(23, pieces::add);
            helper.assertTrue(pieces.size() == 1 && pieces.getFirst().getCount() == 23, "Matching denominations pay only coins");
            item.set("missing:banknote");
            helper.assertValueEqual(Config.banknote(), OneMoreCore.BANKNOTE.get(), "Unknown banknote falls back to the built-in item");
            item.set("onemorecore:ticket");
            helper.assertValueEqual(Config.banknote(), OneMoreCore.BANKNOTE.get(), "Free tickets cannot become banknotes");
            value.set(Integer.MAX_VALUE);
            helper.assertValueEqual(Money.value(new ItemStack(OneMoreCore.BANKNOTE.get(), 64)), 64L * Integer.MAX_VALUE,
                    "Large denominations do not overflow stack value");
        } finally {
            item.set(oldItem);
            value.set(oldValue);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wholeBanknotesAndSplitsRespectBoundaries(GameTestHelper helper) {
        AtomicInteger balance = new AtomicInteger(Integer.MAX_VALUE - 5);
        ItemStack bill = new ItemStack(OneMoreCore.BANKNOTE.get());
        helper.assertValueEqual(Money.deposit(bill, balance.get(), n -> { balance.addAndGet(n); return n; }), 0,
                "No partial banknote deposit at the int cap");
        helper.assertValueEqual(bill.getCount(), 1, "Rejected banknote stays in the hand");
        helper.assertValueEqual(balance.get(), Integer.MAX_VALUE - 5, "Rejected banknote leaves the balance unchanged");
        balance.set(Integer.MAX_VALUE - 15);
        bill.setCount(2);
        helper.assertValueEqual(Money.deposit(bill, balance.get(), n -> { balance.addAndGet(n); return n; }), 10,
                "Only one of two banknotes fits");
        helper.assertValueEqual(bill.getCount(), 1, "Unaccepted banknote remains");
        helper.assertValueEqual(balance.get(), Integer.MAX_VALUE - 5, "Whole banknote deposit preserves the remaining capacity");
        helper.assertValueEqual(Money.value(new ItemStack(OneMoreCore.BANKNOTE.get(), 64)), 640L, "Stack value includes denomination");
        helper.assertValueEqual(Money.value(new ItemStack(Items.STONE)), 0L, "Noncurrency has no value");
        helper.assertValueEqual(Money.value(ItemStack.EMPTY), 0L, "Empty stack has no value");
        for (int denomination : new int[] {2, 10, 17, Integer.MAX_VALUE}) {
            for (int amount : new int[] {0, 1, 9, 10, 23, 50, 99, Integer.MAX_VALUE}) {
                var split = Money.split(amount, denomination);
                helper.assertValueEqual((long) split.bills() * denomination + split.coins(), (long) amount, "Split preserves value");
                helper.assertTrue(split.coins() >= 0 && split.coins() < denomination, "Remainder is smaller than a bill");
            }
        }
        var pieces = new ArrayList<ItemStack>();
        Money.items(0, pieces::add);
        helper.assertTrue(pieces.isEmpty(), "Zero creates no items");
        Money.items(999, pieces::add);
        helper.assertValueEqual(pieces.stream().mapToLong(Money::value).sum(), 999L, "Multiple stacks preserve value");
        helper.assertTrue(pieces.stream().allMatch(stack -> stack.getCount() <= stack.getMaxStackSize()), "Payout respects stack limits");
        helper.succeed();
    }
}
