package dev.mmlf.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Putting items into and taking them out of a block of this runtime, the way this runtime's own automation does.
 * Mods mostly expose items through their loader's API (NeoForge item handler capability, Fabric Transfer API); each
 * loader bridge installs that as {@link #loader}. Blocks that only implement Minecraft's {@link Container} are served by
 * the vanilla hopper rules. Server thread only.
 */
public interface ItemAccess {
    /** Whether the block offers item storage from this side (null: any side). */
    boolean has(ServerLevel level,BlockPos pos,Direction side);
    /** Inserts as much of {@code stack} as fits and returns what did not; {@code stack} itself may be consumed in the process. */
    ItemStack insert(ServerLevel level,BlockPos pos,Direction side,ItemStack stack);
    /** Takes up to {@code max} items of one kind that the block lets out on this side, or an empty stack. */
    ItemStack extract(ServerLevel level,BlockPos pos,Direction side,int max);

    /** Installed by the loader bridge; null where the loader offers nothing beyond vanilla containers. */
    final class Loader { static volatile ItemAccess current; private Loader() {} }
    static void install(ItemAccess access) { Loader.current=access; }

    static boolean has(ServerLevel level,BlockPos pos) {
        ItemAccess loader=Loader.current;
        return HopperBlockEntity.getContainerAt(level,pos)!=null || loader!=null && loader.has(level,pos,null);
    }
    static ItemStack insertInto(ServerLevel level,BlockPos pos,Direction side,ItemStack stack) {
        ItemAccess loader=Loader.current;
        if(loader!=null && loader.has(level,pos,side)) return loader.insert(level,pos,side,stack);
        Container container=HopperBlockEntity.getContainerAt(level,pos);
        if(container==null) return stack;
        ItemStack rest=HopperBlockEntity.addItem(null,container,stack,side);
        container.setChanged();
        return rest;
    }
    static ItemStack extractFrom(ServerLevel level,BlockPos pos,Direction side,int max) {
        ItemAccess loader=Loader.current;
        if(loader!=null && loader.has(level,pos,side)) return loader.extract(level,pos,side,max);
        Container container=HopperBlockEntity.getContainerAt(level,pos);
        if(container==null) return ItemStack.EMPTY;
        int[] slots=container instanceof WorldlyContainer worldly && side!=null ? worldly.getSlotsForFace(side) : null;
        int count=slots==null ? container.getContainerSize() : slots.length;
        for(int i=0;i<count;i++) {
            int slot=slots==null ? i : slots[i];
            ItemStack inside=container.getItem(slot);
            if(inside.isEmpty()) continue;
            if(container instanceof WorldlyContainer worldly && side!=null && !worldly.canTakeItemThroughFace(slot,inside,side)) continue;
            ItemStack taken=container.removeItem(slot,Math.min(max,inside.getCount()));
            container.setChanged();
            return taken;
        }
        return ItemStack.EMPTY;
    }
}
