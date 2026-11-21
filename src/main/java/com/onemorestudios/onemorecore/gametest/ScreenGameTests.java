package com.onemorestudios.onemorecore.gametest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.onemorestudios.onemorecore.Customer;
import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.Menu;
import com.onemorestudios.onemorecore.MenuBoardBlock;
import com.onemorestudios.onemorecore.MenuBoardBlockEntity;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.OrderBoardBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.ScreenClick;
import com.onemorestudios.onemorecore.ScreenRect;
import com.onemorestudios.onemorecore.TrayBlockEntity;
import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.SlotRef;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class ScreenGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    private static final BlockPos SIGN = POS.east();
    private static final float CARD_Y = 90;

    /** CLIENT-4: a wall and excessive reach block ticket requests, and a visible overhang remains clickable. */
    @GameTest(template = "empty")
    public static void orderCardRequiresLineOfSight(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = placeOrderBoard(helper);
        addOrder(helper, restaurant, Items.BREAD.getDefaultInstance());
        UUID id = displayedOrders(helper).getFirst().orderId();
        var player = GameTestSupport.player(helper);
        ScreenClick click = aim(helper, player, id, 50);
        BlockPos wall = POS.west().south();

        helper.setBlock(wall, Blocks.STONE);
        request(click, player);
        assertNoTicket(helper, player);

        helper.setBlock(wall, Blocks.AIR);
        player.setPos(player.getX(), player.getY(), player.getZ() + 11);
        request(click, player);
        assertNoTicket(helper, player);

        aim(helper, player, id, 50);
        request(click, player);
        helper.assertValueEqual(player.getInventory().countItem(OneMoreCore.TICKET.get()), 1, "ticket for the visible overhang");
        helper.succeed();
    }

    /** EXTERNAL-6: the byte budget keeps a component-heavy menu under the clientbound payload limit. */
    @GameTest(template = "empty")
    public static void componentHeavyMenuFitsPayload(GameTestHelper helper) throws ReflectiveOperationException, IOException {
        var restaurant = placeRestaurant(helper);
        ItemStack stack = Items.BREAD.getDefaultInstance();
        CompoundTag components = new CompoundTag();
        components.putByteArray("large", new byte[16 * 1024]);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(components));
        for (int i = 0; i < 80; i++) addDish(restaurant, new Menu.Entry("test:heavy" + i, stack, null, 2, true, 0, 0));

        CompoundTag tag = menuTag(helper, restaurant);
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeAnyTag(tag, new DataOutputStream(bytes));
        helper.assertTrue(bytes.size() < 1024 * 1024, "Menu NBT fits the clientbound payload limit");
        var lines = MenuBoardBlockEntity.parse(tag, helper.getLevel().registryAccess());
        var first = lines.stream().filter(line -> line.id().equals("test:heavy0")).findFirst().orElseThrow();
        helper.assertTrue(ItemStack.matches(first.items().getFirst(), stack), "The retained row still has its full components");
        helper.succeed();
    }

    /** EXTERNAL-2: a spectator and a player denied block interaction cannot obtain a visible order ticket. */
    @GameTest(template = "empty")
    public static void orderCardRequiresPermission(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = placeOrderBoard(helper);
        addOrder(helper, restaurant, Items.BREAD.getDefaultInstance());
        var player = GameTestSupport.player(helper);
        ScreenClick click = aim(helper, player, displayedOrders(helper).getFirst().orderId(), 50);

        player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
        helper.assertTrue(player.isSpectator(), "The test player is a spectator");
        request(click, player);
        assertNoTicket(helper, player);

        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var border = helper.getLevel().getWorldBorder();
        var settings = border.createSettings();
        try {
            excludeFromBorder(helper, click.pos());
            helper.assertTrue(!helper.getLevel().mayInteract(player, click.pos()), "The board is protected");
            request(click, player);
            assertNoTicket(helper, player);
        } finally {
            border.applySettings(settings);
        }
        helper.succeed();
    }

    /** CLIENT-11 / ECONOMY-11: a removed order's ID cannot issue the next card's ticket. */
    @GameTest(template = "empty")
    public static void staleOrderCardDoesNotIssueNextTicket(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = placeOrderBoard(helper);
        Customer first = addOrder(helper, restaurant, Items.BREAD.getDefaultInstance());
        addOrder(helper, restaurant, Items.APPLE.getDefaultInstance());
        var displayed = displayedOrders(helper);
        var player = GameTestSupport.player(helper);
        ScreenClick stale = aim(helper, player, displayed.getFirst().orderId(), 50);
        UUID remaining = displayed.get(1).orderId();
        helper.assertTrue(!remaining.equals(stale.orderId()), "Orders have distinct IDs");

        method(Customer.class, "setState", Customer.State.class).invoke(first, Customer.State.EATING);
        helper.assertValueEqual(displayedOrders(helper).getFirst().orderId(), remaining, "ID after moving into the first slot");
        request(stale, player);
        assertNoTicket(helper, player);

        request(aim(helper, player, remaining, 50), player);
        helper.assertValueEqual(player.getInventory().countItem(OneMoreCore.TICKET.get()), 1, "ticket for the remaining order");
        var contents = player.getMainHandItem().get(DataComponents.CONTAINER);
        helper.assertTrue(contents != null && contents.stream().anyMatch(stack -> stack.is(Items.APPLE)),
                "The ticket contains the remaining order's dish");
        helper.succeed();
    }

    /** EXTERNAL-1: a same-tick tray replacement prevents stale slot moves and closes the open station. */
    @GameTest(template = "empty")
    public static void replacingTrayClosesStation(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("diegesis")) { helper.succeed(); return; }
        var tray = GameTestSupport.place(helper, POS, OneMoreCore.TRAY.get(), TrayBlockEntity.class);
        var player = GameTestSupport.player(helper);
        enableDiegesisChannel(player);
        BlockPos pos = helper.absolutePos(POS);
        var bread = Items.BREAD.getDefaultInstance();
        tray.items.setItem(0, bread);
        helper.assertTrue(Diegesis.open(player, pos), "The tray station opened");

        var station = Diegesis.activeStation(helper.getLevel(), pos).orElseThrow();
        var rules = KitchenStations.TRAY.logic().create(station);
        var slot = new SlotRef("dishes", 0);
        helper.assertTrue(rules.mayTake(station, player, slot), "The original tray can be used");
        // Replace its entity in place, as if the tray were picked up and set down before Diegesis's next tick.
        helper.getLevel().removeBlockEntity(pos);
        var replacement = new TrayBlockEntity(pos, helper.getLevel().getBlockState(pos));
        helper.getLevel().setBlockEntity(replacement);
        helper.assertTrue(!rules.mayTake(station, player, slot), "The detached inventory cannot be withdrawn from");
        helper.assertTrue(!rules.mayPlace(station, player, slot, bread), "The detached inventory cannot be filled");
        helper.assertTrue(replacement.items.isEmpty(), "The new tray is empty");

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(Diegesis.activeStation(helper.getLevel(), pos).isEmpty(), "The detached station closed");
            helper.succeed();
        });
    }

    /** EXTERNAL-3: losing block permission denies tray open, take and place. */
    @GameTest(template = "empty")
    public static void trayStationRequiresPermission(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("diegesis")) { helper.succeed(); return; }
        var tray = GameTestSupport.place(helper, POS, OneMoreCore.TRAY.get(), TrayBlockEntity.class);
        var player = GameTestSupport.player(helper);
        var other = GameTestSupport.player(helper);
        enableDiegesisChannel(player);
        enableDiegesisChannel(other);
        BlockPos pos = helper.absolutePos(POS);
        var state = helper.getLevel().getBlockState(pos);
        var bread = Items.BREAD.getDefaultInstance();
        tray.items.setItem(0, bread);
        helper.assertTrue(Diegesis.open(player, pos), "The tray opens while interaction is allowed");
        var station = Diegesis.activeStation(helper.getLevel(), pos).orElseThrow();
        var rules = KitchenStations.TRAY.logic().create(station);
        var slot = new SlotRef("dishes", 0);
        var border = helper.getLevel().getWorldBorder();
        var settings = border.createSettings();
        try {
            excludeFromBorder(helper, pos);
            helper.assertTrue(!helper.getLevel().mayInteract(other, pos), "The tray is protected");
            helper.assertTrue(!KitchenStations.TRAY.openCondition().test(other, pos, state), "The station denies protected opens");
            helper.assertTrue(!Diegesis.open(other, pos), "The denied player cannot open the tray");
            helper.assertTrue(!rules.mayTake(station, player, slot), "A viewer cannot withdraw after losing permission");
            helper.assertTrue(!rules.mayPlace(station, player, new SlotRef("dishes", 1), bread),
                    "A viewer cannot deposit after losing permission");
        } finally {
            border.applySettings(settings);
            Diegesis.close(player);
        }
        helper.assertTrue(ItemStack.matches(tray.items.getItem(0), bread), "The tray still contains its bread");
        helper.succeed();
    }

    /** EXTERNAL-5 / ECONOMY-14: tag dishes have the same representative icon alone and in fixed or kind combo slots. */
    @GameTest(template = "empty")
    public static void tagDishComboUsesRepresentativeStack(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = placeRestaurant(helper);
        disableConfiguredDishes(restaurant);
        var tag = new Menu.Entry("test:tag", null, ItemTags.FISHES, 4, false, 0, 0, "starter");
        addDish(restaurant, tag);
        addCombo(restaurant, new Restaurant.Combo("combo:test", List.of(tag.id(), "kind:starter"), 6));

        var lines = menu(helper, restaurant);
        var standalone = lines.stream().filter(line -> line.id().equals(tag.id())).findFirst().orElseThrow().items().getFirst();
        var combo = lines.stream().filter(line -> line.id().equals("combo:test")).findFirst().orElseThrow();
        helper.assertTrue(!standalone.isEmpty() && standalone.is(ItemTags.FISHES), "The representative icon belongs to the tag");
        helper.assertValueEqual(combo.items().size(), 2, "combo slot count");
        helper.assertTrue(combo.items().stream().allMatch(stack -> ItemStack.matches(stack, standalone)),
                "Both combo slots have the same representative icon");
        helper.succeed();
    }

    /** EXTERNAL-6: the display keeps a combo past 64 dishes and when the catalog reaches 256 rows. */
    @GameTest(template = "empty")
    public static void menuRowLimitKeepsCombos(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = placeRestaurant(helper);
        disableConfiguredDishes(restaurant);
        for (int i = 0; i < 64; i++) addDish(restaurant, bread(i));
        addCombo(restaurant, new Restaurant.Combo("combo:test", List.of("test:bread0", "test:bread1"), 4));
        var lines = menu(helper, restaurant);
        helper.assertValueEqual(lines.size(), 65, "display rows past the previous cap");
        helper.assertValueEqual(lines.getLast().id(), "combo:test", "combo round trip");

        for (int i = 64; i <= 256; i++) addDish(restaurant, bread(i));
        var full = menu(helper, restaurant);
        helper.assertValueEqual(full.size(), 256, "shared display row cap");
        helper.assertValueEqual(full.getLast().id(), "combo:test", "reserved combo row at capacity");
        helper.assertValueEqual(full.get(254).id(), "test:bread254", "last displayed dish at capacity");
        helper.succeed();
    }

    /** EXTERNAL-7: same-item dishes retain separate IDs, component-backed names and prices. */
    @GameTest(template = "empty")
    public static void menuDistinguishesComponentVariants(GameTestHelper helper) throws ReflectiveOperationException {
        var restaurant = placeRestaurant(helper);
        ItemStack first = Items.BREAD.getDefaultInstance();
        first.set(DataComponents.CUSTOM_NAME, Component.literal("Rye bread"));
        ItemStack second = Items.BREAD.getDefaultInstance();
        second.set(DataComponents.CUSTOM_NAME, Component.literal("Milk bread"));
        addDish(restaurant, new Menu.Entry("test:rye", first, null, 4, true, 0, 0));
        addDish(restaurant, new Menu.Entry("test:milk", second, null, 7, true, 0, 0));

        var lines = menu(helper, restaurant);
        var rye = lines.stream().filter(line -> line.id().equals("test:rye")).findFirst().orElseThrow();
        var milk = lines.stream().filter(line -> line.id().equals("test:milk")).findFirst().orElseThrow();
        helper.assertTrue(ItemStack.matches(rye.items().getFirst(), first), "Rye bread keeps its name component");
        helper.assertTrue(ItemStack.matches(milk.items().getFirst(), second), "Milk bread keeps its name component");
        helper.assertValueEqual(rye.price(), 4, "rye price");
        helper.assertValueEqual(milk.price(), 7, "milk bread price");
        helper.succeed();
    }

    private static Restaurant placeRestaurant(GameTestHelper helper) {
        GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
        return Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
    }

    private static Restaurant placeOrderBoard(GameTestHelper helper) {
        var restaurant = placeRestaurant(helper);
        helper.setBlock(POS, OneMoreCore.ORDER_BOARD.get().defaultBlockState().setValue(MenuBoardBlock.FACING, Direction.SOUTH));
        return restaurant;
    }

    private static Customer addOrder(GameTestHelper helper, Restaurant restaurant, ItemStack dish) throws ReflectiveOperationException {
        var customer = new Customer(OneMoreCore.CUSTOMER.get(), helper.getLevel());
        Class<?> partyClass = Class.forName(Customer.class.getName() + "$Party");
        Constructor<?> constructor = partyClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        method(Customer.class, "setup", Restaurant.class, Restaurant.Seat.class, List.class, partyClass)
                .invoke(customer, restaurant, new Restaurant.Seat(helper.absolutePos(POS.above()), helper.absolutePos(POS)),
                        List.of(new Customer.Line(dish, 2, false)), constructor.newInstance());
        method(Customer.class, "setState", Customer.State.class).invoke(customer, Customer.State.WAITING_FOOD);
        method(Customer.class, "syncOrder").invoke(customer);
        field(Restaurant.class, "customers", restaurant, List.class).add(customer);
        return customer;
    }

    private static List<OrderBoardBlockEntity.Ticket> displayedOrders(GameTestHelper helper) throws ReflectiveOperationException {
        var board = (OrderBoardBlockEntity) helper.getBlockEntity(POS);
        @SuppressWarnings("unchecked")
        var tickets = (List<OrderBoardBlockEntity.Ticket>) method(OrderBoardBlockEntity.class, "tickets", ServerLevel.class)
                .invoke(board, helper.getLevel());
        var registries = helper.getLevel().registryAccess();
        return OrderBoardBlockEntity.decode(OrderBoardBlockEntity.encode(tickets, registries), registries);
    }

    private static ScreenClick aim(GameTestHelper helper, ServerPlayer player, UUID id, float x) {
        BlockPos pos = helper.absolutePos(POS);
        var state = helper.getLevel().getBlockState(pos);
        var rect = ScreenRect.OWN;
        Vec3 point = rect.world(pos, state, rect.x0() + x / 10, rect.y1() - CARD_Y / 10, rect.z());
        player.setPos(point.x, point.y - player.getEyeHeight(), point.z + 2);
        return new ScreenClick(pos, id, x, CARD_Y);
    }

    private static void request(ScreenClick click, ServerPlayer player) throws ReflectiveOperationException {
        method(ScreenClick.class, "handle", ScreenClick.class, ServerPlayer.class).invoke(null, click, player);
    }

    private static void assertNoTicket(GameTestHelper helper, ServerPlayer player) {
        helper.assertValueEqual(player.getInventory().countItem(OneMoreCore.TICKET.get()), 0, "tickets in inventory");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(player.blockPosition()).inflate(3), item -> item.getItem().is(OneMoreCore.TICKET.get())).isEmpty(),
                "No ticket dropped");
    }

    private static void enableDiegesisChannel(ServerPlayer player) {
        var id = ResourceLocation.fromNamespaceAndPath("diegesis", "to_client");
        ChannelAttributes.setPayloadSetup(player.connection.getConnection(),
                new NetworkPayloadSetup(Map.of(ConnectionProtocol.PLAY, Map.of(id, new NetworkChannel(id, "1")))));
    }

    private static void excludeFromBorder(GameTestHelper helper, BlockPos pos) {
        var border = helper.getLevel().getWorldBorder();
        border.setCenter(pos.getX() + 10, pos.getZ());
        border.setSize(2);
    }

    private static Menu.Entry bread(int index) {
        return new Menu.Entry("test:bread" + index, Items.BREAD.getDefaultInstance(), null, 2, false, 0, 0);
    }

    private static void addDish(Restaurant restaurant, Menu.Entry entry) throws ReflectiveOperationException {
        field(Restaurant.class, "custom", restaurant, List.class).add(entry);
    }

    private static void addCombo(Restaurant restaurant, Restaurant.Combo combo) throws ReflectiveOperationException {
        field(Restaurant.class, "combos", restaurant, List.class).add(combo);
    }

    private static void disableConfiguredDishes(Restaurant restaurant) throws ReflectiveOperationException {
        field(Restaurant.class, "off", restaurant, java.util.Set.class).addAll(Menu.entries.stream().map(Menu.Entry::id).toList());
    }

    private static List<MenuBoardBlockEntity.Line> menu(GameTestHelper helper, Restaurant restaurant) throws ReflectiveOperationException {
        return MenuBoardBlockEntity.parse(menuTag(helper, restaurant), helper.getLevel().registryAccess());
    }

    private static CompoundTag menuTag(GameTestHelper helper, Restaurant restaurant) throws ReflectiveOperationException {
        return (CompoundTag) method(MenuBoardBlockEntity.class, "build", Restaurant.class, HolderLookup.Provider.class)
                .invoke(null, restaurant, helper.getLevel().registryAccess());
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static <T> T field(Class<?> owner, String name, Object instance, Class<T> type) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(instance));
    }
}
