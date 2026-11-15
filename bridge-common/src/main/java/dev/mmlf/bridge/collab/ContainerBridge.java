package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.platform.ItemAccess;
import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.WorldFollow;
import dev.mmlf.bridge.world.Worlds;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

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
 * Both server threads share the completion of an operation in this JVM. A queued operation can be cancelled only
 * before the owner claims it. The owner mutates its own container on its server thread and the requester settles the
 * result on its server thread. Neither side treats a timeout as evidence of the outcome of an operation in progress.
 */
public final class ContainerBridge {
    private ContainerBridge() {}
    /** This world's part of ContainerBridge: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        final Map<Boundary.At,RemoteContainer> proxies=new HashMap<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("containers",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    private static final int PULL_BATCH=8;
    private static final long BACKOFF_TICKS=20, INTEREST_TICKS=20, QUEUE_TIMEOUT_NANOS=2_000_000_000L;
    private static MinecraftServer loadedFor;
    private static boolean stateReadable;
    private static long ticks;

    private record Request(Transfer transfer,String world,String objectId) {}
    private record Pending(AtomicBoolean claimed,CompletableFuture<String> result,long started,int count) {
        void cancelQueued() { if(claimed.compareAndSet(false,true)) result.complete(null); }
    }
    private record Stored(String dimension,long pos,String owner,String face,String inbox,String outbox,SlotItem pendingPull,boolean blocked) {}

    private static String key(String session,String runtime) { return "mmlf:containers:"+session+":"+runtime; }
    public static void publish(String session,String runtime) {
        InJvm.registry().put(key(session,runtime),(BiFunction<String,BooleanSupplier,CompletableFuture<String>>)ContainerBridge::dispatch);
    }
    public static void withdraw(String session,String runtime) { InJvm.registry().remove(key(session,runtime)); }

    /** Hopper lookup and the transfer state both belong to the server thread. */
    public static Container proxy(Level level,BlockPos pos) {
        if(!(level instanceof ServerLevel server) || SessionLink.session()==null) return null;
        String dimension=server.dimension().location().toString();
        ObjectState object=Boundary.remote(dimension,pos);
        if(object==null || !"true".equals(object.state().get(Boundary.CONTAINER))) return null;
        if(!ensureLoaded(server.getServer())) return null;
        return state().proxies.computeIfAbsent(new Boundary.At(dimension,pos.immutable()),at->new RemoteContainer(server,at,object.owner()));
    }

    static void tick(MinecraftServer server) {
        if(SessionLink.session()==null || !ensureLoaded(server)) return;
        ticks++;
        for(var it=state().proxies.values().iterator();it.hasNext();) {
            RemoteContainer p=it.next();
            if(p.operation!=null && System.nanoTime()-p.operation.started()>=QUEUE_TIMEOUT_NANOS) p.operation.cancelQueued();
            settle(p);
            if(p.operation!=null || p.pendingPull!=null || SaveSync.transferFrozen()) continue;
            ObjectState object=Boundary.remote(p.at.dimension(),p.at.pos());
            boolean present=object!=null && object.owner().equals(p.owner) && "true".equals(object.state().get(Boundary.CONTAINER));
            if(!present) {
                if(p.absentSince<0) p.absentSince=ticks;
                // Allow a late owner or chunk report to arrive before returning settled items to the local world.
                if(ticks-p.absentSince>=600 && p.level.isLoaded(p.at.pos())) {
                    // The way out of a quarantine: the container it could not account for is gone (broken by the
                    // player), so what waits here returns to the world rather than staying held for good.
                    if(p.blocked) {
                        MmlfLog.warn("items","transfer_quarantine_released",Map.of("position",p.at.pos().toShortString(),"count",p.inbox.getCount()));
                        p.blocked=false;
                    }
                    p.spill();
                    it.remove();
                } else if(!p.blocked && p.inbox.isEmpty() && p.outbox.isEmpty()) it.remove();
                continue;
            }
            p.absentSince=-1;
            if(p.blocked || ticks<p.backoffUntil) continue;
            if(!p.inbox.isEmpty()) {
                SlotItem item=encode(p.level,p.inbox,false);
                if(item==null) { p.spill(); continue; }
                start(p,object,"offer",p.inboxFace,item,p.inbox.getCount());
            } else if(p.outbox.isEmpty() && ticks-p.interestAt<=INTEREST_TICKS) {
                start(p,object,"pull",Direction.DOWN,null,PULL_BATCH);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void start(RemoteContainer p,ObjectState object,String kind,Direction face,SlotItem item,int count) {
        var session=SessionLink.session();
        Object service=InJvm.registry().get(key(session.identity(),p.owner));
        if(!(service instanceof BiFunction<?,?,?> endpoint)) return;
        var at=p.at;
        var transfer=new Transfer(kind,UUID.randomUUID().toString(),at.dimension(),at.pos().getX(),at.pos().getY(),at.pos().getZ(),face.getName(),item,count);
        var claim=new AtomicBoolean();
        var dispatch=(BiFunction<String,BooleanSupplier,CompletableFuture<String>>)endpoint;
        var future=dispatch.apply(Wire.JSON.toJson(new Request(transfer,WorldFollow.saveScope(p.level.getServer()),object.id())),
            ()->claim.compareAndSet(false,true));
        p.operation=new Pending(claim,future,System.nanoTime(),count);
        p.offering=kind.equals("offer");
    }

    private static CompletableFuture<String> dispatch(String payload,BooleanSupplier claim) {
        var result=new CompletableFuture<String>();
        var mc=Minecraft.getInstance();
        var server=mc.getSingleplayerServer();
        if(server==null || !server.isRunning()) return CompletableFuture.completedFuture(null);
        var request=Wire.JSON.fromJson(payload,Request.class);
        server.execute(()->{
            if(mc.getSingleplayerServer()!=server || !server.isRunning() || SaveSync.transferFrozen()
                || !WorldFollow.saveScope(server).equals(request.world()) || !claim.getAsBoolean()) {
                result.complete(null);
                return;
            }
            try { result.complete(Wire.JSON.toJson(execute(server,request))); }
            catch(RuntimeException | LinkageError e) { result.completeExceptionally(e); }
        });
        return result;
    }

    private static Transfer execute(MinecraftServer server,Request request) {
        Transfer t=request.transfer();
        ServerLevel level=RedstoneBridge.level(server,t.dimension());
        BlockPos pos=new BlockPos(t.x(),t.y(),t.z());
        ObjectState local=Boundary.local(t.dimension(),pos);
        if(level==null || !level.isLoaded(pos) || local==null || !local.id().equals(request.objectId())
            || !"true".equals(local.state().get(Boundary.CONTAINER))) return null;
        Direction face=Direction.byName(t.face());
        int count=0;
        SlotItem item=null;
        if(t.kind().equals("offer")) {
            ItemStack stack=decode(level,t.item());
            if(!stack.isEmpty()) {
                stack.setCount(Math.min(t.count(),stack.getMaxStackSize()));
                int offered=stack.getCount();
                count=offered-ItemAccess.insertInto(level,pos,face,stack).getCount();
            }
        } else {
            ItemStack stack=ItemAccess.extractFrom(level,pos,face,t.count());
            if(!stack.isEmpty()) {
                try { item=encode(level,stack,true); }
                catch(RuntimeException e) {
                    MmlfLog.warn("items","pull_unencodable",Map.of("position",pos.toShortString(),"exception",e.toString()));
                }
                if(item==null) {
                    Containers.dropItemStack(level,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,stack);
                } else count=stack.getCount();
            }
        }
        return new Transfer(t.kind(),t.id(),t.dimension(),t.x(),t.y(),t.z(),t.face(),item,count);
    }

    private static void settle(RemoteContainer p) {
        Pending pending=p.operation;
        if(pending!=null && pending.result().isDone()) {
            p.operation=null;
            p.backoffUntil=ticks+BACKOFF_TICKS;
            Transfer answer;
            try {
                answer=Wire.JSON.fromJson(pending.result().join(),Transfer.class);
            } catch(RuntimeException e) {
                // A mod may have changed its container before throwing. Quarantine this proxy for recovery.
                p.blocked=true;
                MmlfLog.error("items","transfer_outcome_unknown",Map.of("position",p.at.pos().toShortString(),"exception",e.toString()));
                return;
            }
            if(answer!=null) {
                if(p.offering) p.inbox.shrink(Math.min(pending.count(),Math.max(0,answer.count())));
                else if(answer.count()>0) p.pendingPull=answer.item();
                if(answer.count()>0) p.backoffUntil=ticks;
            }
        }
        if(p.pendingPull!=null && p.outbox.isEmpty()) {
            ItemStack stack=decode(p.level,p.pendingPull);
            if(!stack.isEmpty()) {
                stack.setCount(p.pendingPull.count());
                p.outbox=stack;
                p.pendingPull=null;
            }
        }
    }

    /** Save barrier: cancel operations that haven't started and settle the results of any that did. */
    static boolean prepareSave(MinecraftServer server) {
        if(SessionLink.session()==null) return true;
        if(!ensureLoaded(server)) return false;
        for(var p:state().proxies.values()) if(p.operation!=null) p.operation.cancelQueued();
        long started=System.nanoTime();
        server.managedBlock(()->state().proxies.values().stream().noneMatch(p->p.operation!=null && !p.operation.result().isDone())
            || System.nanoTime()-started>=QUEUE_TIMEOUT_NANOS);
        for(var p:state().proxies.values()) settle(p);
        return state().proxies.values().stream().noneMatch(p->p.operation!=null);
    }

    private static Path stateFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("mmlf-transfers.json");
    }

    private static boolean ensureLoaded(MinecraftServer server) {
        if(loadedFor==server) return stateReadable;
        loadedFor=server;
        stateReadable=true;
        state().proxies.clear();
        if(!Files.isRegularFile(stateFile(server))) return true;
        try {
            Stored[] saved=Wire.JSON.fromJson(Files.readString(stateFile(server)),Stored[].class);
            for(Stored entry:saved) {
                ServerLevel level=RedstoneBridge.level(server,entry.dimension());
                if(level==null) throw new IllegalStateException("unknown transfer dimension "+entry.dimension());
                var at=new Boundary.At(entry.dimension(),BlockPos.of(entry.pos()));
                var p=new RemoteContainer(level,at,entry.owner());
                p.inbox=loadStack(level,entry.inbox());
                p.outbox=loadStack(level,entry.outbox());
                p.inboxFace=Direction.byName(entry.face());
                p.pendingPull=entry.pendingPull();
                p.blocked=entry.blocked();
                state().proxies.put(at,p);
            }
        } catch(Exception e) {
            stateReadable=false;
            MmlfLog.error("items","transfer_state_load_failed",Map.of("exception",e.toString()));
        }
        return stateReadable;
    }

    public static boolean save(MinecraftServer server) {
        if(SessionLink.session()==null) return true;
        if(!prepareSave(server)) return false;
        var entries=new ArrayList<Stored>();
        try {
            for(var p:state().proxies.values()) {
                if(p.inbox.isEmpty() && p.outbox.isEmpty() && p.pendingPull==null && !p.blocked) continue;
                entries.add(new Stored(p.at.dimension(),p.at.pos().asLong(),p.owner,p.inboxFace.getName(),
                    saveStack(p.level,p.inbox),saveStack(p.level,p.outbox),p.pendingPull,p.blocked));
            }
            Path path=stateFile(server);
            if(entries.isEmpty()) Files.deleteIfExists(path);
            else {
                Path temporary=path.resolveSibling("mmlf-transfers.json.tmp");
                Files.writeString(temporary,Wire.JSON.toJson(entries));
                Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch(Exception e) {
            MmlfLog.error("items","transfer_state_save_failed",Map.of("exception",e.toString()));
            return false;
        }
    }

    private static String saveStack(ServerLevel level,ItemStack stack) {
        return stack.isEmpty()?null:ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack)
            .getOrThrow().toString();
    }
    private static ItemStack loadStack(ServerLevel level,String payload) {
        return payload==null?ItemStack.EMPTY:ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
            JsonParser.parseString(payload)).getOrThrow();
    }

    /** Test hook: count buffered items on this side. */
    public static int waiting() {
        int count=0;
        for(var p:state().proxies.values()) count+=p.inbox.getCount()+p.outbox.getCount()+(p.pendingPull==null?0:p.pendingPull.count());
        return count;
    }

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
        return new SlotItem(-1,RemoteItems.payload(stack,level.registryAccess()),new RemoteItemRef(SessionLink.runtimeId(),token).encode(),stack.getCount(),RemoteItems.describe(stack,level,(Player)null));
    }
    private static ItemStack decode(ServerLevel level,SlotItem item) {
        if(item.reference()!=null) {
            var reference=RemoteItemRef.parse(item.reference());
            if(reference.isEmpty()) return ItemStack.EMPTY;
            if(reference.get().owner().equals(SessionLink.runtimeId())) {
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
        Pending operation;
        SlotItem pendingPull;
        boolean offering,blocked;
        long backoffUntil,interestAt=Long.MIN_VALUE/2,absentSince=-1;
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
            return slot==0 && !blocked && transferable(stack) && (inbox.isEmpty() || ItemStack.isSameItemSameComponents(inbox,stack));
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
            if(!inbox.isEmpty() && operation==null && !blocked) { Containers.dropItemStack(level,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,inbox); inbox=ItemStack.EMPTY; }
            if(!outbox.isEmpty()) { Containers.dropItemStack(level,pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,outbox); outbox=ItemStack.EMPTY; }
        }
    }
}
