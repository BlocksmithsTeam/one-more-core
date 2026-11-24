package com.onemorestudios.onemorecore.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import com.onemorestudios.onemorecore.SkinPool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The fixes of 2026-11-23: one restaurant per screen, chat viewers come once, the tray rack's open shell. */
@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class FixesGameTests {
    private static Object get(Object o, Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    /** Two restaurants that set the same screen: the last one owns it; before, both drew a picture on it (menu and orders mixed). */
    /**
     * The mod's own tags have no vanilla blocks (only the GameTest pack adds stairs and slabs, for these tests): in a world,
     * no vanilla stair, slab, trapdoor or fence is a seat or a table.
     */
    @GameTest(template = "empty")
    public static void vanillaBlocksAreNoFurniture(GameTestHelper helper) {
        var file = net.neoforged.fml.ModList.get().getModFileById(OneMoreCore.MODID).getFile();
        try {
            String seats = java.nio.file.Files.readString(file.findResource("data/onemorecore/tags/block/seats.json"));
            String tables = java.nio.file.Files.readString(file.findResource("data/onemorecore/tags/block/serving_surfaces.json"));
            helper.assertFalse(seats.contains("minecraft:"), "seats name vanilla blocks");
            helper.assertFalse(tables.contains("minecraft:"), "serving surfaces name vanilla blocks");
            // The GameTests get stairs and slabs back from gametest_pack. OneMoreCore adds that pack only when
            // GameTestHooks.isGametestServer(), which is false whenever FMLLoader.isProduction() (every player's game).
            helper.assertTrue(helper.getLevel().getServer().getPackRepository().getSelectedIds().stream().anyMatch(id -> id.contains("gametest_pack")),
                    "the test pack is on in the GameTest server");
            helper.assertTrue(net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState().is(OneMoreCore.SEATS),
                    "the test pack makes stairs seats here");
        } catch (java.io.IOException ex) {
            helper.fail("tag file: " + ex);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void screenBelongsToOneRestaurant(GameTestHelper helper) throws ReflectiveOperationException {
        Restaurants all = Restaurants.get(helper.getLevel());
        Method create = Restaurants.class.getDeclaredMethod("create", BlockPos.class);
        create.setAccessible(true);
        Restaurant a = (Restaurant) create.invoke(all, helper.absolutePos(new BlockPos(0, 1, 0)));
        Restaurant b = (Restaurant) create.invoke(all, helper.absolutePos(new BlockPos(4, 1, 0)));
        BlockPos screen = helper.absolutePos(new BlockPos(2, 2, 2));
        Method set = Restaurant.class.getDeclaredMethod("setScreen", BlockPos.class, int.class);
        set.setAccessible(true);
        set.invoke(a, screen, 1);
        set.invoke(b, screen, 2);
        Map<?, ?> sa = (Map<?, ?>) get(a, Restaurant.class, "screens"), sb = (Map<?, ?>) get(b, Restaurant.class, "screens");
        helper.assertTrue(!sa.containsKey(screen), "the first restaurant still has the screen");
        helper.assertTrue(Integer.valueOf(2).equals(sb.get(screen)), "the second restaurant lost the screen");
        Method remove = Restaurants.class.getDeclaredMethod("remove", BlockPos.class);
        remove.setAccessible(true);
        remove.invoke(all, a.sign);
        remove.invoke(all, b.sign);
        helper.succeed();
    }

    /** A chat viewer comes once: picking takes the entry out; with nothing free a customer gets no shared face (null). */
    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void chatViewerComesOnce(GameTestHelper helper) throws ReflectiveOperationException {
        Map<String, SkinPool.Entry> chat = (Map<String, SkinPool.Entry>) get(null, SkinPool.class, "chat");
        Map<String, SkinPool.Entry> saved = new java.util.LinkedHashMap<>(chat);
        Field namesField = Class.forName("com.onemorestudios.onemorecore.Config").getDeclaredField("SKIN_NAMES");
        namesField.setAccessible(true);
        var names = (net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<List<? extends String>>) namesField.get(null);
        List<? extends String> oldNames = names.get();
        try {
            names.set(List.of());
            chat.clear();
            SkinPool.Entry viewer = new SkinPool.Entry("Viewer", "Notch");
            chat.put("kick:1", viewer);
            Method pick = SkinPool.class.getDeclaredMethod("pick", net.minecraft.util.RandomSource.class, java.util.Collection.class);
            pick.setAccessible(true);
            Object first = pick.invoke(null, helper.getLevel().random, List.of());
            helper.assertTrue(viewer.equals(first), "the viewer was not picked: " + first);
            helper.assertTrue(chat.isEmpty(), "the viewer stays in the pool");
            helper.assertTrue(pick.invoke(null, helper.getLevel().random, List.of()) == null, "a second customer got a face again");
        } finally {
            chat.clear();
            chat.putAll(saved);
            names.set(oldNames);
        }
        helper.succeed();
    }

    /** A broken tray drops with its ticket; the rack keeps only a tray's kind, so it refuses a tray with a ticket (and keeps the ticket safe). */
    @GameTest(template = "empty")
    public static void ticketSurvivesBreakAndRack(GameTestHelper helper) {
        BlockPos at = new BlockPos(1, 2, 1);
        helper.setBlock(at.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(at, OneMoreCore.TRAY.get());
        var tray = (com.onemorestudios.onemorecore.TrayBlockEntity) helper.getBlockEntity(at);
        var ticket = new net.minecraft.world.item.ItemStack(OneMoreCore.TICKET.get());
        ticket.set(OneMoreCore.LABEL.get(), "4");
        tray.putTicket(ticket);
        BlockPos pos = helper.absolutePos(at);
        helper.getLevel().destroyBlock(pos, true);
        var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(2));
        var item = drops.stream().map(net.minecraft.world.entity.item.ItemEntity::getItem).filter(s -> s.is(OneMoreCore.TRAY_ITEM.get())).findFirst().orElse(null);
        helper.assertTrue(item != null && item.has(OneMoreCore.TRAY_TICKET.get()), "The broken tray drops with its ticket: " + item);
        drops.forEach(net.minecraft.world.entity.Entity::discard);

        BlockPos rack = new BlockPos(3, 2, 1);
        helper.setBlock(rack, OneMoreCore.TRAY_RACK.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        var player = GameTestSupport.player(helper);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item);
        BlockPos rackPos = helper.absolutePos(rack);
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(rackPos), Direction.NORTH, rackPos, false);
        var result = helper.getLevel().getBlockState(rackPos).useItemOn(item, helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(result == net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION, "The rack refuses a tray with a ticket: " + result);
        helper.assertTrue(item.getCount() == 1 && item.has(OneMoreCore.TRAY_TICKET.get()), "The tray keeps its ticket");
        var plain = new net.minecraft.world.item.ItemStack(OneMoreCore.TRAY_ITEM.get());
        result = helper.getLevel().getBlockState(rackPos).useItemOn(plain, helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(plain.isEmpty(), "The rack takes a plain tray: " + result);
        helper.succeed();
    }

    /** The rack's hitbox is a shell 1 px thick: the middle, the front and the back are open, its sides are solid at every level. */
    @GameTest(template = "empty")
    public static void trayRackIsAnOpenShell(GameTestHelper helper) {
        var state = OneMoreCore.TRAY_RACK.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        var shape = state.getShape(helper.getLevel(), BlockPos.ZERO, CollisionContext.empty());
        java.util.function.Predicate<Vec3> in = p -> shape.toAabbs().stream().anyMatch(b -> b.contains(p.scale(1 / 16.0)));
        for (double y : new double[] {1.5, 3.5, 7.5, 11.5}) {
            helper.assertTrue(!in.test(new Vec3(8, y, 8)), "the middle is solid at y " + y);
            helper.assertTrue(!in.test(new Vec3(8, y, 0.5)) && !in.test(new Vec3(8, y, 15.5)), "the front or back is solid at y " + y);
            helper.assertTrue(in.test(new Vec3(0.5, y, 8)) && in.test(new Vec3(15.5, y, 8)), "a side is open at y " + y);
        }
        helper.succeed();
    }
    /**
     * A new sign (or a new area) forgets the cards while the Table Number blocks stay on the tables: the restaurant finds them
     * again, so the orders name the table (they showed "TABLE ?" until the number was typed again).
     */
    @GameTest(template = "empty")
    public static void forgottenCardIsFoundAgain(GameTestHelper helper) throws ReflectiveOperationException {
        var level = helper.getLevel();
        Restaurants all = Restaurants.get(level);
        Method create = Restaurants.class.getDeclaredMethod("create", BlockPos.class);
        create.setAccessible(true);
        Restaurant r = (Restaurant) create.invoke(all, helper.absolutePos(new BlockPos(0, 1, 0)));
        Method setArea = Restaurant.class.getDeclaredMethod("setArea", net.minecraft.world.level.levelgen.structure.BoundingBox.class);
        setArea.setAccessible(true);
        setArea.invoke(r, net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(4, 3, 4))));
        BlockPos table = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(table, net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState());
        level.setBlockAndUpdate(table.east(), net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState());
        level.setBlockAndUpdate(table.above(), OneMoreCore.TABLE_NUMBER_BLOCK.get().defaultBlockState());
        var be = level.getBlockEntity(table.above());
        Method setText = be.getClass().getDeclaredMethod("setText", String.class);
        setText.setAccessible(true);
        setText.invoke(be, "12");
        ((Map<?, ?>) get(r, Restaurant.class, "cards")).clear();
        Method number = Restaurant.class.getDeclaredMethod("numberTables", net.minecraft.server.level.ServerLevel.class);
        number.setAccessible(true);
        number.invoke(r, level);
        Method label = Restaurant.class.getDeclaredMethod("tableLabel", BlockPos.class);
        label.setAccessible(true);
        helper.assertValueEqual(label.invoke(r, table.east()), "12", "the table's name");
        Method remove = Restaurants.class.getDeclaredMethod("remove", BlockPos.class);
        remove.setAccessible(true);
        remove.invoke(all, r.sign);
        helper.succeed();
    }
}
