package dev.mmlf.bridge;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where another runtime's block occupies a position of the primary's world ({@link Occupancy}), reads of that position
 * answer with this block, so the primary's own world knows the space is taken: light stops or is emitted there,
 * neighbouring faces are culled and shaded against it, entities collide with it, nothing is placed into it and nothing
 * spawns inside it. It is never placed in saved chunk data and never runs anything.
 * <p>
 * What it carries is only what world logic needs: whether it fills its cube for light and occlusion ({@link #SOLID})
 * and how much light it emits ({@link #LIGHT}); collision boxes are looked up per position. It has no outline, so rays
 * pass through it exactly as before and interactions keep being routed to the owning runtime. It draws nothing itself.
 */
public final class StandInBlock extends Block {
    public static final BooleanProperty SOLID=BooleanProperty.create("solid");
    public static final IntegerProperty LIGHT=IntegerProperty.create("light",0,15);
    public static final MapCodec<StandInBlock> CODEC=simpleCodec(p->new StandInBlock());
    private static volatile StandInBlock instance;

    public StandInBlock() {
        super(BlockBehaviour.Properties.of()
            .strength(-1.0f,3_600_000.0f).noLootTable().dynamicShape()
            .lightLevel(s->s.getValue(LIGHT))
            .isSuffocating((s,l,p)->false).isViewBlocking((s,l,p)->false)
            .isRedstoneConductor((s,l,p)->false).forceSolidOn()
            .pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(SOLID,true).setValue(LIGHT,0));
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(SOLID,LIGHT); }

    /** Each loader registers the block once and hands it over here. */
    public static void installed(StandInBlock block) { instance=block; }
    /** Null until registered. */
    public static StandInBlock instance() { return instance; }
    public static boolean is(BlockState state) { return state.getBlock() instanceof StandInBlock; }
    public static BlockState of(boolean solid,int light) {
        var block=instance; if(block==null) return null;
        return block.defaultBlockState().setValue(SOLID,solid).setValue(LIGHT,Math.max(0,Math.min(15,light)));
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return dev.mmlf.bridge.render.TerrainModels.enabled() ? RenderShape.MODEL : RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) { return Shapes.empty(); }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        VoxelShape shape=Occupancy.collision(level,pos);
        if(shape==null) shape=SharedWorld.collision(level,pos);
        return shape!=null ? shape : state.getValue(SOLID) ? Shapes.block() : Shapes.empty();
    }
    @Override protected VoxelShape getOcclusionShape(BlockState state,BlockGetter level,BlockPos pos) { return state.getValue(SOLID) ? Shapes.block() : Shapes.empty(); }
    @Override protected boolean propagatesSkylightDown(BlockState state,BlockGetter level,BlockPos pos) { return !state.getValue(SOLID); }
    @Override protected float getShadeBrightness(BlockState state,BlockGetter level,BlockPos pos) { return state.getValue(SOLID) ? 0.2f : 1.0f; }
}
