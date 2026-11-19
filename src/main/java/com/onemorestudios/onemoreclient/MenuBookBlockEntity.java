package com.onemorestudios.onemoreclient;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** The menu of a menu book: built and sent like the menu board's, for the Diegesis view of the open book. */
public class MenuBookBlockEntity extends MenuBoardBlockEntity {
    public MenuBookBlockEntity(BlockPos pos, BlockState state) {
        super(OneMoreClient.MENU_BOOK_ENTITY.get(), pos, state);
    }

    /** Server to client: open the book screen with this menu (MenuBoardBlockEntity.build). */
    public record Open(CompoundTag menu) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(OneMoreClient.MODID, "menu_book"));
        static final StreamCodec<io.netty.buffer.ByteBuf, Open> CODEC = ByteBufCodecs.COMPOUND_TAG.map(Open::new, Open::menu);
        /** Client: set by the client setup to open the screen. */
        public static java.util.function.Consumer<Open> handler = o -> {};

        @Override
        public Type<Open> type() { return TYPE; }
    }
}
