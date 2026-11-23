package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

/** Once dispatched, the saved courier owns the order independently of the sign and the delivery point. */
public class Courier extends PathfinderMob {
    private static final int ROUTE_TIMEOUT = 60 * 20;
    private static final int SPAWN_DISTANCE = 8;
    private static final int NO_PATH_GRACE = 10;
    private static final EntityDataAccessor<Boolean> CARRYING =
            SynchedEntityData.defineId(Courier.class, EntityDataSerializers.BOOLEAN);

    private final List<ItemStack> items = new ArrayList<>();
    private BlockPos sign;
    private BlockPos start;
    private BlockPos destination;
    private boolean boxesOnly;
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

    static boolean dispatch(ServerLevel level, Restaurant restaurant, Restaurant.Delivery delivery) {
        BlockPos target = delivery.destination == null ? restaurant.sign : delivery.destination;
        BlockPos origin = restaurant.spawn();
        if (origin == null) origin = findSpawn(level, target, restaurant.area());
        if (origin == null) origin = level.isEmptyBlock(target) ? target : target.above();
        if (!level.isLoaded(origin)) return false;

        Courier courier = OneMoreCore.COURIER.get().create(level);
        if (courier == null) return false;
        courier.sign = restaurant.sign;
        courier.start = origin;
        courier.destination = target;
        courier.boxesOnly = delivery.destination == null;
        delivery.items.forEach(stack -> courier.items.add(stack.copy()));
        courier.entityData.set(CARRYING, true);
        Vec3 at = Vec3.atBottomCenterOf(origin);
        courier.moveTo(at.x, at.y, at.z, 0, 0);
        return level.addFreshEntity(courier);
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
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(level() instanceof ServerLevel level) || start == null) return;
        boolean outbound = !items.isEmpty();
        BlockPos target = outbound ? destination : start;
        if ((outbound && level.isLoaded(target) && closeToDelivery(target))
                || (!outbound && atStart(target))) {
            if (outbound) {
                deliver(level, target, !boxesOnly);
                getNavigation().stop();
                travelTicks = 0;
                noPathTicks = 0;
            } else {
                vanish(level);
            }
            return;
        }
        if (++travelTicks < ROUTE_TIMEOUT) {
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
        }
        if (outbound) {
            BlockPos here = blockPosition();
            Restaurant restaurant = Restaurants.get(level).at(sign);
            BoundingBox area = restaurant == null ? null : restaurant.area();
            boolean nearArea = area == null ? here.distManhattan(sign) <= 1 : area.inflatedBy(1).isInside(here);
            deliver(level, nearArea ? here : start, false);
        }
        vanish(level);
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

    private void deliver(ServerLevel level, BlockPos at, boolean useContainer) {
        var container = useContainer ? Restaurant.container(level, at) : null;
        if (container != null) {
            items.replaceAll(stack -> {
                while (!stack.isEmpty()) {
                    ItemStack part = stack.split(stack.getMaxStackSize());
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(container, part, false);
                    if (!remainder.isEmpty()) {
                        stack.grow(remainder.getCount());
                        break;
                    }
                }
                return stack;
            });
            items.removeIf(ItemStack::isEmpty);
        }
        leaveParcels(level, at);

        level.playSound(null, at, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.75f, 0.85f);
        Restaurant restaurant = Restaurants.get(level).at(sign);
        if (restaurant != null && restaurant.area() != null) {
            for (var player : level.players()) {
                if (restaurant.area().isInside(player.blockPosition()))
                    player.sendSystemMessage(Component.translatable("onemorecore.courier.arrived"));
            }
        }
    }

    private void leaveParcels(ServerLevel level, BlockPos at) {
        if (items.isEmpty()) return;
        var spots = boxSpots(at).iterator();
        var pending = items.iterator();
        ItemStack stack = pending.next();
        while (!stack.isEmpty()) {
            List<ItemStack> parcel = new ArrayList<>(CardboardBoxBlockEntity.CAPACITY);
            while (parcel.size() < CardboardBoxBlockEntity.CAPACITY && !stack.isEmpty()) {
                parcel.add(stack.split(stack.getMaxStackSize()));
                if (stack.isEmpty() && pending.hasNext()) stack = pending.next();
            }
            boolean placed = false;
            while (spots.hasNext() && !placed) {
                BlockPos pos = spots.next();
                if (canPlace(level, pos) && level.getEntities(this, new AABB(pos), entity -> entity.isPickable()).isEmpty()
                        && level.setBlock(pos, OneMoreCore.CARDBOARD_BOX.get().defaultBlockState()
                                .setValue(CardboardBoxBlock.FACING, getDirection().getOpposite()), Block.UPDATE_ALL)
                        && level.getBlockEntity(pos) instanceof CardboardBoxBlockEntity box) {
                    for (int slot = 0; slot < parcel.size(); slot++) box.items.setItem(slot, parcel.get(slot));
                    placed = true;
                }
            }
            if (!placed) {
                ItemStack packed = CardboardBoxBlockEntity.parcel(parcel);
                if (!dropParcel(level, at, packed)) Restaurants.get(level).keepParcel(at, packed);
            }
        }
        items.clear();
        entityData.set(CARRYING, false);
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
    public void remove(RemovalReason reason) {
        if (!isRemoved() && reason.shouldDestroy() && level() instanceof ServerLevel level)
            leaveParcels(level, blockPosition());
        super.remove(reason);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (start == null) return;
        tag.putLong("sign", sign.asLong());
        tag.putLong("start", start.asLong());
        tag.putLong("destination", destination.asLong());
        tag.putBoolean("boxes_only", boxesOnly);
        tag.putInt("travel_ticks", travelTicks);
        tag.put("parcel", saveOrder(items, registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (!tag.contains("start", Tag.TAG_LONG)) return;
        sign = BlockPos.of(tag.getLong("sign"));
        start = BlockPos.of(tag.getLong("start"));
        destination = BlockPos.of(tag.getLong("destination"));
        boxesOnly = tag.getBoolean("boxes_only");
        travelTicks = tag.getInt("travel_ticks");
        items.clear();
        items.addAll(loadOrder(tag.getList("parcel", Tag.TAG_COMPOUND), registryAccess()));
        entityData.set(CARRYING, !items.isEmpty());
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
