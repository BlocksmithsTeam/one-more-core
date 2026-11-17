package com.onemorestudios.onemoreclient;

import java.util.ArrayList;
import java.util.List;

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
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The Restaurant Computer. It belongs to the restaurant whose area contains it.
 * The screen only sends a {@link Request}. The server checks it, changes the restaurant and sends the new {@link State}.
 */
public class Computer extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final int REFRESH = 0, UNLOCK = 1, BUY = 2;
    /** Bits of {@link State#apps}. */
    public static final int APP_MENU = 1, APP_SHOP = 2, APP_MANAGER = 4;
    /** Values of {@link MenuLine#state}. */
    public static final int AVAILABLE = 0, LOCKED_DAY = 1, BUYABLE = 2;

    public record MenuLine(String id, ItemStack icon, int price, int day, int cost, int state) {
        static final StreamCodec<RegistryFriendlyByteBuf, MenuLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, MenuLine::id, ItemStack.OPTIONAL_STREAM_CODEC, MenuLine::icon, ByteBufCodecs.VAR_INT, MenuLine::price,
                ByteBufCodecs.VAR_INT, MenuLine::day, ByteBufCodecs.VAR_INT, MenuLine::cost, ByteBufCodecs.VAR_INT, MenuLine::state, MenuLine::new);
    }

    public record ShopLine(String id, ItemStack stack, ItemStack icon, int price) {
        static final StreamCodec<RegistryFriendlyByteBuf, ShopLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ShopLine::id, ItemStack.OPTIONAL_STREAM_CODEC, ShopLine::stack,
                ItemStack.OPTIONAL_STREAM_CODEC, ShopLine::icon, ByteBufCodecs.VAR_INT, ShopLine::price, ShopLine::new);
    }

    /**
     * Server to client. "open" opens the screen; without it only an open screen is updated.
     * "found" is false when the computer is outside every restaurant area. "info" holds the lines of the manager app.
     */
    public record State(BlockPos pos, boolean open, boolean found, int apps, ItemStack currency, int balance,
            List<MenuLine> menu, List<ShopLine> shop, List<Component> info, Component message) implements CustomPacketPayload {
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
        }, b -> new State(b.readBlockPos(), b.readBoolean(), b.readBoolean(), b.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readVarInt(),
                MenuLine.CODEC.apply(ByteBufCodecs.list()).decode(b), ShopLine.CODEC.apply(ByteBufCodecs.list()).decode(b),
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(b), ComponentSerialization.STREAM_CODEC.decode(b)));

        @Override
        public Type<State> type() { return TYPE; }
    }

    /** Client to server. It holds no price and no item: the server reads them from its own config. */
    public record Request(BlockPos pos, int action, String id) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "computer_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Request::pos, ByteBufCodecs.VAR_INT, Request::action, ByteBufCodecs.stringUtf8(256), Request::id, Request::new);

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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, state(sp.serverLevel(), pos, true, Component.empty()));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                // The handler runs on the client only, so the client class is not loaded on a dedicated server.
                .playToClient(State.TYPE, State.CODEC, (state, context) -> com.onemorestudios.onemoreclient.client.ComputerScreen.receive(state))
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
        Restaurant r = Restaurants.get(level).containing(request.pos);
        Component message = Component.empty();
        if (request.action == UNLOCK) {
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
                case OK -> Component.translatable("onemoreclient.computer.ordered", e.stack().getHoverName(), Config.SHOP_DELIVERY.get() / 20);
                case COST -> Component.translatable("onemoreclient.cmd.cost", e.id(), e.price(), r.register());
                case NO_DELIVERY -> Component.translatable("onemoreclient.computer.no_delivery");
            };
        } else if (request.action != REFRESH) {
            return null;
        }
        return state(level, request.pos, false, message);
    }

    /** Everything the screen shows. The menu and shop files exist on the server only. */
    static State state(ServerLevel level, BlockPos pos, boolean open, Component message) {
        Restaurant r = Restaurants.get(level).containing(pos);
        int apps = (Config.APP_MENU.get() ? APP_MENU : 0) | (Config.APP_SHOP.get() ? APP_SHOP : 0) | (Config.APP_MANAGER.get() ? APP_MANAGER : 0);
        ItemStack currency = new ItemStack(Config.currency());
        if (r == null) return new State(pos, open, false, apps, currency, 0, List.of(), List.of(), List.of(), message);
        List<MenuLine> menu = new ArrayList<>();
        for (Menu.Entry e : Menu.entries) {
            ItemStack icon = e.tag() == null ? e.stack() : BuiltInRegistries.ITEM.getTag(e.tag())
                    .flatMap(set -> set.stream().findFirst()).map(ItemStack::new).orElse(ItemStack.EMPTY);
            menu.add(new MenuLine(e.id(), icon, e.price(), e.day(), e.cost(), r.isAvailable(e) ? AVAILABLE : r.day() < e.day() ? LOCKED_DAY : BUYABLE));
        }
        List<ShopLine> shop = new ArrayList<>();
        for (Shop.Entry e : Shop.entries) shop.add(new ShopLine(e.id(), e.stack(), e.icon(), e.price()));
        BoundingBox a = r.area();
        // ponytail: scans the whole area on every sync of a closed restaurant; cache the seats if large areas make this slow.
        int seats = r.isOpen() ? r.seats.size() : r.scanSeats(level).size();
        Component none = Component.translatable("onemoreclient.computer.info.not_set");
        List<Component> info = List.of(
                Component.translatable("onemoreclient.computer.info.day", r.day()),
                Component.translatable(r.isOpen() ? "onemoreclient.computer.info.open" : "onemoreclient.computer.info.closed"),
                Component.translatable("onemoreclient.computer.info.register", r.register()),
                Component.translatable("onemoreclient.computer.info.area", a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ()),
                Component.translatable("onemoreclient.computer.info.spawn", r.spawn() == null ? none : r.spawn().toShortString()),
                Component.translatable("onemoreclient.computer.info.delivery", r.delivery() == null ? none : r.delivery().toShortString()),
                Component.translatable("onemoreclient.computer.info.seats", seats),
                Component.translatable("onemoreclient.computer.info.orders", r.deliveries.size()));
        return new State(pos, open, true, apps, currency, r.register(), menu, shop, info, message);
    }
}
