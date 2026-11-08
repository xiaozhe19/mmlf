package dev.mmlf.neoforge;

import dev.mmlf.bridge.ItemAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Items of NeoForge mod blocks, through the item handler capability their own automation uses. */
final class NeoForgeItems implements ItemAccess {
    private static IItemHandler handler(ServerLevel level,BlockPos pos,Direction side) { return level.getCapability(Capabilities.ItemHandler.BLOCK,pos,side); }
    @Override public boolean has(ServerLevel level,BlockPos pos,Direction side) { return handler(level,pos,side)!=null; }
    @Override public ItemStack insert(ServerLevel level,BlockPos pos,Direction side,ItemStack stack) {
        IItemHandler items=handler(level,pos,side);
        return items==null ? stack : ItemHandlerHelper.insertItemStacked(items,stack,false);
    }
    @Override public ItemStack extract(ServerLevel level,BlockPos pos,Direction side,int max) {
        IItemHandler items=handler(level,pos,side); if(items==null) return ItemStack.EMPTY;
        for(int slot=0;slot<items.getSlots();slot++) {
            ItemStack taken=items.extractItem(slot,max,false);
            if(!taken.isEmpty()) return taken;
        }
        return ItemStack.EMPTY;
    }
}
