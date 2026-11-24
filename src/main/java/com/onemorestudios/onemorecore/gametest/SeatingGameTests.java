package com.onemorestudios.onemorecore.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.ReadyRequest;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.ScreenData;
import com.onemorestudios.onemorecore.Shop;
import com.onemorestudios.onemorecore.Status;
import com.onemorestudios.onemorecore.WipingStation;
import com.onemorestudios.onemorecore.gametest.GameTestSupport.TestPlayer;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class SeatingGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1), COMPUTER = new BlockPos(1, 1, 2);
    private static final BlockPos TABLE = new BlockPos(0, 1, 2), BRIDGE = COMPUTER, OTHER = new BlockPos(2, 1, 2);
    private static final BlockPos SEAT = TABLE.south(), OTHER_SEAT = OTHER.south();

    /** CORE-3 / CLIENT-9: The exterior computer resolves during setup and cannot charge the configured restaurant. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void exteriorComputerStopsResolvingAfterSetup(GameTestHelper helper) {
        Restaurant r = sign(helper);
        var restaurants = Restaurants.get(helper.getLevel());
        BlockPos computer = helper.absolutePos(COMPUTER);
        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        helper.assertTrue(restaurants.forComputer(computer) == r, "the exterior computer can set up a restaurant");
        GameTestSupport.setRegister(r, 1000);
        BlockPos chest = SIGN.east();
        helper.setBlock(chest, Blocks.CHEST);
        invoke(r, "setDelivery", new Class<?>[] {BlockPos.class}, helper.absolutePos(chest));
        setArea(r, BoundingBox.fromCorners(helper.absolutePos(SIGN), helper.absolutePos(chest)));

        TestPlayer player = GameTestSupport.player(helper);
        List<Shop.Entry> previous = Shop.entries;
        try {
            var bread = Items.BREAD.getDefaultInstance();
            var listing = new Shop.Entry("exterior_fixture", bread, bread, 1000);
            Shop.entries = List.of(listing);
            GameTestSupport.click(helper, player, COMPUTER);
            var result = (Computer.CheckoutResult) Computer.handle(player,
                    new Computer.Request(computer, Computer.BUY_CART, listing.id() + ":1", listing.price(), 1));
            helper.assertFalse(result.success(), "the exterior purchase is rejected");
            helper.assertValueEqual(r.register(), 1000, "register balance");
            helper.assertTrue(list(r, "deliveries").isEmpty(), "no delivery was queued");
            helper.assertTrue(restaurants.forComputer(computer) != r, "the configured restaurant excludes the exterior computer");
        } finally {
            Computer.handle(player, new Computer.Request(computer, Computer.CLOSE, ""));
            Shop.entries = previous;
        }
        helper.succeed();
    }

    /** CORE-4: Duplicate ready and countdown requests skip layout reads, and same-tick changes are limited. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void duplicateReadyDoesNotScan(GameTestHelper helper) {
        Restaurant r = setup(helper);
        TestPlayer first = GameTestSupport.player(helper), second = GameTestSupport.player(helper);
        var area = new CountingArea(r.area(), first.blockPosition());
        setArea(r, area);
        var players = helper.getLevel().players();
        players.add(first);
        players.add(second);
        try {
            withConfig("READY_CHECK", true, () -> withConfig("READY_COUNTDOWN", 60, () -> {
                requestReady(first, true);
                helper.assertTrue(area.layoutReads > 0, "first request inspects the layout");
                // Even an invalidated cache must stay untouched by a duplicate request.
                helper.setBlock(BRIDGE, Blocks.OAK_SLAB);
                area.layoutReads = 0;
                requestReady(first, true);
                helper.assertValueEqual(area.layoutReads, 0, "duplicate request does not rebuild the layout");
                requestReady(first, false);
                helper.assertTrue(listReady(r, first), "a second change in the same tick is ignored");
                requestReady(second, true);
                helper.assertValueEqual(field(r, "countdown"), 60, "all required players start a countdown");

                helper.setBlock(BRIDGE, Blocks.AIR);
                area.layoutReads = 0;
                requestReady(second, true);
                requestReady(first, false);
                helper.assertValueEqual(area.layoutReads, 0, "countdown requests do not rebuild the layout");
                helper.assertValueEqual(field(r, "countdown"), 60, "ready requests do not interrupt a running countdown");
            }));
        } finally {
            players.remove(first);
            players.remove(second);
        }
        helper.succeed();
    }

    /** ECONOMY-8 / CLIENT-14: Manager reuses the layout between refreshes and rebuilds it after block and area edits. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void managerReusesAndInvalidatesLayout(GameTestHelper helper) {
        Restaurant r = setup(helper);
        helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
        BlockPos computer = helper.absolutePos(COMPUTER);
        var area = new CountingArea(r.area(), computer);
        setArea(r, area);
        TestPlayer player = GameTestSupport.player(helper);
        try {
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "computer opens");
            helper.assertTrue(manager(player, computer).surfaces().contains(helper.absolutePos(TABLE)), "initial table is present");
            helper.assertTrue(area.layoutReads > 0, "initial Manager refresh scans");
            area.layoutReads = 0;
            helper.assertTrue(manager(player, computer).surfaces().contains(helper.absolutePos(TABLE)), "unchanged table is present");
            helper.assertValueEqual(area.layoutReads, 0, "unchanged Manager refresh reuses the scan and groups");
                helper.setBlock(SEAT, Blocks.AIR);
            helper.assertTrue(manager(player, computer).surfaces().isEmpty(), "breaking the seat refreshes Manager geometry");
            helper.setBlock(SEAT, Blocks.OAK_STAIRS);
            helper.assertTrue(manager(player, computer).surfaces().contains(helper.absolutePos(TABLE)), "placing a seat refreshes Manager geometry");
            setArea(r, BoundingBox.fromCorners(helper.absolutePos(SIGN), computer));
            helper.assertTrue(manager(player, computer).surfaces().isEmpty(), "cropping the area drops the excluded seats");
        } finally {
            Computer.handle(player, new Computer.Request(computer, Computer.CLOSE, ""));
        }
        helper.succeed();
    }

    /** CORE-5: A fixed-count shift rejects an entirely disabled layout and finishes if its last seat disappears. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void fixedShiftNeedsUsableTables(GameTestHelper helper) {
        Restaurant r = setup(helper);
        withConfig("DAY_LENGTH", 0, () -> {
            toggle(helper, r, TABLE);
            GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
            helper.assertFalse(r.isOpen(), "a fixed shift cannot open with all tables disabled");
            toggle(helper, r, TABLE);
            GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
            helper.assertTrue(r.isOpen(), "enabling the table allows the shift to start");
            helper.setBlock(SEAT, Blocks.AIR);
            tick(helper, r);
            helper.assertFalse(r.isOpen(), "the shift finishes when its last seat disappears");
            helper.assertValueEqual(r.day(), 2, "the shift counts as completed");
        });
        helper.succeed();
    }

    /** CORE-6: A canceled entity join removes the reserved customer from the party and restaurant without retrying. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void rejectedArrivalReleasesReservation(GameTestHelper helper) {
        Restaurant r = setup(helper);
        helper.setBlock(BRIDGE, Blocks.OAK_SLAB);
        secondTable(helper);
        GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
        helper.assertValueEqual(spawnParty(helper, r, 2), 2, "initial party size");
        Customer rejected = (Customer) list(r, "arriving").getFirst();
        Object party = invoke(rejected, "party", new Class<?>[0]);
        try {
            rejectArrival(helper, r, rejected);
            helper.assertFalse(list(r, "customers").contains(rejected), "the rejected customer no longer reserves its seat");
            helper.assertFalse(list(party, "members").contains(rejected), "the rejected customer was removed from the party");
            helper.assertValueEqual(list(party, "members").size(), 1, "the party can continue with its remaining member");
            helper.assertValueEqual(list(r, "arriving").size(), 1, "only the remaining member will arrive");
        } finally {
            discardCustomers(r);
        }
        helper.succeed();
    }

    /** CORE-6: A rejected arrival cancels the party when its remaining reservations are on separate tables. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void rejectedArrivalCancelsSplitParty(GameTestHelper helper) {
        Restaurant r = setup(helper);
        helper.setBlock(BRIDGE, Blocks.OAK_SLAB);
        helper.setBlock(BRIDGE.south(), Blocks.OAK_STAIRS);
        secondTable(helper);
        GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
        helper.assertValueEqual(spawnParty(helper, r, 3), 3, "party reserves the entire connected table");
        Customer rejected = list(r, "arriving").stream().map(Customer.class::cast)
                .filter(c -> helper.absolutePos(BRIDGE).equals(c.getSurface())).findFirst().orElseThrow();
        Object party = invoke(rejected, "party", new Class<?>[0]);
        try {
            helper.setBlock(BRIDGE, Blocks.AIR);
            rejectArrival(helper, r, rejected);
            helper.assertTrue(list(r, "customers").isEmpty(), "both remaining seats are released");
            helper.assertTrue(list(r, "arriving").isEmpty(), "the party has no further arrivals");
            helper.assertTrue(list(party, "members").isEmpty(), "the party is canceled");
        } finally {
            discardCustomers(r);
        }
        helper.succeed();
    }

    /** CORE-9 / ECONOMY-13: Disabling an unseated connecting surface blocks every seat of its table. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void disabledBridgeBlocksEverySeat(GameTestHelper helper) {
        Restaurant r = setup(helper);
        helper.setBlock(BRIDGE, Blocks.OAK_SLAB);
        secondTable(helper);
        GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
        toggle(helper, r, BRIDGE);
        helper.assertValueEqual(spawnParty(helper, r, 2), 0, "the off marker on an unseated surface applies to both seats");
        helper.succeed();
    }

    /** CORE-10: Bridging an occupied table to a free one blocks a second party from the connected table. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bridgePreventsASecondParty(GameTestHelper helper) {
        Restaurant r = setup(helper);
        secondTable(helper);
        GameTestSupport.click(helper, GameTestSupport.player(helper), SIGN);
        try {
            helper.assertValueEqual(spawnParty(helper, r, 1), 1, "one table is occupied");
            helper.setBlock(BRIDGE, Blocks.OAK_SLAB);
            helper.assertValueEqual(spawnParty(helper, r, 1), 0, "the joined table is reserved");
            helper.setBlock(BRIDGE, Blocks.AIR);
            helper.assertValueEqual(spawnParty(helper, r, 1), 1, "the disconnected free table can seat a party again");
        } finally {
            discardCustomers(r);
        }
        helper.succeed();
    }

    /** CORE-11 / EXTERNAL-9: Diegesis permits a coin-only station from either block of a clean, connected table. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void coinsOpenStationFromEitherSurface(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("diegesis")) { helper.succeed(); return; }
        Restaurant r = setup(helper);
        helper.setBlock(BRIDGE, Blocks.OAK_SLAB);
        invoke(r, "addCoins", new Class<?>[] {BlockPos.class, int.class}, helper.absolutePos(TABLE), 5);
        TestPlayer player = GameTestSupport.player(helper);
        helper.assertTrue(WipingStation.opens(player, helper.absolutePos(TABLE), helper.getBlockState(TABLE)), "bill surface opens");
        helper.assertTrue(WipingStation.opens(player, helper.absolutePos(BRIDGE), helper.getBlockState(BRIDGE)), "connected clean surface opens");
        helper.succeed();
    }

    /** CORE-12 / CLIENT-8: A sign click from outside reports an active check to an unready player inside. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void outsideSignClickSendsActiveRoster(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurant r = setup(helper);
        TestPlayer outside = GameTestSupport.player(helper), inside = GameTestSupport.player(helper);
        outside.setPos(helper.absoluteVec(new Vec3(1.5, 1, 0.5)));
        var players = helper.getLevel().players();
        players.add(outside);
        players.add(inside);
        try {
            withConfig("READY_CHECK", true, () -> GameTestSupport.click(helper, outside, SIGN));
            helper.assertFalse(r.isOpen(), "the inside player still needs to ready up");
            invoke(r, "sendStatus", new Class<?>[] {ServerLevel.class}, helper.getLevel());
            Status status = inside.packets.stream().filter(Status.class::isInstance).map(Status.class::cast).findFirst().orElseThrow();
            helper.assertTrue(status.players().stream().anyMatch(m -> m.id().equals(outside.getUUID()) && m.ready()),
                    "the outside initiator is in the required roster");
            var buf = Unpooled.buffer();
            try {
                var codec = Status.class.getDeclaredField("CODEC");
                codec.setAccessible(true);
                @SuppressWarnings("unchecked")
                var stream = (net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, Status>) codec.get(null);
                stream.encode(buf, status);
                status = stream.decode(buf);
            } finally {
                buf.release();
            }
            helper.assertTrue(status.readyCheckActive(), "the status carries the active flag on the wire");
            helper.assertValueEqual(status.sign(), r.sign, "the restaurant sign");
            helper.assertValueEqual(status.area(), r.area(), "the restaurant area");
            helper.assertTrue(status.players().stream().anyMatch(m -> m.id().equals(inside.getUUID()) && !m.ready()), "the unready inside player is required");

            outside.setPos(outside.position().add(128, 0, 0));
            inside.packets.clear();
            invoke(r, "sendStatus", new Class<?>[] {ServerLevel.class}, helper.getLevel());
            Status pending = inside.packets.stream().filter(Status.class::isInstance).map(Status.class::cast).findFirst().orElseThrow();
            helper.assertTrue(pending.readyCheckActive(), "the check stays active when nobody in its roster is ready");
            helper.assertValueEqual(pending.readyCount(), 0, "no required player is ready");
        } finally {
            players.remove(outside);
            players.remove(inside);
        }
        helper.succeed();
    }

    /** CORE-13 / CLIENT-5 / EXTERNAL-4 / ECONOMY-12: Late television viewers get the unchanged picture and all viewers get a clear. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void televisionSendsBaselineAndFinalClear(GameTestHelper helper) {
        Restaurant r = setup(helper);
        helper.setBlock(OTHER, Blocks.NOTE_BLOCK);
        TestPlayer first = GameTestSupport.player(helper), late = GameTestSupport.player(helper);
        var players = helper.getLevel().players();
        players.add(first);
        players.add(late);
        var near = late.position();
        late.setPos(near.add(128, 0, 0));
        try {
            withConfig("SCREEN_BLOCKS", List.of("minecraft:note_block=0,0,16,16,1,0"), () -> {
                cycleScreen(helper, r);
                screens(helper, r);
                helper.assertValueEqual(screenPackets(first).size(), 1, "first viewer receives the baseline");
                helper.assertTrue(screenPackets(late).isEmpty(), "distant player receives no baseline");
                late.setPos(near);
                screens(helper, r);
                helper.assertValueEqual(screenPackets(late), screenPackets(first), "late viewer receives the same snapshot");
                screens(helper, r);
                helper.assertValueEqual(screenPackets(first).size(), 1, "the unchanged snapshot is deduplicated");

                cycleScreen(helper, r);
                screens(helper, r);
                helper.assertValueEqual(screenPackets(first).size(), 2, "a configured viewer receives the mode change");
                late.setPos(near.add(128, 0, 0));
                cycleScreen(helper, r);
                screens(helper, r);
                helper.assertTrue(screenPackets(first).getLast().data().isEmpty(), "the last screen turning off sends an explicit clear");
                helper.assertTrue(screenPackets(late).getLast().data().isEmpty(), "the clear also reaches a viewer who left the range");
                screens(helper, r);
                helper.assertValueEqual(screenPackets(first).size(), 3, "the clear is sent once");
            });
        } finally {
            players.remove(first);
            players.remove(late);
        }
        helper.succeed();
    }

    private static Restaurant sign(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        return Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
    }

    private static Restaurant setup(GameTestHelper helper) {
        Restaurant r = sign(helper);
        int balance = r.register();
        GameTestSupport.setRegister(r, 100);
        GameTestSupport.unlock(helper, r, "wheat");
        GameTestSupport.setRegister(r, balance);
        helper.setBlock(TABLE, Blocks.OAK_SLAB);
        helper.setBlock(SEAT, Blocks.OAK_STAIRS);
        setArea(r, BoundingBox.fromCorners(helper.absolutePos(new BlockPos(0, 1, 1)), helper.absolutePos(new BlockPos(3, 2, 3))));
        invoke(r, "setSpawn", new Class<?>[] {BlockPos.class}, helper.absolutePos(SIGN.north()));
        return r;
    }

    private static void secondTable(GameTestHelper helper) {
        helper.setBlock(OTHER, Blocks.OAK_SLAB);
        helper.setBlock(OTHER_SEAT, Blocks.OAK_STAIRS);
    }

    private static void setArea(Restaurant r, BoundingBox area) {
        invoke(r, "setArea", new Class<?>[] {BoundingBox.class}, area);
    }

    private static void requestReady(ServerPlayer player, boolean ready) {
        invoke(ReadyRequest.class, null, "handle", new Class<?>[] {ReadyRequest.class, ServerPlayer.class}, new ReadyRequest(ready), player);
    }

    private static boolean listReady(Restaurant r, ServerPlayer player) {
        return ((java.util.Set<?>) field(r, "ready")).contains(player.getUUID());
    }

    private static Computer.State manager(TestPlayer player, BlockPos computer) {
        return (Computer.State) Computer.handle(player, new Computer.Request(computer, Computer.REFRESH_MANAGER, ""));
    }

    private static void toggle(GameTestHelper helper, Restaurant r, BlockPos pos) {
        helper.assertValueEqual(invoke(r, "toggleTable", new Class<?>[] {ServerLevel.class, BlockPos.class},
                helper.getLevel(), helper.absolutePos(pos)), true, "table toggled");
    }

    private static int spawnParty(GameTestHelper helper, Restaurant r, int size) {
        return (int) invoke(r, "spawnParty", new Class<?>[] {ServerLevel.class, int.class}, helper.getLevel(), size);
    }

    private static void rejectArrival(GameTestHelper helper, Restaurant r, Customer rejected) {
        AtomicBoolean canceled = new AtomicBoolean();
        Consumer<EntityJoinLevelEvent> listener = event -> {
            if (event.getEntity() == rejected) {
                event.setCanceled(true);
                canceled.set(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            for (int i = 0; i < 21 && !canceled.get(); i++) tick(helper, r);
            helper.assertTrue(canceled.get(), "the selected join was canceled");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
    }

    private static void discardCustomers(Restaurant r) {
        for (Object customer : List.copyOf(list(r, "customers"))) ((Customer) customer).discard();
    }

    private static void tick(GameTestHelper helper, Restaurant r) {
        invoke(r, "tick", new Class<?>[] {ServerLevel.class}, helper.getLevel());
    }

    private static void cycleScreen(GameTestHelper helper, Restaurant r) {
        helper.assertValueEqual(invoke(r, "cycleScreen", new Class<?>[] {ServerLevel.class, BlockPos.class},
                helper.getLevel(), helper.absolutePos(OTHER)), true, "screen mode cycled");
    }

    private static void screens(GameTestHelper helper, Restaurant r) {
        invoke(r, "sendScreens", new Class<?>[] {ServerLevel.class}, helper.getLevel());
    }

    private static List<ScreenData> screenPackets(TestPlayer player) {
        return player.packets.stream().filter(ScreenData.class::isInstance).map(ScreenData.class::cast).toList();
    }

    private static final class CountingArea extends BoundingBox {
        private final BlockPos ignored;
        private int layoutReads;

        private CountingArea(BoundingBox box, BlockPos ignored) {
            super(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            this.ignored = ignored;
        }

        @Override
        public boolean isInside(Vec3i pos) {
            if (!pos.equals(ignored)) layoutReads++;
            return super.isInside(pos);
        }
    }

    private static List<?> list(Object target, String name) {
        return (List<?>) field(target, name);
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        return invoke(target.getClass(), target, name, types, args);
    }

    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) {
        try {
            var method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> void withConfig(String name, T value, Runnable test) {
        try {
            var field = Config.class.getDeclaredField(name);
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var config = (ModConfigSpec.ConfigValue<T>) field.get(null);
            T old = config.get();
            try {
                config.set(value);
                test.run();
            } finally {
                config.set(old);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
