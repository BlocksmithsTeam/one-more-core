package com.onemorestudios.onemorecore.gametest;

import com.onemorestudios.onemorecore.Computer;
import com.onemorestudios.onemorecore.Config;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.OpenSignBlock;
import com.onemorestudios.onemorecore.OpenSignBlockEntity;
import com.onemorestudios.onemorecore.Restaurant;
import com.onemorestudios.onemorecore.Restaurants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class LevelGameTests {
    private static final BlockPos SIGN = new BlockPos(1, 1, 1);
    private static final BlockPos COMPUTER = new BlockPos(1, 1, 2);
    private static final BlockPos WALL = new BlockPos(2, 1, 3);

    @GameTest(template = "empty")
    public static void buyingGrowsAreaAndRemovesWall(GameTestHelper helper) throws ReflectiveOperationException {
        try (Fixture f = new Fixture(helper)) {
            step(f, 7);
            helper.setBlock(WALL, Blocks.STONE);
            var chest = GameTestSupport.place(helper, new BlockPos(2, 1, 2), Blocks.CHEST,
                    net.minecraft.world.level.block.entity.ChestBlockEntity.class);
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
            send(f, Computer.LEVEL_AREA, "0\n" + corners(helper, WALL, WALL));
            key(helper, send(f, Computer.LEVEL_BUY, ""), "onemorecore.tool.sign_outside");
            helper.assertValueEqual(f.restaurant.register(), 20, "Invalid area cannot charge money");
            send(f, Computer.LEVEL_AREA, "0\n" + corners(helper, new BlockPos(0, 1, 0), new BlockPos(4, 3, 4)));
            // The wall box also covers the sign and computer; neither may be removed.
            send(f, Computer.LEVEL_WALL, "0\n" + corners(helper, SIGN, WALL));
            Computer.State bought = send(f, Computer.LEVEL_BUY, "");
            key(helper, bought, "onemorecore.level.bought");
            helper.assertValueEqual(f.restaurant.register(), 13, "Only the server price is charged");
            helper.assertValueEqual(f.restaurant.level(), 2, "Buying advances the level");
            helper.assertTrue(f.restaurant.area().isInside(helper.absolutePos(new BlockPos(4, 2, 4))), "Area grows");
            helper.assertTrue(helper.getBlockState(WALL).isAir(), "Wall is removed");
            helper.assertTrue(helper.getBlockState(new BlockPos(2, 1, 2)).isAir(), "Wall chest is removed");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    net.minecraft.world.phys.AABB.encapsulatingFullBlocks(helper.absolutePos(SIGN), helper.absolutePos(WALL))).isEmpty(),
                    "Wall and chest contents drop no items");
            helper.assertTrue(helper.getBlockState(SIGN).is(OneMoreCore.OPEN_SIGN.get()), "Sign survives");
            helper.assertTrue(helper.getBlockState(COMPUTER).is(OneMoreCore.COMPUTER.get()), "Computer survives");
            key(helper, send(f, Computer.LEVEL_BUY, ""), "onemorecore.level.max");
            helper.assertValueEqual(f.restaurant.register(), 13, "Repeated buy cannot charge again");
            helper.assertValueEqual(bought.levels().size(), 1, "State includes the step");
            helper.assertTrue(bought.levelEditor(), "Operator can edit");
            helper.assertTrue(Computer.handle(f.player, request(f, Computer.LEVEL_REMOVE, "0")) == null, "Bought steps cannot be removed");
            codecRoundTrip(helper, bought);
            CompoundTag progress = progress(f.restaurant);
            restore(f.restaurant, progress);
            helper.assertValueEqual(f.restaurant.level(), 2, "Progress restores level");
            helper.assertValueEqual(f.restaurant.steps().size(), 1, "Progress restores steps");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wallContainingOnlySignPartPreservesSign(GameTestHelper helper) throws ReflectiveOperationException {
        try (Fixture f = new Fixture(helper)) {
            var signState = OneMoreCore.OPEN_SIGN.get().defaultBlockState().setValue(OpenSignBlock.FACE, AttachFace.WALL);
            helper.setBlock(SIGN, signState);
            signState.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(SIGN), signState,
                    f.player, OneMoreCore.OPEN_SIGN_ITEM.get().getDefaultInstance());
            helper.assertTrue(helper.getBlockState(SIGN.above()).is(OneMoreCore.SCREEN_PART.get()), "Blade has an upper part");
            step(f, 7);
            send(f, Computer.LEVEL_WALL, "0\n" + corners(helper, SIGN.above(), SIGN.above()));
            key(helper, send(f, Computer.LEVEL_BUY, ""), "onemorecore.level.bought");
            helper.assertTrue(helper.getBlockState(SIGN).equals(signState), "Sign outside wall box survives");
            helper.assertTrue(helper.getBlockState(SIGN.above()).is(OneMoreCore.SCREEN_PART.get()), "Upper part survives");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    net.minecraft.world.phys.AABB.encapsulatingFullBlocks(helper.absolutePos(SIGN), helper.absolutePos(SIGN.above()))).isEmpty(),
                    "Preserved sign drops no items");
            helper.assertValueEqual(f.restaurant.register(), 13, "Purchase charges once");
            helper.assertValueEqual(f.restaurant.level(), 2, "Purchase advances the level");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insufficientMoneyChangesNothing(GameTestHelper helper) throws ReflectiveOperationException {
        try (Fixture f = new Fixture(helper)) {
            step(f, 21);
            helper.setBlock(WALL, Blocks.STONE);
            send(f, Computer.LEVEL_WALL, "0\n" + corners(helper, WALL, WALL));
            CompoundTag before = progress(f.restaurant);
            BoundingBox area = f.restaurant.area();
            for (int attempt = 0; attempt < 2; attempt++) {
                key(helper, send(f, Computer.LEVEL_BUY, ""), "onemorecore.level.no_money");
                helper.assertValueEqual(progress(f.restaurant), before, "Rejected buy preserves progress");
                helper.assertTrue(f.restaurant.area() == area, "Rejected buy preserves area");
                helper.assertTrue(helper.getBlockState(WALL).is(Blocks.STONE), "Rejected buy preserves wall");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void buyingRescansMachines(GameTestHelper helper) throws ReflectiveOperationException {
        boolean previous = Config.REQUIRE_MACHINES.get();
        try (Fixture f = new Fixture(helper)) {
            Config.REQUIRE_MACHINES.set(true);
            GameTestSupport.setRegister(f.restaurant, 100);
            GameTestSupport.unlock(helper, f.restaurant, "beef");
            GameTestSupport.setRegister(f.restaurant, 20);
            step(f, 7);
            helper.setBlock(new BlockPos(4, 1, 4), Blocks.SMOKER);
            f.restaurant.refreshMachines(helper.getLevel());
            var beef = f.restaurant.dish("cooked_beef");
            helper.assertTrue(beef != null, "Cooked beef exists");
            helper.assertFalse(f.restaurant.orderable(beef), "Smoker outside old area cannot cook beef");
            key(helper, send(f, Computer.LEVEL_BUY, ""), "onemorecore.level.bought");
            helper.assertTrue(f.restaurant.orderable(beef), "New area includes smoker immediately");
        } finally {
            Config.REQUIRE_MACHINES.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void movingTransfersProgress(GameTestHelper helper) throws ReflectiveOperationException {
        try (Fixture f = new Fixture(helper)) {
            BlockPos targetSign = new BlockPos(4, 1, 1);
            GameTestSupport.place(helper, targetSign, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
            var signState = OneMoreCore.OPEN_SIGN.get().defaultBlockState().setValue(OpenSignBlock.FACE, AttachFace.WALL);
            helper.setBlock(targetSign, signState);
            signState.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(targetSign), signState,
                    f.player, OneMoreCore.OPEN_SIGN_ITEM.get().getDefaultInstance());
            Restaurant target = Restaurants.get(helper.getLevel()).at(helper.absolutePos(targetSign));
            setArea(target, helper, new BlockPos(3, 1, 0), new BlockPos(4, 3, 4));
            helper.setBlock(new BlockPos(3, 0, 1), Blocks.STONE);
            GameTestSupport.setRegister(f.restaurant, 100);
            GameTestSupport.unlock(helper, f.restaurant, "beef");
            GameTestSupport.setRegister(f.restaurant, 20);
            var day = Restaurant.class.getDeclaredField("day");
            day.setAccessible(true);
            day.setInt(f.restaurant, 5);
            step(f, 7);
            send(f, Computer.LEVEL_MOVE, "0\n" + coordinates(helper.absolutePos(targetSign.above())));
            helper.assertValueEqual(f.restaurant.steps().getFirst().moveTo(), helper.absolutePos(targetSign),
                    "Upper sign part stores the sign position");
            BoundingBox oldArea = f.restaurant.area();
            key(helper, send(f, Computer.LEVEL_BUY, ""), "onemorecore.level.moved");
            helper.assertValueEqual(target.day(), 5, "Target receives day");
            helper.assertValueEqual(target.register(), 13, "Target receives remaining money");
            helper.assertValueEqual(target.unlockedIngredients(), f.restaurant.unlockedIngredients(), "Target receives unlocks");
            helper.assertValueEqual(target.level(), 2, "Target receives next level");
            helper.assertValueEqual(target.steps().size(), 1, "Target receives steps");
            helper.assertValueEqual(f.restaurant.register(), 0, "Source has no money");
            helper.assertValueEqual(f.restaurant.level(), 1, "Source level resets");
            helper.assertTrue(f.restaurant.steps().isEmpty(), "Source steps clear");
            helper.assertTrue(f.restaurant.area() == oldArea, "Source keeps its area");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyEditorCanAddSteps(GameTestHelper helper) throws ReflectiveOperationException {
        try (Fixture f = new Fixture(helper)) {
            f.player.operator = false;
            helper.assertFalse(f.player.hasPermissions(2), "Plain fake player is not an editor");
            key(helper, send(f, Computer.LEVEL_ADD, ""), "onemorecore.level.not_allowed");
            helper.assertTrue(f.restaurant.steps().isEmpty(), "Non-editor adds nothing");
            f.player.operator = true;
            helper.assertTrue(f.player.hasPermissions(2), "Test operator has editor permission");
            Computer.State reply = send(f, Computer.LEVEL_ADD, "");
            helper.assertValueEqual(reply.levels().size(), 1, "Operator adds a step");
            send(f, Computer.LEVEL_PRICE, "0\n999999999999999999999");
            helper.assertValueEqual(f.restaurant.steps().getFirst().price(), Integer.MAX_VALUE, "Price clamps high");
            send(f, Computer.LEVEL_PRICE, "0\n-1");
            helper.assertValueEqual(f.restaurant.steps().getFirst().price(), 0, "Price clamps low");
            BlockPos far = f.helper.absolutePos(COMPUTER).offset(Computer.PLACE_RANGE + 1, 0, 0);
            key(helper, send(f, Computer.LEVEL_MOVE, "0\n" + coordinates(far)), "onemorecore.tool.too_far");
            helper.assertTrue(f.restaurant.steps().getFirst().moveTo() == null, "Rejected target is not stored");
            send(f, Computer.LEVEL_AREA, "0\n" + corners(helper, SIGN, WALL));
            send(f, Computer.LEVEL_AREA, "0\n");
            helper.assertTrue(f.restaurant.steps().getFirst().area() == null, "Empty area clears the box");
            CompoundTag saved = progress(f.restaurant);
            net.minecraft.nbt.ListTag steps = saved.getList("steps", net.minecraft.nbt.Tag.TAG_COMPOUND);
            CompoundTag entry = steps.getCompound(0).copy();
            for (int i = steps.size(); i < 70; i++) steps.add(entry.copy());
            restore(f.restaurant, saved);
            helper.assertValueEqual(f.restaurant.steps().size(), 64, "Loading bounds steps");
            helper.assertTrue(Computer.handle(f.player, request(f, Computer.LEVEL_ADD, "")) == null, "Adding cannot exceed 64 steps");
            Config.APP_LEVELS.set(false);
            helper.assertTrue(Computer.handle(f.player, request(f, Computer.LEVEL_ADD, "")) == null, "Disabled app rejects requests");
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Restaurant restaurant;
        final GameTestSupport.TestPlayer player;
        final boolean previous = Config.APP_LEVELS.get();

        Fixture(GameTestHelper helper) throws ReflectiveOperationException {
            this.helper = helper;
            Config.APP_LEVELS.set(true);
            GameTestSupport.place(helper, SIGN, OneMoreCore.OPEN_SIGN.get(), OpenSignBlockEntity.class);
            restaurant = Restaurants.get(helper.getLevel()).at(helper.absolutePos(SIGN));
            setArea(restaurant, helper, new BlockPos(0, 1, 0), new BlockPos(2, 3, 2));
            GameTestSupport.setRegister(restaurant, 20);
            helper.setBlock(COMPUTER, OneMoreCore.COMPUTER.get());
            player = GameTestSupport.operator(helper);
            helper.assertTrue(GameTestSupport.click(helper, player, COMPUTER).consumesAction(), "Computer opens");
        }

        @Override
        public void close() {
            Computer.handle(player, request(this, Computer.CLOSE, ""));
            Config.APP_LEVELS.set(previous);
        }
    }

    private static void step(Fixture f, int price) {
        send(f, Computer.LEVEL_ADD, "");
        send(f, Computer.LEVEL_PRICE, "0\n" + price);
        send(f, Computer.LEVEL_AREA, "0\n" + corners(f.helper, new BlockPos(0, 1, 0), new BlockPos(4, 3, 4)));
    }

    private static Computer.Request request(Fixture f, int action, String id) {
        return new Computer.Request(f.helper.absolutePos(COMPUTER), action, id);
    }

    private static Computer.State send(Fixture f, int action, String id) {
        var reply = Computer.handle(f.player, request(f, action, id));
        f.helper.assertTrue(reply instanceof Computer.State, "Request returns State: " + action);
        return (Computer.State) reply;
    }

    private static void key(GameTestHelper helper, Computer.State reply, String expected) {
        helper.assertTrue(reply.message().getContents() instanceof TranslatableContents text && text.getKey().equals(expected),
                "Expected message " + expected);
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String corners(GameTestHelper helper, BlockPos a, BlockPos b) {
        return coordinates(helper.absolutePos(a)) + " " + coordinates(helper.absolutePos(b));
    }

    private static void setArea(Restaurant restaurant, GameTestHelper helper, BlockPos a, BlockPos b) throws ReflectiveOperationException {
        var method = Restaurant.class.getDeclaredMethod("setArea", BoundingBox.class);
        method.setAccessible(true);
        method.invoke(restaurant, BoundingBox.fromCorners(helper.absolutePos(a), helper.absolutePos(b)));
    }

    private static void codecRoundTrip(GameTestHelper helper, Computer.State state) throws ReflectiveOperationException {
        var field = Computer.State.class.getDeclaredField("CODEC");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var codec = (net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Computer.State>) field.get(null);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            codec.encode(buffer, state);
            Computer.State decoded = codec.decode(buffer);
            helper.assertValueEqual(decoded.level(), state.level(), "Codec preserves level");
            helper.assertValueEqual(decoded.levels(), state.levels(), "Codec preserves steps");
            helper.assertValueEqual(decoded.levelEditor(), state.levelEditor(), "Codec preserves editor flag");
            helper.assertValueEqual(buffer.readableBytes(), 0, "Codec consumes all fields");
        } finally {
            buffer.release();
        }
    }

    private static CompoundTag progress(Restaurant restaurant) throws ReflectiveOperationException {
        var method = Restaurant.class.getDeclaredMethod("progress");
        method.setAccessible(true);
        return (CompoundTag) method.invoke(restaurant);
    }

    private static void restore(Restaurant restaurant, CompoundTag tag) throws ReflectiveOperationException {
        var method = Restaurant.class.getDeclaredMethod("restore", CompoundTag.class);
        method.setAccessible(true);
        method.invoke(restaurant, tag);
    }
}
