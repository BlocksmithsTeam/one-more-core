package com.onemorestudios.onemoreclient;

import java.util.ArrayList;
import java.util.List;
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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The Restaurant Computer. It belongs to the restaurant whose area contains it.
 * The screen only sends a {@link Request}. The server checks it, changes the restaurant and sends the new {@link State}.
 */
public class Computer extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The monitor, its back and the keyboard. */
    private static final VoxelShape[] SHAPES = BoxShapes.byFacing(new double[] {2, 0, 4, 14, 11, 13}, new double[] {4, 1, 13, 12, 9, 16},
            new double[] {2, 0, 0.5, 14, 1.5, 3.5});
    public static final int REFRESH = 0, UNLOCK = 1, BUY = 2, SET_AREA = 3, SET_SPAWN = 4, SET_DELIVERY = 5,
            TOGGLE_DISH = 6, ADD_DISH = 7, REMOVE_DISH = 8, SET_PRICE = 9, SET_KIND = 10, ADD_COMBO = 11, REMOVE_COMBO = 12,
            SET_COMBO_PRICE = 13, TOGGLE_TABLE = 14, BUY_UPGRADE = 15, BUY_CART = 16, SET_SCREEN = 17;
    /** How far from the computer the manager app may set a point, in blocks per axis. */
    public static final int PLACE_RANGE = 48;
    /** Bits of {@link State#apps}. */
    public static final int APP_MENU = 1, APP_SHOP = 2, APP_MANAGER = 4, APP_UPGRADES = 8;
    /** The upgrades of the upgrades app, in order. */
    public static final List<String> UPGRADES = List.of("decor", "delivery", "bell");

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
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "computer_state"));
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

    /** Client to server. It holds no price and no item: the server reads them from its own config. */
    public record Request(BlockPos pos, int action, String id) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "computer_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Request::pos, ByteBufCodecs.VAR_INT, Request::action, ByteBufCodecs.stringUtf8(1024), Request::id, Request::new);

        @Override
        public Type<Request> type() { return TYPE; }
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
        if (player instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, state(sp.serverLevel(), pos, true, Component.empty()));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                // The handler runs on the client only, so the client class is not loaded on a dedicated server.
                .playToClient(State.TYPE, State.CODEC, (state, context) -> com.onemorestudios.onemoreclient.client.ComputerCamera.receive(state))
                .playToServer(Request.TYPE, Request.CODEC, (request, context) -> {
                    State state = handle(context.player(), request);
                    if (state != null) context.reply(state);
                });
    }

    /**
     * Checks and runs one request. Returns the new state, or null when the request is rejected:
     * the player is not near the computer, the block is not a computer, the computer is outside every restaurant area,
     * the app is disabled or the id does not exist.
     */
    public static State handle(Player player, Request request) {
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(request.pos)) return null;
        if (!player.canInteractWithBlock(request.pos, 4) || !level.getBlockState(request.pos).is(OneMoreClient.COMPUTER)) return null;
        Restaurants all = Restaurants.get(level);
        Restaurant r = all.forComputer(request.pos);
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
            message = Component.translatable("onemoreclient.tool." + key, key.equals("too_big") ? Config.MAX_AREA.get() : PLACE_RANGE);
        } else if (request.action == BUY_UPGRADE) {
            if (r == null || !Config.APP_UPGRADES.get()) return null;
            Restaurant.Upgrade result = r.buyUpgrade(request.id);
            if (result == Restaurant.Upgrade.UNKNOWN) return null;
            message = Component.translatable("onemoreclient.upgrade." + result.name().toLowerCase(java.util.Locale.ROOT));
        } else if (request.action == TOGGLE_TABLE) {
            if (r == null || !Config.APP_MANAGER.get()) return null;
            int[] n = ints(request.id, 3);
            if (n == null) return null;
            BlockPos p = new BlockPos(n[0], n[1], n[2]);
            String key = !near(request.pos, p) || !level.isLoaded(p) ? "too_far" : r.isOpen() ? "busy" : !r.toggleTable(level, p) ? "not_table"
                    : r.isTableOn(r.tableAt(level, p)) ? "table_on" : "table_off";
            message = Component.translatable("onemoreclient.tool." + key, PLACE_RANGE);
        } else if (request.action == SET_SCREEN) {
            if (r == null || !Config.APP_MANAGER.get()) return null;
            int[] n = ints(request.id, 3);
            if (n == null) return null;
            BlockPos p = new BlockPos(n[0], n[1], n[2]);
            String key = !near(request.pos, p) || !level.isLoaded(p) ? "too_far" : !r.cycleScreen(level, p) ? "not_screen"
                    : "screen_" + r.screens.getOrDefault(p, Restaurant.SCREEN_OFF);
            message = Component.translatable("onemoreclient.tool." + key, PLACE_RANGE);
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
            message = Component.translatable("onemoreclient.computer." + key, Config.MAX_DISH_PRICE.get() * Restaurant.MAX_COMBO_DISHES);
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
            message = Component.translatable("onemoreclient.computer." + key, Config.MAX_DISH_PRICE.get());
        } else if (request.action == UNLOCK) {
            Menu.Entry e = Menu.byId(request.id);
            if (r == null || e == null || !Config.APP_MENU.get()) return null;
            message = switch (r.unlock(e)) {
                case OK -> Component.translatable("onemoreclient.cmd.unlocked", e.id());
                case ALREADY -> Component.translatable("onemoreclient.cmd.already", e.id());
                case DAY -> Component.translatable("onemoreclient.cmd.day", e.id(), e.day());
                case COST -> Component.translatable("onemoreclient.cmd.cost", e.id(), e.cost(), r.register());
            };
        } else if (request.action == BUY) {
            Shop.Entry e = Shop.byId(request.id);
            if (r == null || e == null || !Config.APP_SHOP.get()) return null;
            message = switch (r.buy(e)) {
                case OK -> Component.translatable("onemoreclient.computer.ordered", e.stack().getHoverName(), r.deliveryTicks() / 20);
                case COST -> Component.translatable("onemoreclient.cmd.cost", e.id(), e.price(), r.register());
                case NO_DELIVERY -> Component.translatable("onemoreclient.computer.no_delivery");
            };
        } else if (request.action == BUY_CART) {
            // "id:count;id:count", at most 32 lines.
            if (r == null || !Config.APP_SHOP.get()) return null;
            java.util.Map<Shop.Entry, Integer> cart = new java.util.LinkedHashMap<>();
            String[] parts = request.id.split(";");
            if (parts.length > 32) return null;
            for (String part : parts) {
                int colon = part.lastIndexOf(':');
                if (colon < 0) return null;
                Shop.Entry e = Shop.byId(part.substring(0, colon));
                int count;
                try { count = Integer.parseInt(part.substring(colon + 1)); } catch (NumberFormatException ex) { return null; }
                if (e == null || count < 1 || count > 64) return null;
                cart.merge(e, count, (a, b) -> Math.min(64, a + b));
            }
            long total = cart.entrySet().stream().mapToLong(x -> (long) x.getKey().price() * x.getValue()).sum();
            message = switch (r.buyAll(cart)) {
                case OK -> Component.translatable("onemoreclient.computer.cart_ordered", cart.values().stream().mapToInt(Integer::intValue).sum(), r.deliveryTicks() / 20);
                case COST -> Component.translatable("onemoreclient.computer.cart_cost", total - r.register());
                case NO_DELIVERY -> Component.translatable("onemoreclient.computer.no_delivery");
            };
        } else if (request.action != REFRESH) {
            return null;
        }
        return state(level, request.pos, false, message);
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
    static State state(ServerLevel level, BlockPos pos, boolean open, Component message) {
        Restaurant r = Restaurants.get(level).forComputer(pos);
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
        // ponytail: scans the whole area on every sync of a closed restaurant; cache the seats if large areas make this slow.
        int seats = a == null ? 0 : r.isOpen() ? r.seats.size() : r.scanSeats(level).size();
        Component none = Component.translatable("onemoreclient.computer.info.not_set");
        List<Component> info = List.of(
                Component.translatable("onemoreclient.computer.info.day", r.day()),
                Component.translatable(r.isOpen() ? "onemoreclient.computer.info.open" : "onemoreclient.computer.info.closed"),
                Component.translatable("onemoreclient.computer.info.register", r.register()),
                a == null ? Component.translatable("onemoreclient.computer.info.area_none")
                        : Component.translatable("onemoreclient.computer.info.area", a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()),
                Component.translatable("onemoreclient.computer.info.spawn", r.spawn() == null ? none : r.spawn().toShortString()),
                Component.translatable("onemoreclient.computer.info.delivery", r.delivery() == null ? none : r.delivery().toShortString()),
                Component.translatable("onemoreclient.computer.info.seats", seats),
                Component.translatable("onemoreclient.computer.info.orders", r.deliveries.size()));
        List<ComboLine> combos = r.combos.stream().map(c -> new ComboLine(c.id(), c.dishes(), c.price(), r.canOrder(c))).toList();
        // The serving surfaces next to seats, and the ones of tables that are off, for the free camera's table tool.
        List<BlockPos> surfaces = new ArrayList<>(), off = new ArrayList<>();
        if (a != null) {
            for (List<Restaurant.Seat> table : r.groupTables(level, r.isOpen() ? r.seats : r.scanSeats(level))) {
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
