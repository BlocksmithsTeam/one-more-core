package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The Restaurant Computer. It belongs to the restaurant whose area contains it.
 * The screen only sends a {@link Request}. The server checks it, changes the restaurant and sends the new {@link State}.
 */
@EventBusSubscriber(modid = OneMoreCore.MODID)
public class Computer extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The CRT, its stand, and the keyboard, mouse and cables. */
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(
            new double[] {3, 1, 6, 13, 3, 8},
            new double[] {3, 8, 6, 13, 9, 8},
            new double[] {3, 3, 6, 4, 8, 8},
            new double[] {12, 3, 6, 13, 8, 8},
            new double[] {4, 3, 7, 12, 8, 8},
            new double[] {3, 1, 8, 13, 9, 10},
            new double[] {4, 2, 10, 12, 8, 13},
            new double[] {5, 2, 13, 11, 7, 15},
            new double[] {5, 0, 8, 11, 1, 12},
            new double[] {2, 0, 1, 12, 1, 5},
            new double[] {13, 0, 2, 15, 1, 5},
            new double[] {7.25, 0, 5, 7.75, 0.5, 6.75},
            new double[] {7.75, 0, 6.25, 9.25, 0.5, 6.75},
            new double[] {9.25, 0, 6.25, 9.75, 0.5, 8},
            new double[] {13.75, 0, 5, 14.25, 0.5, 11.75},
            new double[] {11, 0, 11.25, 13.75, 0.5, 11.75},
            new double[] {8.25, 0.5, 15, 8.75, 3, 15.5},
            new double[] {8.25, 0, 15, 13, 0.5, 15.5});
    public static final int REFRESH = 0, UNLOCK_INGREDIENT = 1, BUY = 2, SET_AREA = 3, SET_SPAWN = 4, SET_DELIVERY = 5,
            TOGGLE_DISH = 6, ADD_DISH = 7, REMOVE_DISH = 8, SET_PRICE = 9, SET_KIND = 10, ADD_COMBO = 11, REMOVE_COMBO = 12,
            SET_COMBO_PRICE = 13, TOGGLE_TABLE = 14, BUY_UPGRADE = 15, BUY_CART = 16, SET_SCREEN = 17, REFRESH_MANAGER = 18, CLOSE = 19, EDIT_COMBO = 20, SET_STREAM = 21, REFRESH_MENU = 22,
            LEVEL_BUY = 23, LEVEL_ADD = 24, LEVEL_REMOVE = 25, LEVEL_PRICE = 26, LEVEL_AREA = 27, LEVEL_WALL = 28, LEVEL_MOVE = 29;
    public static final int MAX_REQUEST_LENGTH = 1024, MAX_CART_LINES = 32, MAX_CART_COUNT = 64;
    /** How far from the computer the manager app may set a point, in blocks per axis. */
    public static final int PLACE_RANGE = 48;
    /** Bits of {@link State#apps}. */
    public static final int APP_MENU = 1, APP_SHOP = 2, APP_MANAGER = 4, APP_UPGRADES = 8, APP_STREAM = 16, APP_LEVELS = 32;
    /** The upgrades of the upgrades app, in order. */
    public static final List<String> UPGRADES = List.of("decor", "delivery", "bell");

    private record Session(ServerLevel level, BlockPos pos, Restaurant restaurant) {}
    private static final Map<ServerPlayer, Session> SESSIONS = new HashMap<>();

    /** An upgrade: its level, its last level and the price of the next level (0 at the last level). */
    public record UpgradeLine(String id, int level, int max, int nextCost) {
        static final StreamCodec<RegistryFriendlyByteBuf, UpgradeLine> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, UpgradeLine::id,
                ByteBufCodecs.VAR_INT, UpgradeLine::level, ByteBufCodecs.VAR_INT, UpgradeLine::max, ByteBufCodecs.VAR_INT, UpgradeLine::nextCost, UpgradeLine::new);
    }
    public static final int AVAILABLE = 0, LOCKED_INGREDIENTS = 1;

    /** A dish. State describes the recipe; on is the player's menu toggle. */
    public record MenuLine(String id, ItemStack icon, int price, int state, boolean on, boolean custom, boolean priceEditable, String kind) {
        static final StreamCodec<RegistryFriendlyByteBuf, MenuLine> CODEC = StreamCodec.of((b, l) -> {
            ByteBufCodecs.STRING_UTF8.encode(b, l.id);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(b, l.icon);
            b.writeVarInt(l.price);
            b.writeVarInt(l.state);
            b.writeBoolean(l.on);
            b.writeBoolean(l.custom);
            b.writeBoolean(l.priceEditable);
            ByteBufCodecs.stringUtf8(16).encode(b, l.kind);
        }, b -> new MenuLine(ByteBufCodecs.STRING_UTF8.decode(b), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readVarInt(),
                b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readBoolean(), ByteBufCodecs.stringUtf8(16).decode(b)));
    }

    public record IngredientLine(ResourceLocation id, int unlockCost, boolean unlocked, int newlyOrderable) {
        static final StreamCodec<RegistryFriendlyByteBuf, IngredientLine> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, IngredientLine::id, ByteBufCodecs.VAR_INT, IngredientLine::unlockCost,
                ByteBufCodecs.BOOL, IngredientLine::unlocked, ByteBufCodecs.VAR_INT, IngredientLine::newlyOrderable,
                IngredientLine::new);
    }

    public record LockedDish(String dishId, List<ResourceLocation> missing) {
        static final StreamCodec<RegistryFriendlyByteBuf, LockedDish> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, LockedDish::dishId,
                ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list(6)), LockedDish::missing, LockedDish::new);
    }

    /** This section is present in responses to Menu and Shop catalog or unlock requests. Counts describe omitted rows. */
    public record IngredientData(List<IngredientLine> ingredients, List<LockedDish> lockedDishes,
            int moreIngredients, int moreLockedDishes) {
        static final int BYTE_BUDGET = 128 * 1024;
        private static final int LIST_BUDGET = (BYTE_BUDGET - 20) / 2;
        static final StreamCodec<RegistryFriendlyByteBuf, IngredientData> CODEC = StreamCodec.composite(
                IngredientLine.CODEC.apply(ByteBufCodecs.list()), IngredientData::ingredients,
                LockedDish.CODEC.apply(ByteBufCodecs.list()), IngredientData::lockedDishes,
                ByteBufCodecs.VAR_INT, IngredientData::moreIngredients, ByteBufCodecs.VAR_INT, IngredientData::moreLockedDishes,
                IngredientData::new);

        static IngredientData capped(RegistryAccess registries, List<IngredientLine> ingredients, List<LockedDish> dishes) {
            var listed = withinBudget(registries, ingredients, IngredientLine.CODEC, LIST_BUDGET);
            var locked = withinBudget(registries, dishes, LockedDish.CODEC, LIST_BUDGET);
            return new IngredientData(List.copyOf(listed), List.copyOf(locked),
                    ingredients.size() - listed.size(), dishes.size() - locked.size());
        }
    }

    /** A combo: its dish ids, its price, and whether customers can order it now. */
    public record ComboLine(String id, List<String> dishes, int price, boolean orderable) {
        static final StreamCodec<RegistryFriendlyByteBuf, ComboLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ComboLine::id, ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), ComboLine::dishes,
                ByteBufCodecs.VAR_INT, ComboLine::price, ByteBufCodecs.BOOL, ComboLine::orderable, ComboLine::new);
    }

    public record ShopLine(String id, ItemStack stack, ItemStack icon, int price) {
        static final StreamCodec<RegistryFriendlyByteBuf, ShopLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ShopLine::id, ItemStack.OPTIONAL_STREAM_CODEC, ShopLine::stack,
                ItemStack.OPTIONAL_STREAM_CODEC, ShopLine::icon, ByteBufCodecs.VAR_INT, ShopLine::price, ShopLine::new);
    }

    public record LevelLine(int price, List<Integer> area, List<Integer> wall, Optional<BlockPos> moveTo) {
        public LevelLine {
            price = Math.max(0, price);
            area = area.size() == 6 ? List.copyOf(area) : List.of();
            wall = wall.size() == 6 ? List.copyOf(wall) : List.of();
        }
        static final StreamCodec<RegistryFriendlyByteBuf, LevelLine> CODEC = StreamCodec.of((b, line) -> {
            b.writeVarInt(line.price);
            State.INTS.encode(b, line.area);
            State.INTS.encode(b, line.wall);
            State.OPTIONAL_POS.encode(b, line.moveTo);
        }, b -> new LevelLine(b.readVarInt(), State.INTS.decode(b), State.INTS.decode(b), State.OPTIONAL_POS.decode(b)));
    }

    /**
     * Server to client. "open" opens the screen; without it only an open screen is updated.
     * "found" is false when no restaurant is near the computer (see {@link Restaurants#forComputer}). "info" holds the
     * lines of the manager app. "area" is empty or {minX, minY, minZ, maxX, maxY, maxZ}; sign, spawn and delivery are
     * there for the manager app's free camera.
     */
    public record State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance, int day,
            List<MenuLine> menu, List<ShopLine> shop, List<Component> info, Component message,
            Optional<BlockPos> sign, List<Integer> area, Optional<BlockPos> spawn, Optional<BlockPos> delivery,
            List<ComboLine> combos, List<BlockPos> surfaces, List<BlockPos> offSurfaces, List<UpgradeLine> upgrades,
            List<String> stream, Optional<IngredientData> ingredientData, int level, List<LevelLine> levels, boolean levelEditor, Optional<DaySummary> yesterday) implements CustomPacketPayload {
        public State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance, int day,
                List<MenuLine> menu, List<ShopLine> shop, List<Component> info, Component message,
                Optional<BlockPos> sign, List<Integer> area, Optional<BlockPos> spawn, Optional<BlockPos> delivery,
                List<ComboLine> combos, List<BlockPos> surfaces, List<BlockPos> offSurfaces, List<UpgradeLine> upgrades,
                List<String> stream, Optional<IngredientData> ingredientData, int level, List<LevelLine> levels, boolean levelEditor) {
            this(pos, open, found, apps, currency, balance, day, menu, shop, info, message, sign, area, spawn, delivery,
                    combos, surfaces, offSurfaces, upgrades, stream, ingredientData, level, levels, levelEditor, Optional.empty());
        }
        public State {
            level = Math.max(1, Math.min(65, level));
            levels = List.copyOf(levels.subList(0, Math.min(64, levels.size())));
        }
        public State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance, int day,
                List<MenuLine> menu, List<ShopLine> shop, List<Component> info, Component message,
                Optional<BlockPos> sign, List<Integer> area, Optional<BlockPos> spawn, Optional<BlockPos> delivery,
                List<ComboLine> combos, List<BlockPos> surfaces, List<BlockPos> offSurfaces, List<UpgradeLine> upgrades,
                List<String> stream, Optional<IngredientData> ingredientData) {
            this(pos, open, found, apps, currency, balance, day, menu, shop, info, message, sign, area, spawn, delivery,
                    combos, surfaces, offSurfaces, upgrades, stream, ingredientData, 1, List.of(), false);
        }
        /** stream: platform, channel, command, "on" or "off", viewers in the pool; empty for the other apps. */
        public State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance, List<MenuLine> menu, List<ShopLine> shop,
                List<Component> info, Component message, Optional<BlockPos> sign, List<Integer> area, Optional<BlockPos> spawn, Optional<BlockPos> delivery,
                List<ComboLine> combos, List<BlockPos> surfaces, List<BlockPos> offSurfaces, List<UpgradeLine> upgrades) {
            this(pos, open, found, apps, currency, balance, 0, menu, shop, info, message, sign, area, spawn, delivery, combos, surfaces, offSurfaces, upgrades, List.of(), Optional.empty());
        }
        public State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance, List<MenuLine> menu, List<ShopLine> shop,
                List<Component> info, Component message, Optional<BlockPos> sign, List<Integer> area, Optional<BlockPos> spawn, Optional<BlockPos> delivery,
                List<ComboLine> combos, List<BlockPos> surfaces, List<BlockPos> offSurfaces) {
            this(pos, open, found, apps, currency, balance, menu, shop, info, message, sign, area, spawn, delivery, combos, surfaces, offSurfaces, List.of());
        }
        public State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance, List<MenuLine> menu, List<ShopLine> shop,
                List<Component> info, Component message, Optional<BlockPos> sign, List<Integer> area, Optional<BlockPos> spawn, Optional<BlockPos> delivery) {
            this(pos, open, found, apps, currency, balance, menu, shop, info, message, sign, area, spawn, delivery, List.of(), List.of(), List.of(), List.of());
        }
        static final StreamCodec<io.netty.buffer.ByteBuf, List<BlockPos>> POSITIONS = BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(4096));
        static final StreamCodec<io.netty.buffer.ByteBuf, Optional<BlockPos>> OPTIONAL_POS = ByteBufCodecs.optional(BlockPos.STREAM_CODEC);
        static final StreamCodec<io.netty.buffer.ByteBuf, List<Integer>> INTS = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(6));
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "computer_state"));
        static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of((b, s) -> {
            b.writeBlockPos(s.pos);
            b.writeBoolean(s.open);
            b.writeBoolean(s.found);
            b.writeVarInt(s.apps);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(b, s.currency);
            b.writeVarInt(s.balance);
            b.writeVarInt(s.day);
            MenuLine.CODEC.apply(ByteBufCodecs.list()).encode(b, s.menu);
            ShopLine.CODEC.apply(ByteBufCodecs.list()).encode(b, s.shop);
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(b, s.info);
            ComponentSerialization.STREAM_CODEC.encode(b, s.message);
            OPTIONAL_POS.encode(b, s.sign);
            INTS.encode(b, s.area);
            OPTIONAL_POS.encode(b, s.spawn);
            OPTIONAL_POS.encode(b, s.delivery);
            ComboLine.CODEC.apply(ByteBufCodecs.list()).encode(b, s.combos);
            POSITIONS.encode(b, s.surfaces);
            POSITIONS.encode(b, s.offSurfaces);
            UpgradeLine.CODEC.apply(ByteBufCodecs.list(16)).encode(b, s.upgrades);
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(8)).encode(b, s.stream);
            ByteBufCodecs.optional(IngredientData.CODEC).encode(b, s.ingredientData);
            b.writeVarInt(s.level);
            LevelLine.CODEC.apply(ByteBufCodecs.list(64)).encode(b, s.levels);
            b.writeBoolean(s.levelEditor);
            ByteBufCodecs.optional(DaySummary.CODEC).encode(b, s.yesterday);
        }, b -> new State(b.readBlockPos(), b.readBoolean(), b.readBoolean(), b.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readVarInt(), b.readVarInt(),
                MenuLine.CODEC.apply(ByteBufCodecs.list()).decode(b), ShopLine.CODEC.apply(ByteBufCodecs.list()).decode(b),
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(b), ComponentSerialization.STREAM_CODEC.decode(b),
                OPTIONAL_POS.decode(b), INTS.decode(b), OPTIONAL_POS.decode(b), OPTIONAL_POS.decode(b),
                ComboLine.CODEC.apply(ByteBufCodecs.list()).decode(b), POSITIONS.decode(b), POSITIONS.decode(b),
                UpgradeLine.CODEC.apply(ByteBufCodecs.list(16)).decode(b), ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(8)).decode(b),
                ByteBufCodecs.optional(IngredientData.CODEC).decode(b), b.readVarInt(),
                LevelLine.CODEC.apply(ByteBufCodecs.list(64)).decode(b), b.readBoolean(), ByteBufCodecs.optional(DaySummary.CODEC).decode(b)));

        @Override
        public Type<State> type() { return TYPE; }
    }

    /** Client to server. The checkout total is a quote; the server still uses its own catalog to charge the register. */
    public record Request(BlockPos pos, int action, String id, long expectedTotal, long checkoutId) implements CustomPacketPayload {
        public Request(BlockPos pos, int action, String id) { this(pos, action, id, -1, 0); }

        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "computer_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Request::pos, ByteBufCodecs.VAR_INT, Request::action, ByteBufCodecs.STRING_UTF8, Request::id,
                ByteBufCodecs.VAR_LONG, Request::expectedTotal, ByteBufCodecs.VAR_LONG, Request::checkoutId, Request::new);

        @Override
        public Type<Request> type() { return TYPE; }
    }

    /** Replies to a checkout, with a fresh catalog and balance when the session permits it. */
    public record CheckoutResult(BlockPos pos, long checkoutId, boolean success, Component message, Optional<State> state)
            implements CustomPacketPayload {
        public static final Type<CheckoutResult> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "computer_checkout"));
        static final StreamCodec<RegistryFriendlyByteBuf, CheckoutResult> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, CheckoutResult::pos, ByteBufCodecs.VAR_LONG, CheckoutResult::checkoutId,
                ByteBufCodecs.BOOL, CheckoutResult::success, ComponentSerialization.STREAM_CODEC, CheckoutResult::message,
                ByteBufCodecs.optional(State.CODEC), CheckoutResult::state, CheckoutResult::new);

        @Override
        public Type<CheckoutResult> type() { return TYPE; }
    }

    public Computer(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return SHAPES[state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) {
            ServerLevel serverLevel = sp.serverLevel();
            if (!canUse(sp, serverLevel, pos)) return InteractionResult.PASS;
            Restaurant restaurant = Restaurants.get(serverLevel).forComputer(pos);
            SESSIONS.put(sp, new Session(serverLevel, pos.immutable(), restaurant));
            PacketDistributor.sendToPlayer(sp, state(serverLevel, pos, restaurant, true, false, false, Component.empty(), editor(sp)));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean canUse(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return !player.isSpectator() && player.canInteractWithBlock(pos, 0) && level.isLoaded(pos)
                && level.mayInteract(player, pos) && level.getBlockState(pos).is(OneMoreCore.COMPUTER);
    }

    private static Session session(ServerPlayer player) {
        Session session = SESSIONS.get(player);
        if (session != null && (session.level != player.level() || !canUse(player, session.level, session.pos))) {
            SESSIONS.remove(player);
            return null;
        }
        return session;
    }

    @SubscribeEvent
    static void checkSession(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) session(player);
    }

    @SubscribeEvent
    static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) { SESSIONS.remove(event.getEntity()); }

    @SubscribeEvent
    static void playerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { SESSIONS.remove(event.getEntity()); }

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("5")
                // The handler runs on the client only, so the client class is not loaded on a dedicated server.
                .playToClient(State.TYPE, State.CODEC, (state, context) -> com.onemorestudios.onemorecore.client.ComputerCamera.receive(state))
                .playToClient(CheckoutResult.TYPE, CheckoutResult.CODEC, (result, context) -> com.onemorestudios.onemorecore.client.ComputerCamera.receive(result))
                .playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
                    CustomPacketPayload reply = handle(context.player(), request);
                    if (reply != null) context.reply(reply);
                });
    }

    /** Checks and runs one request. Checkouts always return a result. */
    public static CustomPacketPayload handle(Player player, Request request) {
        Session session = player instanceof ServerPlayer sp ? session(sp) : null;
        boolean checkout = request.action == BUY_CART;
        if (session == null || !session.pos.equals(request.pos)) return checkout ? sessionEnded(request) : null;
        if (request.action == CLOSE) {
            SESSIONS.remove(player);
            return null;
        }
        ServerLevel level = session.level;
        Restaurants all = Restaurants.get(level);
        Restaurant r = all.forComputer(request.pos);
        if (r != session.restaurant) {
            SESSIONS.remove(player);
            return checkout ? sessionEnded(request) : null;
        }
        if (checkout) return checkout(session, request, editor(player));
        if (request.id.length() > MAX_REQUEST_LENGTH) return null;
        Component message = Component.empty();
        if (request.action >= LEVEL_BUY && request.action <= LEVEL_MOVE) {
            if (r == null || !Config.APP_LEVELS.get()) return null;
            if (request.action != LEVEL_BUY && !editor(player)) {
                message = Component.translatable("onemorecore.level.not_allowed");
            } else if (request.action == LEVEL_BUY) {
                message = Component.translatable(r.buyLevel(level), Config.MAX_AREA.get());
            } else if (request.action == LEVEL_ADD) {
                if (r.steps.size() == 64) return null;
                r.steps.add(new Restaurant.Step(0, null, null, null));
                all.setDirty();
            } else {
                String[] fields = request.id.split("\n", -1);
                int index;
                try { index = Integer.parseInt(fields[0]); } catch (NumberFormatException e) { return null; }
                if (index < r.level - 1 || index >= r.steps.size()) return null;
                Restaurant.Step old = r.steps.get(index);
                if (request.action == LEVEL_REMOVE) {
                    if (fields.length != 1) return null;
                    r.steps.remove(index);
                } else {
                    if (fields.length != 2) return null;
                    if (request.action == LEVEL_PRICE) {
                        int price;
                        try { price = new java.math.BigInteger(fields[1].trim()).max(java.math.BigInteger.ZERO)
                                .min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).intValue(); }
                        catch (NumberFormatException e) { return null; }
                        r.steps.set(index, new Restaurant.Step(price, old.area(), old.wall(), old.moveTo()));
                    } else {
                        BoundingBox box = null;
                        BlockPos point = null;
                        if (!fields[1].isBlank()) {
                            String[] coordinates = fields[1].trim().split("\\s+");
                            int count = request.action == LEVEL_MOVE ? 3 : 6;
                            if (coordinates.length != count) return null;
                            int[] n = new int[count];
                            try { for (int i = 0; i < count; i++) n[i] = Integer.parseInt(coordinates[i]); }
                            catch (NumberFormatException e) { return null; }
                            point = new BlockPos(n[0], n[1], n[2]);
                            BlockPos end = count == 3 ? point : new BlockPos(n[3], n[4], n[5]);
                            if (count == 3 && level.isLoaded(point) && level.isLoaded(point.below())
                                    && level.getBlockState(point).is(OneMoreCore.SCREEN_PART.get())
                                    && level.getBlockState(point.below()).is(OneMoreCore.OPEN_SIGN.get())) point = point.below();
                            if (!near(request.pos, point) || !near(request.pos, end) || !level.isLoaded(point) || !level.isLoaded(end))
                                return state(level, request.pos, r, false, false, false,
                                        Component.translatable("onemorecore.tool.too_far", PLACE_RANGE), editor(player));
                            if (count == 6) {
                                box = BoundingBox.fromCorners(point, end);
                                if (!Restaurants.validBox(box)) return state(level, request.pos, r, false, false, false,
                                        Component.translatable("onemorecore.tool.too_big", Config.MAX_AREA.get()), editor(player));
                            }
                        }
                        r.steps.set(index, new Restaurant.Step(old.price(), request.action == LEVEL_AREA ? box : old.area(),
                                request.action == LEVEL_WALL ? box : old.wall(), request.action == LEVEL_MOVE ? point : old.moveTo()));
                    }
                }
                all.setDirty();
            }
        } else if (request.action == SET_AREA || request.action == SET_SPAWN || request.action == SET_DELIVERY) {
            if (r == null || !Config.APP_MANAGER.get()) return null;
            int[] n = ints(request.id, request.action == SET_AREA ? 6 : 3);
            if (n == null) return null;
            BlockPos a = new BlockPos(n[0], n[1], n[2]);
            BlockPos b = request.action == SET_AREA ? new BlockPos(n[3], n[4], n[5]) : a;
            String key;
            if (!near(request.pos, a) || !near(request.pos, b) || !level.isLoaded(a) || !level.isLoaded(b)) key = "too_far";
            else if (r.isOpen()) key = "busy";
            else if (request.action == SET_AREA) key = all.setArea(r, BoundingBox.fromCorners(a, b));
            else if (request.action == SET_SPAWN) { r.setSpawn(a); key = "spawn_set"; }
            else { r.setDelivery(a); key = "delivery_set"; }
            message = Component.translatable("onemorecore.tool." + key, key.equals("too_big") ? Config.MAX_AREA.get() : PLACE_RANGE);
        } else if (request.action == SET_STREAM) {
            // The chat reader is the server's: only an operator, or the owner of a single player world, may change it.
            if (!Config.APP_STREAM.get() || !(player instanceof ServerPlayer sp)) return null;
            String[] f = request.id.split("\n", -1);
            if (f.length != 3) return null;
            boolean allowed = sp.hasPermissions(2) || sp.server.isSingleplayerOwner(sp.getGameProfile());
            message = Component.translatable("onemorecore.computer." + (allowed ? SkinPool.configure(sp.server, f[0], f[1], f[2]) : "stream.not_allowed"));
        } else if (request.action == BUY_UPGRADE) {
            if (r == null || !Config.APP_UPGRADES.get()) return null;
            Restaurant.Upgrade result = r.buyUpgrade(request.id);
            if (result == Restaurant.Upgrade.UNKNOWN) return null;
            message = Component.translatable("onemorecore.upgrade." + result.name().toLowerCase(java.util.Locale.ROOT));
        } else if (request.action == TOGGLE_TABLE) {
            if (r == null || !Config.APP_MANAGER.get()) return null;
            int[] n = ints(request.id, 3);
            if (n == null) return null;
            BlockPos p = new BlockPos(n[0], n[1], n[2]);
            String key = !near(request.pos, p) || !level.isLoaded(p) ? "too_far" : r.isOpen() ? "busy" : !r.toggleTable(level, p) ? "not_table"
                    : r.isTableOn(r.tableAt(level, p)) ? "table_on" : "table_off";
            message = Component.translatable("onemorecore.tool." + key, PLACE_RANGE);
        } else if (request.action == SET_SCREEN) {
            if (r == null || !Config.APP_MANAGER.get()) return null;
            int[] n = ints(request.id, 3);
            if (n == null) return null;
            BlockPos p = new BlockPos(n[0], n[1], n[2]);
            String key = !near(request.pos, p) || !level.isLoaded(p) ? "too_far" : !r.cycleScreen(level, p) ? "not_screen"
                    : "screen_" + r.screens.getOrDefault(p, Restaurant.SCREEN_OFF);
            message = Component.translatable("onemorecore.tool." + key, PLACE_RANGE);
        } else if ((request.action >= SET_KIND && request.action <= SET_COMBO_PRICE) || request.action == EDIT_COMBO) {
            if (r == null || !Config.APP_MENU.get()) return null;
            String key;
            if (request.action == REMOVE_COMBO) {
                if (!r.removeCombo(request.id)) return null;
                key = "combo_removed";
            } else {
                int comma = request.id.lastIndexOf(',');
                if (comma < 0) return null;
                String head = request.id.substring(0, comma), tail = request.id.substring(comma + 1).trim();
                if (request.action == SET_KIND) {
                    if (!r.setKind(head, tail)) return null;
                    key = "kind_set";
                } else {
                    int price;
                    try { price = Integer.parseInt(tail); } catch (NumberFormatException e) { return null; }
                    if (request.action == SET_COMBO_PRICE) key = r.setComboPrice(head, price) ? "price_set" : "price_range";
                    else {
                        String comboId = null;
                        if (request.action == EDIT_COMBO) {
                            int separator = head.indexOf(',');
                            if (separator < 1) return null;
                            comboId = head.substring(0, separator);
                            head = head.substring(separator + 1);
                        }
                        key = switch (r.saveCombo(comboId, List.of(head.split(";")), price)) {
                            case OK -> comboId == null ? "combo_added" : "combo_saved";
                            case DISHES -> "combo_dishes";
                            case PRICE -> "price_range";
                            case FULL -> "combo_full";
                        };
                    }
                }
            }
            message = Component.translatable("onemorecore.computer." + key, Config.MAX_DISH_PRICE.get() * Restaurant.COMBO_PRICE_MULTIPLIER);
        } else if (request.action >= TOGGLE_DISH && request.action <= SET_PRICE) {
            if (r == null || !Config.APP_MENU.get()) return null;
            String key;
            if (request.action == TOGGLE_DISH) {
                if (!r.toggle(request.id)) return null;
                key = r.isOn(r.dish(request.id)) ? "dish_on" : "dish_off";
            } else if (request.action == REMOVE_DISH) {
                if (!r.removeDish(request.id)) return null;
                key = "dish_removed";
            } else {
                // "<id>,<price>": the id is an item id (add) or a dish id (price); the price is the last number.
                int comma = request.id.lastIndexOf(',');
                if (comma < 0) return null;
                int price;
                try { price = Integer.parseInt(request.id.substring(comma + 1).trim()); } catch (NumberFormatException e) { return null; }
                String id = request.id.substring(0, comma);
                if (request.action == SET_PRICE) {
                    key = r.setPrice(id, price) ? "price_set" : "price_range";
                } else {
                    var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id));
                    if (item.isEmpty()) return null;
                    key = switch (r.addDish(item.get(), price)) {
                        case OK -> "dish_added";
                        case NOT_FOOD -> "not_food";
                        case ALREADY -> "dish_already";
                        case PRICE -> "price_range";
                        case FULL -> "menu_full";
                    };
                }
            }
            message = Component.translatable("onemorecore.computer." + key, Config.MAX_DISH_PRICE.get());
        } else if (request.action == UNLOCK_INGREDIENT) {
            if (r == null || !Config.APP_SHOP.get()) return null;
            ResourceLocation item = ResourceLocation.tryParse(request.id);
            message = OneMoreCore.unlockMessage(r, request.id, item, r.unlockIngredient(item));
        } else if (request.action == BUY) {
            if (r == null || !Config.APP_SHOP.get()) return null;
            Shop.Entry e = Shop.byId(r, request.id);
            if (e == null) return null;
            message = switch (r.buy(e)) {
                case OK -> Component.translatable("onemorecore.computer.ordered", e.stack().getHoverName(), r.deliveryTicks() / 20);
                case COST -> Component.translatable("onemorecore.cmd.cost", e.id(), e.price(), r.register());
                case NO_DELIVERY -> Component.translatable("onemorecore.computer.no_delivery");
            };
        } else if (request.action != REFRESH && request.action != REFRESH_MANAGER && request.action != REFRESH_MENU) {
            return null;
        }
        boolean manager = Config.APP_MANAGER.get() && switch (request.action) {
            case REFRESH_MANAGER, SET_AREA, SET_SPAWN, SET_DELIVERY, TOGGLE_TABLE, SET_SCREEN -> true;
            default -> false;
        };
        boolean menu = Config.APP_MENU.get() && switch (request.action) {
            case REFRESH_MENU, TOGGLE_DISH, ADD_DISH, REMOVE_DISH, SET_PRICE, SET_KIND,
                    ADD_COMBO, REMOVE_COMBO, SET_COMBO_PRICE, EDIT_COMBO -> true;
            default -> false;
        };
        boolean shop = Config.APP_SHOP.get() && (request.action == REFRESH || request.action == UNLOCK_INGREDIENT);
        return state(level, request.pos, r, false, manager, menu || shop, message, editor(player));
    }

    private static CheckoutResult sessionEnded(Request request) {
        return new CheckoutResult(request.pos, request.checkoutId, false, Component.translatableWithFallback(
                "onemorecore.computer.cart_session", "Computer session ended. Use the computer again."), Optional.empty());
    }

    private static CheckoutResult checkout(Session session, Request request, boolean levelEditor) {
        Restaurant r = session.restaurant;
        Component message;
        boolean success = false;
        if (r == null) {
            message = Component.translatable("onemorecore.computer.outside");
        } else if (!Config.APP_SHOP.get()) {
            message = Component.translatableWithFallback("onemorecore.computer.cart_disabled", "The Shop is disabled.");
        } else {
            Map<Shop.Entry, Integer> cart = readCart(r, request.id);
            if (cart == null) {
                message = Component.translatableWithFallback("onemorecore.computer.cart_invalid", "The cart contains an invalid or unavailable listing.");
            } else {
                long total = cart.entrySet().stream().mapToLong(e -> (long) e.getKey().price() * e.getValue()).sum();
                if (total != request.expectedTotal) {
                    message = Component.translatableWithFallback("onemorecore.computer.cart_repriced",
                            "Shop prices changed. Review the updated total and check out again.");
                } else {
                    Restaurant.Buy result = r.buyAll(cart);
                    success = result == Restaurant.Buy.OK;
                    message = switch (result) {
                        case OK -> Component.translatable("onemorecore.computer.cart_ordered",
                                cart.values().stream().mapToInt(Integer::intValue).sum(), r.deliveryTicks() / 20);
                        case COST -> Component.translatable("onemorecore.computer.cart_cost", total - r.register());
                        case NO_DELIVERY -> Component.translatable("onemorecore.computer.no_delivery");
                    };
                }
            }
        }
        return new CheckoutResult(request.pos, request.checkoutId, success, message,
                Optional.of(state(session.level, request.pos, r, false, false, false, Component.empty(), levelEditor)));
    }

    /** Reads "id:count;id:count" using the same limits as the Shop screen. */
    private static Map<Shop.Entry, Integer> readCart(Restaurant restaurant, String id) {
        if (id.isEmpty() || id.length() > MAX_REQUEST_LENGTH) return null;
        String[] parts = id.split(";", -1);
        if (parts.length > MAX_CART_LINES) return null;
        Map<Shop.Entry, Integer> cart = new LinkedHashMap<>();
        for (String part : parts) {
            int colon = part.lastIndexOf(':');
            if (colon < 0) return null;
            Shop.Entry e = Shop.byId(restaurant, part.substring(0, colon));
            int count;
            try { count = Integer.parseInt(part.substring(colon + 1)); } catch (NumberFormatException ex) { return null; }
            if (e == null || count < 1 || count > MAX_CART_COUNT || cart.putIfAbsent(e, count) != null) return null;
        }
        return cart;
    }

    /** True if the point is at most PLACE_RANGE blocks from the computer in each axis. */
    static boolean near(BlockPos computer, BlockPos p) {
        return Math.abs((long) p.getX() - computer.getX()) <= PLACE_RANGE && Math.abs((long) p.getY() - computer.getY()) <= PLACE_RANGE
                && Math.abs((long) p.getZ() - computer.getZ()) <= PLACE_RANGE;
    }

    /** Exactly `count` integers separated by commas, or null. */
    static int[] ints(String text, int count) {
        String[] parts = text.split(",", -1);
        if (parts.length != count) return null;
        int[] out = new int[count];
        try {
            for (int i = 0; i < count; i++) out[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** The stream chat settings and state, for the Stream app. */
    private static List<String> stream() {
        return List.of(Config.CHAT_PLATFORM.get(), Config.CHAT_CHANNEL.get(), Config.CHAT_COMMAND.get(), SkinPool.connected() ? "on" : "off",
                String.valueOf(SkinPool.viewers()));
    }

    /** Everything the screen shows. The menu and shop files exist on the server only. */
    private static State state(ServerLevel level, BlockPos pos, Restaurant r, boolean open, boolean manager, boolean menuRequest, Component message, boolean levelEditor) {
        int apps = (Config.APP_MENU.get() ? APP_MENU : 0) | (Config.APP_SHOP.get() ? APP_SHOP : 0) | (Config.APP_MANAGER.get() ? APP_MANAGER : 0)
                | (Config.APP_UPGRADES.get() ? APP_UPGRADES : 0) | (Config.APP_STREAM.get() ? APP_STREAM : 0) | (Config.APP_LEVELS.get() ? APP_LEVELS : 0);
        ItemStack currency = new ItemStack(Money.coin());
        if (r == null) return new State(pos, open, false, apps, currency, 0, List.of(), List.of(), List.of(), message,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        var registries = level.registryAccess();
        List<Menu.Entry> dishes = r.dishes();
        List<MenuLine> menu = dishes.stream().map(e -> {
            boolean available = r.ingredientsAvailable(e);
            ItemStack icon = available ? r.orderableVariant(e, null) : Ingredients.variants(e).findFirst().orElse(ItemStack.EMPTY);
            return new MenuLine(e.id(), icon, e.price(), available ? AVAILABLE : LOCKED_INGREDIENTS,
                    r.isOn(e), e.id().startsWith("custom:"), r.priceEditable(e), r.kind(e));
        }).toList();
        List<ShopLine> shop = Shop.catalog(r).stream().map(e -> new ShopLine(e.id(), e.stack(), e.icon(), e.price())).toList();
        BoundingBox a = r.area();
        List<Restaurant.Seat> seats = !manager || a == null ? List.of() : r.scanSeats(level);
        Component none = Component.translatable("onemorecore.computer.info.not_set");
        List<Component> info = !manager ? List.of() : List.of(
                Component.translatable("onemorecore.computer.info.day", r.day()),
                Component.translatable(r.isOpen() ? "onemorecore.computer.info.open" : "onemorecore.computer.info.closed"),
                Component.translatable("onemorecore.computer.info.register", r.register()),
                a == null ? Component.translatable("onemorecore.computer.info.area_none")
                        : Component.translatable("onemorecore.computer.info.area", a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()),
                Component.translatable("onemorecore.computer.info.spawn", r.spawn() == null ? none : r.spawn().toShortString()),
                Component.translatable("onemorecore.computer.info.delivery", r.delivery() == null ? none : r.delivery().toShortString()),
                Component.translatable("onemorecore.computer.info.seats", seats.size()),
                Component.translatable("onemorecore.computer.info.orders", r.deliveries.size()),
                Config.REVIEWS.get() ? Component.translatable("onemorecore.reviews.rating",
                        String.format(java.util.Locale.ROOT, "%.1f", r.rating())) : Component.empty());
        List<ComboLine> combos = r.combos.stream().map(c -> new ComboLine(c.id(), c.dishes(), c.price(), r.canOrder(c))).toList();
        // The serving surfaces next to seats, and the ones of tables that are off, for the free camera's table tool.
        List<BlockPos> surfaces = new ArrayList<>(), off = new ArrayList<>();
        if (!seats.isEmpty()) {
            for (List<Restaurant.Seat> table : r.groupTables(level, seats)) {
                java.util.Set<BlockPos> all = r.tableAt(level, table.get(0).surface());
                boolean on = r.isTableOn(all);
                for (BlockPos p : all) { surfaces.add(p); if (!on) off.add(p); }
            }
        }
        return new State(pos, open, true, apps, currency, r.register(), r.day(),
                withinBudget(registries, menu, MenuLine.CODEC, 256 * 1024),
                withinBudget(registries, shop, ShopLine.CODEC, 256 * 1024), info, message, Optional.of(r.sign),
                a == null ? List.of() : List.of(a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()),
                Optional.ofNullable(r.spawn()), Optional.ofNullable(r.delivery()), withinBudget(registries, combos, ComboLine.CODEC, 64 * 1024),
                surfaces.size() > 4096 ? surfaces.subList(0, 4096) : surfaces, off.size() > 4096 ? off.subList(0, 4096) : off,
                UPGRADES.stream().map(id -> {
                    var costs = Restaurant.upgradeCosts(id);
                    int lv = r.upgrade(id);
                    return new UpgradeLine(id, lv, costs.size(), lv < costs.size() ? costs.get(lv) : 0);
                }).toList(),
                stream(), menuRequest ? Optional.of(ingredientData(registries, r, dishes, menu)) : Optional.empty(),
                r.level(), !Config.APP_LEVELS.get() ? List.of() : r.steps().stream().limit(64)
                        .map(step -> new LevelLine(step.price(), boxList(step.area()), boxList(step.wall()), Optional.ofNullable(step.moveTo()))).toList(),
                Config.APP_LEVELS.get() && levelEditor, Config.DAY_SUMMARY.get() ? Optional.ofNullable(r.lastSummary()) : Optional.empty());
    }

    private static boolean editor(Player player) {
        return player instanceof ServerPlayer sp && (sp.hasPermissions(2) || sp.server.isSingleplayerOwner(sp.getGameProfile()));
    }

    private static List<Integer> boxList(BoundingBox box) {
        return box == null ? List.of() : List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    private record DishSettings(boolean on, String kind) {}
    private record IngredientMenuKey(Ingredients index, Set<ResourceLocation> unlocked, Set<ResourceLocation> machines,
            List<Menu.Entry> dishes, List<DishSettings> settings) {}
    private record CachedIngredientData(IngredientMenuKey key, IngredientData data) {}

    // Accessed on the server thread. Weak keys let removed restaurants and stopped worlds release their data.
    private static final Map<Restaurant, CachedIngredientData> INGREDIENT_MENUS = new WeakHashMap<>();
    private static long ingredientMenuComputations; // GameTest hook

    private static IngredientData ingredientData(RegistryAccess registries, Restaurant r, List<Menu.Entry> dishes, List<MenuLine> menu) {
        // The index is replaced when recipes, tags or ingredients.json are reloaded.
        var key = new IngredientMenuKey(Ingredients.current(), r.unlockedIngredients(), r.machines(), dishes,
                menu.stream().map(line -> new DishSettings(line.on(), line.kind())).toList());
        var cached = INGREDIENT_MENUS.get(r);
        if (cached != null && cached.key.equals(key)) return cached.data;

        var data = computeIngredientData(registries, r, key);
        INGREDIENT_MENUS.put(r, new CachedIngredientData(key, data));
        return data;
    }

    private static IngredientData computeIngredientData(RegistryAccess registries, Restaurant r, IngredientMenuKey inputs) {
        ingredientMenuComputations++;
        Ingredients index = inputs.index;
        var availability = r.ingredientAvailability();
        var unlocked = inputs.unlocked;
        List<Menu.Entry> locked = inputs.dishes.stream().filter(e -> !r.ingredientsAvailable(e)).toList();
        Map<ResourceLocation, List<Menu.Entry>> lockedByItem = new HashMap<>();
        for (Menu.Entry dish : locked) {
            if (!r.isOn(dish)) continue;
            Ingredients.variants(dish).map(Ingredients::id).distinct()
                    .forEach(id -> lockedByItem.computeIfAbsent(id, key -> new ArrayList<>()).add(dish));
        }
        List<IngredientLine> ingredients = new ArrayList<>();
        for (ResourceLocation item : index.raw) {
            boolean bought = index.purchased(item, unlocked);
            if (!bought && availability.item(item)) continue;
            int newlyOrderable = 0;
            if (!bought) {
                var candidates = index.dishesHelped(item).stream().flatMap(id -> lockedByItem.getOrDefault(id, List.of()).stream())
                        .distinct().toList();
                if (!candidates.isEmpty()) {
                    Set<ResourceLocation> withItem = new java.util.HashSet<>(unlocked);
                    withItem.add(item);
                    var after = r.availabilityWith(withItem);
                    newlyOrderable = (int) candidates.stream().filter(dish -> Ingredients.variants(dish)
                            .anyMatch(stack -> after.dish(Ingredients.id(stack)))).count();
                }
            }
            ingredients.add(new IngredientLine(item, index.unlockCost(item), bought, newlyOrderable));
        }
        return IngredientData.capped(registries, ingredients,
                locked.stream().map(dish -> new LockedDish(dish.id(), availability.missing(dish))).toList());
    }

    private static <T> List<T> withinBudget(RegistryAccess registries, List<T> rows,
            StreamCodec<RegistryFriendlyByteBuf, T> codec, int remaining) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        List<T> kept = new ArrayList<>();
        try {
            for (T row : rows) {
                buffer.clear();
                try {
                    codec.encode(buffer, row);
                } catch (EncoderException e) {
                    continue;
                }
                int size = buffer.readableBytes();
                if (size > remaining) continue;
                kept.add(row);
                remaining -= size;
                if (remaining == 0) break;
            }
        } finally {
            buffer.release();
        }
        return kept;
    }
}
