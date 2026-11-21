package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
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
    /** The monitor, its back and the keyboard. */
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(new double[] {2, 0, 4, 14, 11, 13}, new double[] {4, 1, 13, 12, 9, 16},
            new double[] {2, 0, 0.5, 14, 1.5, 3.5});
    public static final int REFRESH = 0, UNLOCK = 1, BUY = 2, SET_AREA = 3, SET_SPAWN = 4, SET_DELIVERY = 5,
            TOGGLE_DISH = 6, ADD_DISH = 7, REMOVE_DISH = 8, SET_PRICE = 9, SET_KIND = 10, ADD_COMBO = 11, REMOVE_COMBO = 12,
            SET_COMBO_PRICE = 13, TOGGLE_TABLE = 14, BUY_UPGRADE = 15, BUY_CART = 16, SET_SCREEN = 17, REFRESH_MANAGER = 18, CLOSE = 19;
    public static final int MAX_REQUEST_LENGTH = 1024, MAX_CART_LINES = 32, MAX_CART_COUNT = 64;
    /** How far from the computer the manager app may set a point, in blocks per axis. */
    public static final int PLACE_RANGE = 48;
    /** Bits of {@link State#apps}. */
    public static final int APP_MENU = 1, APP_SHOP = 2, APP_MANAGER = 4, APP_UPGRADES = 8;
    /** The upgrades of the upgrades app, in order. */
    public static final List<String> UPGRADES = List.of("decor", "delivery", "bell");

    private record Session(ServerLevel level, BlockPos pos, Restaurant restaurant) {}
    private static final Map<ServerPlayer, Session> SESSIONS = new HashMap<>();

    /** An upgrade: its level, its last level and the price of the next level (0 at the last level). */
    public record UpgradeLine(String id, int level, int max, int nextCost) {
        static final StreamCodec<RegistryFriendlyByteBuf, UpgradeLine> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, UpgradeLine::id,
                ByteBufCodecs.VAR_INT, UpgradeLine::level, ByteBufCodecs.VAR_INT, UpgradeLine::max, ByteBufCodecs.VAR_INT, UpgradeLine::nextCost, UpgradeLine::new);
    }
    /** Values of {@link MenuLine#state}. */
    public static final int AVAILABLE = 0, LOCKED_DAY = 1, BUYABLE = 2;

    /** A dish. "on": customers order it. "custom": a player added it, so its price can change and it can be removed. */
    public record MenuLine(String id, ItemStack icon, int price, int day, int cost, int state, boolean on, boolean custom, String kind) {
        static final StreamCodec<RegistryFriendlyByteBuf, MenuLine> CODEC = StreamCodec.of((b, l) -> {
            ByteBufCodecs.STRING_UTF8.encode(b, l.id);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(b, l.icon);
            b.writeVarInt(l.price);
            b.writeVarInt(l.day);
            b.writeVarInt(l.cost);
            b.writeVarInt(l.state);
            b.writeBoolean(l.on);
            b.writeBoolean(l.custom);
            ByteBufCodecs.stringUtf8(16).encode(b, l.kind);
        }, b -> new MenuLine(ByteBufCodecs.STRING_UTF8.decode(b), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readVarInt(), b.readVarInt(),
                b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean(), ByteBufCodecs.stringUtf8(16).decode(b)));
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

    /**
     * Server to client. "open" opens the screen; without it only an open screen is updated.
     * "found" is false when no restaurant is near the computer (see {@link Restaurants#forComputer}). "info" holds the
     * lines of the manager app. "area" is empty or {minX, minY, minZ, maxX, maxY, maxZ}; sign, spawn and delivery are
     * there for the manager app's free camera.
     */
    public record State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance,
            List<MenuLine> menu, List<ShopLine> shop, List<Component> info, Component message,
            Optional<BlockPos> sign, List<Integer> area, Optional<BlockPos> spawn, Optional<BlockPos> delivery,
            List<ComboLine> combos, List<BlockPos> surfaces, List<BlockPos> offSurfaces, List<UpgradeLine> upgrades) implements CustomPacketPayload {
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
        }, b -> new State(b.readBlockPos(), b.readBoolean(), b.readBoolean(), b.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readVarInt(),
                MenuLine.CODEC.apply(ByteBufCodecs.list()).decode(b), ShopLine.CODEC.apply(ByteBufCodecs.list()).decode(b),
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(b), ComponentSerialization.STREAM_CODEC.decode(b),
                OPTIONAL_POS.decode(b), INTS.decode(b), OPTIONAL_POS.decode(b), OPTIONAL_POS.decode(b),
                ComboLine.CODEC.apply(ByteBufCodecs.list()).decode(b), POSITIONS.decode(b), POSITIONS.decode(b),
                UpgradeLine.CODEC.apply(ByteBufCodecs.list(16)).decode(b)));

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
            PacketDistributor.sendToPlayer(sp, state(serverLevel, pos, restaurant, true, false, Component.empty()));
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
        event.registrar("2")
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
        if (checkout) return checkout(session, request);
        if (request.id.length() > MAX_REQUEST_LENGTH) return null;
        Component message = Component.empty();
        if (request.action == SET_AREA || request.action == SET_SPAWN || request.action == SET_DELIVERY) {
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
            else if (Restaurant.container(level, a) == null) key = "not_container";
            else { r.setDelivery(a); key = "delivery_set"; }
            message = Component.translatable("onemorecore.tool." + key, key.equals("too_big") ? Config.MAX_AREA.get() : PLACE_RANGE);
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
        } else if (request.action >= SET_KIND && request.action <= SET_COMBO_PRICE) {
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
                    else key = switch (r.addCombo(List.of(head.split(";")), price)) {
                        case OK -> "combo_added";
                        case DISHES -> "combo_dishes";
                        case PRICE -> "price_range";
                        case FULL -> "combo_full";
                    };
                }
            }
            message = Component.translatable("onemorecore.computer." + key, Config.MAX_DISH_PRICE.get() * Restaurant.MAX_COMBO_DISHES);
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
        } else if (request.action == UNLOCK) {
            Menu.Entry e = Menu.byId(request.id);
            if (r == null || e == null || !Config.APP_MENU.get()) return null;
            message = switch (r.unlock(e)) {
                case OK -> Component.translatable("onemorecore.cmd.unlocked", e.id());
                case ALREADY -> Component.translatable("onemorecore.cmd.already", e.id());
                case DAY -> Component.translatable("onemorecore.cmd.day", e.id(), e.day());
                case COST -> Component.translatable("onemorecore.cmd.cost", e.id(), e.cost(), r.register());
            };
        } else if (request.action == BUY) {
            Shop.Entry e = Shop.byId(request.id);
            if (r == null || e == null || !Config.APP_SHOP.get()) return null;
            message = switch (r.buy(e)) {
                case OK -> Component.translatable("onemorecore.computer.ordered", e.stack().getHoverName(), r.deliveryTicks() / 20);
                case COST -> Component.translatable("onemorecore.cmd.cost", e.id(), e.price(), r.register());
                case NO_DELIVERY -> Component.translatable("onemorecore.computer.no_delivery");
            };
        } else if (request.action != REFRESH && request.action != REFRESH_MANAGER) {
            return null;
        }
        boolean manager = Config.APP_MANAGER.get() && switch (request.action) {
            case REFRESH_MANAGER, SET_AREA, SET_SPAWN, SET_DELIVERY, TOGGLE_TABLE, SET_SCREEN -> true;
            default -> false;
        };
        return state(level, request.pos, r, false, manager, message);
    }

    private static CheckoutResult sessionEnded(Request request) {
        return new CheckoutResult(request.pos, request.checkoutId, false, Component.translatableWithFallback(
                "onemorecore.computer.cart_session", "Computer session ended. Use the computer again."), Optional.empty());
    }

    private static CheckoutResult checkout(Session session, Request request) {
        Restaurant r = session.restaurant;
        Component message;
        boolean success = false;
        if (r == null) {
            message = Component.translatable("onemorecore.computer.outside");
        } else if (!Config.APP_SHOP.get()) {
            message = Component.translatableWithFallback("onemorecore.computer.cart_disabled", "The Shop is disabled.");
        } else {
            Map<Shop.Entry, Integer> cart = readCart(request.id);
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
                Optional.of(state(session.level, request.pos, r, false, false, Component.empty())));
    }

    /** Reads "id:count;id:count" using the same limits as the Shop screen. */
    private static Map<Shop.Entry, Integer> readCart(String id) {
        if (id.isEmpty() || id.length() > MAX_REQUEST_LENGTH) return null;
        String[] parts = id.split(";", -1);
        if (parts.length > MAX_CART_LINES) return null;
        Map<Shop.Entry, Integer> cart = new LinkedHashMap<>();
        for (String part : parts) {
            int colon = part.lastIndexOf(':');
            if (colon < 0) return null;
            Shop.Entry e = Shop.byId(part.substring(0, colon));
            int count;
            try { count = Integer.parseInt(part.substring(colon + 1)); } catch (NumberFormatException ex) { return null; }
            if (e == null || count < 1 || count > MAX_CART_COUNT || cart.putIfAbsent(e, count) != null) return null;
        }
        return cart;
    }

    /** True if the point is at most PLACE_RANGE blocks from the computer in each axis. */
    static boolean near(BlockPos computer, BlockPos p) {
        return Math.abs(p.getX() - computer.getX()) <= PLACE_RANGE && Math.abs(p.getY() - computer.getY()) <= PLACE_RANGE
                && Math.abs(p.getZ() - computer.getZ()) <= PLACE_RANGE;
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

    /** Everything the screen shows. The menu and shop files exist on the server only. */
    private static State state(ServerLevel level, BlockPos pos, Restaurant r, boolean open, boolean manager, Component message) {
        int apps = (Config.APP_MENU.get() ? APP_MENU : 0) | (Config.APP_SHOP.get() ? APP_SHOP : 0) | (Config.APP_MANAGER.get() ? APP_MANAGER : 0)
                | (Config.APP_UPGRADES.get() ? APP_UPGRADES : 0);
        ItemStack currency = new ItemStack(Config.currency());
        if (r == null) return new State(pos, open, false, apps, currency, 0, List.of(), List.of(), List.of(), message,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        List<MenuLine> menu = new ArrayList<>();
        for (Menu.Entry e : r.dishes()) {
            ItemStack icon = e.tag() == null ? e.stack() : BuiltInRegistries.ITEM.getTag(e.tag())
                    .flatMap(set -> set.stream().findFirst()).map(ItemStack::new).orElse(ItemStack.EMPTY);
            menu.add(new MenuLine(e.id(), icon, e.price(), e.day(), e.cost(), r.isAvailable(e) ? AVAILABLE : r.day() < e.day() ? LOCKED_DAY : BUYABLE,
                    r.isOn(e), e.id().startsWith("custom:"), r.kind(e)));
        }
        List<ShopLine> shop = new ArrayList<>();
        for (Shop.Entry e : Shop.entries) shop.add(new ShopLine(e.id(), e.stack(), e.icon(), e.price()));
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
                Component.translatable("onemorecore.computer.info.orders", r.deliveries.size()));
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
        return new State(pos, open, true, apps, currency, r.register(), menu, shop, info, message, Optional.of(r.sign),
                a == null ? List.of() : List.of(a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()),
                Optional.ofNullable(r.spawn()), Optional.ofNullable(r.delivery()), combos,
                surfaces.size() > 4096 ? surfaces.subList(0, 4096) : surfaces, off.size() > 4096 ? off.subList(0, 4096) : off,
                UPGRADES.stream().map(id -> {
                    var costs = Restaurant.upgradeCosts(id);
                    int lv = r.upgrade(id);
                    return new UpgradeLine(id, lv, costs.size(), lv < costs.size() ? costs.get(lv) : 0);
                }).toList());
    }
}
