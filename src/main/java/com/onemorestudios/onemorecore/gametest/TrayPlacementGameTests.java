package com.onemorestudios.onemorecore.gametest;

import com.onemorestudios.onemorecore.KitchenStations;
import com.onemorestudios.onemorecore.OneMoreCore;
import com.onemorestudios.onemorecore.TrayBlock;
import com.onemorestudios.onemorecore.TrayBlockEntity;
import com.onemorestudios.onemorecore.TrayPose;
import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.ItemPose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

@GameTestHolder(OneMoreCore.MODID)
@PrefixGameTestTemplate(false)
public final class TrayPlacementGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    @GameTest(template = "empty")
    public static void trayPosesSurviveSavePickupAndPlacement(GameTestHelper helper) {
        helper.setBlock(POS.below(), Blocks.STONE);
        var tray = GameTestSupport.place(helper, POS, OneMoreCore.IRON_TRAY.get(), TrayBlockEntity.class);
        tray.items.setItem(0, Items.BREAD.getDefaultInstance());
        tray.items.setItem(2, Items.APPLE.getDefaultInstance());
        TrayPose bread = new TrayPose(4.5f, 1, 5.25f, 0.3f, new Quaternionf().rotationXYZ(1.5f, 0.4f, 0.1f), 0.8f, -1, 0.9f);
        TrayPose apple = new TrayPose(11.25f, 1, 9.5f, 0.4f, new Quaternionf().rotationY(-0.7f), 1.2f, -1, 1.2f);
        tray.setPose(0, bread);
        tray.setPose(2, apple);
        tray.label = "12";

        var level = helper.getLevel();
        var pos = tray.getBlockPos();
        var state = tray.getBlockState();
        var saved = tray.saveWithFullMetadata(level.registryAccess());
        var loaded = (TrayBlockEntity) BlockEntity.loadStatic(pos, state, saved, level.registryAccess());
        helper.assertValueEqual(loaded.getPose(0), bread, "bread after NBT load");
        helper.assertValueEqual(loaded.getPose(2), apple, "apple after NBT load");
        var updated = new TrayBlockEntity(pos, state);
        updated.handleUpdateTag(tray.getUpdateTag(level.registryAccess()), level.registryAccess());
        helper.assertValueEqual(updated.getPose(2), apple, "apple after block entity update");

        level.setBlockEntity(loaded);
        var player = GameTestSupport.player(helper);
        player.setShiftKeyDown(true);
        helper.assertTrue(GameTestSupport.click(helper, player, POS).consumesAction(), "The tray was picked up");
        var stack = player.getMainHandItem();
        helper.assertTrue(stack.is(OneMoreCore.IRON_TRAY_ITEM.get()), "Player holds the iron tray");
        helper.assertValueEqual(stack.get(OneMoreCore.TRAY_POSES.get()).get(2), apple, "apple in item component");

        player.setShiftKeyDown(false);
        helper.assertTrue(GameTestSupport.click(helper, player, POS.below(), 1).consumesAction(), "The tray was put back");
        var replaced = (TrayBlockEntity) helper.getBlockEntity(POS);
        helper.assertTrue(replaced.items.getItem(0).is(Items.BREAD), "Bread stays in its slot");
        helper.assertTrue(replaced.items.getItem(1).isEmpty(), "The gap stays empty");
        helper.assertTrue(replaced.items.getItem(2).is(Items.APPLE), "Apple stays in its slot");
        helper.assertValueEqual(replaced.getPose(0), bread, "bread after placement");
        helper.assertValueEqual(replaced.getPose(2), apple, "apple after placement");
        helper.assertValueEqual(replaced.label, "12", "tray label");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void trayWithoutSavedPosesUsesLegacyCentres(GameTestHelper helper) {
        var tray = GameTestSupport.place(helper, POS, OneMoreCore.TRAY.get(), TrayBlockEntity.class);
        for (int i = 0; i < TrayBlockEntity.SIZE; i++) tray.items.setItem(i, Items.COOKIE.getDefaultInstance());
        var registries = helper.getLevel().registryAccess();
        var tag = tray.saveWithFullMetadata(registries);
        tag.remove("poses");
        var loaded = (TrayBlockEntity) BlockEntity.loadStatic(tray.getBlockPos(), tray.getBlockState(), tag, registries);
        for (int i = 0; i < TrayBlockEntity.SIZE; i++) {
            TrayPose pose = loaded.getPose(i);
            helper.assertValueEqual(pose.x(), 3.0f + 5 * i, "legacy x for slot " + i);
            helper.assertValueEqual(pose.z(), 8.0f, "legacy z for slot " + i);
            helper.assertValueEqual(pose.y(), 1.0f, "legacy bed height");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void trayBlockClickUsesRotatedHitAndAvoidsOccupiedCentres(GameTestHelper helper) {
        helper.setBlock(POS, OneMoreCore.TRAY.get().defaultBlockState().setValue(TrayBlock.FACING, Direction.EAST));
        var tray = (TrayBlockEntity) helper.getBlockEntity(POS);
        var player = GameTestSupport.player(helper);
        player.setYRot(60);
        GameTestSupport.give(player, Items.BREAD.getDefaultInstance().copyWithCount(3));

        // The east-facing model turns (x, z) into (16 - z, x).
        clickEast(helper, player, 6.5f, 9.75f);
        TrayPose first = tray.getPose(0);
        helper.assertTrue(Math.abs(first.x() - 6.5f) < 0.0001f && Math.abs(first.z() - 9.75f) < 0.0001f,
                "First dish goes at the clicked point in tray coordinates");
        helper.assertTrue(first.rotation().equals(new Quaternionf().rotationY((float) Math.toRadians(30)), 0.0001f),
                "The dish turns towards the player in tray coordinates");

        clickEast(helper, player, 6.5f, 9.75f);
        TrayPose second = tray.getPose(1);
        helper.assertTrue(Math.hypot(first.x() - second.x(), first.z() - second.z()) >= 4, "Second dish was nudged away");
        clickEast(helper, player, 0.25f, 2.5f);
        TrayPose third = tray.getPose(2);
        helper.assertTrue(Math.abs(third.x() - 0.5f) < 0.0001f && Math.abs(third.z() - 3) < 0.0001f,
                "A rim click clamps to the inside of the bed");
        helper.assertTrue(second.x() >= 0.5f && second.x() <= 15.5f && second.z() >= 3 && second.z() <= 13,
                "The nudged centre stays in the bed");
        helper.succeed();
    }

    private static void clickEast(GameTestHelper helper, GameTestSupport.TestPlayer player, float x, float z) {
        BlockPos pos = helper.absolutePos(POS);
        Vec3 spot = Vec3.atLowerCornerOf(pos).add((16 - z) / 16, 2 / 16.0, x / 16);
        var hit = new BlockHitResult(spot, Direction.UP, pos, false);
        helper.assertTrue(player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(),
                InteractionHand.MAIN_HAND, hit).consumesAction(), "Dish click was accepted");
    }

    @GameTest(template = "empty")
    public static void diegesisTrayUsesAndPersistsPlacedPoses(GameTestHelper helper) {
        if (ModList.get().isLoaded("diegesis")) WithDiegesis.verify(helper);
        helper.succeed();
    }

    private static final class WithDiegesis {
        static void verify(GameTestHelper helper) {
            var tray = GameTestSupport.place(helper, POS, OneMoreCore.TRAY.get(), TrayBlockEntity.class);
            var player = GameTestSupport.player(helper);
            ScreenGameTests.enableDiegesisChannel(player);
            var group = KitchenStations.TRAY.schema().slotGroups().get(KitchenStations.TRAY_DISHES);
            helper.assertTrue(group.rules().handPlaced() && !group.rules().returnOnClose(), "Tray uses kept free placing");
            helper.assertValueEqual(group.size(), TrayBlockEntity.SIZE + 1, "Three dishes and a place for the ticket");

            var slot = group.ref(0);
            tray.items.setItem(0, Items.BREAD.getDefaultInstance());
            tray.setPose(0, TrayPose.atRest(6, 6, new Quaternionf()));
            var pos = tray.getBlockPos();
            helper.assertTrue(Diegesis.open(player, pos), "The tray station opened");
            try {
                var state = Diegesis.activeStation(helper.getLevel(), pos).orElseThrow().state();
                helper.assertValueEqual(state.getPose(slot).x(), 6.0f, "The station loads the tray pose");
                var rotation = new Quaternionf().rotationY(0.7f);
                var moved = new ItemPose(7, 1, 8, 0.2f, rotation, 0.9f, -1);
                state.setPose(slot, moved);
                helper.assertValueEqual(tray.getPose(0), new TrayPose(7, 1, 8, 0.2f, rotation, 0.9f, -1, 0.9f),
                        "A station state pose write reaches the tray immediately");

                // Diegesis Place writes the pose before it puts the item into the bound inventory.
                var newSlot = group.ref(1);
                state.setPose(newSlot, new ItemPose(12, 1, 6, 0.3f, rotation, 1, -1));
                state.setItem(newSlot, Items.APPLE.getDefaultInstance());
                helper.assertTrue(tray.items.getItem(1).is(Items.APPLE), "The station places into the tray");
                helper.assertValueEqual(tray.getPose(1).x(), 12.0f, "The placement reaches the tray with its pose");

                // A ticket put on the tray becomes its label and takes no dish's place, also on a full tray.
                state.setItem(group.ref(2), Items.CARROT.getDefaultInstance());
                var ticket = new net.minecraft.world.item.ItemStack(OneMoreCore.TICKET.get());
                ticket.set(OneMoreCore.LABEL.get(), "7");
                state.setItem(group.ref(TrayBlockEntity.SIZE), ticket);
                helper.assertValueEqual(tray.label, "7", "The ticket is the tray's label");
                helper.assertTrue(state.getItem(group.ref(TrayBlockEntity.SIZE)).isEmpty() && tray.items.getItem(2).is(Items.CARROT),
                        "The ticket takes no place");
            } finally {
                Diegesis.close(player);
            }
        }
    }
}
