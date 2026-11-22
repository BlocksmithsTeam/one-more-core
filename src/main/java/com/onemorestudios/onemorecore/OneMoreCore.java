package com.onemorestudios.onemorecore;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(OneMoreCore.MODID)
public class OneMoreCore {
    public static final String MODID = "onemorecore";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    private static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS = DeferredRegister.create(NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, MODID);

    public static final DeferredBlock<OpenSignBlock> OPEN_SIGN = BLOCKS.register("open_sign",
            () -> new OpenSignBlock(BlockBehaviour.Properties.of().strength(1.5f).lightLevel(s -> s.getValue(OpenSignBlock.OPEN) ? 9 : 0).noOcclusion().sound(SoundType.WOOD).pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> OPEN_SIGN_ITEM = ITEMS.registerSimpleBlockItem(OPEN_SIGN);
    public static final DeferredBlock<MenuBoardBlock> MENU_BOARD = BLOCKS.register("menu_board",
            () -> new MenuBoardBlock(BlockBehaviour.Properties.of().strength(1f).noOcclusion().noCollission().sound(SoundType.WOOD)));
    public static final DeferredItem<BlockItem> MENU_BOARD_ITEM = ITEMS.registerSimpleBlockItem(MENU_BOARD);
    public static final DeferredBlock<MonitorBlock> MONITOR = BLOCKS.register("monitor",
            () -> new MonitorBlock(BlockBehaviour.Properties.of().strength(1f).noOcclusion().noCollission().sound(SoundType.METAL)));
    public static final DeferredItem<BlockItem> MONITOR_ITEM = ITEMS.registerSimpleBlockItem(MONITOR);
    /** The space of a TV besides its own block. */
    public static final DeferredBlock<ScreenPartBlock> SCREEN_PART = BLOCKS.register("screen_part",
            () -> new ScreenPartBlock(BlockBehaviour.Properties.of().strength(1f).noOcclusion().noCollission().noLootTable().sound(SoundType.METAL)));
    /** The default money of the mod (currencyItem). */
    public static final DeferredItem<Item> COIN = ITEMS.registerSimpleItem("coin", new Item.Properties().stacksTo(99));
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    /** Old: the automatic table number of a ticket or tray (kept so that old items still load). */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>, net.minecraft.core.component.DataComponentType<Integer>> TABLE =
            COMPONENTS.registerComponentType("table", b -> b.persistent(com.mojang.serialization.Codec.INT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));
    /** The table card text of an order ticket and of a tray's label. */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>, net.minecraft.core.component.DataComponentType<String>> LABEL =
            COMPONENTS.registerComponentType("table_label", b -> b.persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.stringUtf8(16)));
    /** A table number card: put it on a table and type its text. */
    /** Splits and joins tables (TableCuts), like a wrench. */
    public static final DeferredItem<Item> SCREWDRIVER = ITEMS.registerSimpleItem("screwdriver", new Item.Properties().stacksTo(1));
    public static final DeferredBlock<TableNumberBlock> TABLE_NUMBER_BLOCK = BLOCKS.register("table_number",
            () -> new TableNumberBlock(BlockBehaviour.Properties.of().strength(0.2f).noOcclusion().sound(SoundType.WOOD).pushReaction(PushReaction.DESTROY)));
    public static final DeferredItem<TableNumberItem> TABLE_NUMBER = ITEMS.register("table_number",
            () -> new TableNumberItem(TABLE_NUMBER_BLOCK.get(), new Item.Properties().stacksTo(16)));
    public static final DeferredBlock<TrayBlock> TRAY = BLOCKS.register("tray", () -> new TrayBlock(trayProperties()));
    public static final DeferredItem<TrayItem> TRAY_ITEM = ITEMS.register("tray", () -> new TrayItem(TRAY.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<TrayBlock> IRON_TRAY = BLOCKS.register("iron_tray", () -> new TrayBlock(trayProperties()));
    public static final DeferredItem<TrayItem> IRON_TRAY_ITEM = ITEMS.register("iron_tray",
            () -> new TrayItem(IRON_TRAY.get(), new Item.Properties().stacksTo(1)));

    private static BlockBehaviour.Properties trayProperties() {
        return BlockBehaviour.Properties.of().strength(0.5f).noOcclusion().sound(SoundType.WOOD).pushReaction(PushReaction.DESTROY);
    }

    public static final DeferredBlock<TrayRackBlock> TRAY_RACK = BLOCKS.register("tray_rack",
            () -> new TrayRackBlock(BlockBehaviour.Properties.of().strength(1f).noOcclusion().sound(SoundType.WOOD)));
    public static final DeferredItem<BlockItem> TRAY_RACK_ITEM = ITEMS.registerSimpleBlockItem(TRAY_RACK);
    public static final DeferredItem<TicketItem> TICKET = ITEMS.register("ticket", () -> new TicketItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<OrderBoardBlock> ORDER_BOARD = BLOCKS.register("order_board",
            () -> new OrderBoardBlock(BlockBehaviour.Properties.of().strength(1f).noOcclusion().noCollission().sound(SoundType.WOOD)));
    public static final DeferredItem<BlockItem> ORDER_BOARD_ITEM = ITEMS.registerSimpleBlockItem(ORDER_BOARD);
    public static final DeferredBlock<MenuBookBlock> MENU_BOOK = BLOCKS.register("menu_book",
            () -> new MenuBookBlock(BlockBehaviour.Properties.of().strength(0.4f).noOcclusion().sound(SoundType.WOOL).pushReaction(PushReaction.DESTROY)));
    public static final DeferredItem<BlockItem> MENU_BOOK_ITEM = ITEMS.registerSimpleBlockItem(MENU_BOOK);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OpenSignBlockEntity>> OPEN_SIGN_ENTITY = BLOCK_ENTITIES.register("open_sign",
            () -> BlockEntityType.Builder.of(OpenSignBlockEntity::new, OPEN_SIGN.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MenuBoardBlockEntity>> MENU_BOARD_ENTITY = BLOCK_ENTITIES.register("menu_board",
            () -> BlockEntityType.Builder.of(MenuBoardBlockEntity::new, MENU_BOARD.get(), MONITOR.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrayBlockEntity>> TRAY_ENTITY = BLOCK_ENTITIES.register("tray",
            () -> BlockEntityType.Builder.of(TrayBlockEntity::new, TRAY.get(), IRON_TRAY.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OrderBoardBlockEntity>> ORDER_BOARD_ENTITY = BLOCK_ENTITIES.register("order_board",
            () -> BlockEntityType.Builder.of(OrderBoardBlockEntity::new, ORDER_BOARD.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TableNumberBlockEntity>> TABLE_NUMBER_ENTITY = BLOCK_ENTITIES.register("table_number",
            () -> BlockEntityType.Builder.of(TableNumberBlockEntity::new, TABLE_NUMBER_BLOCK.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MenuBookBlockEntity>> MENU_BOOK_ENTITY = BLOCK_ENTITIES.register("menu_book",
            () -> BlockEntityType.Builder.of(MenuBookBlockEntity::new, MENU_BOOK.get()).build(null));
    public static final DeferredHolder<EntityType<?>, EntityType<Customer>> CUSTOMER = ENTITIES.register("customer",
            () -> EntityType.Builder.<Customer>of(Customer::new, MobCategory.MISC).sized(0.6f, 1.8f).noSave().noSummon().clientTrackingRange(8).build("customer"));
    public static final DeferredHolder<EntityDataSerializer<?>, EntityDataSerializer<List<ItemStack>>> STACKS = SERIALIZERS.register("stacks",
            () -> EntityDataSerializer.forValueType(ItemStack.OPTIONAL_LIST_STREAM_CODEC));
    public static final DeferredBlock<Computer> COMPUTER = BLOCKS.register("computer",
            () -> new Computer(BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> COMPUTER_ITEM = ITEMS.registerSimpleBlockItem(COMPUTER);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    /** One creative tab with every item of the mod. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.onemorecore"))
            .icon(() -> new ItemStack(OPEN_SIGN_ITEM.get()))
            .displayItems((parameters, output) -> {
                output.accept(OPEN_SIGN_ITEM);
                output.accept(COMPUTER_ITEM);
                output.accept(MENU_BOARD_ITEM);
                output.accept(MENU_BOOK_ITEM);
                output.accept(ORDER_BOARD_ITEM);
                output.accept(MONITOR_ITEM);
                output.accept(TRAY_RACK_ITEM);
                output.accept(TRAY_ITEM);
                output.accept(IRON_TRAY_ITEM);
                output.accept(COIN);
                output.accept(TABLE_NUMBER);
                output.accept(SCREWDRIVER);
            }).build());

    /** Blocks a customer can sit on. */
    public static final TagKey<Block> SEATS = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "seats"));
    /** Blocks that food is served on: tables, counters. */
    public static final TagKey<Block> SURFACES = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "serving_surfaces"));
    /** Items that split and join tables: the screwdriver and other mods' wrenches (c:tools/wrench). */
    public static final TagKey<Item> TABLE_TOOLS = net.minecraft.tags.ItemTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "table_tools"));

    public OneMoreCore(IEventBus bus, ModContainer container) {
        if (net.neoforged.fml.ModList.get().isLoaded("diegesis")) {
            WipingStation.register();
            KitchenStations.register();
        }
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        SERIALIZERS.register(bus);
        TABS.register(bus);
        COMPONENTS.register(bus);
        BLOCK_ENTITIES.register(bus);
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(CUSTOMER.get(), Customer.createAttributes().build()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> Menu.load(e.getServer().registryAccess()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> SkinPool.start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> SkinPool.stop());
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel level) Restaurants.get(level).tick(level);
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getLevel() instanceof ServerLevel level) Restaurants.get(level).blockChanged(e.getPos());
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                Restaurants all = Restaurants.get(level);
                if (e instanceof BlockEvent.EntityMultiPlaceEvent multi)
                    multi.getReplacedBlockSnapshots().forEach(snapshot -> all.blockChanged(snapshot.getPos()));
                else all.blockChanged(e.getPos());
            }
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.NeighborNotifyEvent e) -> {
            if (e.getLevel() instanceof ServerLevel level) Restaurants.get(level).blockChanged(e.getPos());
        });
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> {
            if (e.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.execute(() -> server.getAllLevels().forEach(level -> Restaurants.get(level).invalidateLayouts()));
        });
        bus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(Dirt.TYPE, Dirt.CODEC, (dirt, context) -> Dirt.receive(dirt))
                .playToClient(TableItems.TYPE, TableItems.CODEC, (t, context) -> TableItems.receive(t))
                .playToClient(TableMarks.TYPE, TableMarks.CODEC, (t, context) -> TableMarks.receive(t))
                .playToClient(ScreenData.TYPE, ScreenData.CODEC, (t, context) -> ScreenData.receive(t))
                .playToClient(MenuBookBlockEntity.Open.TYPE, MenuBookBlockEntity.Open.CODEC, (o, context) -> MenuBookBlockEntity.Open.handler.accept(o))
                .playToClient(TableItems.Collected.TYPE, TableItems.Collected.CODEC, (c, context) -> TableItems.Collected.receive(c))
                .playToServer(ReadyRequest.TYPE, ReadyRequest.CODEC, (request, context) -> ReadyRequest.handle(request, (ServerPlayer) context.player()))
                .playToServer(ScreenClick.TYPE, ScreenClick.CODEC, (click, context) -> ScreenClick.handle(click, (ServerPlayer) context.player()))
                .playToClient(TableCards.Edit.TYPE, TableCards.Edit.CODEC, (edit, context) -> TableCards.Edit.handler.accept(edit))
                .playToClient(ScreenSettings.Open.TYPE, ScreenSettings.Open.CODEC, (o, context) -> ScreenSettings.Open.handler.accept(o))
                .playToServer(ScreenSettings.Save.TYPE, ScreenSettings.Save.CODEC, (save, context) -> ScreenSettings.Save.handle(save, (ServerPlayer) context.player()))
                .playToServer(TableCards.Text.TYPE, TableCards.Text.CODEC, (text, context) -> TableCards.Text.handle(text, (ServerPlayer) context.player()))
                .versioned("2").playToClient(Status.TYPE, Status.CODEC, (status, context) -> Status.receive(status)));
        NeoForge.EVENT_BUS.addListener((ChunkWatchEvent.Sent e) -> {
            Restaurants all = Restaurants.get(e.getLevel());
            all.sendDirt(e.getPlayer(), e.getPos());
            all.sendTableItems(e.getPlayer(), e.getPos());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload e) -> {
            if (e.getLevel().isClientSide()) {
                Dirt.CLIENT.clear();
                TableItems.CLIENT.clear();
                TableMarks.CLIENT.clear();
                ScreenData.CLIENT.clear();
                Status.clearClient();
            }
        });
        NeoForge.EVENT_BUS.addListener(OneMoreCore::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(OneMoreCore::registerCommands);
        bus.addListener(Computer::registerPayloads);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> Shop.load(e.getServer().registryAccess()));
    }

    /** Food delivery: a player clicks the serving surface of a waiting customer with an ordered item. */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Furniture mods must not mount a player on a seat already used by a customer.
        if (event.getLevel() instanceof ServerLevel level && level.getBlockState(event.getPos()).is(SEATS)
                && !level.getEntitiesOfClass(Customer.class, new AABB(event.getPos()).inflate(2),
                        c -> c.isSeated() && event.getPos().equals(c.seat)).isEmpty()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (ScreenSettings.onUse(event)) return;
        ItemStack held = event.getItemStack();
        // A Table Number goes on a table like a block, also on tables of other mods that take items on a click (Kaleidoscope).
        if (held.is(TABLE_NUMBER.get()) && event.getLevel().getBlockState(event.getPos()).is(SURFACES)
                && event.getFace() == net.minecraft.core.Direction.UP) {
            event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            return;
        }
        // A screwdriver (or any mod's wrench) on a table top near the edge to another table block splits the two tables there or
        // joins them again, like Create's wrench on mechanical crafters. The restaurant must be closed.
        if (held.is(TABLE_TOOLS) && event.getHand() == InteractionHand.MAIN_HAND && event.getLevel().getBlockState(event.getPos()).is(SURFACES)) {
            net.minecraft.core.Direction edge = TableCuts.edge(event.getLevel(), event.getPos(), event.getHitVec());
            if (edge == null) return;
            if (event.getLevel() instanceof ServerLevel level) {
                Restaurant r = Restaurants.get(level).containing(event.getPos());
                if (r == null) return;
                if (r.isOpen()) {
                    event.getEntity().displayClientMessage(Component.translatable("onemorecore.tool.busy"), true);
                } else if (r.toggleCut(level, event.getPos(), edge)) {
                    boolean cut = r.cuts.cut(event.getPos(), edge);
                    event.getEntity().displayClientMessage(Component.translatable("onemorecore.tool." + (cut ? "table_split" : "table_joined")), true);
                    level.playSound(null, event.getPos(), cut ? net.minecraft.sounds.SoundEvents.CHAIN_BREAK : net.minecraft.sounds.SoundEvents.CHAIN_PLACE,
                            net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 1.2f);
                }
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        // Sneak-click with an empty hand on a table of a closed restaurant turns the table off or on (like PlateUp).
        if (held.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND && event.getEntity().isShiftKeyDown()
                && event.getLevel().getBlockState(event.getPos()).is(SURFACES)) {
            if (event.getLevel() instanceof ServerLevel level) {
                Restaurant r = Restaurants.get(level).containing(event.getPos());
                if (r == null || r.isOpen() || !r.toggleTable(level, event.getPos())) return;
                boolean on = r.isTableOn(r.tableAt(level, event.getPos()));
                event.getEntity().displayClientMessage(Component.translatable("onemorecore.tool." + (on ? "table_on" : "table_off")), true);
                level.playSound(null, event.getPos(), on ? net.minecraft.sounds.SoundEvents.WOODEN_BUTTON_CLICK_ON : net.minecraft.sounds.SoundEvents.WOODEN_BUTTON_CLICK_OFF,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        // A bell in the area of an open restaurant with the bell upgrade calls the next group; the bell rings as usual.
        if (event.getLevel() instanceof ServerLevel level && event.getHand() == InteractionHand.MAIN_HAND
                && level.getBlockState(event.getPos()).is(net.minecraft.world.level.block.Blocks.BELL)) {
            Restaurant r = Restaurants.get(level).containing(event.getPos());
            if (r != null && r.ringBell(level)) event.getEntity().displayClientMessage(Component.translatable("onemorecore.bell.called"), true);
            return;
        }
        // Coins and dirty dishes on a table: an empty hand collects them first (without Diegesis; with it, in the station).
        if (held.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND && event.getLevel().getBlockState(event.getPos()).is(SURFACES)
                && !(net.neoforged.fml.ModList.get().isLoaded("diegesis"))) {
            boolean has;
            if (event.getLevel() instanceof ServerLevel level) {
                Restaurant r = Restaurants.get(level).containing(event.getPos());
                has = r != null && !r.tableAt(level, event.getPos()).stream().filter(r::hasTableItems).toList().isEmpty();
                if (has) r.collect(level, event.getEntity(), event.getPos());
            } else {
                has = TableItems.CLIENT.containsKey(event.getPos());
            }
            if (has) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
        }
        // Wiping: an empty main hand on a dirty surface. Vanilla sends the click again every 4 ticks while the button is down,
        // with the hit position, and checks the reach. No own packet is necessary.
        if (held.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND) {
            // With Diegesis the click opens the wiping station: on the client too, the event must stay uncancelled, because
            // Diegesis (1.0.0-beta.15) opens stations from its own lowest-priority listener, which skips cancelled events.
            // Diegesis also posts this event on the server to check permission before opening a station.
            if (net.neoforged.fml.ModList.get().isLoaded("diegesis")
                    && WipingStation.opens(event.getEntity(), event.getPos(), event.getLevel().getBlockState(event.getPos()))) return;
            boolean wiped;
            if (event.getLevel() instanceof ServerLevel level) {
                Restaurant r = Restaurants.get(level).containing(event.getPos());
                wiped = r != null && r.wipe(level, event.getEntity(), event.getPos(), event.getHitVec().getLocation());
            } else {
                wiped = Dirt.tableDirty(event.getLevel(), event.getPos());
            }
            if (!wiped) return;
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (held.isEmpty() || !event.getLevel().getBlockState(event.getPos()).is(SURFACES)) return;
        Customer customer = Customer.waitingAt(event.getLevel(), event.getPos(), held);
        if (customer == null) return;
        if (!event.getLevel().isClientSide) customer.deliver(event.getEntity(), held);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(MODID)
                .then(Commands.literal("menu").executes(OneMoreCore::listMenu))
                .then(Commands.literal("unlock").then(Commands.argument("id", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Menu.entries.stream().map(Menu.Entry::id), b))
                        .executes(OneMoreCore::unlock)))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> {
                    Menu.load(c.getSource().registryAccess());
                    Shop.load(c.getSource().registryAccess());
                    c.getSource().sendSuccess(() -> Component.translatable("onemorecore.cmd.reloaded", Menu.entries.size()), true);
                    return Menu.entries.size();
                })));
    }

    private static Restaurant restaurantOf(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        Restaurant r = Restaurants.get(source.getLevel()).containing(source.getPlayerOrException().blockPosition());
        if (r == null) source.sendFailure(Component.translatable("onemorecore.cmd.no_restaurant"));
        return r;
    }

    private static int listMenu(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Restaurant r = restaurantOf(c);
        if (r == null) return 0;
        for (Menu.Entry e : Menu.entries)
            c.getSource().sendSuccess(() -> Component.translatable("onemorecore.cmd.menu_line", e.id(), e.price(), e.day(), e.cost(),
                    r.isAvailable(e) ? "available" : "locked"), false);
        return Menu.entries.size();
    }

    private static int unlock(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Restaurant r = restaurantOf(c);
        if (r == null) return 0;
        String id = StringArgumentType.getString(c, "id");
        Menu.Entry e = Menu.byId(id);
        if (e == null) {
            c.getSource().sendFailure(Component.translatable("onemorecore.cmd.unknown", id));
            return 0;
        }
        Component message = switch (r.unlock(e)) {
            case OK -> Component.translatable("onemorecore.cmd.unlocked", id);
            case ALREADY -> Component.translatable("onemorecore.cmd.already", id);
            case DAY -> Component.translatable("onemorecore.cmd.day", id, e.day());
            case COST -> Component.translatable("onemorecore.cmd.cost", id, e.cost(), r.register());
        };
        c.getSource().sendSuccess(() -> message, false);
        return 1;
    }
}
