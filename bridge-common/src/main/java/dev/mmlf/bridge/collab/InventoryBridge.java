package dev.mmlf.bridge.collab;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.bridge.transport.SessionLink;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

/**
 * One player inventory across runtimes. The primary is its authority and mirrors it to every owner, which writes it
 * into its own server player so native screens show the primary's items. While an owner hosts a screen it holds a
 * lease ({@link InventoryLease}): the primary's inventory is locked, the owner reports each change and returns the
 * inventory when the screen closes; if the owner fails, the primary restores the last confirmed state.
 *
 * Items another runtime cannot load travel unchanged: on an owner they become immovable placeholders carrying the
 * primary's slot, on the primary they become proxies referring to the owner's real stack. Nothing is recreated from
 * display data, so an item is never duplicated or invented.
 */
public final class InventoryBridge {
    private static final String PLACEHOLDER="mmlf_placeholder", ENCODED="mmlf_encoded", ENCODED_OWNER="mmlf_encoded_owner";
    private static String self; private static boolean primary;
    private static int tickCount;
    private record OwnerChange(net.minecraft.server.MinecraftServer server,UUID player,String owner,InventorySnapshot snapshot) {}
    private static final RecentIds appliedChanges=new RecentIds(4096);
    private record Kept(UUID token,ItemStack stack) {}
    private InventoryBridge() {}
    /** This world's part of InventoryBridge: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        // primary
        final InventoryLease lease=new InventoryLease();
        List<SlotItem> mirrored;
        long mirroredAt;
        final ArrayDeque<OwnerChange> waitingChanges=new ArrayDeque<>();
        // owner
        final InventoryLease.Holding holding=new InventoryLease.Holding();
        List<SlotItem> reported;
        /** Owner: version of the newest primary snapshot (mirror or grant) applied; 0 after the primary was lost. */
        long primaryVersion;
        /** Owner: the inventory as the primary last mirrored it; anything else in it was changed by this runtime. */
        List<SlotItem> baseline;
        java.lang.ref.WeakReference<ServerPlayer> baselinePlayer=new java.lang.ref.WeakReference<>(null);
        final Map<Integer,Kept> kept=new HashMap<>();
        Runnable returning;
        long returnDeadline;
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("inventory",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    public static void initialize(String runtime,boolean main) { self=runtime; primary=main; }

    /** Primary server thread: while an owner holds the inventory, nothing here may change it. */
    public static boolean locked(Player player) { return primary && player instanceof ServerPlayer && state().lease.leased(); }

    public static void tick(Minecraft mc) {
        var server=mc.getSingleplayerServer(); if(server==null || self==null) return;
        tickCount++;
        if(primary) { if(tickCount%2==0) server.execute(()->mirror(server)); return; }
        if(state().holding.active()) server.execute(()->reportChanges(server));
        else if(state().returning==null && state().baseline!=null) server.execute(()->reportOwnChanges(server));
        if(state().returning!=null) server.execute(()->finishReturn(mc,server));
    }

    // ---- primary ----

    private static void mirror(net.minecraft.server.MinecraftServer server) {
        ServerPlayer player=player(server); if(player==null || state().lease.leased()) return;
        for(OwnerChange pending;(pending=state().waitingChanges.poll())!=null;)
            if(pending.server()==server && pending.player().equals(player.getUUID())) settleOwnerChange(player,pending.owner(),pending.snapshot());
        List<SlotItem> slots=encodeAll(player);
        long now=System.nanoTime();
        if(slots.equals(state().mirrored) && now-state().mirroredAt<5_000_000_000L) return;
        state().mirrored=slots; state().mirroredAt=now;
        SessionLink.send("inventory","*",new InventorySnapshot(null,self,state().lease.nextVersion(),player.getInventory().selected,slots,InventoryLease.MIRROR));
    }
    /** A hosted screen opened: the owner gets the inventory as it is now, which is also the rollback point. */
    static void grant(String owner) {
        var server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{
            ServerPlayer player=player(server); if(player==null) return;
            String id=UUID.randomUUID().toString();
            var current=new InventorySnapshot(id,owner,state().lease.nextVersion(),player.getInventory().selected,encodeAll(player),InventoryLease.GRANT);
            if(!state().lease.grant(owner,id,current)) { MmlfLog.warn("inventory","grant_refused",Map.of("owner",owner,"holder",String.valueOf(state().lease.holder()))); return; }
            SessionLink.sendReliable("inventory",owner,current);
            MmlfLog.info("inventory","lease_granted",Map.of("owner",owner,"leaseId",id,"version",current.version()));
        });
    }
    /** The owner's screen ended; a lease it did not return is rolled back to the last confirmed inventory. */
    static void ownerEnded(String owner,String reason) {
        if(!primary || owner==null) return;
        InventorySnapshot restore=state().lease.rollback(owner);
        if(restore==null) return;
        MmlfLog.warn("inventory","lease_rolled_back",Map.of("owner",owner,"reason",String.valueOf(reason),"version",restore.version()));
        applyOnServer(restore,owner);
    }

    // ---- both ----

    /** Network thread. Lease decisions are made here, in arrival order, before any screen report that follows. */
    public static void receive(Wire message) {
        InventorySnapshot snapshot=message.body(InventorySnapshot.class);
        if(snapshot==null || snapshot.slots()==null || snapshot.slots().size()>64) { MmlfLog.warn("inventory","invalid_snapshot",Map.of("source",message.source())); return; }
        if(primary && InventoryLease.OWNER_CHANGE.equals(snapshot.reason())) { applyOwnerChange(message.source(),snapshot); return; }
        if(primary) {
            var verdict=state().lease.accept(message.source(),snapshot);
            MmlfLog.debug("inventory","owner_snapshot",Map.of("owner",message.source(),"version",snapshot.version(),"reason",String.valueOf(snapshot.reason()),"verdict",verdict.name()));
            if(verdict==InventoryLease.Verdict.REJECTED) { MmlfLog.warn("inventory","snapshot_rejected",Map.of("owner",message.source(),"version",snapshot.version())); return; }
            if(verdict==InventoryLease.Verdict.RETURNED) MmlfLog.info("inventory","lease_returned",Map.of("owner",message.source(),"version",snapshot.version()));
            applyOnServer(snapshot,message.source());
            return;
        }
        if(!message.source().equals(SessionLink.primaryId()) || !self.equals(snapshot.authority()) && !InventoryLease.MIRROR.equals(snapshot.reason())) return;
        // Mirrors and grants share the primary's version counter; one overtaken by a newer one must not undo it.
        synchronized(InventoryBridge.class) {
            if(snapshot.version()<=state().primaryVersion) { MmlfLog.warn("inventory","stale_snapshot",Map.of("version",snapshot.version(),"applied",state().primaryVersion,"reason",String.valueOf(snapshot.reason()))); return; }
            state().primaryVersion=snapshot.version();
        }
        if(InventoryLease.GRANT.equals(snapshot.reason())) {
            if(!GuiHost.hosting()) { MmlfLog.warn("inventory","grant_without_screen",Map.of("leaseId",String.valueOf(snapshot.leaseId()))); return; }
            state().holding.begin(snapshot.leaseId(),snapshot.version());
            MmlfLog.info("inventory","lease_held",Map.of("leaseId",snapshot.leaseId(),"version",snapshot.version()));
        } else if(state().holding.active()) return;
        applyOnServer(snapshot,SessionLink.primaryId());
    }
    private static void applyOnServer(InventorySnapshot snapshot,String from) {
        var server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{
            ServerPlayer player=player(server); if(player==null) return;
            var inventory=player.getInventory();
            if(!primary) {
                if(!state().holding.active()) reportOwnChanges(server);
                state().kept.clear();
            }
            int unavailable=0;
            for(SlotItem slot:snapshot.slots()) {
                if(slot.slot()<0 || slot.slot()>=inventory.getContainerSize()) continue;
                ItemStack stack=decode(player,slot,from);
                if(stack.has(DataComponents.CUSTOM_DATA) && stack.get(DataComponents.CUSTOM_DATA).contains(PLACEHOLDER)) unavailable++;
                inventory.setItem(slot.slot(),stack);
            }
            player.inventoryMenu.broadcastChanges(); player.containerMenu.broadcastChanges();
            if(!primary && state().holding.active()) state().reported=encodeAll(player);
            if(!primary && !state().holding.active()) {
                state().baseline=encodeAll(player);
                state().baselinePlayer=new java.lang.ref.WeakReference<>(player);
            }
            MmlfLog.debug("inventory","applied",Map.of("from",from,"version",snapshot.version(),"reason",String.valueOf(snapshot.reason()),"placeholders",unavailable));
        });
    }

    // ---- owner ----

    private static void reportChanges(net.minecraft.server.MinecraftServer server) {
        if(!state().holding.active() || state().returning!=null) return;
        ServerPlayer player=player(server); if(player==null) return;
        List<SlotItem> slots=encodeAll(player);
        if(slots.equals(state().reported)) return;
        verifyPlaceholders(slots);
        state().reported=slots;
        SessionLink.sendReliable("inventory",SessionLink.primaryId(),new InventorySnapshot(state().holding.leaseId(),self,state().holding.next(),player.getInventory().selected,slots,InventoryLease.UPDATE));
    }
    /** Render thread, when the hosted screen ends: the inventory goes back once the server has closed the menu. */
    static void returnLease(Runnable then) {
        if(!state().holding.active()) { then.run(); return; }
        state().returning=then; state().returnDeadline=System.nanoTime()+2_000_000_000L;
    }
    private static void finishReturn(Minecraft mc,net.minecraft.server.MinecraftServer server) {
        Runnable then=state().returning; if(then==null) return;
        ServerPlayer player=player(server);
        boolean closed=player==null || player.containerMenu==player.inventoryMenu;
        if(!closed && System.nanoTime()-state().returnDeadline<0) return;
        state().returning=null;
        if(player!=null && state().holding.active()) {
            List<SlotItem> slots=encodeAll(player); verifyPlaceholders(slots);
            SessionLink.sendReliable("inventory",SessionLink.primaryId(),new InventorySnapshot(state().holding.leaseId(),self,state().holding.next(),player.getInventory().selected,slots,InventoryLease.RETURN));
            MmlfLog.info("inventory","lease_returning",Map.of("leaseId",state().holding.leaseId(),"menuClosed",closed));
        }
        state().holding.end(); state().reported=null; state().baseline=null;
        mc.execute(then);
    }
    static void primaryLost() { state().holding.end(); state().returning=null; state().reported=null; state().baseline=null; synchronized(InventoryBridge.class) { state().primaryVersion=0; } }

    /**
     * Owner, outside a lease: this runtime's own game changed the mirrored inventory (picked up an item a machine
     * dropped, used one up). Before and after stacks let the primary settle the complete inventory change, even if
     * a matching item has been moved to a different slot.
     */
    private static void reportOwnChanges(net.minecraft.server.MinecraftServer server) {
        ServerPlayer player=player(server); if(player==null || state().baselinePlayer.get()!=player || state().baseline==null || state().holding.active()) return;
        List<SlotItem> current=encodeAll(player);
        List<SlotItem> after=new ArrayList<>(), before=new ArrayList<>();
        for(int i=0;i<current.size() && i<state().baseline.size();i++) if(!same(current.get(i),state().baseline.get(i))) { after.add(current.get(i)); before.add(state().baseline.get(i)); }
        if(after.isEmpty()) return;
        state().baseline=current;
        SessionLink.sendReliable("inventory",SessionLink.primaryId(),new InventorySnapshot(UUID.randomUUID().toString(),self,0,player.getInventory().selected,after,InventoryLease.OWNER_CHANGE,before));
        MmlfLog.info("inventory","owner_change_sent",Map.of("slots",after.size(),"slot",after.getFirst().slot(),"before",before.getFirst().count(),"after",after.getFirst().count()));
    }
    /** Primary: make the whole batch of debits possible before changing any real slot. */
    private static void applyOwnerChange(String owner,InventorySnapshot change) {
        if(change.before()==null || change.before().size()!=change.slots().size()) {
            MmlfLog.warn("inventory","owner_change_invalid",Map.of("owner",owner));
            return;
        }
        var mc=Minecraft.getInstance();
        var server=mc.getSingleplayerServer();
        if(server==null) return;
        server.execute(()->{
            ServerPlayer player=player(server);
            if(mc.getSingleplayerServer()!=server || player==null) return;
            if(state().lease.leased()) state().waitingChanges.add(new OwnerChange(server,player.getUUID(),owner,change));
            else settleOwnerChange(player,owner,change);
        });
    }

    private static void settleOwnerChange(ServerPlayer player,String owner,InventorySnapshot change) {
        if(change.leaseId()!=null && !appliedChanges.firstTime(owner+":"+change.leaseId())) return;
        var inventory=player.getInventory();
        int size=inventory.getContainerSize();
        Map<String,Integer> debits=new LinkedHashMap<>(), credits=new LinkedHashMap<>();
        Map<String,SlotItem> stacks=new HashMap<>();
        Set<Integer> seen=new HashSet<>();
        for(int i=0;i<change.slots().size();i++) {
            SlotItem before=change.before().get(i), after=change.slots().get(i);
            if(before==null || after==null || before.slot()!=after.slot() || after.slot()<0 || after.slot()>=size ||
                !seen.add(after.slot()) || before.count()<0 || after.count()<0 || before.count()>99 || after.count()>99) return;
            if(before.count()>0) debits.merge(identity(before),before.count(),Integer::sum);
            if(after.count()>0) {
                credits.merge(identity(after),after.count(),Integer::sum);
                stacks.putIfAbsent(identity(after),after);
            }
        }
        for(var entry:debits.entrySet()) {
            int matched=Math.min(entry.getValue(),credits.getOrDefault(entry.getKey(),0));
            entry.setValue(entry.getValue()-matched);
            if(matched>0) credits.merge(entry.getKey(),-matched,Integer::sum);
        }

        Map<Integer,Integer> take=new LinkedHashMap<>();
        // Check the originally affected slots first, then the rest of the inventory.
        Set<Integer> order=new LinkedHashSet<>();
        for(SlotItem slot:change.before()) order.add(slot.slot());
        for(int slot=0;slot<size;slot++) order.add(slot);
        for(int slot:order) {
            ItemStack stack=inventory.getItem(slot);
            if(stack.isEmpty()) continue;
            String key=identity(encode(player,slot,stack));
            int count=Math.min(debits.getOrDefault(key,0),stack.getCount());
            if(count>0) {
                debits.merge(key,-count,Integer::sum);
                take.put(slot,count);
            }
        }
        if(debits.values().stream().anyMatch(n->n>0)) {
            state().mirrored=null;
            MmlfLog.error("inventory","owner_change_conflict",Map.of("owner",owner,"slots",change.slots().size()));
            return;
        }

        record Credit(ItemStack stack,int count,int slot) {}
        List<Credit> add=new ArrayList<>();
        for(var credit:credits.entrySet()) {
            if(credit.getValue()<=0) continue;
            SlotItem source=stacks.get(credit.getKey());
            ItemStack stack=decode(player,source,owner);
            if(stack.isEmpty()) {
                state().mirrored=null;
                MmlfLog.error("inventory","owner_change_undecodable",Map.of("owner",owner));
                return;
            }
            add.add(new Credit(stack,credit.getValue(),source.slot()));
        }
        take.forEach((slot,count)->inventory.getItem(slot).shrink(count));
        for(Credit credit:add) {
            int remaining=credit.count();
            while(remaining>0) {
                int batch=Math.min(remaining,credit.stack().getMaxStackSize());
                ItemStack part=credit.stack().copyWithCount(batch);
                insertCredit(inventory,part,credit.slot());
                if(!part.isEmpty()) player.drop(part,false);
                remaining-=batch;
            }
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        state().mirrored=null;
        MmlfLog.info("inventory","owner_change_applied",Map.of("owner",owner,"slots",change.slots().size()));
    }

    /** Add to the main inventory without the creative-mode discard in Inventory.add. */
    private static void insertCredit(net.minecraft.world.entity.player.Inventory inventory,ItemStack stack,int preferred) {
        if(inventory.getItem(preferred).isEmpty()) {
            inventory.setItem(preferred,stack.split(stack.getCount()));
            return;
        }
        for(int slot=0;slot<inventory.items.size() && !stack.isEmpty();slot++) {
            ItemStack current=inventory.getItem(slot);
            if(current.isEmpty() || !ItemStack.isSameItemSameComponents(current,stack)) continue;
            int count=Math.min(stack.getCount(),Math.max(0,current.getMaxStackSize()-current.getCount()));
            current.grow(count);
            stack.shrink(count);
        }
        int slot=inventory.getFreeSlot();
        if(!stack.isEmpty() && slot>=0) inventory.setItem(slot,stack.split(stack.getCount()));
    }

    /** Same item in a slot, ignoring display data, which is derived. */
    private static boolean same(SlotItem a,SlotItem b) {
        if(a.count()<=0 && b.count()<=0) return true;
        return a.count()==b.count() && sameItem(a,b);
    }
    /** Same item type and data, whatever the count and whichever reference names it. */
    private static boolean sameItem(SlotItem a,SlotItem b) { return Objects.equals(identity(a),identity(b)); }
    private static String identity(SlotItem item) {
        if(item.encoded()==null) return item.reference();
        try {
            var json=JsonParser.parseString(item.encoded());
            if(json.isJsonObject()) { json.getAsJsonObject().remove("count"); return json.toString(); }
        } catch(RuntimeException ignored) { }
        return item.encoded();
    }
    /** A placeholder that left its slot means a mod moved it despite the slot lock; report it rather than hide it. */
    private static void verifyPlaceholders(List<SlotItem> slots) {
        for(SlotItem slot:slots) if(slot.display()!=null && slot.display().containsKey("placeholderSlot") && !String.valueOf(slot.slot()).equals(slot.display().get("placeholderSlot")))
            MmlfLog.error("inventory","placeholder_moved",Map.of("from",slot.display().get("placeholderSlot"),"to",slot.slot()));
    }

    /** Why the owner's held stack cannot stand for the primary's, or null when it can. */
    public static String heldProblem(ItemStack held,String expected) {
        if(isPlaceholder(held)) return "held_item_unavailable";
        var reference=RemoteItemRef.parse(expected);
        if(reference.isPresent()) {
            if(!reference.get().owner().equals(self)) return "held_item_unavailable";
            ItemStack real=RemoteItems.nativeStack(reference.get().token());
            return real!=null && real.getItem()==held.getItem() ? null : "inventory_not_synced";
        }
        String wanted=expected==null?"minecraft:air":expected;
        var key=net.minecraft.resources.ResourceLocation.tryParse(wanted);
        if(key==null || !BuiltInRegistries.ITEM.containsKey(key)) return "held_item_unavailable";
        return BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(wanted) ? null : "inventory_not_synced";
    }

    // ---- encoding ----

    private static List<SlotItem> encodeAll(ServerPlayer player) {
        var inventory=player.getInventory(); List<SlotItem> out=new ArrayList<>(inventory.getContainerSize());
        for(int i=0;i<inventory.getContainerSize();i++) out.add(encode(player,i,inventory.getItem(i)));
        return out;
    }
    private static SlotItem encode(ServerPlayer player,int slot,ItemStack stack) {
        if(stack.isEmpty()) return new SlotItem(slot,null,null,0,null);
        CustomData data=stack.get(DataComponents.CUSTOM_DATA); CompoundTag tag=data==null?null:data.copyTag();
        if(tag!=null && tag.contains(PLACEHOLDER)) {
            SlotItem original=Wire.JSON.fromJson(tag.getString(PLACEHOLDER),SlotItem.class);
            Map<String,String> display=new HashMap<>(original.display()==null?Map.of():original.display());
            display.put("placeholderSlot",String.valueOf(original.slot()));
            return new SlotItem(slot,original.encoded(),original.reference(),original.count(),display);
        }
        if(tag!=null && RemoteItemRef.parse(tag.getString("mmlf_item")).isPresent())
            return new SlotItem(slot,RemoteItems.payload(stack),tag.getString("mmlf_item"),stack.getCount(),RemoteItems.display(stack));
        if(tag!=null && tag.contains(ENCODED)) return new SlotItem(slot,tag.getString(ENCODED),null,stack.getCount(),null);
        if(!primary && !portable(stack)) {
            Kept previous=state().kept.get(slot); UUID token;
            if(previous!=null && ItemStack.matches(previous.stack(),stack)) token=previous.token();
            else { token=RemoteItems.keep(stack); state().kept.put(slot,new Kept(token,stack.copy())); }
            return new SlotItem(slot,RemoteItems.payload(stack,player.registryAccess()),new RemoteItemRef(self,token).encode(),stack.getCount(),RemoteItems.describe(stack,player));
        }
        var json=ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack).result();
        if(json.isEmpty()) { MmlfLog.error("inventory","encode_failed",Map.of("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())); return new SlotItem(slot,null,null,0,null); }
        return new SlotItem(slot,json.get().toString(),null,stack.getCount(),Map.of("name",stack.getHoverName().getString()));
    }
    /** Items whose type and components are all vanilla can be recreated by any runtime of the same version. */
    static boolean portable(ItemStack stack) {
        if(!BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft")) return false;
        for(var entry:stack.getComponentsPatch().entrySet()) {
            var key=BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
            if(key==null || !key.getNamespace().equals("minecraft")) return false;
        }
        return true;
    }
    private static ItemStack decode(ServerPlayer player,SlotItem slot,String from) {
        if(slot.count()<=0 || (slot.encoded()==null && slot.reference()==null)) return ItemStack.EMPTY;
        if(slot.reference()!=null) {
            var reference=RemoteItemRef.parse(slot.reference());
            if(reference.isPresent() && reference.get().owner().equals(self)) {
                ItemStack real=RemoteItems.resolveOwned(reference.get().token(),slot.encoded(),player.registryAccess());
                if(real!=null) { real.setCount(slot.count()); if(!primary) state().kept.put(slot.slot(),new Kept(reference.get().token(),real.copy())); return real; }
                MmlfLog.warn("inventory","reference_unavailable",Map.of("reference",slot.reference()));
            } else if(reference.isPresent() && primary) return RemoteItems.proxy(reference.get(),slot.display(),slot.count(),slot.encoded());
            return placeholder(slot);
        }
        try {
            var parsed=ItemStack.CODEC.parse(player.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString(slot.encoded())).result();
            if(parsed.isPresent()) return parsed.get();
        } catch(RuntimeException e) { MmlfLog.debug("inventory","decode_failed",Map.of("exception",e.toString())); }
        return primary ? encodedProxy(slot,from) : placeholder(slot);
    }
    /** Owner: a primary item this runtime cannot load. It keeps the primary's slot data and cannot be moved. */
    private static ItemStack placeholder(SlotItem slot) {
        ItemStack stack=new ItemStack(Items.BARRIER,Math.max(1,Math.min(64,slot.count())));
        CompoundTag tag=new CompoundTag();
        tag.putString(PLACEHOLDER,Wire.JSON.toJson(new SlotItem(slot.slot(),slot.encoded(),slot.reference(),slot.count(),slot.display()==null?null:withoutSlot(slot.display()))));
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        String name=slot.display()==null?null:slot.display().get("name");
        stack.set(DataComponents.CUSTOM_NAME,Component.literal((name==null?"?":name)+" [主端物品]"));
        return stack;
    }
    private static Map<String,String> withoutSlot(Map<String,String> display) { var copy=new HashMap<>(display); copy.remove("placeholderSlot"); return copy; }
    /** Primary: an owner's stack this runtime could not decode is kept verbatim, still owned by that runtime. */
    private static ItemStack encodedProxy(SlotItem slot,String owner) {
        ItemStack stack=new ItemStack(Items.PAPER,Math.max(1,Math.min(64,slot.count())));
        CompoundTag tag=new CompoundTag(); tag.putString(ENCODED,slot.encoded()); tag.putString(ENCODED_OWNER,String.valueOf(owner));
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        String name=slot.display()==null?null:slot.display().get("name");
        stack.set(DataComponents.CUSTOM_NAME,Component.literal((name==null?"?":name)+" [远端物品 / "+owner+"]"));
        return stack;
    }
    /**
     * Whether a stack only stands for an item of another runtime (a proxy, an encoded proxy or a placeholder). Its base
     * item (paper, a barrier) is not what it is, so recipes, trades and other uses of that base item must not take it.
     */
    public static boolean standsIn(ItemStack stack) {
        if(stack==null || stack.isEmpty()) return false;
        CustomData data=stack.get(DataComponents.CUSTOM_DATA); if(data==null) return false;
        return data.contains(PLACEHOLDER) || data.contains(ENCODED) || data.contains("mmlf_item");
    }
    public static boolean isPlaceholder(ItemStack stack) {
        CustomData data=stack.get(DataComponents.CUSTOM_DATA); return data!=null && data.contains(PLACEHOLDER);
    }
    private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
        var players=server.getPlayerList().getPlayers(); return players.isEmpty()?null:players.getFirst();
    }
}
