package dev.mmlf.bridge;

import com.mojang.serialization.MapCodec;
import dev.mmlf.common.StandInSpec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A position of the primary's map that belongs to another runtime ({@link Occupancy}): that runtime holds the actual
 * block in its own world, and the primary's map (and save) holds this in its place. It is the one record of who a
 * position belongs to: the position cannot hold the primary's own block at the same time.
 * <p>
 * It carries what the primary's world logic needs of the actual block ({@link StandInSpec}): whether it fills its cube
 * for light and occlusion ({@link #SOLID}), the light it emits ({@link #LIGHT}), the vanilla fluid it holds as a source
 * ({@link #FLUID}: none, water, lava; water shows, flows on and is swum in as around a waterlogged block) and whether it
 * conducts redstone like a solid block ({@link #CONDUCTOR}). Collision boxes are looked up per position. Only the owner
 * changes any of it: buckets, sponges and flowing fluids leave it as it is, it cannot be broken, pushed or replaced
 * here. It has no outline, so rays pass through it and interactions are routed to the owner. It draws nothing itself.
 */
public final class StandInBlock extends Block implements BucketPickup,LiquidBlockContainer {
    public static final BooleanProperty SOLID=BooleanProperty.create("solid");
    public static final IntegerProperty LIGHT=IntegerProperty.create("light",0,15);
    /** 0: none, 1: water, 2: lava (a source). */
    public static final IntegerProperty FLUID=IntegerProperty.create("fluid",0,2);
    public static final BooleanProperty CONDUCTOR=BooleanProperty.create("conductor");
    public static final MapCodec<StandInBlock> CODEC=simpleCodec(p->new StandInBlock());
    private static volatile StandInBlock instance;

    public StandInBlock() {
        super(BlockBehaviour.Properties.of()
            .strength(-1.0f,3_600_000.0f).noLootTable().dynamicShape()
            .lightLevel(s->s.getValue(LIGHT))
            .isSuffocating((s,l,p)->false).isViewBlocking((s,l,p)->false)
            .isRedstoneConductor((s,l,p)->s.getValue(CONDUCTOR)).forceSolidOn()
            .pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(SOLID,true).setValue(LIGHT,0).setValue(FLUID,0).setValue(CONDUCTOR,false));
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(SOLID,LIGHT,FLUID,CONDUCTOR); }

    /** Each loader registers the block once and hands it over here. */
    public static void installed(StandInBlock block) { instance=block; }
    /** Null until registered. */
    public static StandInBlock instance() { return instance; }
    public static boolean is(BlockState state) { return state.getBlock() instanceof StandInBlock; }
    /** A dry, non-conducting stand-in. */
    public static BlockState of(boolean solid,int light) { return of(new StandInSpec(solid,light,StandInSpec.NONE,false)); }
    public static BlockState of(StandInSpec spec) {
        var block=instance; if(block==null) return null;
        int fluid=StandInSpec.WATER.equals(spec.fluid()) ? 1 : StandInSpec.LAVA.equals(spec.fluid()) ? 2 : 0;
        return block.defaultBlockState().setValue(SOLID,spec.solid()).setValue(LIGHT,spec.light()).setValue(FLUID,fluid).setValue(CONDUCTOR,spec.conductor());
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return dev.mmlf.bridge.render.TerrainModels.enabled() ? RenderShape.MODEL : RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) { return Shapes.empty(); }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        VoxelShape shape=Occupancy.collision(level,pos);
        if(shape==null) shape=SharedWorld.collision(level,pos);
        return shape!=null ? shape : state.getValue(SOLID) ? Shapes.block() : Shapes.empty();
    }
    @Override protected VoxelShape getOcclusionShape(BlockState state,BlockGetter level,BlockPos pos) { return state.getValue(SOLID) ? Shapes.block() : Shapes.empty(); }
    @Override protected boolean propagatesSkylightDown(BlockState state,BlockGetter level,BlockPos pos) { return !state.getValue(SOLID) && state.getValue(FLUID)==0; }
    @Override protected float getShadeBrightness(BlockState state,BlockGetter level,BlockPos pos) { return state.getValue(SOLID) ? 0.2f : 1.0f; }

    // ---- the fluid it holds, as a waterlogged block holds water; only the owner changes it ----
    @Override protected FluidState getFluidState(BlockState state) {
        return switch(state.getValue(FLUID)) { case 1 -> Fluids.WATER.getSource(false); case 2 -> Fluids.LAVA.getSource(false); default -> Fluids.EMPTY.defaultFluidState(); };
    }
    @Override protected BlockState updateShape(BlockState state,Direction direction,BlockState neighbour,LevelAccessor level,BlockPos pos,BlockPos neighbourPos) {
        // As a waterlogged block: a neighbour changing lets the held fluid flow on.
        FluidState fluid=state.getFluidState();
        if(!fluid.isEmpty()) level.scheduleTick(pos,fluid.getType(),fluid.getType().getTickDelay(level));
        return state;
    }
    @Override public boolean canPlaceLiquid(Player player,BlockGetter level,BlockPos pos,BlockState state,Fluid fluid) { return false; }
    @Override public boolean placeLiquid(LevelAccessor level,BlockPos pos,BlockState state,FluidState fluid) { return false; }
    @Override public ItemStack pickupBlock(Player player,LevelAccessor level,BlockPos pos,BlockState state) { return ItemStack.EMPTY; }
    @Override public Optional<SoundEvent> getPickupSound() { return Optional.empty(); }
}
