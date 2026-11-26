package com.onemorestudios.onemorecore;

import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Balances are currency units; only this class converts them to physical pieces. */
public final class Money {
    private Money() {}

    public static Item coin() { return Config.currency(); }

    // Equal configured items cannot represent two denominations: preserve the currency's value of one.
    public static boolean hasBanknote() { return Config.banknote() != coin(); }
    public static int banknoteValue() { return Config.BANKNOTE_VALUE.get(); }

    public static int pieceValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.is(coin())) return 1;
        return stack.is(Config.banknote()) ? banknoteValue() : 0;
    }

    public static long value(ItemStack stack) { return (long) pieceValue(stack) * stack.getCount(); }

    public record Split(int bills, int coins) {}

    public static Split split(int amount, int denomination) {
        if (amount < 0 || denomination < 1) throw new IllegalArgumentException("Invalid money split");
        return new Split(amount / denomination, amount % denomination);
    }

    public static Split split(int amount) {
        return hasBanknote() ? split(amount, banknoteValue()) : new Split(0, amount);
    }

    /** Emits full stacks, largest denomination first, without allocating a list for large balances. */
    public static void items(long amount, Consumer<ItemStack> give) {
        if (amount < 0) throw new IllegalArgumentException("Negative money");
        if (hasBanknote()) {
            emit(Config.banknote(), amount / banknoteValue(), give);
            amount %= banknoteValue();
        }
        emit(coin(), amount, give);
    }

    private static void emit(Item item, long count, Consumer<ItemStack> give) {
        int max = item.getDefaultMaxStackSize();
        while (count > 0) {
            int n = (int) Math.min(count, max);
            give.accept(new ItemStack(item, n));
            count -= n;
        }
    }

    /** Deposits only whole pieces that fit; a full balance never consumes part of a banknote. */
    public static int deposit(ItemStack stack, int balance, IntUnaryOperator deposit) {
        int value = pieceValue(stack);
        if (value == 0) return 0;
        int count = Math.min(stack.getCount(), (Integer.MAX_VALUE - balance) / value);
        int credited = deposit.applyAsInt(count * value);
        stack.shrink(credited / value);
        return credited;
    }

    /** Exact withdrawal: insufficient funds leave both the balance and the inventory unchanged. */
    public static boolean take(int amount, int balance, IntUnaryOperator take, Consumer<ItemStack> give) {
        if (amount <= 0 || amount > balance) return false;
        items(take.applyAsInt(amount), give);
        return true;
    }
}
