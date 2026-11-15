package dev.mmlf.bridge.collab;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the render layer ({@code dev.mmlf.bridge.render}) reads of the collaboration layer, and nothing else: which
 * blocks and entities this runtime draws, and what the primary's map holds where a remote block is. The world's
 * lifecycle comes from {@link Worlds}, the session from {@link SessionLink}.
 */
public final class RenderInputs {
    private RenderInputs() {}

    // ---- submitter: what this runtime draws and submits ----

    /** Whether this runtime draws the block: its own (mod blocks, vanilla blocks it placed), all of them on the primary. */
    public static boolean blockOwned(Level level,BlockState state,BlockPos pos) { return Ownership.renderOwned(level,state,pos); }
    /** The same in the client world. */
    public static boolean blockOwned(BlockState state,BlockPos pos) { return Ownership.renderOwned(state,pos); }
    /** The same for a section mesher; also false where the primary has rejected this runtime's block. */
    public static boolean drawsCell(BlockState state,BlockPos pos) { return Ownership.renderOwnedInSection(state,pos); }
    public static boolean drawsFluid(net.minecraft.world.level.material.FluidState fluid,BlockPos pos) { return Ownership.renderOwnedFluidInSection(fluid,pos); }
    /** The vanilla blocks this runtime placed itself (few, known by position). */
    public static Set<BlockPos> ownedVanillaPositions(Level level) { return Ownership.ownedVanillaPositions(level); }
    /** Whether this runtime owns, and so draws and submits, the entity. */
    public static boolean ownsEntity(Entity entity) { return RemoteEntities.owned(entity); }
    /** A particle another runtime's entity made, drawn by the primary from that runtime. */
    public static boolean entityParticle(net.minecraft.client.particle.Particle particle) { return RemoteEntities.entityParticle(particle); }

    // ---- items: what the render layer shows of items another runtime owns ----

    /** The reference of the item stack the player holds, or its plain id. */
    public static String held(net.minecraft.world.item.ItemStack stack) { return RemoteItems.held(stack); }
    /** A proxy stack's payload (the owner's item data), or null. */
    public static String payload(net.minecraft.world.item.ItemStack proxy) { return RemoteItems.payload(proxy); }
    /** Owner: its real stack for a reference, rebuilt from the payload when needed. */
    public static net.minecraft.world.item.ItemStack resolveOwned(UUID token,String payload,net.minecraft.core.HolderLookup.Provider registries) { return RemoteItems.resolveOwned(token,payload,registries); }
    /** Owner: the hand the primary selected holds an item of this runtime. */
    public static boolean nativeHandSelected() { return RemoteItems.nativeHandSelected(); }
    /** Owner: a copy of its stack for a reference token, or null. */
    public static net.minecraft.world.item.ItemStack nativeStack(UUID token) { return RemoteItems.nativeStack(token); }
    /** The reference of the item the primary's player selected. */
    public static String selectedReference() { return RemoteItems.selectedReference(); }
    /** Owner: why its held stack does not match the expected reference, or null when it does. */
    public static String heldProblem(net.minecraft.world.item.ItemStack held,String expected) { return InventoryBridge.heldProblem(held,expected); }
    /** Primary: the icon of an owner's item in its catalog atlas, or null. */
    public static RemoteCatalog.AtlasIcon catalogIcon(String owner,String reference) { return RemoteCatalog.atlasIcon(owner,reference); }

    // ---- host: the primary's map ----

    /** The stand-in the owner's reported block takes at the position, or null when nothing was reported there. */
    public static BlockState reportedStandIn(String dimension,BlockPos pos) { return Occupancy.reportedStandIn(dimension,pos); }
    /** Whether the state is another runtime's stand-in in the primary's map. */
    public static boolean isStandIn(BlockState state) { return StandInBlock.is(state); }
    /** A stand-in with these properties (null before the block is registered). */
    public static BlockState standIn(boolean solid,int light) { return StandInBlock.of(solid,light); }
    /** The server's level of that dimension, or null. */
    public static ServerLevel serverLevel(MinecraftServer server,String dimension) { return RedstoneBridge.levelOf(server,dimension); }
}
