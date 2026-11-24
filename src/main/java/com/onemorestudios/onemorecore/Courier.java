package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** A courier transports a paid order whose contents and progress belong to its restaurant. */
public class Courier extends PathfinderMob {
    static final int ROUTE_TIMEOUT = 60 * 20;
    private static final int SPAWN_DISTANCE = 8;
    private static final int NO_PATH_GRACE = 10;
    private static final EntityDataAccessor<Boolean> CARRYING =
            SynchedEntityData.defineId(Courier.class, EntityDataSerializers.BOOLEAN);

    private UUID orderId;
    private BlockPos sign;
    private BlockPos start;
    private BlockPos unroutableAt;
    private int travelTicks;
    private int noPathTicks;

    public Courier(EntityType<? extends Courier> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCustomNameVisible(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMobAttributes().add(Attributes.MOVEMENT_SPEED, 0.26).add(Attributes.FOLLOW_RANGE, 72);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CARRYING, false);
    }

    @Override
    public Component getName() {
        return getType().getDescription();
    }

    public boolean carrying() {
        return entityData.get(CARRYING);
    }

    static UUID dispatch(ServerLevel level, Restaurant restaurant, Restaurant.Delivery delivery) {
        BlockPos target = delivery.target(restaurant.sign);
        BlockPos origin = restaurant.spawn();
        if (origin == null) origin = findSpawn(level, target, restaurant.area());
        if (origin == null) origin = level.isEmptyBlock(target) ? target : target.above();
        if (!level.isLoaded(origin)) return null;

        Courier courier = OneMoreCore.COURIER.get().create(level);
        if (courier == null) return null;
        courier.sign = restaurant.sign;
        courier.start = origin;
        courier.orderId = delivery.id;
        courier.entityData.set(CARRYING, true);
        Vec3 at = Vec3.atBottomCenterOf(origin);
        courier.moveTo(at.x, at.y, at.z, 0, 0);
        return level.addFreshEntity(courier) ? courier.getUUID() : null;
    }

    private static BlockPos findSpawn(ServerLevel level, BlockPos target, BoundingBox area) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-10, -2, -10), target.offset(10, 2, 10))) {
            int dx = pos.getX() - target.getX();
            int dz = pos.getZ() - target.getZ();
            int score = Math.abs(dx * dx + dz * dz - SPAWN_DISTANCE * SPAWN_DISTANCE)
                    + Math.abs(pos.getY() - target.getY()) * 4;
            if (area != null && area.isInside(pos)) score += 256;
            if (score < bestScore && canPlace(level, pos) && level.isEmptyBlock(pos.above())) {
                best = pos.immutable();
                bestScore = score;
            }
        }
        return best;
    }

    @Override
    public void tick() {
        if (isNoAi() && orderId != null && level() instanceof ServerLevel level) {
            Restaurant restaurant = Restaurants.get(level).at(sign);
            if (restaurant == null || restaurant.courierOrder(orderId, getUUID()) == null) {
                vanish(level);
                return;
            }
        }
        super.tick();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(level() instanceof ServerLevel level)) return;
        if (start == null) {
            vanish(level);
            return;
        }
        Restaurant restaurant = Restaurants.get(level).at(sign);
        Restaurant.Delivery order = restaurant == null ? null : restaurant.courierOrder(orderId, getUUID());
        boolean outbound = orderId != null;
        if (outbound && order == null) {
            vanish(level);
            return;
        }
        BlockPos target = outbound ? order.target(sign) : start;
        if (outbound) {
            long now = level.getGameTime();
            if (now >= order.fallbackAt || !level.isLoaded(target)) {
                getNavigation().stop();
                return;
            }
            if (closeToDelivery(target) || unroutableAt != null) {
                unloadOrder(level, restaurant, order, target);
                return;
            }
        } else if (atStart(target) || ++travelTicks >= ROUTE_TIMEOUT) {
            vanish(level);
            return;
        }
        if (getNavigation().isInProgress()) return;
        Set<BlockPos> stops = new LinkedHashSet<>();
        if (outbound) {
            for (BlockPos pos : boxSpots(target)) if (canStand(level, pos)) stops.add(pos);
            if (canStand(level, target.above())) stops.add(target.above());
        } else if (level.isLoaded(target)) {
            stops.add(target);
        }
        var path = stops.isEmpty() ? null : getNavigation().createPath(stops, 0);
        if (path != null && path.canReach() && getNavigation().moveTo(path, 1.0)) {
            noPathTicks = 0;
            return;
        }
        // On the first tick after spawning or loading, ground navigation can still see the courier as airborne.
        if (++noPathTicks < NO_PATH_GRACE) return;
        if (!outbound) {
            vanish(level);
            return;
        }
        BlockPos here = blockPosition();
        BoundingBox area = restaurant.area();
        boolean nearArea = area == null ? here.distManhattan(sign) <= 1 : area.inflatedBy(1).isInside(here);
        unroutableAt = nearArea ? here : start;
        unloadOrder(level, restaurant, order, target);
    }

    private void unloadOrder(ServerLevel level, Restaurant restaurant, Restaurant.Delivery order, BlockPos target) {
        getNavigation().stop();
        BlockPos at = unroutableAt != null && level.isLoaded(unroutableAt) ? unroutableAt : target;
        boolean useContainer = at.equals(target) && order.destination != null;
        if (!deliver(level, restaurant, order, at, useContainer, this)) return;
        if (unroutableAt != null) {
            vanish(level);
        } else {
            orderId = null;
            entityData.set(CARRYING, false);
            noPathTicks = 0;
        }
    }

    private boolean closeToDelivery(BlockPos target) {
        BlockPos here = blockPosition();
        return Math.abs(here.getX() - target.getX()) <= 1 && Math.abs(here.getZ() - target.getZ()) <= 1
                && (here.getY() == target.getY() || here.getX() == target.getX() && here.getZ() == target.getZ()
                && here.getY() == target.getY() + 1);
    }

    private boolean atStart(BlockPos target) {
        return distanceToSqr(Vec3.atBottomCenterOf(target)) < 0.5;
    }

    static boolean deliver(ServerLevel level, Restaurant restaurant, Restaurant.Delivery order, BlockPos at,
            boolean useContainer, Courier courier) {
        long now = level.getGameTime();
        if (now < order.retryAt || restaurant.deliveryStacksLeft(now) == 0) return false;
        var container = useContainer ? Restaurant.container(level, at) : null;
        List<ItemStack> parcel = new ArrayList<>();
        boolean progressed = false;
        for (var pending = order.items.iterator(); pending.hasNext();) {
            ItemStack stack = pending.next();
            while (!stack.isEmpty() && restaurant.takeDeliveryStack(now)) {
                ItemStack part = stack.split(stack.getMaxStackSize());
                ItemStack remainder = container == null ? part : ItemHandlerHelper.insertItemStacked(container, part, false);
                if (!remainder.isEmpty()) parcel.add(remainder);
                progressed = true;
            }
            if (!stack.isEmpty()) break;
            pending.remove();
        }
        if (!progressed) return false;
        if (!parcel.isEmpty() && !leaveParcel(level, at, parcel, courier)) {
            order.items.addAll(parcel);
            order.retryAt = now + 20;
        }
        if (!restaurant.deliveryProgress(order)) return false;

        level.playSound(null, at, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.75f, 0.85f);
        if (restaurant.area() != null) {
            for (var player : level.players()) {
                if (restaurant.area().isInside(player.blockPosition()))
                    player.sendSystemMessage(Component.translatable("onemorecore.courier.arrived"));
            }
        }
        return true;
    }

    private static boolean leaveParcel(ServerLevel level, BlockPos at, List<ItemStack> parcel, Courier courier) {
        Direction facing = courier == null ? Direction.NORTH : courier.getDirection().getOpposite();
        for (BlockPos pos : boxSpots(at)) {
            if (canPlace(level, pos) && level.getEntities(courier, new AABB(pos), Entity::isPickable).isEmpty()
                    && level.setBlock(pos, OneMoreCore.CARDBOARD_BOX.get().defaultBlockState()
                            .setValue(CardboardBoxBlock.FACING, facing), Block.UPDATE_ALL)
                    && level.getBlockEntity(pos) instanceof CardboardBoxBlockEntity box) {
                for (int slot = 0; slot < parcel.size(); slot++) box.items.setItem(slot, parcel.get(slot));
                return true;
            }
        }
        return dropParcel(level, at, CardboardBoxBlockEntity.parcel(parcel));
    }

    private static List<BlockPos> boxSpots(BlockPos center) {
        List<BlockPos> positions = new ArrayList<>(9);
        positions.add(center);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dz != 0) positions.add(center.offset(dx, 0, dz));
            }
        }
        return positions;
    }

    private static boolean canPlace(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos) && level.isLoaded(pos)
                && level.isEmptyBlock(pos) && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private static boolean canStand(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos) && level.isLoaded(pos)
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }

    static boolean dropParcel(ServerLevel level, BlockPos at, ItemStack parcel) {
        if (!level.isLoaded(at)) return false;
        if (!level.isEmptyBlock(at)) {
            for (int dy = 0; dy <= 3; dy++) {
                var free = boxSpots(at.above(dy)).stream()
                        .filter(pos -> level.isInWorldBounds(pos) && level.isLoaded(pos) && level.isEmptyBlock(pos))
                        .findFirst();
                if (free.isPresent()) {
                    at = free.get();
                    break;
                }
            }
        }
        double y = Math.clamp(at.getY() + 0.65, level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1);
        ItemEntity drop = new ItemEntity(level, at.getX() + 0.5, y, at.getZ() + 0.5, parcel, 0, 0, 0);
        drop.setDefaultPickUpDelay();
        drop.setUnlimitedLifetime();
        drop.setInvulnerable(true);
        // An overflow parcel in an unsupported or obstructed spot stays available at its drop height.
        drop.setNoGravity(true);
        return level.addFreshEntity(drop);
    }

    private void vanish(ServerLevel level) {
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.75, getZ(), 14, 0.24, 0.36, 0.24, 0.035);
        discard();
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    public boolean shouldDropLoot() {
        return false;
    }

    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    @Override
    public void kill() {
        remove(RemovalReason.KILLED);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (start == null) return;
        tag.putLong("sign", sign.asLong());
        tag.putLong("start", start.asLong());
        if (orderId != null) tag.putUUID("order", orderId);
        else tag.putBoolean("returning", true);
        if (unroutableAt != null) tag.putLong("unroutable_at", unroutableAt.asLong());
        tag.putInt("travel_ticks", travelTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (!tag.contains("start", Tag.TAG_LONG) || !tag.hasUUID("order") && !tag.getBoolean("returning")) return;
        sign = BlockPos.of(tag.getLong("sign"));
        start = BlockPos.of(tag.getLong("start"));
        orderId = tag.hasUUID("order") ? tag.getUUID("order") : null;
        unroutableAt = tag.contains("unroutable_at", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("unroutable_at")) : null;
        travelTicks = tag.getInt("travel_ticks");
        entityData.set(CARRYING, orderId != null);
    }

    static ListTag saveOrder(List<ItemStack> stacks, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            CompoundTag entry = new CompoundTag();
            // A paid line can exceed vanilla's serialized stack size limit.
            entry.put("stack", stack.copyWithCount(1).save(registries));
            entry.putInt("count", stack.getCount());
            list.add(entry);
        }
        return list;
    }

    static List<ItemStack> loadOrder(ListTag list, HolderLookup.Provider registries) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Tag entry : list) readLine((CompoundTag) entry, registries).ifPresent(stacks::add);
        return stacks;
    }

    static Optional<ItemStack> readLine(CompoundTag entry, HolderLookup.Provider registries) {
        return ItemStack.parse(registries, entry.getCompound("stack"))
                .map(stack -> stack.copyWithCount(Math.max(1, entry.getInt("count"))));
    }
}
