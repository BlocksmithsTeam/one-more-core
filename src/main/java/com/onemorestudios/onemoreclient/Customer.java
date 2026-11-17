package com.onemorestudios.onemoreclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Customer extends PathfinderMob {
    public enum State { WALKING_IN, WAITING_ORDER, WAITING_FOOD, EATING, LEAVING }

    /** One wanted item of an order. */
    public record Line(ItemStack stack, int price, boolean exact) {
        boolean matches(ItemStack held) {
            return exact ? ItemStack.isSameItemSameComponents(stack, held) : ItemStack.isSameItem(stack, held);
        }
    }

    private static final EntityDataAccessor<Byte> STATE = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> PATIENCE = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<List<ItemStack>> ORDER = SynchedEntityData.defineId(Customer.class, OneMoreClient.STACKS.get());
    private static final EntityDataAccessor<List<ItemStack>> SERVED = SynchedEntityData.defineId(Customer.class, OneMoreClient.STACKS.get());
    private static final EntityDataAccessor<Optional<BlockPos>> SURFACE = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<String> SKIN_NAME = SynchedEntityData.defineId(Customer.class, EntityDataSerializers.STRING);

    // ponytail: a customer that stands still this long, or walks longer than the timeout, is moved to its target.
    // Add better pathing if this shows in play.
    private static final int WALK_TIMEOUT = 600, STUCK_TICKS = 40;

    // Server only.
    private Restaurant restaurant;
    BlockPos seat;
    private final List<Line> wanted = new ArrayList<>();
    private int timer, stuck, bill;
    private Party party;
    private Vec3 standPos;
    SkinPool.Entry identity;

    /**
     * Customers that sit together at one table. A single customer is a party of one.
     * The party has one patience and pays one bill. The first member runs the party.
     */
    static class Party {
        final List<Customer> members = new ArrayList<>();
        // patience 0: the party does not wait yet (a member still walks).
        int patience, patienceMax = 1, bill;

        void start(int ticks) {
            patience = patienceMax = ticks;
            members.forEach(m -> m.entityData.set(PATIENCE, 1f));
        }

        void tick(Restaurant restaurant) {
            State state = members.get(0).getState();
            boolean same = members.stream().allMatch(m -> m.getState() == state);
            if (!same) return;
            if (state == State.WAITING_ORDER && patience == 0) {
                start(Config.ORDER_PATIENCE.get());
            } else if (state == State.WAITING_ORDER || state == State.WAITING_FOOD) {
                if (--patience <= 0) {
                    restaurant.lost += members.size();
                    List.copyOf(members).forEach(Customer::leave);
                } else if (patience % 10 == 0) {
                    members.forEach(m -> m.entityData.set(PATIENCE, patience / (float) patienceMax));
                }
            } else if (state == State.EATING && members.get(0).timer > Config.EAT_TICKS.get()) {
                restaurant.served += members.size();
                restaurant.earned += bill;
                restaurant.addMoney(bill);
                for (Customer m : List.copyOf(members)) {
                    if (Config.DIRT_ENABLED.get()) restaurant.setDirt((ServerLevel) m.level(), m.getSurface(), Dirt.FULL);
                    m.leave();
                }
            }
        }
    }

    public Customer(EntityType<? extends Customer> type, Level level) {
        super(type, level);
        ((GroundPathNavigation) getNavigation()).setCanOpenDoors(true);
    }

    static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.3);
    }

    void setup(Restaurant restaurant, Restaurant.Seat seat, List<Line> order, Party party) {
        this.restaurant = restaurant;
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
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
    }

    public State getState() { return State.values()[entityData.get(STATE)]; }
    /** Patience that is left, from 0 to 1. */
    public float getPatience() { return entityData.get(PATIENCE); }
    /** The items the customer still waits for. Empty until a player takes the order. */
    public List<ItemStack> getOrder() { return entityData.get(ORDER); }
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
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (restaurant == null || !restaurant.isOpen()) { discard(); return; }
        timer++;
        if (party.members.get(0) == this) party.tick(restaurant);
        switch (getState()) {
            case WALKING_IN -> {
                if (walkTo(seat)) sit();
            }
            case WAITING_ORDER, WAITING_FOOD, EATING -> holdSeat();
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
            for (Customer m : party.members) {
                m.setState(State.WAITING_FOOD);
                m.syncOrder();
            }
        } else {
            ItemStack held = player.getItemInHand(hand);
            if (!deliver(player, held) && !held.isEmpty()) {
                party.patience = Math.max(1, party.patience - Config.WRONG_PENALTY.get());
                player.displayClientMessage(Component.translatable("onemoreclient.customer.wrong"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** True if the customer still waits for this item. Works on both sides; the client only compares the item type. */
    public boolean wants(ItemStack held) {
        if (getState() != State.WAITING_FOOD || held.isEmpty()) return false;
        if (level().isClientSide) return getOrder().stream().anyMatch(s -> ItemStack.isSameItem(s, held));
        return wanted.stream().anyMatch(l -> l.matches(held));
    }

    /** Takes one matching item from the stack. Server only. */
    public boolean deliver(Player player, ItemStack held) {
        if (!wants(held)) return false;
        Line line = wanted.stream().filter(l -> l.matches(held)).findFirst().orElseThrow();
        wanted.remove(line);
        bill += line.price();
        List<ItemStack> served = new ArrayList<>(getServed());
        served.add(held.copyWithCount(1));
        entityData.set(SERVED, served);
        held.consume(1, player);
        syncOrder();
        // A member that has all its food waits until every member is served.
        if (party.members.stream().allMatch(m -> m.wanted.isEmpty())) {
            int price = party.members.stream().mapToInt(m -> m.bill).sum();
            party.bill = payment(price, party.patience / (float) party.patienceMax, Config.MAX_TIP.get());
            party.members.forEach(m -> m.setState(State.EATING));
        }
        return true;
    }

    /** Order price plus a tip. The tip is maxTipPercent of the price when no patience was used, and 0 when none is left. */
    static int payment(int price, float patienceLeft, int maxTipPercent) {
        return price + (int) (price * (long) maxTipPercent * Mth.clamp(patienceLeft, 0, 1) / 100);
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
    int bill() { return bill; }
    Party party() { return party; }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (level() instanceof ServerLevel && restaurant != null) {
            restaurant.customers.remove(this);
            party.members.remove(this);
        }
    }
}
