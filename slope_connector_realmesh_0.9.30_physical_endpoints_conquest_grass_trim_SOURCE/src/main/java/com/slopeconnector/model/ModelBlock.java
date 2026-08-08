package com.slopeconnector.model;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

public final class ModelBlock extends BlockWithEntity {
    public static final BooleanProperty SKINNED = BooleanProperty.of("skinned");

    public ModelBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(SKINNED, false));
    }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(SKINNED); }

    @Nullable @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ModelBlockEntity(pos, state);
    }

    @Override public BlockRenderType getRenderType(BlockState state) {
        return state.get(SKINNED) ? BlockRenderType.INVISIBLE : BlockRenderType.MODEL;
    }

    @Override public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (!state.get(SKINNED)) return VoxelShapes.fullCube();
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ModelBlockEntity model && model.isSkinned()) {
            try { return model.getDisplayState().getOutlineShape(world, pos, context); }
            catch (RuntimeException ignored) { }
        }
        return VoxelShapes.fullCube();
    }


    @Override public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        // A skinned endpoint may visually be a thin fence/pane/balustrade.  Returning the inherited
        // full-cube culling shape makes Minecraft hide the terrain/block faces under and beside the
        // invisible ModelBlock holder, producing the "void but collision still exists" bug.
        return state.get(SKINNED) ? VoxelShapes.empty() : VoxelShapes.fullCube();
    }

    @Override
    public boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
        // Unskinned widened endpoint companions are real adjacent full ModelBlocks.  Their internal
        // touching faces are never visible and drawing both coplanar faces is a pure Z-fighting cost.
        if (!state.get(SKINNED) && stateFrom.getBlock() == this && !stateFrom.get(SKINNED)) return true;
        return super.isSideInvisible(state, stateFrom, direction);
    }

    @Override public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (!state.get(SKINNED)) return VoxelShapes.fullCube();
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ModelBlockEntity model && model.isSkinned()) {
            try { return model.getDisplayState().getCollisionShape(world, pos, context); }
            catch (RuntimeException ignored) { }
        }
        return VoxelShapes.fullCube();
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                BlockState newState, boolean moved) {
        if (state.getBlock() != newState.getBlock()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ModelBlockEntity model && model.isEndpointGridMember()
                    && !model.isEndpointGridCompanion()) {
                EndpointCompanionManager.removeCompanions(world, pos);
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                 WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ModelBlockEntity model) model.onNeighborChanged();
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }
}
