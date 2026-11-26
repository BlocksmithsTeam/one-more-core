package com.onemorestudios.onemorecore.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.authlib.GameProfile;
import com.onemorestudios.onemorecore.Restaurant;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Shared player, block and interaction fixtures for server GameTests. */
public final class GameTestSupport {
    private static final AtomicLong NEXT_PLAYER = new AtomicLong();

    private GameTestSupport() {}

    static void setRegister(Restaurant restaurant, int balance) {
        try {
            var field = Restaurant.class.getDeclaredField("register");
            field.setAccessible(true);
            field.setInt(restaurant, balance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void unlock(GameTestHelper helper, Restaurant restaurant, String... ingredients) {
        for (String ingredient : ingredients) {
            var result = restaurant.unlockIngredient(ResourceLocation.withDefaultNamespace(ingredient));
            helper.assertTrue(result == Restaurant.Unlock.OK || result == Restaurant.Unlock.ALREADY,
                    "Could not unlock " + ingredient + ": " + result);
        }
    }

    /** A fake player whose server listener records outgoing payloads and vanilla packets. */
    static final class TestPlayer extends FakePlayer {
        final List<Object> packets = new ArrayList<>();
        boolean operator;

        @Override
        public boolean hasPermissions(int level) {
            return operator && level <= 2 || super.hasPermissions(level);
        }

        private TestPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "omc" + Long.toUnsignedString(NEXT_PLAYER.incrementAndGet(), 36)));
            Connection transport = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(transport);
            connection = new ServerGamePacketListenerImpl(level.getServer(), transport, this,
                    CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override
                public void send(Packet<?> packet) {
                    capture(packet);
                }

                @Override
                public void send(Packet<?> packet, PacketSendListener listener) {
                    capture(packet);
                }

                private void capture(Packet<?> packet) {
                    packets.add(packet instanceof ClientboundCustomPayloadPacket custom ? custom.payload() : packet);
                }
            };
        }
    }

    static TestPlayer player(GameTestHelper helper) {
        TestPlayer player = new TestPlayer(helper.getLevel());
        Vec3 position = helper.absoluteVec(new Vec3(1.5, 1, 2.5));
        player.setPos(position.x, position.y, position.z);
        return player;
    }

    static TestPlayer operator(GameTestHelper helper) {
        TestPlayer player = player(helper);
        player.operator = true;
        return player;
    }

    static <T extends BlockEntity> T place(GameTestHelper helper, BlockPos pos, Block block, Class<T> entityClass) {
        helper.setBlock(pos, block);
        BlockEntity entity = helper.getBlockEntity(pos);
        helper.assertTrue(entityClass.isInstance(entity), "Expected " + entityClass.getSimpleName() + " at " + pos);
        return entityClass.cast(entity);
    }

    static void give(TestPlayer player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    static InteractionResult click(GameTestHelper helper, TestPlayer player, BlockPos pos) {
        return click(helper, player, pos, 0.5);
    }

    static InteractionResult click(GameTestHelper helper, TestPlayer player, BlockPos pos, double height) {
        BlockPos absolutePos = helper.absolutePos(pos);
        Vec3 spot = Vec3.atBottomCenterOf(absolutePos).add(0, height, 0);
        BlockHitResult hit = new BlockHitResult(spot, Direction.UP, absolutePos, false);
        return player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }
}
