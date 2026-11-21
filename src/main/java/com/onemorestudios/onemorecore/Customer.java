package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Customer extends PathfinderMob {
    public enum State { WALKING_IN, WAITING_ORDER, WAITING_FOOD, EATING, LEAVING }

    /** One wanted item of an order. */
    /**
     * One wanted item of an order. tipPercent scales the tip of this line (a price far above the suggested price gets
     * less); drink: it is served in a glass, not on a plate.
     */
    public record Line(ItemStack stack, int price, boolean exact, int tipPercent, boolean drink) {
        public Line(ItemStack stack, int price, boolean exact) {
            this(stack, price, exact, 100, false);
        }

        boolean matches(ItemStack held) {
            return exact ? ItemStack.isSameItemSameComponents(stack, held) : ItemStack.isSameItem(stack, held);
        }
    }

    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> PATIENCE = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<List<ItemStack>> ORDER = SynchedEntityData.defineId(Customer.class, OneMoreCore.STACKS.get());
    private static final EntityDataAccessor<List<ItemStack>> SERVED = SynchedEntityData.defineId(Customer.class, OneMoreCore.STACKS.get());
    private static final EntityDataAccessor<Optional<BlockPos>> SURFACE = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> GUEST = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.BYTE);

    // A customer that stands still this long, or walks longer than the timeout, is moved to its target.
    private static final int WALK_TIMEOUT = 600, STUCK_TICKS = 40;

    // Server only.
    private Restaurant restaurant;
    BlockPos seat;
    private final List<Line> wanted = new ArrayList<>();
    private int timer, stuck;
    /** Where the customer was at the last progress check while the navigation was moving. */
    private Vec3 lastProgress;
    private long bill;
    /** Price times tipPercent of the served lines: the base of the tip. */
    private long tipBase;
    /** Served lines on a plate and in a glass; dirty after eating. Server only. */
    /** Ids of the dishes that this customer ordered, for the favourites of the restaurant. Server only. */
    final List<String> dishIds = new ArrayList<>();
    private Party party;
    private Vec3 standPos;
    SkinPool.Entry identity;

    /**
     * Customers that sit together at one table. A single customer is a party of one.
     * The party has one patience and pays one bill. The first member runs the party.
     */
    /** Kinds of guests. patience: factor of the patience; tip: percent of the normal tip. */
    public enum Type {
        NORMAL(1, 100), REGULAR(1.2, 150), VIP(0.6, 200), FAMILY(1.2, 100);

        final double patience;
        final int tip;

        Type(double patience, int tip) {
            this.patience = patience;
            this.tip = tip;
        }
    }

    /** A party that waited well (more than half of its patience left) makes its dishes favourites of the restaurant. */
    void rememberFavourites(Restaurant restaurant) {
        if (party.patienceMax > 0 && party.patience * 2 > party.patienceMax) restaurant.favourites.addAll(dishIds);
    }

    static class Party {
        final List<Customer> members = new ArrayList<>();
        // patience 0: the party does not wait yet (a member still walks).
        int patience, patienceMax = 1, bill;
        boolean settled;
        /** What kind of guests the party is; it changes the patience and the tip. */
        Type type = Type.NORMAL;

        void settle(Restaurant restaurant, boolean paid) {
            if (settled) return;
            settled = true;
            if (paid) {
                restaurant.served += members.size();
                restaurant.earned = (int) Math.min(Integer.MAX_VALUE, (long) restaurant.earned + bill);
                // The bill stays on the table as coins until a player collects it (collectPayments), or goes to the register.
                if (Config.COLLECT_PAYMENTS.get() && members.get(0).getSurface() != null) restaurant.addCoins(members.get(0).getSurface(), bill);
                else restaurant.addMoney(bill);
                for (Customer m : members) m.rememberFavourites(restaurant);
            } else {
                restaurant.lost += members.size();
                long penalty = Math.min(restaurant.register(), (long) members.size() * Config.LOST_CUSTOMER_PENALTY.get());
                if (penalty > 0) restaurant.penalties += restaurant.take((int) penalty);
                int maximum = Config.MAX_LOST_PER_DAY.get();
                if (maximum > 0 && restaurant.lost >= maximum)
                    restaurant.endDay((ServerLevel) members.get(0).level(), true);
            }
        }

        void start(int ticks) {
            // Decor upgrades give more patience; the type of guests changes it too.
            Restaurant r = members.isEmpty() ? null : members.get(0).restaurant;
            double decor = r == null ? 1 : 1 + 0.1 * r.upgrade("decor");
            ticks = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(ticks * decor * type.patience)));
            patience = patienceMax = ticks;
            members.forEach(m -> m.entityData.set(PATIENCE, 1f));
        }

        void tick(Restaurant restaurant) {
            if (settled) return;
            State state = members.get(0).getState();
            boolean same = members.stream().allMatch(m -> m.getState() == state);
            if (!same) return;
            if (state == State.WAITING_ORDER && patience == 0) {
                start(Config.ORDER_PATIENCE.get());
                members.get(0).effect(SoundEvents.NOTE_BLOCK_BELL.value(), null);
            } else if (state == State.WAITING_ORDER || state == State.WAITING_FOOD) {
                if (--patience <= 0) {
                    settle(restaurant, false);
                    for (Customer m : List.copyOf(members)) {
                        m.effect(SoundEvents.VILLAGER_NO, ParticleTypes.ANGRY_VILLAGER);
                        m.leave();
                    }
                } else if (patience % 10 == 0) {
                    members.forEach(m -> m.entityData.set(PATIENCE, patience / (float) patienceMax));
                }
            } else if (state == State.EATING && members.get(0).timer > Config.EAT_TICKS.get()) {
                settle(restaurant, true);
                if (Config.DIRT_ENABLED.get()) restaurant.soilTable((ServerLevel) members.get(0).level(), List.copyOf(members));
                members.get(0).effect(SoundEvents.EXPERIENCE_ORB_PICKUP, null);
                for (Customer m : List.copyOf(members)) {
                    m.effect(null, ParticleTypes.HAPPY_VILLAGER);
                    m.leave();
                }
            }
        }
    }

    public Customer(EntityType<? extends Customer> type, Level level) {
        super(type, level);
        ((GroundPathNavigation) getNavigation()).setCanOpenDoors(true);
    }

    /**
     * Ground navigation that does not walk into or over the restaurant's seats and serving surfaces. Furniture mods give
     * chairs and tables low or partial collision shapes; the vanilla path finder then takes them as floor, and the
     * customer walks into the back of a chair and stays there. With them blocked, a path to a seat ends next to it, and
     * the customer sits from there. Only blocks that are seats of this restaurant (a seat block next to a table) count:
     * the seats tag also holds every stair, and a staircase must stay walkable.
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                nodeEvaluator = new WalkNodeEvaluator() {
                    @Override
                    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
                        if (furniture(context.level(), x, y, z) || furniture(context.level(), x, y - 1, z)) return PathType.BLOCKED;
                        return super.getPathType(context, x, y, z);
                    }
                };
                nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    /** The seat positions of the restaurant, rebuilt when its seat list changes. */
    private java.util.Set<BlockPos> seatSet = java.util.Set.of();
    private List<Restaurant.Seat> seatSetFrom;

    private boolean furniture(BlockGetter level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBlockState(pos).is(OneMoreCore.SURFACES)) return true;
        if (restaurant == null) return false;
        if (seatSetFrom != restaurant.seats) {
            seatSetFrom = restaurant.seats;
            java.util.Set<BlockPos> set = new java.util.HashSet<>();
            for (Restaurant.Seat s : restaurant.seats) set.add(s.seat());
            seatSet = set;
        }
        return seatSet.contains(pos);
    }

    static AttributeSupplier.Builder createAttributes() {
        // The follow range limits how far the path finder searches: 16, the vanilla value, is too short for a seat on an
        // upper floor of a large restaurant.
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FOLLOW_RANGE, 64);
    }

    void setup(Restaurant restaurant, Restaurant.Seat seat, List<Line> order, Party party) {
        this.restaurant = restaurant;
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(Config.WALK_SPEED.get());
        this.party = party;
        party.members.add(this);
        this.seat = seat.seat();
        wanted.addAll(order);
        entityData.set(SURFACE, Optional.of(seat.surface()));
        setIdentity(SkinPool.pick(random, restaurant.customers));
    }

    /** The skin comes from the Minecraft account; the visible name is the vanilla custom name. Null keeps a default skin and no name. */
    void setIdentity(SkinPool.Entry entry) {
        identity = entry;
        if (entry == null) return;
        entityData.set(SKIN_NAME, entry.minecraftName());
        setCustomName(Component.literal(entry.displayName()));
        setCustomNameVisible(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, (byte) 0);
        builder.define(PATIENCE, 1f);
        builder.define(ORDER, List.of());
        builder.define(SERVED, List.of());
        builder.define(SURFACE, Optional.empty());
        builder.define(SKIN_NAME, "");
        builder.define(GUEST, (byte) 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
    }

    public State getState() { return State.values()[entityData.get(STATE)]; }
    /** The kind of guests of the customer's party; the client shows a badge for regulars, VIPs and families. */
    public Type getGuestType() { return Type.values()[Math.floorMod(entityData.get(GUEST), Type.values().length)]; }
    void setType(Type type) { entityData.set(GUEST, (byte) type.ordinal()); }
    /** Patience that is left, from 0 to 1. */
    public float getPatience() { return entityData.get(PATIENCE); }
    /** The items the customer still waits for. Empty until a player takes the order. */
    public List<ItemStack> getOrder() { return entityData.get(ORDER); }
    /** Server: the lines still wanted (copy). */
    List<Line> wantedLines() { return List.copyOf(wanted); }
    public List<ItemStack> getServed() { return entityData.get(SERVED); }
    public BlockPos getSurface() { return entityData.get(SURFACE).orElse(null); }
    /** The Minecraft account whose skin the customer wears. Empty: a default skin. */
    public String getSkinName() { return entityData.get(SKIN_NAME); }

    public boolean isSeated() {
        State s = getState();
        return s == State.WAITING_ORDER || s == State.WAITING_FOOD || s == State.EATING;
    }

    private void setState(State s) {
        entityData.set(STATE, (byte) s.ordinal());
        timer = stuck = 0;
        lastProgress = null;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (restaurant == null || !restaurant.isOpen()) { discard(); return; }
        timer++;
        if (party.members.get(0) == this) party.tick(restaurant);
        if (isRemoved()) return;
        switch (getState()) {
            case WALKING_IN -> {
                if (walkTo(seat)) sit();
            }
            case WAITING_ORDER, WAITING_FOOD -> holdSeat();
            case EATING -> {
                holdSeat();
                if (timer % 16 == 0) eatEffect();
            }
            case LEAVING -> {
                if (walkTo(restaurant.spawn)) discard();
            }
        }
    }

    /** Walks toward the block. True when the customer is there, or cannot get closer. */
    private boolean walkTo(BlockPos pos) {
        Vec3 target = Vec3.atBottomCenterOf(pos);
        if (position().distanceToSqr(target) < 2.25 || timer > WALK_TIMEOUT) return true;
        if (!getNavigation().isDone()) {
            stuck = 0;
            // A path that does not move the customer: a block in the way that the path finder took as free.
            // Stop it; the code below makes a new path and gives up after STUCK_TICKS.
            if (timer % 20 == 0) {
                if (lastProgress != null && lastProgress.distanceToSqr(position()) < 0.01) getNavigation().stop();
                lastProgress = position();
            }
        } else if (onGround()) {
            // A new mob is in the air for a tick, and the navigation refuses a path then.
            if (stuck++ > STUCK_TICKS) return true;
            if (stuck % 10 == 1) getNavigation().moveTo(target.x, target.y, target.z, 1);
        }
        return false;
    }

    private Vec3 seatPos() {
        // A sitting player model has its seat 0.6 above the entity position (Player.DEFAULT_VEHICLE_ATTACHMENT).
        double y = seat.getY() + Config.seatHeight(level().getBlockState(seat).getBlock()) - 0.6;
        return new Vec3(seat.getX() + 0.5, y, seat.getZ() + 0.5);
    }

    private void sit() {
        standPos = position();
        getNavigation().stop();
        noPhysics = true;
        setNoGravity(true);
        holdSeat();
        setState(State.WAITING_ORDER);
        party.tick(restaurant); // the patience starts in the tick in which the last member sits
    }

    private void holdSeat() {
        Vec3 p = seatPos();
        setDeltaMovement(Vec3.ZERO);
        setPos(p.x, p.y, p.z);
        BlockPos surface = getSurface();
        float yaw = (float) (Mth.atan2(surface.getZ() - seat.getZ(), surface.getX() - seat.getX()) * Mth.RAD_TO_DEG) - 90;
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
    }

    private void leave() {
        noPhysics = false;
        setNoGravity(false);
        setPos(standPos.x, standPos.y, standPos.z);
        entityData.set(ORDER, List.of());
        entityData.set(SERVED, List.of());
        setState(State.LEAVING);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        State state = getState();
        if (hand != InteractionHand.MAIN_HAND || (state != State.WAITING_ORDER && state != State.WAITING_FOOD)) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (state == State.WAITING_ORDER) {
            // The order of the whole party is taken at once, after the last member sat down.
            if (party.patience == 0) return InteractionResult.SUCCESS;
            party.start(Config.FOOD_PATIENCE.get());
            effect(SoundEvents.BOOK_PAGE_TURN, null);
            for (Customer m : party.members) {
                m.setState(State.WAITING_FOOD);
                m.syncOrder();
            }
        } else {
            ItemStack held = player.getItemInHand(hand);
            if (!deliver(player, held) && !held.isEmpty()) {
                party.patience = Math.max(1, party.patience - Config.WRONG_PENALTY.get());
                party.members.forEach(m -> m.entityData.set(PATIENCE, party.patience / (float) party.patienceMax));
                effect(SoundEvents.VILLAGER_NO, null);
                player.displayClientMessage(Component.translatable("onemorecore.customer.wrong"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** The dishes on a tray (empty for any other item). */
    static List<ItemStack> trayDishes(ItemStack tray) {
        if (!tray.is(OneMoreCore.TRAY_ITEM.get())) return List.of();
        List<ItemStack> out = new ArrayList<>();
        tray.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY)
                .nonEmptyItems().forEach(s -> out.add(s.copy()));
        return out;
    }

    /** True if the customer still waits for this item (on a tray: for one of its dishes). Works on both sides; the client only compares the item type. */
    public boolean wants(ItemStack held) {
        if (getState() != State.WAITING_FOOD || held.isEmpty()) return false;
        if (held.is(OneMoreCore.TRAY_ITEM.get())) return trayDishes(held).stream().anyMatch(this::wants);
        if (level().isClientSide) return getOrder().stream().anyMatch(s -> ItemStack.isSameItem(s, held));
        return wanted.stream().anyMatch(l -> l.matches(held));
    }

    /**
     * Serves a tray (every dish on it that this customer ordered; the tray stays in the hand with what is left) or one
     * matching item of the stack. With requireTray, food must come on a tray. Server only.
     */
    public boolean deliver(Player player, ItemStack held) {
        if (held.is(OneMoreCore.TRAY_ITEM.get())) {
            if (!wants(held)) return false;
            List<ItemStack> dishes = trayDishes(held), left = new ArrayList<>();
            for (ItemStack dish : dishes) {
                if (wants(dish)) serve(player, dish); else left.add(dish);
            }
            held.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(left));
            if (left.isEmpty()) held.remove(OneMoreCore.LABEL.get());
            checkServed();
            return true;
        }
        if (!wants(held)) return false;
        if (Config.REQUIRE_TRAY.get()) {
            player.displayClientMessage(Component.translatable("onemorecore.customer.use_tray"), true);
            return false;
        }
        serve(player, held);
        held.consume(1, player);
        checkServed();
        return true;
    }

    /** Serves one item of the stack (the stack itself is not changed). */
    private void serve(Player player, ItemStack held) {
        Line line = wanted.stream().filter(l -> l.matches(held)).findFirst().orElseThrow();
        wanted.remove(line);
        bill += line.price();
        tipBase += (long) line.price() * line.tipPercent();
        List<ItemStack> served = new ArrayList<>(getServed());
        // The main dish goes first: it stands big right in front of the guest, the rest smaller beside it.
        boolean main = restaurant != null && Menu.entries.stream().anyMatch(e -> "main".equals(restaurant.kind(e))
                && (e.tag() != null ? held.is(e.tag()) : ItemStack.isSameItem(held, e.stack())));
        if (main) served.add(0, held.copyWithCount(1)); else served.add(held.copyWithCount(1));
        entityData.set(SERVED, served);
        syncOrder();
        effect(SoundEvents.ITEM_PICKUP, null);
    }

    /** A member that has all its food waits until every member is served; then the party eats. */
    private void checkServed() {
        if (party.members.stream().allMatch(m -> m.wanted.isEmpty())) {
            long price = party.members.stream().mapToLong(m -> m.bill).sum();
            long base = party.members.stream().mapToLong(m -> m.tipBase).sum();
            // The tip: maxTipPercent of the tip base (prices weighted by their tipPercent), scaled by the patience left and
            // by the type of guests. Without weights (all 100 percent) this is the old formula.
            // tip = base * maxTip * typeTip * patience / (100 * 100 * 100 * patienceMax). The product can pass 2^63 (base up
            // to 9.6e9, maxTip 1000, typeTip 200, patience 72000), so it is computed exactly with BigInteger.
            java.math.BigInteger tip = java.math.BigInteger.valueOf(base).multiply(java.math.BigInteger.valueOf((long) Config.MAX_TIP.get() * party.type.tip))
                    .multiply(java.math.BigInteger.valueOf(Mth.clamp(party.patience, 0, party.patienceMax)))
                    .divide(java.math.BigInteger.valueOf(1_000_000L * party.patienceMax));
            party.bill = (int) Math.min(Integer.MAX_VALUE, java.math.BigInteger.valueOf(price).add(tip).min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).longValue());
            party.members.forEach(m -> m.setState(State.EATING));
        }
    }

    /** Plays a sound at the customer and shows particles above its head. Either can be null. Server only. */
    private void effect(SoundEvent sound, SimpleParticleType particle) {
        if (!(level() instanceof ServerLevel level)) return;
        if (sound != null) level.playSound(null, getX(), getY(), getZ(), sound, SoundSource.NEUTRAL, 0.8f, 0.9f + random.nextFloat() * 0.2f);
        if (particle != null) level.sendParticles(particle, getX(), getY() + getBbHeight() + 0.3, getZ(), 5, 0.3, 0.2, 0.3, 0);
    }

    /** Eating sound and crumbs of the first served item over the plate. */
    private void eatEffect() {
        List<ItemStack> served = getServed();
        BlockPos surface = getSurface();
        if (served.isEmpty() || surface == null || !(level() instanceof ServerLevel level)) return;
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.5f, 0.9f + random.nextFloat() * 0.2f);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, served.get(0)), surface.getX() + 0.5, surface.getY() + 1.1, surface.getZ() + 0.5, 4, 0.1, 0.05, 0.1, 0.05);
    }

    /** Order price plus a tip. The tip is maxTipPercent of the price when no patience was used, and 0 when none is left. */
    static int payment(long price, float patienceLeft, int maxTipPercent) {
        long tip = (long) (price * maxTipPercent * (double) Mth.clamp(patienceLeft, 0, 1) / 100);
        return (int) Math.min(Integer.MAX_VALUE, price + tip);
    }

    /** Keep patience as ticks so a rounded float cannot change the whole-number tip. */
    static int payment(long price, int patience, int patienceMax, int maxTipPercent) {
        long tip = price * maxTipPercent * Mth.clamp(patience, 0, patienceMax) / (100L * patienceMax);
        return (int) Math.min(Integer.MAX_VALUE, price + tip);
    }

    private void syncOrder() {
        entityData.set(ORDER, wanted.stream().map(Line::stack).toList());
    }

    /** The customer of this serving surface that waits for the item, or null. */
    public static Customer waitingAt(Level level, BlockPos surface, ItemStack held) {
        List<Customer> list = level.getEntitiesOfClass(Customer.class, new AABB(surface).inflate(2),
                c -> surface.equals(c.getSurface()) && c.wants(held));
        return list.isEmpty() ? null : list.get(0);
    }

    // The served food is drawn by the customer renderer on the surface next to the customer.
    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(2);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Override public boolean isPushable() { return false; }
    @Override protected void pushEntities() {}
    @Override public boolean removeWhenFarAway(double distance) { return false; }

    /** For tests. */
    Restaurant restaurant() { return restaurant; }
    long bill() { return bill; }
    Party party() { return party; }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        removeFromRestaurant();
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (getRemovalReason() != null) removeFromRestaurant();
    }

    void removeFromRestaurant() {
        if (level() instanceof ServerLevel && restaurant != null) {
            if (restaurant.isOpen() && getState() != State.LEAVING && !party.settled) {
                party.settle(restaurant, getState() == State.EATING);
                for (Customer c : List.copyOf(party.members)) if (c != this) c.discard();
            }
            if (getSurface() != null && restaurant.customers.contains(this)) restaurant.freed((ServerLevel) level(), getSurface());
            restaurant.customers.remove(this);
            restaurant.arriving.remove(this);
            party.members.remove(this);
        }
    }
}
