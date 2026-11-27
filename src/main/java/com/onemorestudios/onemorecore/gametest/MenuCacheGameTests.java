package com.onemorestudios.onemorecore.gametest;

import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurants;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class MenuCacheGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1);
    private static final BlockPos COMPUTER = SIGN.south();
    private static final ResourceLocation WHEAT = ResourceLocation.withDefaultNamespace("wheat");

    @GameTest(template = "empty")
    public static void shopRequestsReuseIngredientData(GameTestHelper helper) throws ReflectiveOperationException {
        var computations = Computer.class.getDeclaredField("ingredientMenuComputations");
        computations.setAccessible(true);
        var player = open(helper);
        BlockPos pos = helper.absolutePos(COMPUTER);
        var restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
        var refresh = new Computer.Request(pos, Computer.REFRESH, "");
        var unlock = new Computer.Request(pos, Computer.UNLOCK_INGREDIENT, WHEAT.toString());
        try {
            long before = computations.getLong(null);
            var first = menu(player, refresh);
            helper.assertValueEqual(menu(player, refresh), first, "Both Shop replies carry the same ingredient data");
            helper.assertValueEqual(computations.getLong(null) - before, 1L, "Two unchanged requests compute once");

            var wheat = wheat(first);
            helper.assertTrue(wheat.newlyOrderable() > 0, "Wheat would add bread");
            GameTestSupport.setRegister(restaurant, 0);
            helper.assertValueEqual(menu(player, unlock), first, "A denied unlock returns the current ingredient data");
            helper.assertValueEqual(computations.getLong(null) - before, 1L, "A denied unlock reuses the cache");

            var toggle = new Computer.Request(pos, Computer.TOGGLE_DISH, "bread");
            var toggled = menu(player, toggle);
            helper.assertValueEqual(wheat(toggled).newlyOrderable(), wheat.newlyOrderable() - 1, "Turning off bread changes the count");
            helper.assertValueEqual(computations.getLong(null) - before, 2L, "The menu edit recomputes ingredient data");

            GameTestSupport.setRegister(restaurant, wheat.unlockCost());
            var unlocked = menu(player, unlock);
            helper.assertTrue(wheat(unlocked).unlocked(), "The unlock reply includes the purchase");
            helper.assertValueEqual(menu(player, refresh), unlocked, "The next refresh reuses the unlock reply data");
            helper.assertValueEqual(computations.getLong(null) - before, 3L, "The purchase recomputes exactly once");
        } finally {
            Computer.handle(player, new Computer.Request(pos, Computer.CLOSE, ""));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void desktopStateCarriesDayWithoutArea(GameTestHelper helper) throws ReflectiveOperationException {
        var player = open(helper);
        try {
            var desktop = player.packets.stream().filter(Computer.State.class::isInstance).map(Computer.State.class::cast)
                    .filter(Computer.State::open).findFirst().orElseThrow();
            helper.assertTrue(desktop.found() && desktop.area().isEmpty(), "The desktop opens for a restaurant without an area");
            var codecField = Computer.State.class.getDeclaredField("CODEC");
            codecField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var codec = (StreamCodec<RegistryFriendlyByteBuf, Computer.State>) codecField.get(null);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                codec.encode(buffer, desktop);
                helper.assertValueEqual(codec.decode(buffer).day(), 1, "Day 1 reaches the desktop through the packet");
            } finally {
                buffer.release();
            }
        } finally {
            Computer.handle(player, new Computer.Request(helper.absolutePos(COMPUTER), Computer.CLOSE, ""));
        }
        helper.succeed();
    }

    private static GameTestSupport.TestPlayer open(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        var player = GameTestSupport.player(helper);
        helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "The computer opens");
        return player;
    }

    private static Computer.IngredientData menu(GameTestSupport.TestPlayer player, Computer.Request request) {
        return ((Computer.State) Computer.handle(player, request)).ingredientData().orElseThrow();
    }

    private static Computer.IngredientLine wheat(Computer.IngredientData data) {
        return data.ingredients().stream().filter(line -> line.id().equals(WHEAT)).findFirst().orElseThrow();
    }
}
