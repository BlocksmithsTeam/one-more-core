package com.onemorestudios.onemorecore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Screen: a frame and glass in the style of a city billboard. It stands on a floor on a post (both sides show a picture),
 * hangs on a wall on a bracket (the front only), or hangs from a ceiling on rods (both sides), upright (portrait, two blocks
 * high) or wide (landscape, two blocks wide); on a wall or a ceiling it can turn down 22.5 or 45 degrees. Screens of the same
 * kind side by side (on a wall also one over the other) join: their frames open and the restaurant draws one picture over them.
 * Each side shows the menu, the orders or nothing (ScreenSettings); a screwdriver changes the side it is clicked on.
 */
public class ScreenBlock extends MenuBoardBlock {
    public static final EnumProperty<AttachFace> ATTACH = BlockStateProperties.ATTACH_FACE;
    public static final BooleanProperty LANDSCAPE = BooleanProperty.create("landscape");
    public static final BooleanProperty CONNECT_LEFT = BooleanProperty.create("connect_left"), CONNECT_RIGHT = BooleanProperty.create("connect_right"),
            CONNECT_UP = BooleanProperty.create("connect_up"), CONNECT_DOWN = BooleanProperty.create("connect_down");
    /**
     * A screen on a floor or under a ceiling can stand between the four directions, like a sign: turn 1 is 22.5 degrees
     * clockwise (seen from above) from FACING, 2 is 45, 3 is -22.5. A turned screen joins no other; ScreenRenderer.Turned draws
     * its model.
     */
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty TURN =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("turn", 0, 3);
    private static final Map<String, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    /** The extra turn of a screen, degrees (clockwise from above, like a blockstate's y). */
    public static float turnDegrees(BlockState state) {
        if (!state.hasProperty(TURN)) return 0;
        int t = state.getValue(TURN);
        return t == 3 ? -22.5f : t * 22.5f;
    }

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TILT, 0).setValue(ATTACH, AttachFace.FLOOR)
                .setValue(LANDSCAPE, false).setValue(CONNECT_LEFT, false).setValue(CONNECT_RIGHT, false).setValue(CONNECT_UP, false)
                .setValue(CONNECT_DOWN, false).setValue(TURN, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ATTACH, LANDSCAPE, CONNECT_LEFT, CONNECT_RIGHT, CONNECT_UP, CONNECT_DOWN, TURN);
    }

    /**
     * On top of a block: it stands on its post and faces the player. Under a block: it hangs, wide, and turns down as much as the
     * player looks up. On the side of a block: on the wall, facing away from it, turned down as much as the player looks up.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        float pitch = context.getPlayer() == null ? 0 : context.getPlayer().getXRot();
        BlockState state = defaultBlockState();
        if (face == Direction.UP) state = turned(state.setValue(ATTACH, AttachFace.FLOOR), context.getRotation());
        else if (face == Direction.DOWN) state = turned(state.setValue(ATTACH, AttachFace.CEILING), context.getRotation())
                .setValue(LANDSCAPE, true).setValue(TILT, tiltFor(pitch));
        else state = state.setValue(ATTACH, AttachFace.WALL).setValue(FACING, face).setValue(TILT, tiltFor(pitch));
        if (!roomFor(context, state)) return null;
        return connected(context.getLevel(), context.getClickedPos(), state);
    }

    /** Faces a player who looks along yaw, in sixteen directions as a sign: the nearest of the four, and the turn from it. */
    static BlockState turned(BlockState state, float yaw) {
        int segment = net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(yaw + 180);
        int quarter = segment / 4, rest = segment % 4;
        if (rest == 3) quarter = (quarter + 1) % 4;
        return state.setValue(FACING, Direction.from2DDataValue(quarter)).setValue(TURN, rest);
    }

    @Override
    public boolean wide() {
        return false;
    }

    @Override
    public List<BlockPos> parts(BlockPos pos, BlockState state) {
        return ScreenLayout.of(state).parts(pos, state.getValue(FACING));
    }

    /** A screen on a floor stands straight. */
    @Override
    public boolean tilts(BlockState state) {
        return state.getValue(ATTACH) != AttachFace.FLOOR;
    }

    /** Whether two screens are of one kind (they join). */
    static boolean sameKind(BlockState a, BlockState b) {
        return a.getBlock() instanceof ScreenBlock && b.getBlock() instanceof ScreenBlock && a.getValue(FACING) == b.getValue(FACING)
                && a.getValue(ATTACH) == b.getValue(ATTACH) && a.getValue(LANDSCAPE).equals(b.getValue(LANDSCAPE)) && a.getValue(TILT).equals(b.getValue(TILT))
                && a.getValue(TURN) == 0 && b.getValue(TURN) == 0;
    }

    /** The state with its joins to the neighbouring screens of its kind. */
    static BlockState connected(LevelAccessor level, BlockPos pos, BlockState state) {
        ScreenLayout l = ScreenLayout.of(state);
        Direction right = state.getValue(FACING).getCounterClockWise();
        boolean up = l.joinsUp() && state.getValue(TILT) == 0;
        return state.setValue(CONNECT_RIGHT, sameKind(state, level.getBlockState(pos.relative(right, l.stepX()))))
                .setValue(CONNECT_LEFT, sameKind(state, level.getBlockState(pos.relative(right, -l.stepX()))))
                .setValue(CONNECT_UP, up && sameKind(state, level.getBlockState(pos.above(l.stepY()))))
                .setValue(CONNECT_DOWN, up && sameKind(state, level.getBlockState(pos.below(l.stepY()))));
    }

    /** Updates the joins of the screen at pos and of its neighbours (after a screen came, went or changed). */
    static void refreshAround(Level level, BlockPos pos) {
        List<BlockPos> around = new ArrayList<>();
        around.add(pos);
        for (Direction d : Direction.values()) for (int k = 1; k <= 2; k++) around.add(pos.relative(d, k));
        for (BlockPos p : around) {
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof ScreenBlock)) continue;
            BlockState now = connected(level, p, s);
            if (now != s) level.setBlock(p, now, 2);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) refreshAround(level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        super.onRemove(state, level, pos, newState, moved);
        if (!level.isClientSide && !newState.is(this)) refreshAround(level, pos);
    }

    /** Turns a screen upright or wide: its old part goes, the new one comes (false when that cell is taken). */
    static boolean setLandscape(Level level, BlockPos pos, boolean landscape) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScreenBlock screen) || state.getValue(LANDSCAPE) == landscape) return true;
        BlockState next = state.setValue(LANDSCAPE, landscape);
        List<BlockPos> old = screen.parts(pos, state), now = screen.parts(pos, next);
        for (BlockPos cell : now) {
            if (old.contains(cell)) continue;
            BlockState there = level.getBlockState(cell);
            if (level.isOutsideBuildHeight(cell) || !there.canBeReplaced() || there.is(OneMoreCore.SCREEN_PART.get())) return false;
        }
        for (BlockPos cell : old)
            if (level.getBlockState(cell).is(OneMoreCore.SCREEN_PART.get())) level.setBlock(cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos, next, 3);
        for (BlockPos cell : now) level.setBlock(cell, ScreenPartBlock.towards(cell, pos), 3);
        refreshAround(level, pos);
        return true;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeIn(state, BlockPos.ZERO);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    /**
     * The part of the screen in a cell (offset from the screen's block): the panel (turned down as it is) and the mount, as boxes
     * of whole pixels, clipped to the cell.
     */
    public static VoxelShape shapeIn(BlockState state, BlockPos offset) {
        String key = state.getValue(FACING) + "/" + state.getValue(TURN) + "/" + state.getValue(ATTACH) + "/" + state.getValue(LANDSCAPE) + "/" + state.getValue(TILT) + "/" + offset;
        return SHAPES.computeIfAbsent(key, k -> {
            ScreenLayout l = ScreenLayout.of(state);
            double tilt = Math.toRadians(state.getValue(TILT) * 22.5);
            List<double[]> boxes = new ArrayList<>();
            double[] panel = {l.x0(), l.y0(), l.z0(), l.x1(), l.y1(), l.z1()};
            if (tilt != 0) {
                // The bounds of the turned panel: its corners turned about the pivot edge.
                double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (double y : new double[] {l.y0(), l.y1()})
                    for (double z : new double[] {l.z0(), l.z1()}) {
                        double dy = y - l.pivotY(), dz = z - l.pivotZ();
                        double ny = l.pivotY() + dy * Math.cos(tilt) - dz * Math.sin(tilt), nz = l.pivotZ() + dy * Math.sin(tilt) + dz * Math.cos(tilt);
                        minY = Math.min(minY, ny); maxY = Math.max(maxY, ny); minZ = Math.min(minZ, nz); maxZ = Math.max(maxZ, nz);
                    }
                panel = new double[] {l.x0(), Math.floor(minY), Math.floor(minZ), l.x1(), Math.ceil(maxY), Math.ceil(maxZ)};
            }
            boxes.add(panel);
            for (float[] m : l.mounts()) boxes.add(new double[] {m[0], m[1], m[2], m[3], m[4], m[5]});
            // The cell's offset in the model's own axes (south-facing: +x is the screen's right, +z its front).
            Direction facing = state.getValue(FACING), right = facing.getCounterClockWise();
            int ox = offset.getX() * right.getStepX() + offset.getZ() * right.getStepZ();
            int oz = offset.getX() * facing.getStepX() + offset.getZ() * facing.getStepZ();
            int oy = offset.getY();
            List<double[]> north = new ArrayList<>();
            for (double[] b : boxes) {
                double x1 = Math.max(0, b[0] - 16 * ox), y1 = Math.max(0, b[1] - 16 * oy), z1 = Math.max(0, b[2] - 16 * oz);
                double x2 = Math.min(16, b[3] - 16 * ox), y2 = Math.min(16, b[4] - 16 * oy), z2 = Math.min(16, b[5] - 16 * oz);
                if (x2 <= x1 || y2 <= y1 || z2 <= z1) continue;
                // BoxShapes takes the model that faces north: the south model turned half round.
                north.add(new double[] {16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1});
            }
            if (north.isEmpty()) return Shapes.empty();
            return BoxShapes.byFacing(north.toArray(double[][]::new))[facing.get2DDataValue()];
        });
    }
}
