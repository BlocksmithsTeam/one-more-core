package com.onemorestudios.onemoreclient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

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
    BlockPos delivery;
    final List<Delivery> deliveries = new ArrayList<>();

    // Not saved: a restaurant is closed after a world load, and the day starts again.
    boolean open;
    int toSpawn, spawnTimer, served, lost, earned;
    final Set<UUID> ready = new HashSet<>();
    List<Seat> seats = List.of();
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
        register += amount;
        owner.setDirty();
    }

    public boolean isAvailable(Menu.Entry e) {
        return day >= e.day() && (e.cost() == 0 || unlocked.contains(e.id()));
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
        for (Delivery d : List.copyOf(deliveries)) {
            if (--d.ticks > 0) continue;
            deliveries.remove(d);
            deliver(level, d.stack);
            owner.setDirty();
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

    int takeMoney() {
        int n = register;
        register = 0;
        owner.setDirty();
        return n;
    }

    /** A player clicked the sign of the closed restaurant. */
    void requestOpen(ServerLevel level, Player player) {
        String problem = area == null ? "no_area" : spawn == null ? "no_spawn"
                : Menu.entries.stream().noneMatch(this::isAvailable) ? "no_menu" : null;
        if (problem == null && (seats = scanSeats(level)).isEmpty()) problem = "no_seats";
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
            broadcast(level, Component.translatable("onemoreclient.open.ready", player.getDisplayName(), ready.size(), needed.size()));
            return;
        }
        ready.clear();
        open = true;
        served = lost = earned = 0;
        toSpawn = Config.CUSTOMERS_BASE.get() + Config.CUSTOMERS_PER_DAY.get() * (day - 1);
        spawnTimer = 40;
        broadcast(level, Component.translatable("onemoreclient.open.started", day, toSpawn));
    }

    void tick(ServerLevel level) {
        if (!level.isLoaded(sign)) return;
        BlockState state = level.getBlockState(sign);
        if (!state.is(OneMoreClient.OPEN_SIGN)) return;
        tickDeliveries(level);
        if (state.getValue(OpenSignBlock.OPEN) != open) level.setBlockAndUpdate(sign, state.setValue(OpenSignBlock.OPEN, open));
        if (!open) return;
        customers.removeIf(Entity::isRemoved);
        if (toSpawn > 0) {
            if (--spawnTimer > 0) return;
            Seat seat = freeSeat(level);
            if (seat == null) return;
            List<Customer.Line> order = makeOrder(level);
            if (order.isEmpty()) { toSpawn = 0; return; }
            Customer c = new Customer(OneMoreClient.CUSTOMER.get(), level);
            c.setup(this, seat, order);
            c.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
            level.addFreshEntity(c);
            customers.add(c);
            toSpawn--;
            spawnTimer = Config.SPAWN_INTERVAL.get();
        } else if (customers.isEmpty()) {
            open = false;
            broadcast(level, Component.translatable("onemoreclient.day.end", day, served, lost, earned));
            day++;
            owner.setDirty();
        }
    }

    private List<Customer.Line> makeOrder(ServerLevel level) {
        List<Menu.Entry> available = Menu.entries.stream().filter(this::isAvailable).toList();
        List<Customer.Line> order = new ArrayList<>();
        if (available.isEmpty()) return order;
        int n = 1 + level.random.nextInt(Config.MAX_ORDER_ITEMS.get());
        for (int i = 0; i < n; i++) {
            Menu.Entry e = available.get(level.random.nextInt(available.size()));
            ItemStack stack = e.pick(level.random);
            if (!stack.isEmpty()) order.add(new Customer.Line(stack, e.price(), e.exact()));
        }
        return order;
    }

    private Seat freeSeat(ServerLevel level) {
        List<Seat> free = seats.stream().filter(s -> isSeat(level, s)
                && customers.stream().noneMatch(c -> s.seat().equals(c.seat))).toList();
        return free.isEmpty() ? null : free.get(level.random.nextInt(free.size()));
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

    void broadcast(ServerLevel level, Component message) {
        BoundingBox near = area.inflatedBy(16);
        for (ServerPlayer p : level.players()) if (near.isInside(p.blockPosition())) p.sendSystemMessage(message);
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
        if (tag.contains("delivery")) r.delivery = BlockPos.of(tag.getLong("delivery"));
        for (Tag t : tag.getList("deliveries", Tag.TAG_COMPOUND)) {
            CompoundTag d = (CompoundTag) t;
            ItemStack.parse(owner.registries, d.getCompound("stack")).ifPresent(stack ->
                    r.deliveries.add(new Delivery(stack.copyWithCount(Math.max(1, d.getInt("count"))), d.getInt("ticks"))));
        }
        return r;
    }
}
