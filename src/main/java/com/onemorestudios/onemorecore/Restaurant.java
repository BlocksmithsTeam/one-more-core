package com.onemorestudios.onemorecore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** One restaurant. The Open/Closed Sign position is its identity. Progress and money belong to the restaurant. */
public class Restaurant {
    public record Seat(BlockPos seat, BlockPos surface) {}

    private final Restaurants owner;
    public final BlockPos sign;
    BoundingBox area;
    BlockPos spawn;
    int day = 1;
    int register;
    private final Set<ResourceLocation> unlockedIngredients = new HashSet<>();
    private Ingredients ingredientIndex;
    private Ingredients.Availability ingredientAvailability;
    /** Ids of dishes that the players took off the menu. */
    final Set<String> off = new HashSet<>();
    /** The kind of a dish, set by a player; without an entry the dish keeps its own kind. */
    final Map<String, String> kinds = new HashMap<>();
    /** Price overrides for recipe-discovered dishes. */
    private final Map<String, Integer> dishPrices = new HashMap<>();
    /** Combos that the players made: several dishes for one price. */
    final List<Combo> combos = new ArrayList<>();
    /** Surfaces of tables that the players turned off. A table with one of them gets no customers. */
    final Set<BlockPos> offTables = new HashSet<>();
    /** Game time when the last party left a surface's table. */
    private final Map<BlockPos, Long> freedAt = new HashMap<>();
    /** Ticks of service left today; 0 ends the arrivals. Only with dayLengthTicks > 0. */
    int serviceLeft;

    /** Upgrade levels bought on the computer: "decor", "delivery", "bell". */
    final Map<String, Integer> upgrades = new HashMap<>();
    /** Coins (the bills) on the serving surfaces; a player collects them. */
    final Map<BlockPos, Integer> coins = new HashMap<>();
    /** Game time when the call bell may ring again. */
    private long bellReady;

    public int upgrade(String id) {
        return upgrades.getOrDefault(id, 0);
    }

    /** The prices of the levels of an upgrade, from the config; null for an unknown upgrade. */
    static List<? extends Integer> upgradeCosts(String id) {
        return switch (id) {
            case "decor" -> Config.DECOR_COSTS.get();
            case "delivery" -> Config.DELIVERY_COSTS.get();
            case "bell" -> Config.BELL_COSTS.get();
            default -> null;
        };
    }

    /** Result of {@link #buyUpgrade}. */
    public enum Upgrade { OK, MAX, COST, UNKNOWN }

    /** Buys the next level of an upgrade with money from the register. */
    Upgrade buyUpgrade(String id) {
        List<? extends Integer> costs = upgradeCosts(id);
        if (costs == null) return Upgrade.UNKNOWN;
        int level = upgrade(id);
        if (level >= costs.size()) return Upgrade.MAX;
        int cost = costs.get(level);
        if (register < cost) return Upgrade.COST;
        register -= cost;
        upgrades.put(id, level + 1);
        owner.setDirty();
        return Upgrade.OK;
    }

    /** Shop deliveries: each delivery level makes them 40 percent faster. */
    int deliveryTicks() {
        return (int) Math.round(Config.SHOP_DELIVERY.get() * Math.pow(0.6, upgrade("delivery")));
    }

    /**
     * A player rang a bell in the area. With the bell upgrade and during the service, the next group comes at once; the
     * bell rests 200 ticks. True if it called a group.
     */
    boolean ringBell(ServerLevel level) {
        if (!open || upgrade("bell") == 0 || toSpawn <= 0 || level.getGameTime() < bellReady) return false;
        bellReady = level.getGameTime() + 200;
        spawnTimer = 1;
        return true;
    }

    /** The bill of a party lies on its table as coins. */
    void addCoins(BlockPos surface, int amount) {
        if (amount <= 0) return;
        coins.merge(surface.immutable(), amount, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        owner.setDirty();
        if (serverLevel != null) sendTable(serverLevel, surface);
    }

    private ServerLevel serverLevel;

    private void sendTable(ServerLevel level, BlockPos pos) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), new TableItems(pos, coins.getOrDefault(pos, 0)));
    }

    /**
     * A player collects the coins of the whole table of a surface: they go to the register with a pop and a "+N" notice
     * for the player. Returns the coins collected.
     */
    int collect(ServerLevel level, Player player, BlockPos pos) {
        int money = 0;
        Set<BlockPos> table = tableAt(level, pos);
        table.add(pos.immutable());
        for (BlockPos p : table) {
            int bill = coins.getOrDefault(p, 0);
            if (bill <= 0) continue;
            int credited = deposit(bill);
            if (credited == 0) break;
            if (credited == bill) coins.remove(p); else coins.put(p, bill - credited);
            money += credited;
            sendTable(level, p);
        }
        if (money > 0) {
            level.playSound(null, pos, SoundEvents.CHICKEN_EGG, SoundSource.PLAYERS, 0.8f, 1.6f);
            if (player instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new TableItems.Collected(money));
        }
        return money;
    }

    boolean hasTableItems(BlockPos pos) {
        return coins.getOrDefault(pos, 0) > 0;
    }

    /** Sends the coins of a chunk to a player that starts to see it. */
    void sendTableItems(ServerPlayer player, ChunkPos chunk) {
        for (var e : coins.entrySet())
            if (chunk.equals(new ChunkPos(e.getKey()))) PacketDistributor.sendToPlayer(player, new TableItems(e.getKey(), e.getValue()));
    }

    void payRegister(ServerLevel level, long amount) {
        if (amount <= 0) return;
        long left = amount - deposit((int) Math.min(Integer.MAX_VALUE, amount));
        if (left == 0) return;
        ItemStack currency = new ItemStack(Config.currency());
        while (left > 0) {
            int count = (int) Math.min(currency.getMaxStackSize(), left);
            ItemEntity drop = new ItemEntity(level, sign.getX() + 0.5, sign.getY() + 0.5, sign.getZ() + 0.5, currency.copyWithCount(count));
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
            left -= count;
        }
    }

    /** Moves bills into the sign's balance and clears their displays. */
    void settleOnRemoval(ServerLevel level) {
        clearScreens(level);
        for (BlockPos surface : List.copyOf(coins.keySet())) {
            payRegister(level, coins.remove(surface));
            sendTable(level, surface);
        }
        for (ServedBill bill : servedBills.values()) payRegister(level, bill.amount());
        for (ServedBill bill : interruptedBills) payRegister(level, bill.amount());
        servedBills.clear();
        interruptedBills.clear();
    }

    /** Dish prices already handed over, while the party is still waiting or eating. */
    private record ServedBill(List<BlockPos> surfaces, long amount) {}
    private final Map<Customer.Party, ServedBill> servedBills = new HashMap<>();
    private final List<ServedBill> interruptedBills = new ArrayList<>();

    void recordServed(ServerLevel level, Customer.Party party, BlockPos surface, int price) {
        if (price <= 0) return;
        ServedBill previous = servedBills.get(party);
        if (previous == null) {
            Set<BlockPos> table = tableAt(level, surface);
            table.remove(surface);
            List<BlockPos> surfaces = new ArrayList<>(List.of(surface));
            surfaces.addAll(table);
            servedBills.put(party, new ServedBill(surfaces, price));
        } else {
            servedBills.put(party, new ServedBill(previous.surfaces(), previous.amount() + price));
        }
        owner.setDirty();
    }

    void finishService(Customer.Party party) {
        if (servedBills.remove(party) != null) owner.setDirty();
    }

    /** Customers are transient. Settle their saved dishes once the table's chunk can be checked. */
    private void recoverInterruptedBills(ServerLevel level) {
        for (Iterator<ServedBill> it = interruptedBills.iterator(); it.hasNext();) {
            ServedBill bill = it.next();
            BlockPos surface = null;
            boolean unloaded = false;
            if (area != null) for (BlockPos pos : bill.surfaces()) {
                if (!area.isInside(pos)) continue;
                if (!level.isLoaded(pos)) {
                    unloaded = true;
                } else if (level.getBlockState(pos).is(OneMoreCore.SURFACES)) {
                    surface = pos;
                    break;
                }
            }
            if (surface == null && unloaded) continue;
            long amount = bill.amount();
            if (surface != null) {
                int onTable = (int) Math.min(amount, Integer.MAX_VALUE - coins.getOrDefault(surface, 0));
                addCoins(surface, onTable);
                amount -= onTable;
            }
            payRegister(level, amount);
            it.remove();
            owner.setDirty();
        }
    }

    /** A combo: dish ids and the price of all of them together. */
    public record Combo(String id, List<String> dishes, int price) {}
    /** Dishes that the players added, ids "custom:<item id>". */
    final List<Menu.Entry> custom = new ArrayList<>();
    BlockPos delivery;
    final List<Delivery> deliveries = new ArrayList<>();
    private long lastDispatchTick = Long.MIN_VALUE;
    private long deliveryBudgetTick = Long.MIN_VALUE;
    private int deliveryBudget;
    /** Dirty serving surfaces and their dirty cells, see {@link Dirt}. */
    final Map<BlockPos, Long> dirt = new HashMap<>();

    // Not saved: a restaurant is closed after a world load, and the day starts again.
    boolean open;
    int toSpawn, spawnTimer, served, lost, earned;
    long penalties;
    final Set<UUID> ready = new HashSet<>();
    private static final Map<Player, Integer> LAST_READY_CHANGE = new WeakHashMap<>();
    private boolean readyCheckActive;
    private UUID readyInitiator;
    /** Ticks until the day starts, after every player is ready. 0: no countdown. */
    int countdown;
    List<Seat> seats = List.of();
    /** The seats, grouped by table. One party sits at a table. */
    List<List<Seat>> tables = List.of();
    private final Map<BlockPos, Set<BlockPos>> tableSurfaces = new HashMap<>();
    private boolean layoutDirty = true;
    private Set<ChunkPos> loadedLayoutChunks = Set.of();
    /** Group members that wait to walk in. They are in {@link #customers} already. */
    final List<Customer> arriving = new ArrayList<>();
    int arriveTimer;
    final List<Customer> customers = new ArrayList<>();

    Restaurant(Restaurants owner, BlockPos sign) {
        this.owner = owner;
        this.sign = sign;
    }

    public boolean isOpen() { return open; }
    public int day() { return day; }
    public int register() { return register; }
    public BoundingBox area() { return area; }
    public BlockPos spawn() { return spawn; }
    public BlockPos delivery() { return delivery; }

    public void addMoney(int amount) {
        register = (int) Math.min(Integer.MAX_VALUE, (long) register + amount);
        owner.setDirty();
    }

    Ingredients.Availability ingredientAvailability() {
        Ingredients index = Ingredients.current();
        if (ingredientAvailability == null || ingredientIndex != index) {
            ingredientIndex = index;
            ingredientAvailability = index.availability(unlockedIngredients);
        }
        return ingredientAvailability;
    }

    public Set<ResourceLocation> unlockedIngredients() {
        return Set.copyOf(unlockedIngredients);
    }

    public boolean orderable(Menu.Entry dish) {
        return isOn(dish) && ingredientsAvailable(dish);
    }

    boolean ingredientsAvailable(Menu.Entry dish) {
        var availability = ingredientAvailability();
        return Ingredients.variants(dish).anyMatch(stack -> availability.dish(Ingredients.id(stack)));
    }

    ItemStack orderableVariant(Menu.Entry dish, RandomSource random) {
        if (dish.tag() == null) return dish.stack().copy();
        var availability = ingredientAvailability();
        var available = Ingredients.variants(dish).filter(stack -> availability.dish(Ingredients.id(stack)));
        if (random == null) return available.findFirst().orElse(ItemStack.EMPTY);
        List<ItemStack> variants = available.toList();
        return variants.isEmpty() ? ItemStack.EMPTY : variants.get(random.nextInt(variants.size()));
    }

    /** Configured dishes, automatically discovered dishes, and dishes added by players. */
    public List<Menu.Entry> dishes() {
        List<Menu.Entry> all = new ArrayList<>(Menu.entries);
        Set<String> ids = new HashSet<>();
        Menu.entries.forEach(entry -> ids.add(entry.id()));
        for (Menu.Entry entry : Ingredients.current().automatic) {
            if (!ids.add(entry.id())) continue;
            Integer price = dishPrices.get(entry.id());
            all.add(price == null ? entry : new Menu.Entry(entry.id(), entry.stack(), null,
                    Math.min(price, Config.MAX_DISH_PRICE.get()), entry.exact(), entry.category()));
        }
        all.addAll(custom);
        return all;
    }

    public Menu.Entry dish(String id) {
        for (Menu.Entry e : dishes()) if (e.id().equals(id)) return e;
        return null;
    }

    public boolean isOn(Menu.Entry e) {
        return !off.contains(e.id());
    }

    /** Puts a dish on the menu or takes it off. Customers order only dishes that are on the menu. */
    boolean toggle(String id) {
        if (dish(id) == null) return false;
        if (!off.remove(id)) off.add(id);
        owner.setDirty();
        return true;
    }

    /** Most dishes that the players can add to one restaurant. */
    static final int MAX_CUSTOM = 64;

    /** Result of {@link #addDish}. */
    public enum Add { OK, NOT_FOOD, ALREADY, PRICE, FULL }

    /**
     * Adds any food or drink item as a dish with a price from 1 to maxDishPrice. The id is "custom:" and the item id.
     * Food is an item with a food component, or an item in the c:foods or c:drinks tag.
     */
    Add addDish(net.minecraft.world.item.Item item, int price) {
        ItemStack stack = new ItemStack(item);
        if (stack.isEmpty() || !isFood(stack)) return Add.NOT_FOOD;
        if (price < 1 || price > Config.MAX_DISH_PRICE.get()) return Add.PRICE;
        String id = "custom:" + BuiltInRegistries.ITEM.getKey(item);
        if (dish(id) != null) return Add.ALREADY;
        if (custom.size() >= MAX_CUSTOM) return Add.FULL;
        custom.add(new Menu.Entry(id, stack, null, price, false));
        owner.setDirty();
        return Add.OK;
    }

    public static boolean isFood(ItemStack stack) {
        return stack.has(net.minecraft.core.component.DataComponents.FOOD)
                || stack.getUseAnimation() == net.minecraft.world.item.UseAnim.DRINK
                || stack.is(net.minecraft.tags.ItemTags.create(net.minecraft.resources.ResourceLocation.parse("c:foods")))
                || stack.is(net.minecraft.tags.ItemTags.create(net.minecraft.resources.ResourceLocation.parse("c:drinks")));
    }

    boolean priceEditable(Menu.Entry entry) {
        return Menu.byId(entry.id()) == null;
    }

    /** Changes a discovered or custom dish's price. Configured prices are edited in menu.json. */
    boolean setPrice(String id, int price) {
        if (price < 1 || price > Config.MAX_DISH_PRICE.get()) return false;
        Menu.Entry dish = dish(id);
        if (dish == null || !priceEditable(dish)) return false;
        for (int i = 0; i < custom.size(); i++) {
            Menu.Entry e = custom.get(i);
            if (!e.id().equals(id)) continue;
            custom.set(i, new Menu.Entry(e.id(), e.stack(), null, price, false));
            owner.setDirty();
            return true;
        }
        dishPrices.put(id, price);
        owner.setDirty();
        return true;
    }

    /** The kind of a dish in this restaurant. */
    public String kind(Menu.Entry e) {
        return kinds.getOrDefault(e.id(), e.category());
    }

    boolean setKind(String id, String kind) {
        Menu.Entry e = dish(id);
        if (e == null || !Menu.CATEGORIES.contains(kind)) return false;
        if (kind.equals(e.category())) kinds.remove(id); else kinds.put(id, kind);
        owner.setDirty();
        return true;
    }

    /** Every customer's order must fit on one tray. */
    public static final int MAX_ORDER_ITEMS = TrayBlockEntity.SIZE;
    static final int MAX_COMBOS = 16;
    private static final int LEGACY_COMBO_PLACES = 4;
    static final int COMBO_PRICE_MULTIPLIER = 4;

    /** Result of {@link #saveCombo}. */
    public enum AddCombo { OK, DISHES, PRICE, FULL }

    /** The kinds a combo place can ask for as a whole ("kind:drink": any drink on the menu). */
    static final List<String> KINDS = List.of("main", "drink", "starter", "dessert");

    /** The kind of a combo place "kind:<kind>", or null for a place that names one dish. */
    static String slotKind(String slot) {
        return slot.startsWith("kind:") && KINDS.contains(slot.substring(5)) ? slot.substring(5) : null;
    }

    /** Creates a combo, or replaces the places and price of an existing combo. */
    AddCombo saveCombo(String id, List<String> places, int price) {
        List<String> dishIds = places.stream().filter(place -> slotKind(place) == null).toList();
        if (places.size() < 2 || places.size() > MAX_ORDER_ITEMS || dishIds.stream().distinct().count() != dishIds.size()
                || dishIds.stream().anyMatch(dishId -> dish(dishId) == null)) return AddCombo.DISHES;
        if (price < 1 || price > Config.MAX_DISH_PRICE.get() * COMBO_PRICE_MULTIPLIER) return AddCombo.PRICE;
        if (id != null) {
            Combo existing = combo(id);
            if (existing == null) return AddCombo.DISHES;
            combos.set(combos.indexOf(existing), new Combo(id, List.copyOf(places), price));
        } else {
            if (combos.size() >= MAX_COMBOS) return AddCombo.FULL;
            int n = 1;
            while (combo("combo:" + n) != null) n++;
            combos.add(new Combo("combo:" + n, List.copyOf(places), price));
        }
        owner.setDirty();
        return AddCombo.OK;
    }

    Combo combo(String id) {
        for (Combo c : combos) if (c.id().equals(id)) return c;
        return null;
    }

    boolean removeCombo(String id) {
        boolean removed = combos.removeIf(c -> c.id().equals(id));
        if (removed) owner.setDirty();
        return removed;
    }

    boolean setComboPrice(String id, int price) {
        if (price < 1 || price > Config.MAX_DISH_PRICE.get() * COMBO_PRICE_MULTIPLIER) return false;
        for (int i = 0; i < combos.size(); i++) {
            if (!combos.get(i).id().equals(id)) continue;
            combos.set(i, new Combo(id, combos.get(i).dishes(), price));
            owner.setDirty();
            return true;
        }
        return false;
    }

    /** A combo can be ordered when every one of its places can: a dish that can be ordered, or a kind with such a dish. */
    boolean canOrder(Combo c) {
        if (c.dishes().size() < 2 || c.dishes().size() > MAX_ORDER_ITEMS) return false;
        for (String id : c.dishes()) {
            String kind = slotKind(id);
            if (kind != null) {
                if (ofKind(kind).isEmpty()) return false;
                continue;
            }
            Menu.Entry e = dish(id);
            if (e == null || !orderable(e)) return false;
        }
        return true;
    }

    /** The dishes of a kind that customers can order now. */
    List<Menu.Entry> ofKind(String kind) {
        return dishes().stream().filter(e -> orderable(e) && kind(e).equals(kind)).toList();
    }

    /** The serving surfaces that touch the one at pos (one table), inside the area. */
    Set<BlockPos> tableAt(ServerLevel level, BlockPos pos) {
        if (area == null) return new HashSet<>();
        Set<BlockPos> cached = layoutDirty ? null : tableSurfaces.get(pos);
        if (cached != null && layoutChunksMatch(level)) return new HashSet<>(cached);
        return connectedTable(level, area, pos, cuts);
    }

    /** The places where two touching serving surfaces are not joined: they belong to two tables. */
    final TableCuts cuts = new TableCuts();

    /**
     * Splits two touching tables at the edge of a surface towards dir, or joins them again (like Create's mechanical
     * crafters). False if no serving surface of the area lies there.
     */
    boolean toggleCut(ServerLevel level, BlockPos pos, Direction dir) {
        BlockPos next = pos.relative(dir);
        if (area == null || !area.isInside(pos) || !area.isInside(next) || !level.getBlockState(pos).is(OneMoreCore.SURFACES)
                || !level.getBlockState(next).is(OneMoreCore.SURFACES)) return false;
        cuts.toggle(pos, dir);
        owner.setDirty();
        invalidateLayout();
        numberTables(level);
        return true;
    }

    static Set<BlockPos> connectedTable(Level level, BoundingBox area, BlockPos pos, TableCuts cuts) {
        Set<BlockPos> table = new HashSet<>();
        if (!area.isInside(pos) || !level.isLoaded(pos) || !level.getBlockState(pos).is(OneMoreCore.SURFACES)) return table;
        ArrayDeque<BlockPos> todo = new ArrayDeque<>(List.of(pos.immutable()));
        table.add(pos.immutable());
        while (!todo.isEmpty()) {
            BlockPos p = todo.pop();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = p.relative(dir);
                if (!table.contains(next) && !cuts.cut(p, dir) && area.isInside(next) && level.isLoaded(next)
                        && level.getBlockState(next).is(OneMoreCore.SURFACES) && table.add(next)) todo.push(next);
            }
        }
        return table;
    }

    public boolean isTableOn(Set<BlockPos> table) {
        for (BlockPos p : table) if (offTables.contains(p)) return false;
        return true;
    }

    /** Turns the table at pos off or on. False if pos is not a serving surface in the area. */
    boolean toggleTable(ServerLevel level, BlockPos pos) {
        Set<BlockPos> table = tableAt(level, pos);
        if (table.isEmpty()) return false;
        if (isTableOn(table)) offTables.add(pos.immutable()); else offTables.removeAll(table);
        owner.setDirty();
        return true;
    }

    /** A party left the table of these seats: the table stays empty for tablePauseTicks. */
    void freed(ServerLevel level, BlockPos surface) {
        freedAt.put(surface, level.getGameTime());
    }

    /** Removes a dish that a player added. Orders that customers already have are not changed. */
    boolean removeDish(String id) {
        boolean removed = custom.removeIf(e -> e.id().equals(id));
        if (removed) {
            off.remove(id);
            kinds.remove(id);
            // A combo without one of its dishes is no combo any more.
            combos.removeIf(c -> c.dishes().contains(id));
            owner.setDirty();
        }
        return removed;
    }

    /** Result of {@link #unlockIngredient}. */
    public enum Unlock { OK, ALREADY, COST, UNKNOWN }

    public Unlock unlockIngredient(ResourceLocation item) {
        if (unlockedIngredients.contains(item)) return Unlock.ALREADY;
        int cost = Ingredients.current().unlockCost(item);
        if (cost < 0) return Unlock.UNKNOWN;
        if (ingredientAvailability().item(item)) return Unlock.ALREADY;
        if (register < cost) return Unlock.COST;
        register -= cost;
        unlockedIngredients.add(item);
        ingredientAvailability = null;
        owner.setDirty();
        return Unlock.OK;
    }

    void setArea(BoundingBox box) {
        area = box;
        invalidateLayout();
        resetReadyCheck();
        owner.setDirty();
    }
    void setSpawn(BlockPos pos) { spawn = pos; owner.setDirty(); }
    void setDelivery(BlockPos pos) { delivery = pos; owner.setDirty(); }

    /** A paid shop order that is on its way. */
    static final class Delivery {
        final UUID id;
        final List<ItemStack> items;
        final BlockPos destination;
        int ticks;
        long fallbackAt;
        long retryAt;
        UUID courierId;
        boolean standIn;

        Delivery(UUID id, List<ItemStack> items, int ticks, BlockPos destination) {
            this.id = id;
            this.items = new ArrayList<>(items);
            this.ticks = ticks;
            this.destination = destination;
        }

        BlockPos target(BlockPos sign) {
            return destination == null ? sign : destination;
        }
    }

    /** Result of {@link #buy}. */
    public enum Buy { OK, COST, NO_DELIVERY }

    /** Orders a whole cart, or nothing when the register cannot pay all of it. Counts are 1..64 per line. */
    public Buy buyAll(Map<Shop.Entry, Integer> cart) {
        if (delivery == null) return Buy.NO_DELIVERY;
        long total = 0;
        for (var e : cart.entrySet()) total += (long) e.getKey().price() * e.getValue();
        if (total > register) return Buy.COST;
        if (cart.isEmpty()) return Buy.OK;
        List<ItemStack> items = new ArrayList<>(cart.size());
        for (var e : cart.entrySet()) {
            ItemStack stack = e.getKey().stack();
            if (!stack.isEmpty()) items.add(stack.copyWithCount(stack.getCount() * e.getValue()));
        }
        if (items.isEmpty()) return Buy.OK;
        register -= (int) total;
        deliveries.add(new Delivery(UUID.randomUUID(), items, deliveryTicks(), delivery.immutable()));
        owner.setDirty();
        return Buy.OK;
    }

    public Buy buy(Shop.Entry entry) {
        return buyAll(Map.of(entry, 1));
    }

    /** The item handler of the block, or null. */
    static IItemHandler container(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        return handler == null && level.getBlockEntity(pos) instanceof Container c ? new InvWrapper(c) : handler;
    }

    private void tickDeliveries(ServerLevel level) {
        long now = level.getGameTime();
        for (Delivery delivery : List.copyOf(deliveries)) {
            if (delivery.ticks > 0) {
                delivery.ticks--;
                owner.setDirty();
                if (delivery.ticks > 0) continue;
            }
            if (delivery.fallbackAt == 0) {
                delivery.fallbackAt = now + Courier.ROUTE_TIMEOUT;
                owner.setDirty();
            }
            if (now < delivery.retryAt) continue;
            BlockPos target = delivery.target(sign);
            boolean loaded = level.isLoaded(target);
            if (!loaded || now >= delivery.fallbackAt) useStandIn(level, delivery);
            if (!loaded) {
                delivery.retryAt = now + 20;
                continue;
            }
            if (!delivery.standIn) {
                if (delivery.courierId != null && level.getEntity(delivery.courierId) instanceof Courier) continue;
                if (lastDispatchTick == now) continue;
                lastDispatchTick = now;
                delivery.courierId = Courier.dispatch(level, this, delivery);
                owner.setDirty();
                if (delivery.courierId != null) continue;
                useStandIn(level, delivery);
            }
            Courier.deliver(level, this, delivery, target, delivery.destination != null, null);
        }
    }

    private void useStandIn(ServerLevel level, Delivery delivery) {
        if (delivery.standIn) return;
        delivery.standIn = true;
        if (delivery.courierId != null && level.getEntity(delivery.courierId) instanceof Courier courier)
            courier.discard();
        delivery.courierId = null;
        owner.setDirty();
    }

    Delivery courierOrder(UUID orderId, UUID courierId) {
        for (Delivery delivery : deliveries)
            if (delivery.id.equals(orderId) && courierId.equals(delivery.courierId)) return delivery;
        return null;
    }

    int deliveryStacksLeft(long now) {
        if (now != deliveryBudgetTick) {
            deliveryBudgetTick = now;
            deliveryBudget = 16;
        }
        return deliveryBudget;
    }

    boolean takeDeliveryStack(long now) {
        if (deliveryStacksLeft(now) == 0) return false;
        deliveryBudget--;
        return true;
    }

    boolean deliveryProgress(Delivery delivery) {
        if (delivery.items.isEmpty()) deliveries.remove(delivery);
        owner.setDirty();
        return delivery.items.isEmpty();
    }

    /** Takes up to max money from the register, like one click on a storage drawer. */
    int take(int max) {
        int n = Math.min(max, register);
        register -= n;
        owner.setDirty();
        return n;
    }

    /** Puts money into the register. Returns how much fitted; the register never wraps past Integer.MAX_VALUE. */
    int deposit(int amount) {
        int n = (int) Math.min(amount, (long) Integer.MAX_VALUE - register);
        if (n != 0) {
            register += n;
            owner.setDirty();
        }
        return n;
    }

    /** Day, register, unlocks and paid orders keep their progress when the sign is broken. */
    CompoundTag progress() {
        CompoundTag tag = save();
        for (String key : List.of("sign", "area", "spawn", "delivery", "dirt", "dirt_start", "tables_off", "table_items", "served_bills", "screens", "screen_modes", "cards", "table_cuts_east", "table_cuts_south")) tag.remove(key);
        return tag;
    }

    boolean hasProgress() {
        return day > 1 || register > 0 || !unlockedIngredients.isEmpty() || !deliveries.isEmpty()
                || !off.isEmpty() || !custom.isEmpty() || !kinds.isEmpty() || !dishPrices.isEmpty()
                || !combos.isEmpty() || !upgrades.isEmpty() || !coins.isEmpty() || !servedBills.isEmpty() || !interruptedBills.isEmpty();
    }

    void restore(CompoundTag progress) {
        progress.putLong("sign", sign.asLong());
        Restaurant from = load(owner, progress);
        day = from.day;
        register = from.register;
        unlockedIngredients.clear();
        unlockedIngredients.addAll(from.unlockedIngredients);
        ingredientAvailability = null;
        off.addAll(from.off);
        custom.addAll(from.custom);
        kinds.putAll(from.kinds);
        dishPrices.putAll(from.dishPrices);
        combos.addAll(from.combos);
        upgrades.putAll(from.upgrades);
        favourites.addAll(from.favourites);
        deliveries.addAll(from.deliveries);
        owner.setDirty();
    }

    /** A player clicked the sign of the closed restaurant: the player is ready. */
    void requestOpen(ServerLevel level, Player player) {
        setReady(level, player, true);
    }

    /** Why the day cannot start, as a lang key suffix, or null. */
    private String problem(ServerLevel level) {
        if (area == null) return "no_area";
        if (spawn == null) return "no_spawn";
        if (dishes().stream().noneMatch(this::orderable)) return "no_menu";
        if (scanSeats(level).isEmpty() || Config.DAY_LENGTH.get() == 0 && !hasEnabledTable()) return "no_seats";
        return null;
    }

    private List<ServerPlayer> requiredPlayers(ServerLevel level) {
        BoundingBox near = area.inflatedBy(8);
        return level.players().stream().filter(p -> area.isInside(p.blockPosition())
                || p.getUUID().equals(readyInitiator) && near.isInside(p.blockPosition())).toList();
    }

    /** Marks the player ready or not ready. An outside sign clicker also counts for the check they start. */
    void setReady(ServerLevel level, Player player, boolean on) {
        UUID id = player.getUUID();
        if (open || countdown > 0 || ready.contains(id) == on) return;
        int now = level.getServer().getTickCount();
        if (now - LAST_READY_CHANGE.getOrDefault(player, now - 10) < 10) return;
        LAST_READY_CHANGE.put(player, now);
        if (!on) {
            ready.remove(id);
            return;
        }
        String problem = problem(level);
        if (problem != null) {
            player.displayClientMessage(Component.translatable("onemorecore.open." + problem), true);
            return;
        }
        if (!readyCheckActive) {
            readyCheckActive = true;
            readyInitiator = id;
        }
        Set<UUID> needed = new HashSet<>();
        for (ServerPlayer p : requiredPlayers(level)) needed.add(p.getUUID());
        needed.add(id);
        ready.add(id);
        ready.retainAll(needed);
        if (Config.READY_CHECK.get() && ready.size() < needed.size()) {
            BoundingBox near = area.inflatedBy(16);
            Component message = Component.translatable("onemorecore.open.ready", player.getDisplayName(), ready.size(), needed.size());
            for (ServerPlayer p : level.players()) if (near.isInside(p.blockPosition())) p.displayClientMessage(message, true);
            level.playSound(null, sign, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1, 1.4f);
            return;
        }
        countdown = needed.size() <= 1 ? 0 : Config.READY_COUNTDOWN.get();
        if (countdown == 0) startDay(level);
    }

    private void resetReadyCheck() {
        ready.clear();
        readyCheckActive = false;
        readyInitiator = null;
        countdown = 0;
    }

    private void stopCountdown(ServerLevel level) {
        countdown = 0;
        BoundingBox near = area.inflatedBy(16);
        for (ServerPlayer p : level.players())
            if (near.isInside(p.blockPosition())) p.displayClientMessage(Component.translatable("onemorecore.open.stopped"), true);
        level.playSound(null, sign, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 1, 0.8f);
    }

    /** Update the check as players come and go; revalidate the layout before opening. */
    private void tickReadyCheck(ServerLevel level) {
        Set<UUID> required = new HashSet<>();
        for (ServerPlayer p : requiredPlayers(level)) required.add(p.getUUID());
        if (required.isEmpty()) { resetReadyCheck(); return; }
        ready.retainAll(required);
        if (Config.READY_CHECK.get() && ready.size() < required.size()) {
            if (countdown > 0) stopCountdown(level);
            return;
        }
        if (countdown > 0) {
            if (countdown % 20 == 0) level.playSound(null, sign, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 1, 1.2f);
            if (--countdown > 0) return;
        } else if (required.size() > 1 && Config.READY_COUNTDOWN.get() > 0) {
            countdown = Config.READY_COUNTDOWN.get();
            return;
        }
        String problem = problem(level);
        if (problem == null) { startDay(level); return; }
        resetReadyCheck();
        BoundingBox near = area.inflatedBy(16);
        for (ServerPlayer p : level.players())
            if (near.isInside(p.blockPosition())) p.displayClientMessage(Component.translatable("onemorecore.open." + problem), true);
    }

    /** Starts the service. The seats are scanned already. */
    private void startDay(ServerLevel level) {
        resetReadyCheck();
        arriving.clear();
        open = true;
        served = lost = earned = 0;
        penalties = 0;
        serviceLeft = Config.DAY_LENGTH.get();
        // With a day length the number of customers is open: they come until the service ends.
        toSpawn = serviceLeft > 0 ? Integer.MAX_VALUE
                : (int) Math.min(Integer.MAX_VALUE, Config.CUSTOMERS_BASE.get() + (long) Config.CUSTOMERS_PER_DAY.get() * (day - 1));
        spawnTimer = 40;
        // The clients show the day's start themselves (DayFade): a dark fade with "Day N" and a chime.
    }

    void tick(ServerLevel level) {
        serverLevel = level;
        // Hidden chunks can unload entities without calling Customer.remove or a second tracking callback.
        for (Customer c : List.copyOf(customers)) if (c.isRemoved()) c.removeFromRestaurant();
        if (level.getGameTime() % 10 == 5) sendScreens(level);
        tickDeliveries(level);
        if (!level.isLoaded(sign)) return;
        BlockState state = level.getBlockState(sign);
        if (!state.is(OneMoreCore.OPEN_SIGN)) return;
        recoverInterruptedBills(level);
        if (state.getValue(OpenSignBlock.OPEN) != open) level.setBlockAndUpdate(sign, state.setValue(OpenSignBlock.OPEN, open));
        if (!open && readyCheckActive) tickReadyCheck(level);
        if (area != null && level.getGameTime() % 10 == 0) sendStatus(level);
        if (area != null && level.getGameTime() % 100 == Math.floorMod(sign.asLong(), 100)) numberTables(level);
        if (!open) return;
        scanSeats(level);
        if (!hasEnabledTable()) {
            toSpawn = 0;
            serviceLeft = 0;
            interruptedBills.addAll(servedBills.values());
            servedBills.clear();
            recoverInterruptedBills(level);
            endDay(level, false);
            for (Customer c : List.copyOf(customers)) c.discard();
            return;
        }
        if (serviceLeft > 0 && --serviceLeft == 0) {
            // The service is over: no new parties. Parties that are on their way and the customers inside finish, then the day ends.
            toSpawn = 0;
        }
        if (!arriving.isEmpty() && --arriveTimer <= 0) {
            Customer customer = arriving.removeFirst();
            if (!level.addFreshEntity(customer)) rejectArrival(level, customer);
            arriveTimer = 10;
        }
        if (toSpawn > 0) {
            if (--spawnTimer > 0) return;
            int size = 1;
            if (Config.MAX_GROUP.get() > 1 && level.random.nextDouble() < Config.GROUP_CHANCE.get())
                size = groupSize(level.random.nextDouble(), Config.MAX_GROUP.get());
            if (spawnParty(level, size) > 0) spawnTimer = nextInterval(level);
        }
        if (toSpawn == 0 && customers.isEmpty()) endDay(level, false);
    }

    /** A rejected join never counts as a guest who left without food. */
    private void rejectArrival(ServerLevel level, Customer customer) {
        Customer.Party party = customer.party();
        customers.remove(customer);
        party.members.remove(customer);
        if (party.members.isEmpty()) return;
        Set<BlockPos> table = tableAt(level, party.members.getFirst().getSurface());
        if (party.members.stream().allMatch(c -> table.contains(c.getSurface()) && isSeat(level, new Seat(c.seat, c.getSurface())))) return;
        party.settled = true;
        for (Customer c : List.copyOf(party.members)) {
            if (arriving.remove(c)) {
                customers.remove(c);
                party.members.remove(c);
            } else c.discard();
        }
    }

    /** Size of a group (2 to max) from a uniform u in [0, 1): size k has weight 2^-(k-2), so pairs are the most common. */
    static int groupSize(double u, int max) {
        double total = 0;
        for (int k = 2; k <= max; k++) total += Math.pow(0.5, k - 2);
        double x = u * total;
        for (int k = 2; k < max; k++) {
            x -= Math.pow(0.5, k - 2);
            if (x < 0) return k;
        }
        return max;
    }

    /** The next pause between arrivals: spawnIntervalTicks, 6 percent shorter per day down to 40 percent, then 60 to 140 percent of that. */
    int nextInterval(ServerLevel level) {
        double base = Config.SPAWN_INTERVAL.get() * Math.max(0.4, 1 - 0.06 * (day - 1));
        return Math.max(1, (int) Math.round(base * (0.6 + 0.8 * level.random.nextDouble())));
    }

    void endDay(ServerLevel level, boolean failed) {
        if (!open) return;
        open = false;
        if (failed) {
            toSpawn = 0;
            // A customer whose removal failed the day is removed already; a second discard sends a second leave event.
            for (Customer c : List.copyOf(customers)) {
                if (c.isRemoved()) c.removeFromRestaurant();
                else c.discard();
            }
        }
        var message = Component.translatable(failed ? "onemorecore.day.failed" : "onemorecore.day.end", day, served, lost, earned);
        if (penalties > 0) message.append(Component.translatable("onemorecore.day.penalty", penalties));
        announce(level, Component.translatable(failed ? "onemorecore.day.failed.title" : "onemorecore.day.end.title", day), message);
        level.playSound(null, sign, failed ? SoundEvents.ANVIL_LAND : SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, failed ? 0.5f : 1, 1);
        if (!failed && day < Integer.MAX_VALUE) day++;
        owner.setDirty();
    }

    /**
     * Makes a party of up to {@code size} customers for one free table. The members walk in one after the other.
     * Each member counts as one customer of the day. Returns the number of members; 0 if no table is free.
     */
    int spawnParty(ServerLevel level, int size) {
        Customer.Party party = new Customer.Party();
        List<Seat> chosen = freeSeats(level, Math.min(size, toSpawn));
        // The kind of guests: a family is a group of 3 or more; 1 or 2 can be a VIP or, when the restaurant has
        // favourite dishes, a regular.
        double roll = level.random.nextDouble();
        if (chosen.size() >= 3) party.type = roll < Config.FAMILY_CHANCE.get() ? Customer.Type.FAMILY : Customer.Type.NORMAL;
        else if (roll < Config.VIP_CHANCE.get()) party.type = Customer.Type.VIP;
        else if (!favourites.isEmpty() && roll < Config.VIP_CHANCE.get() + Config.REGULAR_CHANCE.get()) party.type = Customer.Type.REGULAR;
        for (Seat seat : chosen) {
            List<String> ids = new ArrayList<>();
            List<Customer.Line> order = makeOrder(level.random, party.type, ids);
            if (order.isEmpty()) { toSpawn = 0; break; }
            Customer c = new Customer(OneMoreCore.CUSTOMER.get(), level);
            c.setup(this, seat, order, party);
            c.dishIds.addAll(ids);
            c.setType(party.type);
            c.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
            arriving.add(c);
            customers.add(c);
            toSpawn--;
        }
        return party.members.size();
    }

    /** Dish ids that parties enjoyed (served with more than half of the patience left); regulars order them. */
    final Set<String> favourites = new HashSet<>();

    /** The price that a dish is worth: its nutrition plus its saturation; a drink without food values is worth 3. */
    static int suggestedPrice(Menu.Entry e) {
        ItemStack stack = e.stack() != null ? e.stack() : e.pick(net.minecraft.util.RandomSource.create(0));
        return worth(stack);
    }

    static int worth(ItemStack stack) {
        var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        return food == null ? 3 : Math.max(1, food.nutrition() + Math.round(food.saturation()));
    }

    /**
     * How much a price above the worth puts customers off. Up to 1.5 times the worth: no effect. Above it the order weight
     * falls to 1 / (1 + 2 (r - 1.5)) and the tip to (1 - (r - 1.5)) of normal, where r is price / worth.
     */
    static double orderWeight(Menu.Entry e) {
        if (!Config.PRICE_EFFECT.get()) return 1;
        double r = e.price() / (double) suggestedPrice(e);
        return r <= 1.5 ? 1 : 1 / (1 + 2 * (r - 1.5));
    }

    static int tipPercent(Menu.Entry e) {
        if (!Config.PRICE_EFFECT.get()) return 100;
        double r = e.price() / (double) suggestedPrice(e);
        return r <= 1.5 ? 100 : (int) Math.round(100 * Math.max(0, 1 - (r - 1.5)));
    }

    private Menu.Entry weighted(List<Menu.Entry> of, RandomSource random) {
        double total = 0;
        for (Menu.Entry e : of) total += orderWeight(e);
        double x = random.nextDouble() * total;
        for (Menu.Entry e : of) if ((x -= orderWeight(e)) < 0) return e;
        return of.get(of.size() - 1);
    }

    private Customer.Line line(Menu.Entry e, ItemStack stack, int price) {
        return new Customer.Line(stack, price, e.exact(), tipPercent(e), kind(e).equals("drink"));
    }

    private List<Customer.Line> makeOrder(RandomSource random, Customer.Type type, List<String> ids) {
        List<Menu.Entry> available = dishes().stream().filter(this::orderable).toList();
        List<Customer.Line> order = new ArrayList<>();
        if (available.isEmpty()) return order;
        // A combo: all its dishes; the combo price is split over the lines (the first line takes the rest of the division).
        List<Combo> orderable = combos.stream().filter(this::canOrder).toList();
        if (!orderable.isEmpty() && random.nextDouble() < Config.COMBO_CHANCE.get()) {
            Combo c = orderable.get(random.nextInt(orderable.size()));
            int share = c.price() / c.dishes().size(), rest = c.price() - share * c.dishes().size();
            for (int i = 0; i < c.dishes().size(); i++) {
                String kind = slotKind(c.dishes().get(i));
                // A kind place: one dish of that kind, chosen like a single order (prices weigh in), not one already taken.
                Menu.Entry e = kind == null ? dish(c.dishes().get(i))
                        : weighted(ofKind(kind).stream().filter(x -> !ids.contains(x.id())).toList().isEmpty() ? ofKind(kind)
                                : ofKind(kind).stream().filter(x -> !ids.contains(x.id())).toList(), random);
                ItemStack stack = orderableVariant(e, random);
                if (stack.isEmpty()) return new ArrayList<>();
                order.add(new Customer.Line(stack, share + (i == 0 ? rest : 0), e.exact(), 100, kind(e).equals("drink")));
                ids.add(e.id());
            }
            return order;
        }
        // Otherwise one dish of each of up to maxOrderItems different kinds: no two main dishes in one order.
        Map<String, List<Menu.Entry>> byKind = new java.util.LinkedHashMap<>();
        for (Menu.Entry e : available) byKind.computeIfAbsent(kind(e), k -> new ArrayList<>()).add(e);
        List<String> kinds = new ArrayList<>(byKind.keySet());
        // A main dish comes first when there is one; the other kinds in random order.
        Util.shuffle(kinds, random);
        if (kinds.remove("main")) kinds.add(0, "main");
        int n = Math.min(kinds.size(), 1 + random.nextInt(Math.min(MAX_ORDER_ITEMS, Config.MAX_ORDER_ITEMS.get())));
        List<String> chosen = new ArrayList<>(kinds.subList(0, n));
        // A family takes a dessert too.
        if (type == Customer.Type.FAMILY && byKind.containsKey("dessert") && !chosen.contains("dessert")) {
            if (chosen.size() == MAX_ORDER_ITEMS) chosen.removeLast();
            chosen.add("dessert");
        }
        // A regular orders one of its favourites.
        Menu.Entry favourite = null;
        if (type == Customer.Type.REGULAR) {
            List<Menu.Entry> fav = available.stream().filter(e -> favourites.contains(e.id())).toList();
            if (!fav.isEmpty()) {
                favourite = fav.get(random.nextInt(fav.size()));
                if (!chosen.contains(kind(favourite))) {
                    if (chosen.size() == MAX_ORDER_ITEMS) chosen.removeLast();
                    chosen.addFirst(kind(favourite));
                }
            }
        }
        for (String k : chosen) {
            Menu.Entry e = favourite != null && kind(favourite).equals(k) ? favourite : weighted(byKind.get(k), random);
            ItemStack stack = orderableVariant(e, random);
            if (stack.isEmpty()) continue;
            order.add(line(e, stack, e.price()));
            ids.add(e.id());
        }
        return order;
    }

    /**
     * Up to {@code wanted} seats of one table where no party sits. Fewer, if no free table has that many clean seats.
     * Empty, if no table is free.
     */
    private List<Seat> freeSeats(ServerLevel level, int wanted) {
        // Like PlateUp: a table is free when nobody sits there, no part of it is dirty or off, and its last party left
        // at least tablePauseTicks ago. The party takes the smallest free table that has room for it.
        List<List<Seat>> free = new ArrayList<>();
        int most = 0;
        long now = level.getGameTime();
        scanSeats(level);
        for (List<Seat> table : tables) {
            Set<BlockPos> surfaces = tableSurfaces.get(table.getFirst().surface());
            if (!isTableOn(surfaces) || customers.stream().anyMatch(c -> surfaces.contains(c.getSurface())
                    || table.stream().anyMatch(s -> s.seat().equals(c.seat)))) continue;
            if (surfaces.stream().anyMatch(p -> dirt.containsKey(p) || hasTableItems(p)
                    || now - freedAt.getOrDefault(p, Long.MIN_VALUE / 2) < Config.TABLE_PAUSE.get())) continue;
            free.add(table);
            most = Math.max(most, table.size());
        }
        int size = Math.min(wanted, most);
        free.removeIf(t -> t.size() < size);
        if (free.isEmpty()) return List.of();
        int smallest = free.stream().mapToInt(List::size).min().orElseThrow();
        free.removeIf(t -> t.size() != smallest);
        List<Seat> table = new ArrayList<>(free.get(level.random.nextInt(free.size())));
        Util.shuffle(table, level.random);
        return table.subList(0, size);
    }

    private boolean hasEnabledTable() {
        return tables.stream().anyMatch(t -> isTableOn(tableSurfaces.get(t.getFirst().surface())));
    }

    private static boolean isSeat(ServerLevel level, Seat s) {
        return level.isLoaded(s.seat()) && level.isLoaded(s.surface())
                && level.getBlockState(s.seat()).is(OneMoreCore.SEATS) && level.getBlockState(s.surface()).is(OneMoreCore.SURFACES);
    }

    void invalidateLayout() {
        layoutDirty = true;
    }

    private boolean layoutChunksMatch(ServerLevel level) {
        int count = 0;
        int y = Math.max(area.minY(), level.getMinBuildHeight());
        if (y > area.maxY()) return loadedLayoutChunks.isEmpty();
        for (int z = area.minZ() >> 4; z <= area.maxZ() >> 4; z++) {
            for (int x = area.minX() >> 4; x <= area.maxX() >> 4; x++) {
                if (!level.isLoaded(new BlockPos(x << 4, y, z << 4))) continue;
                if (!loadedLayoutChunks.contains(new ChunkPos(x, z))) return false;
                count++;
            }
        }
        return count == loadedLayoutChunks.size();
    }

    /** Reuses the loaded seats and connected tables until the area, blocks or available chunks change. */
    List<Seat> scanSeats(ServerLevel level) {
        if (area == null) return List.of();
        if (!layoutDirty && layoutChunksMatch(level)) return seats;
        Set<ChunkPos> loaded = new HashSet<>();
        List<Seat> found = new ArrayList<>();
        int minY = Math.max(area.minY(), level.getMinBuildHeight()), maxY = Math.min(area.maxY(), level.getMaxBuildHeight() - 1);
        for (int z = area.minZ() >> 4; z <= area.maxZ() >> 4; z++) {
            for (int x = area.minX() >> 4; x <= area.maxX() >> 4; x++) {
                if (minY > maxY || !level.isLoaded(new BlockPos(x << 4, minY, z << 4))) continue;
                loaded.add(new ChunkPos(x, z));
                int minX = Math.max(x << 4, area.minX()), maxX = Math.min((x << 4) + 15, area.maxX());
                int minZ = Math.max(z << 4, area.minZ()), maxZ = Math.min((z << 4) + 15, area.maxZ());
                for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
                    if (!level.getBlockState(pos).is(OneMoreCore.SEATS)) continue;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos surface = pos.relative(dir);
                        if (area.isInside(surface) && level.isLoaded(surface) && level.getBlockState(surface).is(OneMoreCore.SURFACES)) {
                            found.add(new Seat(pos.immutable(), surface));
                            break;
                        }
                    }
                }
            }
        }
        seats = List.copyOf(found);
        loadedLayoutChunks = loaded;
        rebuildTables(level);
        layoutDirty = false;
        return seats;
    }

    private void rebuildTables(ServerLevel level) {
        List<List<Seat>> found = new ArrayList<>();
        Map<BlockPos, List<Seat>> tableOf = new HashMap<>();
        tableSurfaces.clear();
        for (Seat s : seats) {
            List<Seat> table = tableOf.get(s.surface());
            if (table == null) {
                found.add(table = new ArrayList<>());
                Set<BlockPos> surfaces = connectedTable(level, area, s.surface(), cuts);
                for (BlockPos pos : surfaces) {
                    tableOf.put(pos, table);
                    tableSurfaces.put(pos, surfaces);
                }
            }
            table.add(s);
        }
        tables = found;
    }

    List<List<Seat>> groupTables(ServerLevel level, List<Seat> seats) {
        scanSeats(level);
        return tables;
    }

    /**
     * Dirt cells a table had when its party left; the table is clean when dirtCleanFraction of them are wiped.
     * ponytail: not saved; after a reload the cells left then count as the start.
     */
    final Map<BlockPos, Integer> dirtStart = new HashMap<>();

    /**
     * A party leaves dirt on its table: one irregular spot where each member ate (the side of the table next to the
     * seat) and a few crumbs around it. Spots go over the joins of the blocks, so a table of many blocks gets one stain.
     */
    void soilTable(ServerLevel level, List<Customer> eaters) {
        if (eaters.isEmpty()) return;
        Set<BlockPos> table = tableAt(level, eaters.get(0).getSurface());
        Map<BlockPos, Long> cells = new HashMap<>();
        var random = level.random;
        for (Customer m : eaters) {
            BlockPos s = m.getSurface();
            // The middle of the spot, in cells (8 per block): a quarter block from the middle towards the seat.
            double cx = (s.getX() + 0.5 + (m.seat.getX() - s.getX()) * 0.25) * 8, cz = (s.getZ() + 0.5 + (m.seat.getZ() - s.getZ()) * 0.25) * 8;
            double radius = 1.6 + random.nextDouble() * 1.2, stretch = 0.7 + random.nextDouble() * 0.6;
            for (int z = (int) Math.floor(cz - 4); z <= cz + 4; z++)
                for (int x = (int) Math.floor(cx - 4); x <= cx + 4; x++) {
                    double dx = (x + 0.5 - cx) / (radius * stretch), dz = (z + 0.5 - cz) / (radius / stretch), d = Math.sqrt(dx * dx + dz * dz);
                    if (d < 0.7 || d < 1.2 && random.nextDouble() < (1.2 - d) * 2) mark(cells, table, x, z);
                }
            for (int i = 2 + random.nextInt(3); i > 0; i--) mark(cells, table, (int) Math.floor(cx + random.nextGaussian() * 3), (int) Math.floor(cz + random.nextGaussian() * 3));
        }
        cells.forEach((pos, c) -> {
            long all = c | dirt.getOrDefault(pos, 0L);
            setDirt(level, pos, all);
            dirtStart.put(pos, Long.bitCount(all));
        });
    }

    /** The screens of the restaurant (the mod's own and other mods' televisions) and what they show (ScreenSettings, packed). */
    final Map<BlockPos, Integer> screens = new HashMap<>();
    private final Map<ServerPlayer, CompoundTag> sentScreens = new HashMap<>();
    private final Set<ServerPlayer> nearbyScreenViewers = new HashSet<>();

    /** Screen modes: none, the menu, the orders. */
    public static final int SCREEN_OFF = 0, SCREEN_MENU = 1, SCREEN_ORDERS = 2;

    /** Turns a screen to the next mode (off, menu, orders), its other settings kept. False if the block is no known screen. */
    boolean cycleScreen(ServerLevel level, BlockPos pos) {
        ScreenGroups.Group group = ScreenGroups.of(level, pos);
        if (group == null) return false;
        pos = group.anchor();
        int now = screens.getOrDefault(pos, ScreenSettings.pack(SCREEN_OFF, 0, 0, true));
        setScreen(pos, ScreenSettings.withMode(now, (ScreenSettings.mode(now) + 1) % 3));
        return true;
    }

    /** Sets what a screen shows (ScreenSettings, packed). */
    void setScreen(BlockPos pos, int settings) {
        owner.claimScreen(this, pos);
        screens.put(pos.immutable(), settings);
        owner.setDirty();
    }

    /** A screen of the mod that is not known yet shows its default (the menu or the orders). */
    void ensureScreen(BlockPos pos, int mode) {
        if (!screens.containsKey(pos) && screens.size() < 256) setScreen(pos, ScreenSettings.pack(mode, 0, 0, true));
    }

    /** Sends a baseline on arrival and subsequent changes to viewers still in range. */
    private void sendScreens(ServerLevel level) {
        if (screens.keySet().removeIf(p -> level.isLoaded(p) && ScreenRect.of(level.getBlockState(p)) == null)) owner.setDirty();
        // A screen turned off keeps its settings; when every screen is off, the viewers get a clear.
        if (screens.values().stream().allMatch(ScreenSettings::allOff)) { clearScreens(level); return; }
        List<ServerPlayer> players = level.players();
        sentScreens.keySet().retainAll(players);
        BoundingBox near = area == null ? null : area.inflatedBy(32);
        Set<ServerPlayer> viewers = new HashSet<>();
        for (ServerPlayer p : players) {
            BlockPos pos = p.blockPosition();
            if (near != null && near.isInside(pos) || screens.keySet().stream().anyMatch(screen -> screen.closerThan(pos, 33)))
                viewers.add(p);
        }
        nearbyScreenViewers.retainAll(viewers);
        if (viewers.isEmpty()) return;

        CompoundTag now = new CompoundTag();
        now.put("menu", MenuBoardBlockEntity.build(this, level.registryAccess()));
        now.put("tickets", Tickets.encode(tickets(), level.registryAccess()));
        List<Map.Entry<BlockPos, Integer>> configured = List.copyOf(screens.entrySet());
        now.putLongArray("pos", configured.stream().mapToLong(e -> e.getKey().asLong()).toArray());
        now.putIntArray("mode", configured.stream().mapToInt(Map.Entry::getValue).toArray());
        ScreenData packet = new ScreenData(sign, now);
        for (ServerPlayer p : viewers) {
            if (nearbyScreenViewers.add(p) || !now.equals(sentScreens.get(p))) {
                PacketDistributor.sendToPlayer(p, packet);
                sentScreens.put(p, now);
            }
        }
    }

    private void clearScreens(ServerLevel level) {
        if (sentScreens.isEmpty()) return;
        ScreenData clear = new ScreenData(sign, new CompoundTag());
        for (ServerPlayer p : level.players()) if (sentScreens.containsKey(p)) PacketDistributor.sendToPlayer(p, clear);
        sentScreens.clear();
        nearbyScreenViewers.clear();
    }

    /** The table number cards, by the surface they stand on. */
    final Map<BlockPos, TableCards.Card> cards = new HashMap<>();
    /** The text of each table's card, by serving surface (every surface of the table), from the last scan. */
    private final Map<BlockPos, String> tableLabels = new HashMap<>();

    /**
     * Names the tables by their cards (a table is all its touching surfaces), forgets cards whose table is gone, and sends the
     * cards to the players near the area. Every 5 seconds and when a card changes.
     */
    void numberTables(ServerLevel level) {
        // A card is a Table Number block on the surface; one that is gone (or an old loose card) is forgotten.
        if (cards.keySet().removeIf(p -> level.isLoaded(p) && (!level.getBlockState(p).is(OneMoreCore.SURFACES)
                || !level.getBlockState(p.above()).is(OneMoreCore.TABLE_NUMBER_BLOCK.get())))) owner.setDirty();
        adoptCards(level);
        tableLabels.clear();
        List<BlockPos> marks = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Float> places = new ArrayList<>();
        // In a fixed order, so that a table with two cards always takes the same name (the first card with a text).
        List<Map.Entry<BlockPos, TableCards.Card>> sorted = new ArrayList<>(cards.entrySet());
        sorted.sort(java.util.Comparator.comparingLong(e -> e.getKey().asLong()));
        for (var e : sorted) {
            if (marks.size() >= 256) break;
            if (!e.getValue().text().isEmpty()) for (BlockPos p : tableAt(level, e.getKey())) tableLabels.putIfAbsent(p, e.getValue().text());
            marks.add(e.getKey());
            texts.add(e.getValue().text());
            places.addAll(List.of(e.getValue().x(), e.getValue().z(), e.getValue().yaw()));
        }
        BoundingBox near = area.inflatedBy(32);
        TableMarks packet = new TableMarks(sign, marks, texts, places, cuts.east(), cuts.south());
        for (ServerPlayer p : level.players()) if (near.isInside(p.blockPosition())) PacketDistributor.sendToPlayer(p, packet);
    }

    /**
     * The Table Number blocks in the area that the restaurant does not know: a new sign or a new area forgets the cards, while the
     * blocks stay on the tables with their text (the orders showed "TABLE ?" until the text was typed again).
     */
    private void adoptCards(ServerLevel level) {
        if (area == null) return;
        for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
            for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    BlockPos surface = be.getBlockPos().below();
                    if (!(be instanceof TableNumberBlockEntity card) || cards.containsKey(surface) || cards.size() >= 256 || !area.isInside(surface)
                            || !level.getBlockState(surface).is(OneMoreCore.SURFACES)) continue;
                    cards.put(surface.immutable(), TableNumberBlock.card(card.getBlockState(), card.text()));
                    owner.setDirty();
                }
            }
        }
    }

    /** The card text of the table of a serving surface, "" when it has no card. */
    String tableLabel(BlockPos surface) {
        return surface == null ? "" : tableLabels.getOrDefault(surface, "");
    }

    /** A Table Number block was put on a surface. */
    void placeCard(ServerLevel level, BlockPos surface, TableCards.Card card) {
        if (cards.size() >= 256 && !cards.containsKey(surface)) return;
        cards.put(surface.immutable(), card);
        owner.setDirty();
        numberTables(level);
    }

    void removeCard(ServerLevel level, BlockPos surface) {
        if (cards.remove(surface) == null) return;
        owner.setDirty();
        numberTables(level);
    }

    /** The taken orders, oldest first: one ticket per party that waits for its food. */
    List<Tickets.Ticket> tickets() {
        return Tickets.tickets(this);
    }

    /** Whether any block of the table at pos is dirty. */
    boolean tableDirty(ServerLevel level, BlockPos pos) {
        if (dirt.isEmpty()) return false;
        for (BlockPos p : tableAt(level, pos)) if (dirt.containsKey(p)) return true;
        return false;
    }

    /** Marks world cell (x, z) dirty, if its block is part of the table. */
    private static void mark(Map<BlockPos, Long> cells, Set<BlockPos> table, int x, int z) {
        for (BlockPos p : table) {
            if (p.getX() != Math.floorDiv(x, 8) || p.getZ() != Math.floorDiv(z, 8)) continue;
            cells.merge(p, 1L << (Math.floorMod(z, 8) * 8 + Math.floorMod(x, 8)), (a, b) -> a | b);
            return;
        }
    }

    /** Like setDirt, but a block wiped clean keeps its share of the table's start count until the table is clean. */
    private void setDirtKeepStart(ServerLevel level, BlockPos pos, long cells) {
        Integer start = dirtStart.get(pos);
        setDirt(level, pos, cells);
        if (start != null) dirtStart.put(pos, start);
    }

    /** Sets the dirty cells of a surface and tells the clients that see it. 0 cleans the surface. */
    void setDirt(ServerLevel level, BlockPos pos, long cells) {
        if (cells == 0) { dirt.remove(pos); dirtStart.remove(pos); } else dirt.put(pos, cells);
        owner.setDirty();
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), new Dirt(pos, cells));
    }

    /**
     * A player wipes a dirty surface at the hit position: the cell and its 8 neighbours become clean.
     * False if the surface is not dirty, the player is too far away or the hit is not on the block.
     */
    boolean wipe(ServerLevel level, Player player, BlockPos pos, Vec3 hit) {
        if (!tableDirty(level, pos) || !level.getBlockState(pos).is(OneMoreCore.SURFACES)
                || !Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)
                || !player.canInteractWithBlock(pos, 1) || !new AABB(pos).inflate(0.01).contains(hit)) return false;
        return wipeCells(level, tableAt(level, pos), pos.getX() * 8 + Mth.clamp((int) ((hit.x - pos.getX()) * 8), 0, 7),
                pos.getZ() * 8 + Mth.clamp((int) ((hit.z - pos.getZ()) * 8), 0, 7), hit);
    }

    /**
     * Wipes world cell (cx, cz) and its 8 neighbours, in any block of the table. The whole table is one thing to clean: when
     * dirtCleanFraction of the table's dirt is gone, all of it is clean.
     */
    boolean wipeCells(ServerLevel level, Set<BlockPos> table, int cx, int cz, Vec3 hit) {
        return wipeCells(level, table, cx, cz, cx, cz, hit);
    }

    private long lastWipeSound = Long.MIN_VALUE / 2;

    /** Wipes along a stroke from cell (x0, z0) to (x1, z1): every cell on the way and its 8 neighbours, as wipeCells does. */
    boolean wipeCells(ServerLevel level, Set<BlockPos> table, int x0, int z0, int x1, int z1, Vec3 hit) {
        boolean changed = false;
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        for (BlockPos p : table) {
            Long cells = dirt.get(p);
            if (cells == null) continue;
            long left = cells;
            for (int i = 0; i <= steps; i++) {
                // Offsets from the start, not whole coordinates: a float cannot hold a cell coordinate far from the origin.
                int cx = steps == 0 ? x0 : x0 + Math.round((x1 - x0) * (float) i / steps), cz = steps == 0 ? z0 : z0 + Math.round((z1 - z0) * (float) i / steps);
                for (int z = cz - 1; z <= cz + 1; z++)
                    for (int x = cx - 1; x <= cx + 1; x++)
                        if (Math.floorDiv(x, 8) == p.getX() && Math.floorDiv(z, 8) == p.getZ()) left &= ~(1L << (Math.floorMod(z, 8) * 8 + Math.floorMod(x, 8)));
            }
            if (left != cells) {
                dirtStart.putIfAbsent(p, Long.bitCount(cells));
                setDirtKeepStart(level, p, left);
                changed = true;
            }
        }
        if (changed) {
            int start = 0, left = 0;
            for (BlockPos p : table) {
                start += dirtStart.getOrDefault(p, 0);
                left += Long.bitCount(dirt.getOrDefault(p, 0L));
            }
            if (left == 0 || left <= (1 - Config.DIRT_CLEAN_FRACTION.get()) * start)
                for (BlockPos p : table) if (dirt.containsKey(p) || dirtStart.containsKey(p)) setDirt(level, p, 0);
            // A stroke comes every tick while the cloth moves: the brush sounds at most every 4 ticks.
            if (level.getGameTime() - lastWipeSound >= 4) {
                lastWipeSound = level.getGameTime();
                level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BRUSH_GENERIC,
                        SoundSource.BLOCKS, 0.35f, 1.2f);
            }
            level.sendParticles(ParticleTypes.SPLASH,
                    hit.x, hit.y + 0.03, hit.z, 3, 0.06, 0.01, 0.06, 0.01);
        }
        return true;
    }

    /** Shows a title and a subtitle to the players in and near the area. */
    void announce(ServerLevel level, Component title, Component subtitle) {
        if (area == null) return;
        BoundingBox near = area.inflatedBy(16);
        for (ServerPlayer p : level.players()) {
            if (!near.isInside(p.blockPosition())) continue;
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            p.connection.send(new ClientboundSetTitleTextPacket(title));
        }
    }

    void sendStatus(ServerLevel level) {
        if (area == null) return;
        BoundingBox near = area.inflatedBy(8);
        List<ServerPlayer> viewers = level.players().stream().filter(p -> near.isInside(p.blockPosition())).toList();
        if (viewers.isEmpty()) return;
        List<Status.Member> members = readyCheckActive ? requiredPlayers(level).stream()
                .map(p -> new Status.Member(p.getUUID(), p.getGameProfile().getName(), ready.contains(p.getUUID()))).toList() : List.of();
        Status status = new Status(open, day, serviceLeft > 0 ? customers.size() : toSpawn + customers.size(), served, lost,
                Config.MAX_LOST_PER_DAY.get(), register, earned, BuiltInRegistries.ITEM.getKey(Config.currency()), members,
                countdown, open ? serviceLeft : 0, sign, area, readyCheckActive);
        for (ServerPlayer p : viewers) PacketDistributor.sendToPlayer(p, status);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("sign", sign.asLong());
        if (area != null) tag.putIntArray("area", new int[] {area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()});
        if (spawn != null) tag.putLong("spawn", spawn.asLong());
        tag.putInt("day", day);
        tag.putInt("register", register);
        ListTag list = new ListTag();
        unlockedIngredients.stream().sorted().forEach(id -> list.add(StringTag.valueOf(id.toString())));
        tag.put("unlockedIngredients", list);
        ListTag offList = new ListTag();
        for (String id : off) offList.add(StringTag.valueOf(id));
        tag.put("off", offList);
        ListTag customList = new ListTag();
        for (Menu.Entry e : custom) {
            CompoundTag t = new CompoundTag();
            t.putString("item", BuiltInRegistries.ITEM.getKey(e.stack().getItem()).toString());
            t.putInt("price", e.price());
            customList.add(t);
        }
        tag.put("custom", customList);
        CompoundTag kindTag = new CompoundTag();
        kinds.forEach(kindTag::putString);
        tag.put("kinds", kindTag);
        CompoundTag prices = new CompoundTag();
        dishPrices.forEach(prices::putInt);
        tag.put("dishPrices", prices);
        ListTag comboList = new ListTag();
        for (Combo c : combos) {
            CompoundTag t = new CompoundTag();
            t.putString("id", c.id());
            ListTag ids = new ListTag();
            for (String id : c.dishes()) ids.add(StringTag.valueOf(id));
            t.put("dishes", ids);
            t.putInt("price", c.price());
            comboList.add(t);
        }
        tag.put("combos", comboList);
        CompoundTag up = new CompoundTag();
        upgrades.forEach(up::putInt);
        tag.put("upgrades", up);
        ListTag favs = new ListTag();
        for (String id : favourites) favs.add(StringTag.valueOf(id));
        tag.put("favourites", favs);
        ListTag table = new ListTag();
        for (var e : coins.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("pos", e.getKey().asLong());
            t.putInt("coins", e.getValue());
            table.add(t);
        }
        tag.put("table_items", table);
        ListTag served = new ListTag();
        for (ServedBill bill : servedBills.values()) saveServedBill(served, bill);
        for (ServedBill bill : interruptedBills) saveServedBill(served, bill);
        tag.put("served_bills", served);
        long[] tablesOff = offTables.stream().mapToLong(BlockPos::asLong).toArray();
        tag.putLongArray("tables_off", tablesOff);
        cuts.save(tag);
        tag.putLongArray("screens", screens.keySet().stream().mapToLong(BlockPos::asLong).toArray());
        tag.putIntArray("screen_modes", screens.values().stream().mapToInt(Integer::intValue).toArray());
        ListTag cardList = new ListTag();
        for (var e : cards.entrySet()) {
            CompoundTag t = e.getValue().save();
            t.putLong("pos", e.getKey().asLong());
            cardList.add(t);
        }
        tag.put("cards", cardList);
        if (delivery != null) tag.putLong("delivery", delivery.asLong());
        ListTag pending = new ListTag();
        for (Delivery d : deliveries) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", d.id);
            t.put("items", Courier.saveOrder(d.items, owner.registries));
            t.putInt("ticks", d.ticks);
            if (d.fallbackAt != 0) t.putLong("fallback_at", d.fallbackAt);
            if (d.courierId != null) t.putUUID("courier", d.courierId);
            if (d.standIn) t.putBoolean("stand_in", true);
            if (d.destination != null) t.putLong("destination", d.destination.asLong());
            pending.add(t);
        }
        tag.put("deliveries", pending);
        long[] d = new long[dirt.size() * 2];
        int i = 0;
        for (Map.Entry<BlockPos, Long> e : dirt.entrySet()) {
            d[i++] = e.getKey().asLong();
            d[i++] = e.getValue();
        }
        tag.putLongArray("dirt", d);
        long[] start = new long[dirtStart.size() * 2];
        i = 0;
        for (var e : dirtStart.entrySet()) {
            start[i++] = e.getKey().asLong();
            start[i++] = e.getValue();
        }
        tag.putLongArray("dirt_start", start);
        return tag;
    }

    private static void saveServedBill(ListTag list, ServedBill bill) {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("surfaces", bill.surfaces().stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLong("amount", bill.amount());
        list.add(tag);
    }

    static Restaurant load(Restaurants owner, CompoundTag tag) {
        Restaurant r = new Restaurant(owner, BlockPos.of(tag.getLong("sign")));
        int[] a = tag.getIntArray("area");
        if (a.length == 6) r.area = new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]);
        if (tag.contains("spawn")) r.spawn = BlockPos.of(tag.getLong("spawn"));
        r.day = Math.max(1, tag.getInt("day"));
        r.register = Math.max(0, tag.getInt("register"));
        for (Tag t : tag.getList("unlockedIngredients", Tag.TAG_STRING)) {
            ResourceLocation item = ResourceLocation.tryParse(t.getAsString());
            if (item != null) r.unlockedIngredients.add(item);
        }
        for (Tag t : tag.getList("off", Tag.TAG_STRING)) r.off.add(t.getAsString());
        CompoundTag prices = tag.getCompound("dishPrices");
        for (String id : prices.getAllKeys()) {
            if (prices.contains(id, Tag.TAG_INT) && prices.getInt(id) > 0) r.dishPrices.put(id, prices.getInt(id));
        }
        CompoundTag kindTag = tag.getCompound("kinds");
        for (String id : kindTag.getAllKeys()) if (Menu.CATEGORIES.contains(kindTag.getString(id))) r.kinds.put(id, kindTag.getString(id));
        for (Tag t : tag.getList("combos", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            List<String> ids = new ArrayList<>();
            for (Tag d : c.getList("dishes", Tag.TAG_STRING)) ids.add(d.getAsString());
            if (ids.size() >= 2 && ids.size() <= LEGACY_COMBO_PLACES && r.combos.size() < MAX_COMBOS)
                r.combos.add(new Combo(c.getString("id"), List.copyOf(ids), Math.max(1, c.getInt("price"))));
        }
        for (long p : tag.getLongArray("tables_off")) r.offTables.add(BlockPos.of(p));
        r.cuts.load(tag);
        long[] sp = tag.getLongArray("screens");
        int[] sm = tag.getIntArray("screen_modes");
        for (int i = 0; i < sp.length && i < sm.length && i < 256; i++) r.screens.put(BlockPos.of(sp[i]), ScreenSettings.valid(sm[i]));
        for (Tag t : tag.getList("cards", Tag.TAG_COMPOUND)) {
            if (r.cards.size() >= 256) break;
            r.cards.put(BlockPos.of(((CompoundTag) t).getLong("pos")), TableCards.Card.load((CompoundTag) t));
        }
        CompoundTag up = tag.getCompound("upgrades");
        for (String id : up.getAllKeys()) if (upgradeCosts(id) != null) r.upgrades.put(id, Math.max(0, Math.min(upgradeCosts(id).size(), up.getInt(id))));
        for (Tag t : tag.getList("favourites", Tag.TAG_STRING)) r.favourites.add(t.getAsString());
        for (Tag t : tag.getList("table_items", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            BlockPos p = BlockPos.of(c.getLong("pos"));
            if (c.getInt("coins") > 0) r.coins.put(p, c.getInt("coins"));
        }
        for (Tag t : tag.getList("served_bills", Tag.TAG_COMPOUND)) {
            CompoundTag bill = (CompoundTag) t;
            long amount = bill.getLong("amount");
            if (amount > 0) {
                List<BlockPos> surfaces = new ArrayList<>();
                for (long pos : bill.getLongArray("surfaces")) surfaces.add(BlockPos.of(pos));
                r.interruptedBills.add(new ServedBill(surfaces, amount));
            }
        }
        for (Tag t : tag.getList("custom", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            // An item of a mod that was removed is skipped. A price out of range is clamped to the allowed range.
            BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.tryParse(c.getString("item"))).ifPresent(item -> {
                ItemStack stack = new ItemStack(item);
                if (stack.isEmpty() || r.custom.size() >= MAX_CUSTOM) return;
                int price = Math.max(1, Math.min(Config.MAX_DISH_PRICE.get(), c.getInt("price")));
                r.custom.add(new Menu.Entry("custom:" + BuiltInRegistries.ITEM.getKey(item), stack, null, price, false));
            });
        }
        if (!tag.contains("unlockedIngredients", Tag.TAG_LIST)) {
            Set<String> paid = new HashSet<>();
            for (Tag t : tag.getList("unlocked", Tag.TAG_STRING)) paid.add(t.getAsString());
            for (Menu.Entry entry : Menu.entries) {
                if (Menu.wasAvailable(entry, r.day, paid)) Ingredients.current().grantLegacy(entry, r.unlockedIngredients);
            }
            for (Menu.Entry entry : r.custom) Ingredients.current().grantLegacy(entry, r.unlockedIngredients);
            owner.setDirty();
        }
        if (tag.contains("delivery")) r.delivery = BlockPos.of(tag.getLong("delivery"));
        for (Tag t : tag.getList("deliveries", Tag.TAG_COMPOUND)) {
            CompoundTag d = (CompoundTag) t;
            BlockPos destination = d.contains("destination", Tag.TAG_LONG) ? BlockPos.of(d.getLong("destination")) : null;
            List<ItemStack> items = d.contains("items", Tag.TAG_LIST)
                    ? Courier.loadOrder(d.getList("items", Tag.TAG_COMPOUND), owner.registries)
                    : Courier.readLine(d, owner.registries).map(List::of).orElseGet(List::of);
            if (!items.isEmpty()) {
                var order = new Delivery(d.hasUUID("id") ? d.getUUID("id") : UUID.randomUUID(),
                        items, Math.max(0, d.getInt("ticks")), destination);
                order.fallbackAt = d.getLong("fallback_at");
                if (d.hasUUID("courier")) order.courierId = d.getUUID("courier");
                order.standIn = d.getBoolean("stand_in");
                r.deliveries.add(order);
            }
        }
        long[] d = tag.getLongArray("dirt");
        for (int i = 0; i + 1 < d.length; i += 2) {
            if (d[i + 1] == 0) continue;
            BlockPos pos = BlockPos.of(d[i]);
            r.dirt.put(pos, d[i + 1]);
            r.dirtStart.put(pos, Long.bitCount(d[i + 1]));
        }
        long[] start = tag.getLongArray("dirt_start");
        for (int i = 0; i + 1 < start.length; i += 2) {
            if (start[i + 1] > 0 && start[i + 1] <= Long.SIZE)
                r.dirtStart.merge(BlockPos.of(start[i]), (int) start[i + 1], Math::max);
        }
        return r;
    }
}
