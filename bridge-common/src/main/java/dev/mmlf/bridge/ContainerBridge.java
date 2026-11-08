package dev.mmlf.bridge;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.*;

/**
 * Hoppers next to another runtime's container. To a hopper, that block is a two-slot container of this runtime
 * ({@link RemoteContainer}): slot 0 takes items in from above and the sides, slot 1 lets items out downwards. Neither
 * slot is the remote container itself, which only its owner can touch:
 * <ul>
 * <li>pushed items wait in slot 0, still this runtime's, while they are offered to the owner, which inserts what fits
 * with its own automation rules and says how many; only those leave slot 0;</li>
 * <li>while a hopper below asks for items, the owner is asked to take up to a few out; what it took arrives in slot 1,
 * now this runtime's, for the hopper to pull.</li>
 * </ul>
 * Every item is in exactly one runtime at a time: an offer is answered once ({@link ReliableLink}), and nothing is
 * created from an answer that did not take it out first. A full or refusing container keeps slot 0 full, which stops the
 * hopper as a full chest would. Server thread only, except {@link #receive}, which hands over to it.
 */
public final class ContainerBridge {
    private ContainerBridge() {}
    private static final Map<Boundary.At,RemoteContainer> proxies=new HashMap<>();
    private static final int PULL_BATCH=8;
    private static final long BACKOFF_TICKS=20, INTEREST_TICKS=20, UNCONFIRMED_TICKS=600;
    private static long ticks;

    /** {@code HopperContainerMixin}: the stand-in for another runtime's container at this position, or null. */
    public static Container proxy(Level level,BlockPos pos) {
        if(!(level instanceof ServerLevel server)) return null;
        String dimension=server.dimension().location().toString();
        ObjectState object=Boundary.remote(dimension,pos);
        if(object==null || !"true".equals(object.state().get(Boundary.CONTAINER))) return null;
        return proxies.computeIfAbsent(new Boundary.At(dimension,pos.immutable()),at->new RemoteContainer(server,at,object.owner()));
    }

    /** Server thread, once a tick: offers what waits in slot 0, asks for items wanted in slot 1, and lets go of stale stand-ins. */
    static void tick(MinecraftServer server) {
        ticks++;
        for(var it=proxies.values().iterator();it.hasNext();) {
            RemoteContainer p=it.next();
            ObjectState object=Boundary.remote(p.at.dimension(),p.at.pos());
            boolean present=object!=null && object.owner().equals(p.owner) && "true".equals(object.state().get(Boundary.CONTAINER));
            if(p.offerId!=null && !present && ticks-p.offerAt>UNCONFIRMED_TICKS) {
                // The owner restarted or the container went away without answering: whether it inserted is unknown.
                MmlfLog.warn("items","transfer_unconfirmed",Map.of("owner",p.owner,"position",p.at.pos().toShortString(),"count",p.offered));
                p.offerId=null;
            }
            if(!present) {
                if(p.offerId==null && p.pullId==null) { p.spill(); it.remove(); }
                continue;
            }
            if(!p.inbox.isEmpty() && p.offerId==null && ticks>=p.backoffUntil) {
                SlotItem item=encode(p.level,p.inbox,false);
                if(item==null) { p.spill(); continue; }
                p.offerId=UUID.randomUUID().toString(); p.offered=p.inbox.getCount(); p.offerAt=ticks;
                BridgeCore.sendReliable("transfer",p.owner,new Transfer("offer",p.offerId,p.at.dimension(),p.at.pos().getX(),p.at.pos().getY(),p.at.pos().getZ(),p.inboxFace.getName(),item,p.offered));
            }
            if(p.outbox.isEmpty() && p.pullId==null && ticks-p.interestAt<=INTEREST_TICKS && ticks>=p.backoffUntil) {
                p.pullId=UUID.randomUUID().toString();
                BridgeCore.sendReliable("transfer",p.owner,new Transfer("pull",p.pullId,p.at.dimension(),p.at.pos().getX(),p.at.pos().getY(),p.at.pos().getZ(),Direction.DOWN.getName(),null,PULL_BATCH));
            }
        }
    }

    /** Network thread. */
    static void receive(String source,Transfer t) {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if(server==null || t==null || t.kind()==null || t.id()==null) return;
        server.execute(()->{
            ServerLevel level=RedstoneBridge.level(server,t.dimension()); if(level==null) return;
            BlockPos pos=new BlockPos(t.x(),t.y(),t.z());
            Direction face=Direction.byName(t.face());
            Boundary.At at=new Boundary.At(t.dimension(),pos);
            switch(t.kind()) {
                case "offer" -> {
                    ItemStack stack=t.item()==null ? ItemStack.EMPTY : decode(level,t.item());
                    int accepted=0;
                    if(!stack.isEmpty() && Boundary.local(t.dimension(),pos)!=null) {
                        stack.setCount(Math.min(t.count(),stack.getMaxStackSize()));
                        int offered=stack.getCount();
                        // Insertion may shrink the stack it is given and return that same stack: count before, hand over a copy.
                        ItemStack rest=ItemAccess.insertInto(level,pos,face,stack.copy());
                        accepted=offered-rest.getCount();
                    }
                    MmlfLog.debug("items","offer_answered",Map.of("from",source,"position",pos.toShortString(),"offered",t.count(),"accepted",accepted));
                    BridgeCore.sendReliable("transfer",source,new Transfer("accepted",t.id(),t.dimension(),t.x(),t.y(),t.z(),t.face(),null,accepted));
                }
                case "accepted" -> {
                    RemoteContainer p=proxies.get(at);
                    if(p==null || !t.id().equals(p.offerId)) { MmlfLog.warn("items","answer_without_offer",Map.of("from",source,"position",pos.toShortString(),"accepted",t.count())); return; }
                    p.inbox.shrink(Math.min(t.count(),p.inbox.getCount()));
                    if(t.count()<p.offered) p.backoffUntil=ticks+BACKOFF_TICKS;
                    p.offerId=null;
                    MmlfLog.debug("items","offer_settled",Map.of("owner",source,"position",pos.toShortString(),"accepted",t.count(),"waiting",p.inbox.getCount()));
                }
                case "pull" -> {
                    SlotItem item=null; int count=0;
                    if(Boundary.local(t.dimension(),pos)!=null) {
                        ItemStack taken=ItemAccess.extractFrom(level,pos,face,Math.max(1,Math.min(t.count(),64)));
                        if(!taken.isEmpty()) {
                            item=encode(level,taken,true);
                            if(item==null) { ItemAccess.insertInto(level,pos,face,taken); MmlfLog.warn("items","pull_unencodable",Map.of("position",pos.toShortString())); }
                            else count=taken.getCount();
                        }
                    }
                    BridgeCore.sendReliable("transfer",source,new Transfer("pulled",t.id(),t.dimension(),t.x(),t.y(),t.z(),t.face(),item,count));
                }
                case "pulled" -> {
                    RemoteContainer p=proxies.get(at);
                    ItemStack got=t.item()==null || t.count()<=0 ? ItemStack.EMPTY : decode(level,t.item());
                    if(!got.isEmpty()) got.setCount(t.count());
                    if(p==null || !t.id().equals(p.pullId)) {
                        // The stand-in went away meanwhile; the items are this runtime's now and are dropped, never lost.
                        if(!got.isEmpty()) Containers.dropItemStack(level,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,got);
                        return;
                    }
                    p.pullId=null;
                    if(got.isEmpty()) { p.backoffUntil=ticks+BACKOFF_TICKS; return; }
                    if(p.outbox.isEmpty()) p.outbox=got;
                    else if(ItemStack.isSameItemSameComponents(p.outbox,got)) p.outbox.grow(got.getCount());
                    else Containers.dropItemStack(level,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,got);
                    MmlfLog.debug("items","pull_settled",Map.of("owner",source,"position",pos.toShortString(),"count",t.count()));
                }
                default -> MmlfLog.warn("items","unknown_transfer",Map.of("kind",t.kind()));
            }
        });
    }
    /** The session is ending: what waits in stand-ins drops where it is instead of vanishing with the process. */
    static void spillAll(MinecraftServer server) {
        server.execute(()->{ proxies.values().forEach(RemoteContainer::spill); proxies.clear(); });
    }
    /** Test hook: what waits in stand-ins, so a self-test can count every item. */
    static int waiting() { int n=0; for(var p:proxies.values()) n+=p.inbox.getCount()+p.outbox.getCount(); return n; }

    /** Whether an item can be handed to another runtime as it is: vanilla items, and other runtimes' items by reference. */
    static boolean transferable(ItemStack stack) {
        var data=stack.get(DataComponents.CUSTOM_DATA);
        if(data!=null && RemoteItemRef.parse(data.copyTag().getString("mmlf_item")).isPresent()) return true;
        return InventoryBridge.portable(stack);
    }
    /**
     * An item as it travels: vanilla items as data, another runtime's items by its reference, and, when {@code owned},
     * this runtime's own mod items by a new reference that keeps the real stack here.
     */
    private static SlotItem encode(ServerLevel level,ItemStack stack,boolean owned) {
        var data=stack.get(DataComponents.CUSTOM_DATA);
        String reference=data==null ? null : data.copyTag().getString("mmlf_item");
        if(reference!=null && RemoteItemRef.parse(reference).isPresent()) return new SlotItem(-1,RemoteItems.payload(stack),reference,stack.getCount(),RemoteItems.display(stack));
        if(InventoryBridge.portable(stack)) {
            var json=ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack).result();
            return json.map(j->new SlotItem(-1,j.toString(),null,stack.getCount(),null)).orElse(null);
        }
        if(!owned) return null;
        UUID token=RemoteItems.keep(stack);
        return new SlotItem(-1,RemoteItems.payload(stack,level.registryAccess()),new RemoteItemRef(BridgeCore.runtimeId(),token).encode(),stack.getCount(),RemoteItems.describe(stack,level,(Player)null));
    }
    private static ItemStack decode(ServerLevel level,SlotItem item) {
        if(item.reference()!=null) {
            var reference=RemoteItemRef.parse(item.reference());
            if(reference.isEmpty()) return ItemStack.EMPTY;
            if(reference.get().owner().equals(BridgeCore.runtimeId())) {
                ItemStack real=RemoteItems.resolveOwned(reference.get().token(),item.encoded(),level.registryAccess());
                if(real==null) return ItemStack.EMPTY;
                real.setCount(item.count()); return real;
            }
            return RemoteItems.proxy(reference.get(),item.display(),item.count(),item.encoded());
        }
        if(item.encoded()==null) return ItemStack.EMPTY;
        try { return ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString(item.encoded())).result().orElse(ItemStack.EMPTY); }
        catch(RuntimeException e) { return ItemStack.EMPTY; }
    }

    /** A hopper's view of another runtime's container. */
    static final class RemoteContainer implements WorldlyContainer {
        final ServerLevel level; final Boundary.At at; final String owner;
        ItemStack inbox=ItemStack.EMPTY, outbox=ItemStack.EMPTY;
        Direction inboxFace=Direction.UP;
        String offerId, pullId; int offered; long offerAt, backoffUntil, interestAt=Long.MIN_VALUE/2;
        RemoteContainer(ServerLevel level,Boundary.At at,String owner) { this.level=level; this.at=at; this.owner=owner; }
        private static final int[] IN={0}, OUT={1};
        public int[] getSlotsForFace(Direction face) {
            // Only a hopper below pulls through the bottom face: that is the only sign that items are wanted out.
            if(face==Direction.DOWN) { interestAt=ticks; return OUT; }
            return IN;
        }
        public boolean canPlaceItemThroughFace(int slot,ItemStack stack,Direction face) {
            if(slot!=0 || face==Direction.DOWN || !canPlaceItem(slot,stack)) return false;
            if(inbox.isEmpty()) inboxFace=face==null ? Direction.UP : face;
            return true;
        }
        public boolean canTakeItemThroughFace(int slot,ItemStack stack,Direction face) { return slot==1 && face==Direction.DOWN; }
        public boolean canPlaceItem(int slot,ItemStack stack) {
            // Slot 0 holds one kind at a time, the kind on offer; items another runtime cannot take are refused.
            return slot==0 && transferable(stack) && (inbox.isEmpty() || ItemStack.isSameItemSameComponents(inbox,stack));
        }
        public int getContainerSize() { return 2; }
        public boolean isEmpty() { return inbox.isEmpty() && outbox.isEmpty(); }
        public ItemStack getItem(int slot) { return slot==0 ? inbox : slot==1 ? outbox : ItemStack.EMPTY; }
        public ItemStack removeItem(int slot,int amount) {
            if(slot!=1 || outbox.isEmpty()) return ItemStack.EMPTY;
            return outbox.split(amount);
        }
        public ItemStack removeItemNoUpdate(int slot) {
            if(slot!=1) return ItemStack.EMPTY;
            ItemStack all=outbox; outbox=ItemStack.EMPTY; return all;
        }
        public void setItem(int slot,ItemStack stack) {
            if(slot==0) inbox=stack; else if(slot==1) outbox=stack;
        }
        public void setChanged() { }
        public boolean stillValid(Player player) { return false; }
        public void clearContent() { spill(); }
        void spill() {
            BlockPos pos=at.pos();
            if(!inbox.isEmpty() && offerId==null) { Containers.dropItemStack(level,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,inbox); inbox=ItemStack.EMPTY; }
            if(!outbox.isEmpty()) { Containers.dropItemStack(level,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,outbox); outbox=ItemStack.EMPTY; }
        }
    }
}
