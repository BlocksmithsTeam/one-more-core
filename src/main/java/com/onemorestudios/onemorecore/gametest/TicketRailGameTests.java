package com.onemorestudios.onemorecore.gametest;

import java.lang.reflect.Method;
import java.util.List;
import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.ScreenClick;
import com.onemorestudios.onemorecore.TicketItem;
import com.onemorestudios.onemorecore.TicketRailBlock;
import com.onemorestudios.onemorecore.TicketRailBlockEntity;
import com.onemorestudios.onemorecore.Tickets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class TicketRailGameTests {
    private static final BlockPos POS = new BlockPos(2, 2, 2), SIGN = new BlockPos(1, 1, 1);

    @GameTest(template = "empty")
    public static void hangAndTakeEverySpotInEveryFacing(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var rail = place(helper, facing);
            var player = GameTestSupport.player(helper);
            for (int slot = 0; slot < TicketRailBlockEntity.SIZE; slot++) {
                ItemStack ticket = ticket("" + slot);
                ItemStack expected = ticket.copy();
                GameTestSupport.give(player, ticket);
                click(helper, player, facing, slot);
                helper.assertTrue(ticket.isEmpty(), "Hanging moves the ticket from the hand");
                helper.assertTrue(ItemStack.matches(rail.ticket(slot), expected), "Next free spot keeps the whole ticket");
            }
            for (int slot = 0; slot < TicketRailBlockEntity.SIZE; slot++) {
                ItemStack expected = rail.ticket(slot).copy();
                GameTestSupport.give(player, ItemStack.EMPTY);
                click(helper, player, facing, slot);
                helper.assertTrue(rail.ticket(slot).isEmpty(), "Hit spot becomes empty: " + facing + "/" + slot);
                helper.assertTrue(ItemStack.matches(player.getMainHandItem(), expected), "Taking returns the original ticket");
            }
            helper.getLevel().removeBlock(helper.absolutePos(POS), false);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void seventhTicketIsRefusedAndHoleIsReused(GameTestHelper helper) {
        var rail = place(helper, Direction.NORTH);
        for (int slot = 0; slot < TicketRailBlockEntity.SIZE; slot++) helper.assertTrue(rail.hang(ticket("" + slot)), "Free spot accepts ticket");
        var player = GameTestSupport.player(helper);
        ItemStack extra = ticket("seventh");
        GameTestSupport.give(player, extra);
        click(helper, player, Direction.NORTH, 0);
        helper.assertValueEqual(extra.getCount(), 1, "Seventh ticket stays in the hand");
        helper.assertValueEqual(rail.ticket(0).get(OneMoreCore.LABEL.get()), "0", "Full rail does not replace a ticket");
        rail.take(2);
        click(helper, player, Direction.NORTH, 0);
        helper.assertTrue(extra.isEmpty(), "Freed spot accepts the ticket");
        helper.assertValueEqual(rail.ticket(2).get(OneMoreCore.LABEL.get()), "seventh", "First hole is reused");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void railRejectsNonTicketsAndInvalidSpots(GameTestHelper helper) {
        var rail = place(helper, Direction.NORTH);
        var player = GameTestSupport.player(helper);
        ItemStack stone = new ItemStack(Items.STONE, 5);
        GameTestSupport.give(player, stone);
        click(helper, player, Direction.NORTH, 0);
        helper.assertValueEqual(stone.getCount(), 5, "Non-ticket hand is unchanged");
        helper.assertTrue(!rail.hang(stone) && rail.ticket(0).isEmpty(), "Storage rejects non-ticket items too");
        helper.assertTrue(rail.take(-1).isEmpty() && rail.take(6).isEmpty(), "Invalid slots give nothing");
        rail.hang(ticket("legacy"));
        helper.assertTrue(!TicketItem.finished(helper.getLevel(), rail.ticket(0)), "Legacy tickets without identity are never guessed done");
        var state = rail.getBlockState();
        BlockPos distant = new BlockPos(29999984, 80, -29999984);
        for (int slot = 0; slot < 6; slot++) {
            Vec3 hit = Vec3.atLowerCornerOf(distant).add((slot + 0.5) / 6, 0.5, 14 / 16d);
            helper.assertValueEqual(TicketRailBlock.spot(state, distant, hit), slot, "Distant hits retain precision");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void saveAndClientUpdateKeepWholeTickets(GameTestHelper helper) {
        var rail = place(helper, Direction.NORTH);
        for (int i = 0; i < 6; i++) rail.hang(ticket("" + i));
        rail.take(2);
        var registries = helper.getLevel().registryAccess();
        var loaded = (TicketRailBlockEntity) BlockEntity.loadStatic(rail.getBlockPos(), rail.getBlockState(), rail.saveWithFullMetadata(registries), registries);
        helper.assertTrue(loaded != null, "Rail loads from its registered type");
        var client = new TicketRailBlockEntity(rail.getBlockPos(), rail.getBlockState());
        client.handleUpdateTag(rail.getUpdateTag(registries), registries);
        for (int i = 0; i < 6; i++) {
            helper.assertTrue(ItemStack.matches(loaded.ticket(i), rail.ticket(i)), "Save keeps slot " + i);
            helper.assertTrue(ItemStack.matches(client.ticket(i), rail.ticket(i)), "Client update keeps slot " + i);
        }
        helper.assertTrue(rail.getUpdatePacket() != null, "Rail sends block entity updates");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void fullyServedOrderMarksAndDropsItsTicket(GameTestHelper helper) throws ReflectiveOperationException {
        var rail = place(helper, Direction.NORTH);
        Restaurant restaurant = restaurant(helper);
        Customer customer = order(helper, restaurant);
        ItemStack ticket = issuedTicket(helper, restaurant);
        ItemStack original = ticket.copy();
        rail.hang(ticket);
        helper.assertTrue(!TicketItem.finished(helper.getLevel(), original), "Issued ticket identifies an active order");
        var player = GameTestSupport.player(helper);
        serve(helper, customer, player, Items.BREAD.getDefaultInstance());
        helper.assertTrue(!TicketItem.finished(helper.getLevel(), original), "Partial service keeps the order active");
        helper.runAfterDelay(21, () -> {
            helper.assertTrue(!rail.done(0), "A partly served ticket keeps its normal look");
            serve(helper, customer, player, Items.CARROT.getDefaultInstance());
            helper.assertTrue(TicketItem.finished(helper.getLevel(), original), "Final dish completes the identified order");
            helper.runAfterDelay(21, () -> {
                helper.assertTrue(rail.done(0) && !rail.ticket(0).isEmpty(), "Done look precedes removal");
                var registries = helper.getLevel().registryAccess();
                var loaded = (TicketRailBlockEntity) BlockEntity.loadStatic(rail.getBlockPos(), rail.getBlockState(), rail.saveWithFullMetadata(registries), registries);
                helper.assertTrue(loaded != null && loaded.done(0), "Done look and expiry survive saving");
                helper.getLevel().setBlockEntity(loaded);
                helper.runAfterDelay(61, () -> {
                    helper.assertTrue(loaded.ticket(0).isEmpty(), "Completed ticket leaves the rail after the delay");
                    helper.assertValueEqual(dropped(helper).size(), 1, "Ticket drops once instead of disappearing");
                    helper.assertTrue(ItemStack.matches(dropped(helper).getFirst().getItem(), original), "Dropped ticket retains all data");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 110)
    public static void departedPartyDoesNotMatchAnotherOrderAtTheSameTable(GameTestHelper helper) throws ReflectiveOperationException {
        var rail = place(helper, Direction.NORTH);
        Restaurant restaurant = restaurant(helper);
        Customer departed = order(helper, restaurant);
        ItemStack oldTicket = issuedTicket(helper, restaurant);
        rail.hang(oldTicket);
        method(Customer.class, "setState", Customer.State.class).invoke(departed, Customer.State.LEAVING);
        order(helper, restaurant);
        helper.assertTrue(TicketItem.finished(helper.getLevel(), rail.ticket(0)), "New order at same table does not keep the old ticket alive");
        helper.runAfterDelay(21, () -> {
            helper.assertTrue(rail.done(0), "Departed party marks its ticket done");
            helper.runAfterDelay(61, () -> {
                helper.assertTrue(rail.ticket(0).isEmpty(), "Departed party's ticket drops");
                helper.assertValueEqual(dropped(helper).size(), 1, "Departure returns a physical ticket");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void breakingSupportDropsAllTickets(GameTestHelper helper) {
        var rail = place(helper, Direction.NORTH);
        for (int i = 0; i < 6; i++) rail.hang(ticket("" + i));
        helper.getLevel().destroyBlock(helper.absolutePos(POS.south()), false);
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(POS)).isAir(), "Unsupported rail breaks");
        helper.assertValueEqual(dropped(helper).size(), 6, "Breaking support preserves all six tickets");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void takingWithOtherInventorySlotsFullPreservesTheTicket(GameTestHelper helper) {
        var rail = place(helper, Direction.NORTH);
        ItemStack expected = ticket("full");
        rail.hang(expected.copy());
        var player = GameTestSupport.player(helper);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        GameTestSupport.give(player, ItemStack.EMPTY);
        player.getInventory().offhand.set(0, new ItemStack(Items.STONE, 64));
        // The empty selected hand can hold the ticket, even when every other slot is full.
        click(helper, player, Direction.NORTH, 0);
        helper.assertTrue(rail.ticket(0).isEmpty(), "Taking clears the rail once");
        helper.assertTrue(ItemStack.matches(player.getMainHandItem(), expected), "Empty hand receives the whole ticket");
        click(helper, GameTestSupport.player(helper), Direction.NORTH, 0);
        helper.assertTrue(dropped(helper).isEmpty(), "A second player cannot take a duplicate");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void optionalStationValidatesCursorAndRequests(GameTestHelper helper) {
        if (net.neoforged.fml.ModList.get().isLoaded("diegesis")) WithDiegesis.verify(helper);
        else helper.succeed();
    }

    private static final class WithDiegesis {
        static void verify(GameTestHelper helper) {
            var rail = place(helper, Direction.NORTH);
            var player = GameTestSupport.player(helper);
            ScreenGameTests.enableDiegesisChannel(player);
            var api = com.onemorestudios.onemorecore.TicketRailStation.TYPE;
            helper.assertTrue(com.pockyl.diegesis.api.Diegesis.open(player, rail.getBlockPos()), "Empty hand opens the rail station");
            var station = com.pockyl.diegesis.api.Diegesis.activeStation(helper.getLevel(), rail.getBlockPos()).orElseThrow();
            helper.assertValueEqual(station.type(), api, "Rail chooses its optional station");
            var rules = api.logic().create(station);
            var data = new net.minecraft.nbt.CompoundTag();
            data.putInt("slot", 0);
            ItemStack expected = ticket("cursor");
            player.inventoryMenu.setCarried(expected.copy());
            var outsider = GameTestSupport.player(helper);
            outsider.inventoryMenu.setCarried(expected.copy());
            rules.onCustomAction(station, outsider, "hang", data);
            helper.assertTrue(rail.ticket(0).isEmpty() && !outsider.inventoryMenu.getCarried().isEmpty(), "Outsider cannot move a ticket");
            rules.onCustomAction(station, player, "unknown", data);
            data.putInt("slot", 6);
            rules.onCustomAction(station, player, "take", data);
            data.putString("slot", "0");
            rules.onCustomAction(station, player, "take", data);
            helper.assertTrue(!player.inventoryMenu.getCarried().isEmpty() && rail.ticket(0).isEmpty(), "Invalid actions preserve inventory");
            rules.onCustomAction(station, player, "hang", data);
            helper.assertTrue(player.inventoryMenu.getCarried().isEmpty() && ItemStack.matches(rail.ticket(0), expected), "Cursor ticket reaches the same storage");
            data.putInt("slot", 0);
            rules.onCustomAction(station, player, "take", data);
            helper.assertTrue(!rail.ticket(0).isEmpty(), "Second action in the same tick is refused");
            helper.runAfterDelay(1, () -> {
                try {
                    player.inventoryMenu.setCarried(Items.STONE.getDefaultInstance());
                    rules.onCustomAction(station, player, "take", data);
                    helper.assertTrue(!rail.ticket(0).isEmpty() && player.inventoryMenu.getCarried().is(Items.STONE), "Occupied cursor cannot erase an item");
                } catch (RuntimeException | AssertionError failure) {
                    com.pockyl.diegesis.api.Diegesis.close(player);
                    throw failure;
                }
                helper.runAfterDelay(1, () -> {
                    try {
                        player.inventoryMenu.setCarried(ItemStack.EMPTY);
                        rules.onCustomAction(station, player, "take", data);
                        helper.assertTrue(rail.ticket(0).isEmpty() && ItemStack.matches(player.inventoryMenu.getCarried(), expected),
                                "Taking moves the original ticket onto the cursor");
                    } finally {
                        com.pockyl.diegesis.api.Diegesis.close(player);
                    }
                    helper.succeed();
                });
            });
        }
    }

    private static TicketRailBlockEntity place(GameTestHelper helper, Direction facing) {
        helper.setBlock(POS.relative(facing.getOpposite()), Blocks.STONE);
        helper.setBlock(POS, OneMoreCore.TICKET_RAIL.get().defaultBlockState().setValue(TicketRailBlock.FACING, facing));
        return (TicketRailBlockEntity) helper.getBlockEntity(POS);
    }

    private static ItemStack ticket(String label) {
        ItemStack ticket = new ItemStack(OneMoreCore.TICKET.get());
        ticket.set(OneMoreCore.LABEL.get(), label);
        ticket.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(Items.BREAD.getDefaultInstance(), Items.CARROT.getDefaultInstance())));
        return ticket;
    }

    private static void click(GameTestHelper helper, GameTestSupport.TestPlayer player, Direction facing, int slot) {
        double x = (slot + 0.5) / 6, z = 14 / 16d;
        for (int i = 0, turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; }; i < turns; i++) {
            double next = 1 - z;
            z = x;
            x = next;
        }
        BlockPos pos = helper.absolutePos(POS);
        Vec3 hit = Vec3.atLowerCornerOf(pos).add(x, 0.5, z);
        player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, facing, pos, false));
    }

    private static Restaurant restaurant(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        return Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
    }

    private static Customer order(GameTestHelper helper, Restaurant restaurant) throws ReflectiveOperationException {
        var customer = new Customer(OneMoreCore.CUSTOMER.get(), helper.getLevel());
        Class<?> party = Class.forName(Customer.class.getName() + "$Party");
        var constructor = party.getDeclaredConstructor();
        constructor.setAccessible(true);
        method(Customer.class, "setup", Restaurant.class, Restaurant.Seat.class, List.class, party).invoke(customer, restaurant,
                new Restaurant.Seat(helper.absolutePos(POS.below()), helper.absolutePos(POS.below())),
                List.of(new Customer.Line(Items.BREAD.getDefaultInstance(), 2, false), new Customer.Line(Items.CARROT.getDefaultInstance(), 1, false)),
                constructor.newInstance());
        method(Customer.class, "setState", Customer.State.class).invoke(customer, Customer.State.WAITING_FOOD);
        method(Customer.class, "syncOrder").invoke(customer);
        var field = Restaurant.class.getDeclaredField("customers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var customers = (List<Customer>) field.get(restaurant);
        customers.add(customer);
        return customer;
    }

    private static ItemStack issuedTicket(GameTestHelper helper, Restaurant restaurant) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked") var tickets = (List<Tickets.Ticket>) method(Tickets.class, "tickets", Restaurant.class).invoke(null, restaurant);
        var player = GameTestSupport.player(helper);
        method(ScreenClick.class, "giveTicket", ServerLevel.class, Player.class, BlockPos.class, List.class, java.util.UUID.class)
                .invoke(null, helper.getLevel(), player, helper.absolutePos(POS), tickets, tickets.getLast().orderId());
        return player.getMainHandItem();
    }

    private static void serve(GameTestHelper helper, Customer customer, Player player, ItemStack dish) {
        ItemStack tray = new ItemStack(OneMoreCore.TRAY_ITEM.get());
        tray.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(dish)));
        helper.assertTrue(customer.deliver(player, tray), "Customer accepts the requested dish");
    }

    private static List<ItemEntity> dropped(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(helper.absolutePos(POS)).inflate(3))
                .stream().filter(item -> item.getItem().is(OneMoreCore.TICKET.get())).toList();
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
}
