package com.onemorestudios.onemoreclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
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
    final Set<String> unlocked = new HashSet<>();
    /** Ids of dishes that the players took off the menu. */
    final Set<String> off = new HashSet<>();
    /** The kind of a dish, set by a player; without an entry the dish keeps its own kind. */
    final Map<String, String> kinds = new HashMap<>();
    /** Combos that the players made: several dishes for one price. */
    final List<Combo> combos = new ArrayList<>();
    /** Surfaces of tables that the players turned off. A table with one of them gets no customers. */
    final Set<BlockPos> offTables = new HashSet<>();
    /** Game time when the last party left a surface's table. */
    private final Map<BlockPos, Long> freedAt = new HashMap<>();
    /** Ticks of service left today; 0 ends the arrivals. Only with dayLengthTicks > 0. */
    int serviceLeft;

    /** A combo: dish ids and the price of all of them together. */
    public record Combo(String id, List<String> dishes, int price) {}
    /** Dishes that the players added, ids "custom:<item id>". */
    final List<Menu.Entry> custom = new ArrayList<>();
    BlockPos delivery;
    final List<Delivery> deliveries = new ArrayList<>();
    /** Dirty serving surfaces and their dirty cells, see {@link Dirt}. */
    final Map<BlockPos, Long> dirt = new HashMap<>();

    // Not saved: a restaurant is closed after a world load, and the day starts again.
    boolean open;
    int toSpawn, spawnTimer, served, lost, earned;
    long penalties;
    final Set<UUID> ready = new HashSet<>();
    /** Ticks until the day starts, after every player is ready. 0: no countdown. */
    int countdown;
    List<Seat> seats = List.of();
    /** The seats, grouped by table. One party sits at a table. */
    List<List<Seat>> tables = List.of();
    /** Group members that wait to walk in. They are in {@link #customers} already. */
    final List<Customer> arriving = new ArrayList<>();
    int arriveTimer, seatScanTimer;
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

    public boolean isAvailable(Menu.Entry e) {
        return day >= e.day() && (e.cost() == 0 || unlocked.contains(e.id()));
    }

    boolean canOrder(Menu.Entry e) {
        return isAvailable(e) && !off.contains(e.id()) && (e.tag() == null || BuiltInRegistries.ITEM.getTag(e.tag()).filter(tag -> tag.size() > 0).isPresent());
    }

    /** The dishes of this restaurant: the entries of menu.json, then the dishes that the players added. */
    public List<Menu.Entry> dishes() {
        List<Menu.Entry> all = new ArrayList<>(Menu.entries);
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
        custom.add(new Menu.Entry(id, stack, null, price, false, 0, 0));
        owner.setDirty();
        return Add.OK;
    }

    public static boolean isFood(ItemStack stack) {
        return stack.has(net.minecraft.core.component.DataComponents.FOOD)
                || stack.is(net.minecraft.tags.ItemTags.create(net.minecraft.resources.ResourceLocation.parse("c:foods")))
                || stack.is(net.minecraft.tags.ItemTags.create(net.minecraft.resources.ResourceLocation.parse("c:drinks")));
    }

    /** Changes the price of a dish that a player added. False for an entry of menu.json or a price out of range. */
    boolean setPrice(String id, int price) {
        if (price < 1 || price > Config.MAX_DISH_PRICE.get()) return false;
        for (int i = 0; i < custom.size(); i++) {
            Menu.Entry e = custom.get(i);
            if (!e.id().equals(id)) continue;
            custom.set(i, new Menu.Entry(e.id(), e.stack(), null, price, false, 0, 0));
            owner.setDirty();
            return true;
        }
        return false;
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

    /** Most combos of one restaurant, and most dishes in one combo. */
    static final int MAX_COMBOS = 16, MAX_COMBO_DISHES = 4;

    /** Result of {@link #addCombo}. */
    public enum AddCombo { OK, DISHES, PRICE, FULL }

    /** Adds a combo of 2 to MAX_COMBO_DISHES different dishes of this restaurant, with a price from 1 to maxDishPrice. */
    AddCombo addCombo(List<String> ids, int price) {
        if (ids.size() < 2 || ids.size() > MAX_COMBO_DISHES || ids.stream().distinct().count() != ids.size()
                || ids.stream().anyMatch(id -> dish(id) == null)) return AddCombo.DISHES;
        if (price < 1 || price > Config.MAX_DISH_PRICE.get() * MAX_COMBO_DISHES) return AddCombo.PRICE;
        if (combos.size() >= MAX_COMBOS) return AddCombo.FULL;
        int n = 1;
        while (combo("combo:" + n) != null) n++;
        combos.add(new Combo("combo:" + n, List.copyOf(ids), price));
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
        if (price < 1 || price > Config.MAX_DISH_PRICE.get() * MAX_COMBO_DISHES) return false;
        for (int i = 0; i < combos.size(); i++) {
            if (!combos.get(i).id().equals(id)) continue;
            combos.set(i, new Combo(id, combos.get(i).dishes(), price));
            owner.setDirty();
            return true;
        }
        return false;
    }

    /** A combo can be ordered when every one of its dishes can be ordered. */
    boolean canOrder(Combo c) {
        for (String id : c.dishes()) {
            Menu.Entry e = dish(id);
            if (e == null || !canOrder(e)) return false;
        }
        return true;
    }

    /** The serving surfaces that touch the one at pos (one table), inside the area, at most 256. */
    Set<BlockPos> tableAt(ServerLevel level, BlockPos pos) {
        Set<BlockPos> table = new HashSet<>();
        if (area == null || !level.getBlockState(pos).is(OneMoreClient.SURFACES)) return table;
        ArrayDeque<BlockPos> todo = new ArrayDeque<>(List.of(pos.immutable()));
        table.add(pos.immutable());
        while (!todo.isEmpty() && table.size() < 256) {
            BlockPos p = todo.pop();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = p.relative(dir);
                if (area.isInside(next) && level.getBlockState(next).is(OneMoreClient.SURFACES) && table.add(next)) todo.push(next);
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

    /** Result of {@link #unlock}. */
    public enum Unlock { OK, ALREADY, DAY, COST }

    /** Buys a menu entry with money from the register. */
    public Unlock unlock(Menu.Entry e) {
        if (day < e.day()) return Unlock.DAY;
        if (e.cost() == 0 || unlocked.contains(e.id())) return Unlock.ALREADY;
        if (register < e.cost()) return Unlock.COST;
        register -= e.cost();
        unlocked.add(e.id());
        owner.setDirty();
        return Unlock.OK;
    }

    void setArea(BoundingBox box) { area = box; owner.setDirty(); }
    void setSpawn(BlockPos pos) { spawn = pos; owner.setDirty(); }
    void setDelivery(BlockPos pos) { delivery = pos; owner.setDirty(); }

    /** A paid shop order that is on its way. */
    static final class Delivery {
        final ItemStack stack;
        int ticks;

        Delivery(ItemStack stack, int ticks) {
            this.stack = stack;
            this.ticks = ticks;
        }
    }

    /** Result of {@link #buy}. */
    public enum Buy { OK, COST, NO_DELIVERY }

    /** Orders a shop entry with money from the register. The items arrive at the delivery point after shopDeliveryTicks. */
    public Buy buy(Shop.Entry e) {
        if (delivery == null) return Buy.NO_DELIVERY;
        if (register < e.price()) return Buy.COST;
        register -= e.price();
        deliveries.add(new Delivery(e.stack().copy(), Config.SHOP_DELIVERY.get()));
        owner.setDirty();
        return Buy.OK;
    }

    /** The item handler of the block, or null. */
    static IItemHandler container(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        return handler == null && level.getBlockEntity(pos) instanceof Container c ? new InvWrapper(c) : handler;
    }

    /** Waits while the delivery point is not loaded, so no order is lost. */
    private void tickDeliveries(ServerLevel level) {
        if (deliveries.isEmpty() || delivery == null || !level.isLoaded(delivery)) return;
        owner.setDirty();
        for (Delivery d : List.copyOf(deliveries)) {
            if (--d.ticks > 0) continue;
            deliveries.remove(d);
            deliver(level, d.stack);
        }
    }

    /** Puts the stack into the container at the delivery point. Without a container, or when it is full, the items drop. */
    void deliver(ServerLevel level, ItemStack stack) {
        IItemHandler handler = container(level, delivery);
        BlockPos at = handler == null ? delivery : delivery.above();
        int max = stack.getMaxStackSize();
        for (int left = stack.getCount(); left > 0; left -= max) {
            ItemStack part = stack.copyWithCount(Math.min(max, left));
            if (handler != null) part = ItemHandlerHelper.insertItemStacked(handler, part, false);
            if (!part.isEmpty()) level.addFreshEntity(new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.25, at.getZ() + 0.5, part, 0, 0, 0));
        }
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
        register += n;
        owner.setDirty();
        return n;
    }

    /** Day, register, unlocks and paid orders: what the sign item keeps when the sign is broken. Positions are not kept. */
    CompoundTag progress() {
        CompoundTag tag = save();
        for (String key : List.of("sign", "area", "spawn", "delivery", "dirt", "tables_off")) tag.remove(key);
        return tag;
    }

    boolean hasProgress() {
        return day > 1 || register > 0 || !unlocked.isEmpty() || !deliveries.isEmpty() || !off.isEmpty() || !custom.isEmpty() || !kinds.isEmpty() || !combos.isEmpty();
    }

    void restore(CompoundTag progress) {
        progress.putLong("sign", sign.asLong());
        Restaurant from = load(owner, progress);
        day = from.day;
        register = from.register;
        unlocked.addAll(from.unlocked);
        off.addAll(from.off);
        custom.addAll(from.custom);
        kinds.putAll(from.kinds);
        combos.addAll(from.combos);
        deliveries.addAll(from.deliveries);
        owner.setDirty();
    }

    /** A player clicked the sign of the closed restaurant: the player is ready. */
    void requestOpen(ServerLevel level, Player player) {
        setReady(level, player, true);
    }

    /** Why the day cannot start, as a lang key suffix, or null. Scans the seats. */
    private String problem(ServerLevel level) {
        String problem = area == null ? "no_area" : spawn == null ? "no_spawn"
                : dishes().stream().noneMatch(this::canOrder) ? "no_menu" : null;
        if (problem == null && (seats = scanSeats(level)).isEmpty()) problem = "no_seats";
        return problem;
    }

    /** True if every player inside the area is ready, or the ready check is off. */
    private boolean allReady(ServerLevel level) {
        if (!Config.READY_CHECK.get()) return true;
        for (ServerPlayer p : level.players()) if (area.isInside(p.blockPosition()) && !ready.contains(p.getUUID())) return false;
        return true;
    }

    /**
     * Marks the player ready or not ready. When every player inside the area is ready, the countdown starts;
     * the day starts when it ends. A player who is alone starts the day at once. The player who clicks counts even when
     * outside the area.
     */
    void setReady(ServerLevel level, Player player, boolean on) {
        if (open) return;
        if (!on) {
            if (ready.remove(player.getUUID()) && countdown > 0) stopCountdown(level);
            return;
        }
        String problem = problem(level);
        if (problem != null) {
            player.displayClientMessage(Component.translatable("onemoreclient.open." + problem), true);
            return;
        }
        Set<UUID> needed = new HashSet<>();
        needed.add(player.getUUID());
        for (ServerPlayer p : level.players()) if (area.isInside(p.blockPosition())) needed.add(p.getUUID());
        ready.add(player.getUUID());
        ready.retainAll(needed);
        if (Config.READY_CHECK.get() && ready.size() < needed.size()) {
            BoundingBox near = area.inflatedBy(16);
            Component message = Component.translatable("onemoreclient.open.ready", player.getDisplayName(), ready.size(), needed.size());
            for (ServerPlayer p : level.players()) if (near.isInside(p.blockPosition())) p.displayClientMessage(message, true);
            level.playSound(null, sign, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1, 1.4f);
            return;
        }
        if (countdown > 0) return;
        // Alone, the day starts at once; with others, after a countdown in which a player can still cancel.
        countdown = needed.size() <= 1 ? 0 : Config.READY_COUNTDOWN.get();
        if (countdown == 0) startDay(level);
    }

    private void stopCountdown(ServerLevel level) {
        countdown = 0;
        BoundingBox near = area.inflatedBy(16);
        for (ServerPlayer p : level.players())
            if (near.isInside(p.blockPosition())) p.displayClientMessage(Component.translatable("onemoreclient.open.stopped"), true);
        level.playSound(null, sign, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 1, 0.8f);
    }

    /** One tick of the countdown. It stops when a player inside the area is not ready, and the day starts at 0. */
    private void tickCountdown(ServerLevel level) {
        if (!allReady(level)) { stopCountdown(level); return; }
        if (countdown % 20 == 0) level.playSound(null, sign, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 1, 1.2f);
        if (--countdown > 0) return;
        String problem = problem(level);
        if (problem == null) { startDay(level); return; }
        ready.clear();
        BoundingBox near = area.inflatedBy(16);
        for (ServerPlayer p : level.players())
            if (near.isInside(p.blockPosition())) p.displayClientMessage(Component.translatable("onemoreclient.open." + problem), true);
    }

    /** Starts the service. The seats are scanned already. */
    private void startDay(ServerLevel level) {
        countdown = 0;
        ready.clear();
        seatScanTimer = 0;
        tables = groupTables(level, seats);
        arriving.clear();
        open = true;
        served = lost = earned = 0;
        penalties = 0;
        serviceLeft = Config.DAY_LENGTH.get();
        // With a day length the number of customers is open: they come until the service ends.
        toSpawn = serviceLeft > 0 ? Integer.MAX_VALUE
                : (int) Math.min(Integer.MAX_VALUE, Config.CUSTOMERS_BASE.get() + (long) Config.CUSTOMERS_PER_DAY.get() * (day - 1));
        spawnTimer = 40;
        announce(level, Component.translatable("onemoreclient.open.started.title", day), serviceLeft > 0
                ? Component.translatable("onemoreclient.open.started_time", day, (serviceLeft + 1199) / 1200)
                : Component.translatable("onemoreclient.open.started", day, toSpawn));
        level.playSound(null, sign, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1, 1);
    }

    void tick(ServerLevel level) {
        // Hidden chunks can unload entities without calling Customer.remove or a second tracking callback.
        for (Customer c : List.copyOf(customers)) if (c.isRemoved()) c.removeFromRestaurant();
        if (!level.isLoaded(sign)) return;
        BlockState state = level.getBlockState(sign);
        if (!state.is(OneMoreClient.OPEN_SIGN)) return;
        tickDeliveries(level);
        if (state.getValue(OpenSignBlock.OPEN) != open) level.setBlockAndUpdate(sign, state.setValue(OpenSignBlock.OPEN, open));
        if (!open && countdown > 0 && area != null) tickCountdown(level);
        if (area != null && level.getGameTime() % 10 == 0) sendStatus(level);
        if (!open) return;
        if (serviceLeft > 0 && --serviceLeft == 0) {
            // The service is over: no new parties. Parties that are on their way and the customers inside finish, then the day ends.
            toSpawn = 0;
        }
        if (seatScanTimer > 0) seatScanTimer--;
        if (!arriving.isEmpty() && --arriveTimer <= 0) {
            level.addFreshEntity(arriving.remove(0));
            arriveTimer = 10;
        }
        if (toSpawn > 0) {
            if (--spawnTimer > 0) return;
            int size = 1;
            if (Config.MAX_GROUP.get() > 1 && level.random.nextDouble() < Config.GROUP_CHANCE.get())
                size = 2 + level.random.nextInt(Config.MAX_GROUP.get() - 1);
            if (spawnParty(level, size) == 0) {
                if (seatScanTimer == 0) {
                    seats = scanSeats(level);
                    tables = groupTables(level, seats);
                    seatScanTimer = 100;
                }
                if (seats.isEmpty() && customers.isEmpty()) toSpawn = 0;
            } else spawnTimer = nextInterval(level);
        }
        if (toSpawn == 0 && customers.isEmpty()) endDay(level, false);
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
        var message = Component.translatable(failed ? "onemoreclient.day.failed" : "onemoreclient.day.end", day, served, lost, earned);
        if (penalties > 0) message.append(Component.translatable("onemoreclient.day.penalty", penalties));
        announce(level, Component.translatable(failed ? "onemoreclient.day.failed.title" : "onemoreclient.day.end.title", day), message);
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
        for (Seat seat : freeSeats(level, Math.min(size, toSpawn))) {
            List<Customer.Line> order = makeOrder(level);
            if (order.isEmpty()) { toSpawn = 0; break; }
            Customer c = new Customer(OneMoreClient.CUSTOMER.get(), level);
            c.setup(this, seat, order, party);
            c.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
            arriving.add(c);
            customers.add(c);
            toSpawn--;
        }
        return party.members.size();
    }

    private List<Customer.Line> makeOrder(ServerLevel level) {
        List<Menu.Entry> available = dishes().stream().filter(this::canOrder).toList();
        List<Customer.Line> order = new ArrayList<>();
        if (available.isEmpty()) return order;
        // A combo: all its dishes; the combo price is split over the lines (the first line takes the rest of the division).
        List<Combo> orderable = combos.stream().filter(this::canOrder).toList();
        if (!orderable.isEmpty() && level.random.nextDouble() < Config.COMBO_CHANCE.get()) {
            Combo c = orderable.get(level.random.nextInt(orderable.size()));
            int share = c.price() / c.dishes().size(), rest = c.price() - share * c.dishes().size();
            for (int i = 0; i < c.dishes().size(); i++) {
                Menu.Entry e = dish(c.dishes().get(i));
                ItemStack stack = e.pick(level.random);
                if (stack.isEmpty()) return new ArrayList<>();
                order.add(new Customer.Line(stack, share + (i == 0 ? rest : 0), e.exact()));
            }
            return order;
        }
        // Otherwise one dish of each of up to maxOrderItems different kinds: no two main dishes in one order.
        Map<String, List<Menu.Entry>> byKind = new java.util.LinkedHashMap<>();
        for (Menu.Entry e : available) byKind.computeIfAbsent(kind(e), k -> new ArrayList<>()).add(e);
        List<String> kinds = new ArrayList<>(byKind.keySet());
        // A main dish comes first when there is one; the other kinds in random order.
        Util.shuffle(kinds, level.random);
        if (kinds.remove("main")) kinds.add(0, "main");
        int n = Math.min(kinds.size(), 1 + level.random.nextInt(Config.MAX_ORDER_ITEMS.get()));
        for (int i = 0; i < n; i++) {
            List<Menu.Entry> of = byKind.get(kinds.get(i));
            Menu.Entry e = of.get(level.random.nextInt(of.size()));
            ItemStack stack = e.pick(level.random);
            if (!stack.isEmpty()) order.add(new Customer.Line(stack, e.price(), e.exact()));
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
        for (List<Seat> table : tables) {
            if (table.stream().anyMatch(s -> customers.stream().anyMatch(c -> s.seat().equals(c.seat)))) continue;
            if (table.stream().anyMatch(s -> dirt.containsKey(s.surface()) || offTables.contains(s.surface())
                    || now - freedAt.getOrDefault(s.surface(), Long.MIN_VALUE / 2) < Config.TABLE_PAUSE.get())) continue;
            List<Seat> seated = new ArrayList<>(table.stream().filter(s -> isSeat(level, s)).toList());
            if (seated.isEmpty() || !isTableOn(tableSurfaces(table))) continue;
            free.add(seated);
            most = Math.max(most, seated.size());
        }
        int size = Math.min(wanted, most);
        free.removeIf(t -> t.size() < size);
        if (free.isEmpty()) return List.of();
        int smallest = free.stream().mapToInt(List::size).min().orElseThrow();
        free.removeIf(t -> t.size() != smallest);
        List<Seat> table = free.get(level.random.nextInt(free.size()));
        Util.shuffle(table, level.random);
        return table.subList(0, size);
    }

    private static Set<BlockPos> tableSurfaces(List<Seat> table) {
        Set<BlockPos> out = new HashSet<>();
        for (Seat s : table) out.add(s.surface());
        return out;
    }

    private static boolean isSeat(ServerLevel level, Seat s) {
        return level.getBlockState(s.seat()).is(OneMoreClient.SEATS) && level.getBlockState(s.surface()).is(OneMoreClient.SURFACES);
    }

    /** A seat is a block in the seats tag with a block in the serving_surfaces tag next to it. */
    List<Seat> scanSeats(ServerLevel level) {
        List<Seat> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ())) {
            if (!level.getBlockState(pos).is(OneMoreClient.SEATS)) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos surface = pos.relative(dir);
                if (area.isInside(surface) && level.getBlockState(surface).is(OneMoreClient.SURFACES)) {
                    found.add(new Seat(pos.immutable(), surface));
                    break;
                }
            }
        }
        return found;
    }

    /** A table is a set of serving surfaces that touch each other. Its seats are the seats next to these surfaces. */
    List<List<Seat>> groupTables(ServerLevel level, List<Seat> seats) {
        List<List<Seat>> found = new ArrayList<>();
        Map<BlockPos, List<Seat>> tableOf = new HashMap<>();
        for (Seat s : seats) {
            List<Seat> table = tableOf.get(s.surface());
            if (table == null) {
                found.add(table = new ArrayList<>());
                tableOf.put(s.surface(), table);
                ArrayDeque<BlockPos> todo = new ArrayDeque<>(List.of(s.surface()));
                while (!todo.isEmpty()) {
                    BlockPos pos = todo.pop();
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos next = pos.relative(dir);
                        if (area.isInside(next) && level.getBlockState(next).is(OneMoreClient.SURFACES) && tableOf.putIfAbsent(next, table) == null) todo.push(next);
                    }
                }
            }
            table.add(s);
        }
        return found;
    }

    /** Sets the dirty cells of a surface and tells the clients that see it. 0 cleans the surface. */
    void setDirt(ServerLevel level, BlockPos pos, long cells) {
        if (cells == 0) dirt.remove(pos); else dirt.put(pos, cells);
        owner.setDirty();
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), new Dirt(pos, cells));
    }

    /**
     * A player wipes a dirty surface at the hit position: the cell and its 8 neighbours become clean.
     * False if the surface is not dirty, the player is too far away or the hit is not on the block.
     */
    boolean wipe(ServerLevel level, Player player, BlockPos pos, Vec3 hit) {
        Long cells = dirt.get(pos);
        if (cells == null || !level.getBlockState(pos).is(OneMoreClient.SURFACES)
                || !Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)
                || !player.canInteractWithBlock(pos, 1) || !new AABB(pos).inflate(0.01).contains(hit)) return false;
        int cx = Mth.clamp((int) ((hit.x - pos.getX()) * 8), 0, 7), cz = Mth.clamp((int) ((hit.z - pos.getZ()) * 8), 0, 7);
        long left = cells;
        for (int z = Math.max(0, cz - 1); z <= Math.min(7, cz + 1); z++)
            for (int x = Math.max(0, cx - 1); x <= Math.min(7, cx + 1); x++) left &= ~(1L << (z * 8 + x));
        if (left != cells) {
            double clean = 1 - Long.bitCount(left) / (double) Long.bitCount(Dirt.FULL);
            if (clean >= Config.DIRT_CLEAN_FRACTION.get()) {
                // One table is one thing to clean: a clean part makes every part of the table clean.
                for (BlockPos p : tableAt(level, pos)) if (dirt.containsKey(p)) setDirt(level, p, 0);
                setDirt(level, pos, 0);
            } else {
                setDirt(level, pos, left);
            }
            level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BRUSH_GENERIC,
                    SoundSource.BLOCKS, 0.35f, 1.2f);
            level.sendParticles(ParticleTypes.SPLASH,
                    hit.x, hit.y + 0.03, hit.z, 3, 0.06, 0.01, 0.06, 0.01);
        }
        return true;
    }

    /** Shows a title and a subtitle to the players in and near the area. */
    void announce(ServerLevel level, Component title, Component subtitle) {
        BoundingBox near = area.inflatedBy(16);
        for (ServerPlayer p : level.players()) {
            if (!near.isInside(p.blockPosition())) continue;
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            p.connection.send(new ClientboundSetTitleTextPacket(title));
        }
    }

    // ponytail: a player near two restaurants gets both, and the HUD shows the last one. Pick the nearest if this shows.
    void sendStatus(ServerLevel level) {
        BoundingBox near = area.inflatedBy(8);
        Status status = null;
        for (ServerPlayer p : level.players()) {
            if (!near.isInside(p.blockPosition())) continue;
            if (status == null) {
                // The players inside the area; a ready player who left does not count, like in setReady.
                List<Status.Member> members = new ArrayList<>();
                for (ServerPlayer q : level.players())
                    if (area.isInside(q.blockPosition()))
                        members.add(new Status.Member(q.getUUID(), q.getGameProfile().getName(), ready.contains(q.getUUID())));
                status = new Status(open, day, serviceLeft > 0 ? customers.size() : toSpawn + customers.size(), served, lost, Config.MAX_LOST_PER_DAY.get(), register, earned,
                        BuiltInRegistries.ITEM.getKey(Config.currency()), members, countdown, open ? serviceLeft : 0);
            }
            PacketDistributor.sendToPlayer(p, status);
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("sign", sign.asLong());
        if (area != null) tag.putIntArray("area", new int[] {area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()});
        if (spawn != null) tag.putLong("spawn", spawn.asLong());
        tag.putInt("day", day);
        tag.putInt("register", register);
        ListTag list = new ListTag();
        for (String id : unlocked) list.add(StringTag.valueOf(id));
        tag.put("unlocked", list);
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
        long[] tablesOff = offTables.stream().mapToLong(BlockPos::asLong).toArray();
        tag.putLongArray("tables_off", tablesOff);
        if (delivery != null) tag.putLong("delivery", delivery.asLong());
        ListTag pending = new ListTag();
        for (Delivery d : deliveries) {
            // The count is saved apart from the stack: an order can be larger than one stack.
            CompoundTag t = new CompoundTag();
            t.put("stack", d.stack.copyWithCount(1).save(owner.registries));
            t.putInt("count", d.stack.getCount());
            t.putInt("ticks", d.ticks);
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
        return tag;
    }

    static Restaurant load(Restaurants owner, CompoundTag tag) {
        Restaurant r = new Restaurant(owner, BlockPos.of(tag.getLong("sign")));
        int[] a = tag.getIntArray("area");
        if (a.length == 6) r.area = new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]);
        if (tag.contains("spawn")) r.spawn = BlockPos.of(tag.getLong("spawn"));
        r.day = Math.max(1, tag.getInt("day"));
        r.register = Math.max(0, tag.getInt("register"));
        for (Tag t : tag.getList("unlocked", Tag.TAG_STRING)) r.unlocked.add(t.getAsString());
        for (Tag t : tag.getList("off", Tag.TAG_STRING)) r.off.add(t.getAsString());
        CompoundTag kindTag = tag.getCompound("kinds");
        for (String id : kindTag.getAllKeys()) if (Menu.CATEGORIES.contains(kindTag.getString(id))) r.kinds.put(id, kindTag.getString(id));
        for (Tag t : tag.getList("combos", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            List<String> ids = new ArrayList<>();
            for (Tag d : c.getList("dishes", Tag.TAG_STRING)) ids.add(d.getAsString());
            if (ids.size() >= 2 && ids.size() <= MAX_COMBO_DISHES && r.combos.size() < MAX_COMBOS)
                r.combos.add(new Combo(c.getString("id"), List.copyOf(ids), Math.max(1, c.getInt("price"))));
        }
        for (long p : tag.getLongArray("tables_off")) r.offTables.add(BlockPos.of(p));
        for (Tag t : tag.getList("custom", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            // An item of a mod that was removed is skipped. A price out of range is clamped to the allowed range.
            BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.tryParse(c.getString("item"))).ifPresent(item -> {
                ItemStack stack = new ItemStack(item);
                if (stack.isEmpty() || r.custom.size() >= MAX_CUSTOM) return;
                int price = Math.max(1, Math.min(Config.MAX_DISH_PRICE.get(), c.getInt("price")));
                r.custom.add(new Menu.Entry("custom:" + BuiltInRegistries.ITEM.getKey(item), stack, null, price, false, 0, 0));
            });
        }
        if (tag.contains("delivery")) r.delivery = BlockPos.of(tag.getLong("delivery"));
        for (Tag t : tag.getList("deliveries", Tag.TAG_COMPOUND)) {
            CompoundTag d = (CompoundTag) t;
            ItemStack.parse(owner.registries, d.getCompound("stack")).ifPresent(stack ->
                    r.deliveries.add(new Delivery(stack.copyWithCount(Math.max(1, d.getInt("count"))), d.getInt("ticks"))));
        }
        long[] d = tag.getLongArray("dirt");
        for (int i = 0; i + 1 < d.length; i += 2) r.dirt.put(BlockPos.of(d[i]), d[i + 1]);
        return r;
    }
}
