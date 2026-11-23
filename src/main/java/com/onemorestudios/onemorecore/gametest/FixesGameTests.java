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
}
