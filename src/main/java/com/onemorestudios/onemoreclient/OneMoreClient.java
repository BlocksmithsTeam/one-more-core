package com.onemorestudios.onemoreclient;

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
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(OneMoreClient.MODID)
public class OneMoreClient {
    public static final String MODID = "onemoreclient";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    private static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS = DeferredRegister.create(NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, MODID);

    public static final DeferredBlock<OpenSignBlock> OPEN_SIGN = BLOCKS.register("open_sign",
            () -> new OpenSignBlock(BlockBehaviour.Properties.of().strength(1.5f).lightLevel(s -> s.getValue(OpenSignBlock.OPEN) ? 9 : 0).noOcclusion().sound(SoundType.WOOD).pushReaction(PushReaction.BLOCK)));
    public static final DeferredItem<BlockItem> OPEN_SIGN_ITEM = ITEMS.registerSimpleBlockItem(OPEN_SIGN);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OpenSignBlockEntity>> OPEN_SIGN_ENTITY = BLOCK_ENTITIES.register("open_sign",
            () -> BlockEntityType.Builder.of(OpenSignBlockEntity::new, OPEN_SIGN.get()).build(null));
    public static final DeferredItem<AreaToolItem> AREA_TOOL = ITEMS.register("area_tool",
            () -> new AreaToolItem(new Item.Properties().stacksTo(1)));
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
            .title(Component.translatable("itemGroup.onemoreclient"))
            .icon(() -> new ItemStack(OPEN_SIGN_ITEM.get()))
            .displayItems((parameters, output) -> {
                output.accept(OPEN_SIGN_ITEM);
                output.accept(COMPUTER_ITEM);
                output.accept(AREA_TOOL);
            }).build());

    /** Blocks a customer can sit on. */
    public static final TagKey<Block> SEATS = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "seats"));
    /** Blocks that food is served on: tables, counters. */
    public static final TagKey<Block> SURFACES = BlockTags.create(ResourceLocation.fromNamespaceAndPath(MODID, "serving_surfaces"));

    public OneMoreClient(IEventBus bus, ModContainer container) {
        if (net.neoforged.fml.ModList.get().isLoaded("diegesis")) WipingStation.register();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        SERIALIZERS.register(bus);
        TABS.register(bus);
        BLOCK_ENTITIES.register(bus);
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(CUSTOMER.get(), Customer.createAttributes().build()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> Menu.load(e.getServer().registryAccess()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> SkinPool.start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> SkinPool.stop());
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel level) Restaurants.get(level).tick(level);
        });
        bus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(Dirt.TYPE, Dirt.CODEC, (dirt, context) -> Dirt.receive(dirt))
                .playToClient(Status.TYPE, Status.CODEC, (status, context) -> { Status.client = status; Status.clientTime = System.currentTimeMillis(); })
                .playToServer(ReadyRequest.TYPE, ReadyRequest.CODEC, (request, context) -> ReadyRequest.handle(request, (ServerPlayer) context.player())));
        NeoForge.EVENT_BUS.addListener((ChunkWatchEvent.Sent e) -> Restaurants.get(e.getLevel()).sendDirt(e.getPlayer(), e.getPos()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload e) -> {
            if (e.getLevel().isClientSide()) {
                Dirt.CLIENT.clear();
                Status.client = null;
            }
        });
        NeoForge.EVENT_BUS.addListener(OneMoreClient::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(OneMoreClient::registerCommands);
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
        ItemStack held = event.getItemStack();
        // Wiping: an empty main hand on a dirty surface. Vanilla sends the click again every 4 ticks while the button is down,
        // with the hit position, and checks the reach. No own packet is necessary.
        if (held.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND) {
            // Diegesis also posts this event to check permission before opening a station.
            if (event.getLevel() instanceof ServerLevel && net.neoforged.fml.ModList.get().isLoaded("diegesis")
                    && WipingStation.opens(event.getEntity(), event.getPos(), event.getLevel().getBlockState(event.getPos()))) return;
            boolean wiped;
            if (event.getLevel() instanceof ServerLevel level) {
                Restaurant r = Restaurants.get(level).containing(event.getPos());
                wiped = r != null && r.wipe(level, event.getEntity(), event.getPos(), event.getHitVec().getLocation());
            } else {
                wiped = Dirt.CLIENT.containsKey(event.getPos());
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
                .then(Commands.literal("menu").executes(OneMoreClient::listMenu))
                .then(Commands.literal("unlock").then(Commands.argument("id", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Menu.entries.stream().map(Menu.Entry::id), b))
                        .executes(OneMoreClient::unlock)))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(c -> {
                    Menu.load(c.getSource().registryAccess());
                    Shop.load(c.getSource().registryAccess());
                    c.getSource().sendSuccess(() -> Component.translatable("onemoreclient.cmd.reloaded", Menu.entries.size()), true);
                    return Menu.entries.size();
                })));
    }

    private static Restaurant restaurantOf(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        Restaurant r = Restaurants.get(source.getLevel()).containing(source.getPlayerOrException().blockPosition());
        if (r == null) source.sendFailure(Component.translatable("onemoreclient.cmd.no_restaurant"));
        return r;
    }

    private static int listMenu(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Restaurant r = restaurantOf(c);
        if (r == null) return 0;
        for (Menu.Entry e : Menu.entries)
            c.getSource().sendSuccess(() -> Component.translatable("onemoreclient.cmd.menu_line", e.id(), e.price(), e.day(), e.cost(),
                    r.isAvailable(e) ? "available" : "locked"), false);
        return Menu.entries.size();
    }

    private static int unlock(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Restaurant r = restaurantOf(c);
        if (r == null) return 0;
        String id = StringArgumentType.getString(c, "id");
        Menu.Entry e = Menu.byId(id);
        if (e == null) {
            c.getSource().sendFailure(Component.translatable("onemoreclient.cmd.unknown", id));
            return 0;
        }
        Component message = switch (r.unlock(e)) {
            case OK -> Component.translatable("onemoreclient.cmd.unlocked", id);
            case ALREADY -> Component.translatable("onemoreclient.cmd.already", id);
            case DAY -> Component.translatable("onemoreclient.cmd.day", id, e.day());
            case COST -> Component.translatable("onemoreclient.cmd.cost", id, e.cost(), r.register());
        };
        c.getSource().sendSuccess(() -> message, false);
        return 1;
    }
}
