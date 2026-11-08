package dev.mmlf.fabric;

import dev.mmlf.bridge.ItemAccess;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Items of Fabric mod blocks, through the Transfer API their own automation uses. Fabric serves plain vanilla
 * containers through the same lookup, so nothing falls back to the vanilla rules here.
 */
final class FabricItems implements ItemAccess {
    private static Storage<ItemVariant> storage(ServerLevel level,BlockPos pos,Direction side) { return ItemStorage.SIDED.find(level,pos,side==null?Direction.UP:side); }
    @Override public boolean has(ServerLevel level,BlockPos pos,Direction side) { return storage(level,pos,side)!=null; }
    @Override public ItemStack insert(ServerLevel level,BlockPos pos,Direction side,ItemStack stack) {
        Storage<ItemVariant> items=storage(level,pos,side); if(items==null || stack.isEmpty()) return stack;
        try(Transaction transaction=Transaction.openOuter()) {
            long inserted=items.insert(ItemVariant.of(stack),stack.getCount(),transaction);
            transaction.commit();
            return stack.copyWithCount(stack.getCount()-(int)inserted);
        }
    }
    @Override public ItemStack extract(ServerLevel level,BlockPos pos,Direction side,int max) {
        Storage<ItemVariant> items=storage(level,pos,side); if(items==null) return ItemStack.EMPTY;
        try(Transaction transaction=Transaction.openOuter()) {
            ItemVariant kind=StorageUtil.findExtractableResource(items,transaction);
            if(kind==null) return ItemStack.EMPTY;
            long taken=items.extract(kind,max,transaction);
            transaction.commit();
            return taken<=0 ? ItemStack.EMPTY : kind.toStack((int)taken);
        }
    }
}
