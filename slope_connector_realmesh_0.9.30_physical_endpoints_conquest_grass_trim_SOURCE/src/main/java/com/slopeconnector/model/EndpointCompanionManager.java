package com.slopeconnector.model;

import com.slopeconnector.hotfix.ArcHotfixMod;
import com.slopeconnector.hotfix.ArcTrimBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;

/**
 * Materializes side-width endpoint tiles as real ModelBlocks.
 *
 * <p>0.9.29 widened an endpoint only inside one block-entity renderer.  The extra visible tiles had
 * no block/collision at all and could occupy the same space as auto-trimmed terrain.  This manager
 * turns the lateral row into actual ModelBlocks.  Only the lateral/"side width" axis is physicalized
 * (never the whole vertical x lateral rectangle), keeping the number of new block entities bounded.</p>
 */
public final class EndpointCompanionManager {
    private static final int MAX_PHYSICAL_LATERAL_TILES = 32;
    private static final int CLEANUP_RADIUS = 32;

    private EndpointCompanionManager() {}

    public static void sync(World world, BlockPos rootPos,
                            ArcModelFrameLayout.Layout fullLayout,
                            int requestedLateralTiles, int verticalTiles,
                            boolean terminalEnd, Direction connectionDirection,
                            Direction innerArcDirection, BlockState capturedState,
                            boolean skinned) {
        if (world == null || rootPos == null || fullLayout == null) return;
        BlockEntity rootBe = world.getBlockEntity(rootPos);
        if (!(rootBe instanceof ModelBlockEntity root)) return;

        int lateralTiles = Math.max(1, Math.min(MAX_PHYSICAL_LATERAL_TILES, requestedLateralTiles));
        Direction lateralDirection = dominant(fullLayout.lateral());
        int[] offsets = offsets(lateralTiles);
        Set<BlockPos> desired = new HashSet<>();
        for (int offset : offsets) desired.add(rootPos.offset(lateralDirection, offset));

        // Delete only companions that explicitly belong to this root.  Search six short rays so a
        // changed arc face/orientation also cleans companions from the old axis without a cube scan.
        cleanupOldCompanions(world, rootPos, desired);

        double measuredTileSpan = fullLayout.lateralSpan() / lateralTiles;
        // Integer width settings normally produce one exact block per tile.  Snap tiny floating
        // drift to 1.0 so neighboring physical companion cells cannot develop micro gaps/overlap.
        double tileSpan = Math.abs(measuredTileSpan - 1.0) <= 0.125 ? 1.0 : measuredTileSpan;
        ArcModelFrameLayout.Layout tileLayout = new ArcModelFrameLayout.Layout(
                fullLayout.lateral(), fullLayout.vertical(), tileSpan, fullLayout.verticalSpan());

        for (int offset : offsets) {
            BlockPos pos = rootPos.offset(lateralDirection, offset);
            boolean companion = offset != 0;
            BlockState previous = companion ? previousState(world, pos, rootPos) : Blocks.AIR.getDefaultState();

            if (companion && !ensureModelBlock(world, pos, previous)) continue;
            BlockEntity be = world.getBlockEntity(pos);
            if (!(be instanceof ModelBlockEntity model)) continue;

            model.setEndpointGrid(rootPos, companion, companion ? previous : Blocks.AIR.getDefaultState());
            model.setTerminalEnd(terminalEnd);
            model.setSeamLayout(tileLayout, 1, Math.max(1, verticalTiles));
            model.setArcMetadata(connectionDirection, innerArcDirection);

            if (skinned) {
                model.setSkin(capturedState, connectionDirection, innerArcDirection);
            } else if (model.isSkinned()) {
                // Template endpoints are real pure-white ModelBlocks.  Do not render a virtual white
                // concrete copy through the BER; vanilla block rendering gives stable opaque faces.
                model.clearSkin();
                model.setEndpointGrid(rootPos, companion, companion ? previous : Blocks.AIR.getDefaultState());
                model.setTerminalEnd(terminalEnd);
                model.setSeamLayout(tileLayout, 1, Math.max(1, verticalTiles));
                model.setArcMetadata(connectionDirection, innerArcDirection);
            }
        }

        // One local refresh after all blocks exist; avoids O(tileCount) neighbor cascades during setup.
        for (BlockPos pos : desired) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ModelBlockEntity model) model.onNeighborChanged();
        }
    }

    public static void removeCompanions(World world, BlockPos rootPos) {
        if (world == null || rootPos == null) return;
        cleanupOldCompanions(world, rootPos, Set.of(rootPos));
    }

    public static boolean isSameGridNeighbor(World world, ModelBlockEntity entity,
                                             Vec3d positiveAxis, int sign) {
        if (world == null || entity == null || !entity.isEndpointGridMember()) return false;
        Direction direction = dominant(positiveAxis);
        if (sign < 0) direction = direction.getOpposite();
        BlockEntity neighbor = world.getBlockEntity(entity.getPos().offset(direction));
        return neighbor instanceof ModelBlockEntity other && entity.sameEndpointGrid(other);
    }

    private static void cleanupOldCompanions(World world, BlockPos rootPos, Set<BlockPos> desired) {
        for (Direction direction : Direction.values()) {
            for (int step = 1; step <= CLEANUP_RADIUS; step++) {
                BlockPos pos = rootPos.offset(direction, step);
                BlockEntity be = world.getBlockEntity(pos);
                if (!(be instanceof ModelBlockEntity model)) continue;
                if (!model.isEndpointGridCompanion() || !rootPos.equals(model.getEndpointGridRoot())) continue;
                if (desired.contains(pos)) continue;
                restoreCompanion(world, pos, model);
            }
        }
    }

    private static void restoreCompanion(World world, BlockPos pos, ModelBlockEntity model) {
        BlockState previous = model.getReplacedState();
        if (previous == null || previous.getBlock() == ModelSystemMod.MODEL_BLOCK
                || previous.getBlock() == ArcHotfixMod.ARC_TRIM) previous = Blocks.AIR.getDefaultState();
        world.setBlockState(pos, previous, 2);
    }

    private static BlockState previousState(World world, BlockPos pos, BlockPos rootPos) {
        if (pos.equals(rootPos)) return Blocks.AIR.getDefaultState();
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ModelBlockEntity model && model.isEndpointGridCompanion()
                && rootPos.equals(model.getEndpointGridRoot())) return model.getReplacedState();
        if (be instanceof ArcTrimBlockEntity trim) return trim.getSourceState();
        return world.getBlockState(pos);
    }

    private static boolean ensureModelBlock(World world, BlockPos pos, BlockState previous) {
        if (world.getBlockState(pos).getBlock() == ModelSystemMod.MODEL_BLOCK) return true;
        BlockEntity existingBe = world.getBlockEntity(pos);
        // Do not destroy an unrelated data-carrying block.  ArcTrim is explicitly safe because its
        // original source state was extracted above and the companion replaces the cut cell.
        if (existingBe != null && !(existingBe instanceof ArcTrimBlockEntity)) return false;
        return world.setBlockState(pos, ModelSystemMod.MODEL_BLOCK.getDefaultState(), 2);
    }

    private static int[] offsets(int count) {
        int[] values = new int[count];
        int start = -(count / 2); // odd: symmetric; even: deterministic half-block bias to negative side.
        for (int i = 0; i < count; i++) values[i] = start + i;
        return values;
    }

    private static Direction dominant(Vec3d axis) {
        if (axis == null || axis.lengthSquared() < 1.0E-12) return Direction.EAST;
        double ax = Math.abs(axis.x), ay = Math.abs(axis.y), az = Math.abs(axis.z);
        if (ay >= ax && ay >= az) return axis.y >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return axis.x >= 0 ? Direction.EAST : Direction.WEST;
        return axis.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
