package com.onemorestudios.onemorecore;

import java.util.List;
import java.util.Objects;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.SlotRules;
import com.pockyl.diegesis.api.state.SlotStorage;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;
import com.pockyl.diegesis.api.station.StationType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Loaded only when Diegesis is present. The tray up close: drag dishes from the inventory onto it and back (its three
 * slots hold the tray's own dishes and their poses). The order rail up close: click a ticket to take a copy of it.
 */
public final class KitchenStations {
    private KitchenStations() {}

    public static final String TRAY_DISHES = "dishes";

    public static final StationType TRAY = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "tray"))
            .block(OneMoreCore.TRAY).block(OneMoreCore.IRON_TRAY)
            .schema(StationSchema.builder().slots(TRAY_DISHES, TrayBlockEntity.SIZE, SlotRules.DEFAULT.maxStack(1).placed().kept()).build())
            // An empty hand opens it; with an item the tray takes the dish or the ticket as without Diegesis; sneaking picks it up.
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown()
                    && mayUseTray(player, pos))
            .logic(context -> new StationLogic() {
                private final TrayBlockEntity tray = context.blockEntity() instanceof TrayBlockEntity entity ? entity : null;
                private final ItemPose[] stationPoses = new ItemPose[TrayBlockEntity.SIZE];
                private boolean closing;

                {
                    if (tray != null) {
                        context.state().bindSlots(TRAY_DISHES, SlotStorage.of(tray.items, 0, TrayBlockEntity.SIZE));
                        readTray();
                        // Observe all writes, including Place (pose before item), physics settling, and direct setPose.
                        context.state().setChangeListener(this::writeTray);
                    }
                }

                private boolean bound(StationContext station) {
                    return tray != null && !tray.isRemoved() && station.blockEntity() == tray;
                }

                private void readTray() {
                    for (int slot = 0; slot < TrayBlockEntity.SIZE; slot++) {
                        ItemPose pose = tray.items.getItem(slot).isEmpty() ? null : stationPose(tray.getPose(slot));
                        stationPoses[slot] = pose;
                        context.state().setPose(new SlotRef(TRAY_DISHES, slot), pose);
                    }
                }

                private void writeTray() {
                    if (!bound(context)) return;
                    for (int slot = 0; slot < TrayBlockEntity.SIZE; slot++) {
                        if (tray.items.getItem(slot).isEmpty()) {
                            stationPoses[slot] = null;
                            continue;
                        }
                        ItemPose pose = context.state().getPose(new SlotRef(TRAY_DISHES, slot));
                        if (Objects.equals(stationPoses[slot], pose)) continue;
                        stationPoses[slot] = pose;
                        tray.setPose(slot, pose == null ? null : trayPose(pose));
                    }
                }

                @Override
                public boolean mayTake(StationContext station, Player player, SlotRef slot) {
                    return bound(station) && mayUseTray(player, station.pos());
                }

                @Override
                public boolean mayPlace(StationContext station, Player player, SlotRef slot, ItemStack stack) {
                    return !(stack.getItem() instanceof TrayItem) && bound(station) && mayUseTray(player, station.pos());
                }

                @Override
                public void tick(StationContext station) {
                    if (bound(station)) {
                        // A dish may also arrive through ordinary block use while someone is in the close-up.
                        readTray();
                        return;
                    }
                    if (closing || !(station.level() instanceof ServerLevel level)) return;
                    closing = true;
                    var server = level.getServer();
                    // Defer closing until Diegesis has finished ticking this station.
                    server.tell(new TickTask(server.getTickCount(), () -> {
                        if (Diegesis.activeStation(level, station.pos()).orElse(null) != station) return;
                        for (Player user : List.copyOf(station.users()))
                            if (user instanceof ServerPlayer player) Diegesis.close(player);
                    }));
                }
            })
            .build();

    private static ItemPose stationPose(TrayPose pose) {
        return new ItemPose(pose.x(), pose.y(), pose.z(), pose.lift(), pose.rotation(), pose.scale(), pose.on(), pose.wanted());
    }

    private static TrayPose trayPose(ItemPose pose) {
        return new TrayPose(Mth.clamp(pose.x(), TrayPose.MIN_X, TrayPose.MAX_X),
                Mth.clamp(pose.y(), TrayPose.BED, TrayPose.BED + 10),
                Mth.clamp(pose.z(), TrayPose.MIN_Z, TrayPose.MAX_Z), Mth.clamp(pose.lift(), 0, 10),
                pose.rotation(), pose.scale(), pose.on(), pose.wanted());
    }

    private static boolean mayUseTray(Player player, BlockPos pos) {
        return !player.isSpectator() && (!(player.level() instanceof ServerLevel level) || level.mayInteract(player, pos));
    }

    /**
     * The menu screen and the order screen up close: the camera in front of the television, the mouse as the pointer
     * (tooltips of the dishes), and on the order screen a click on a card gives its ticket.
     */
    public static final StationType SCREEN = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen"))
            .block(OneMoreCore.ORDER_BOARD).block(OneMoreCore.MONITOR)
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .build();

    /**
     * The Screen up close, one station for each tilt (its scene looks at the glass square on): the camera goes to the side the
     * player stands on (each side has its own close-up), and the client moves it to the middle of the picture of the screen or
     * of its joined group, as far as the picture needs (ScreenStationBehavior).
     */
    public static final StationType[] SCREEN_BOARD = new StationType[3];
    static {
        for (int k = 0; k < 3; k++) {
            int tilt = k;
            SCREEN_BOARD[k] = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_board_" + k))
                    .block(OneMoreCore.MENU_BOARD)
                    .when(state -> state.getValue(MenuBoardBlock.TILT) == tilt)
                    .orientation(StationType.Orientation.PLAYER)
                    .scene(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "screen_board_" + k))
                    .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
                    .build();
        }
    }

    /** The menu book up close: the open book on the table, its pages turned by clicks (client only, it shows the synced menu). */
    public static final StationType BOOK = Diegesis.station(ResourceLocation.fromNamespaceAndPath(OneMoreCore.MODID, "menu_book"))
            .block(OneMoreCore.MENU_BOOK)
            .clientOnly()
            .openWhen((player, pos, state) -> player.getMainHandItem().isEmpty() && !player.isShiftKeyDown())
            .build();

    public static void register() {
        Diegesis.register(BOOK);
        Diegesis.register(TRAY);
        Diegesis.register(SCREEN);
        for (StationType t : SCREEN_BOARD) Diegesis.register(t);
    }
}
