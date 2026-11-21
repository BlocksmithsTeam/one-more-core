package com.onemorestudios.onemorecore.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
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

    /** A fake player whose server listener records outgoing payloads and vanilla packets. */
    static final class TestPlayer extends FakePlayer {
        final List<Object> packets = new ArrayList<>();

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
        BlockPos absolutePos = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolutePos), Direction.UP, absolutePos, false);
        return player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }
}
