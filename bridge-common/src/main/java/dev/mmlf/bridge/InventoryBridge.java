package dev.mmlf.bridge;

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
    // primary
    private static final InventoryLease lease=new InventoryLease();
    private static List<SlotItem> mirrored; private static long mirroredAt; private static int tickCount;
    // owner
    private static final InventoryLease.Holding holding=new InventoryLease.Holding();
    private static List<SlotItem> reported;
    /** Owner: the inventory as the primary last mirrored it; anything else in it was changed by this runtime. */
    private static List<SlotItem> baseline;
    private record Kept(UUID token,ItemStack stack) {}
    private static final Map<Integer,Kept> kept=new HashMap<>();
    private static Runnable returning; private static long returnDeadline;
    private InventoryBridge() {}

    public static void initialize(String runtime,boolean main) { self=runtime; primary=main; }

    /** Primary server thread: while an owner holds the inventory, nothing here may change it. */
    public static boolean locked(Player player) { return primary && player instanceof ServerPlayer && lease.leased(); }

    public static void tick(Minecraft mc) {
        var server=mc.getSingleplayerServer(); if(server==null || self==null) return;
        tickCount++;
        if(primary) { if(tickCount%2==0) server.execute(()->mirror(server)); return; }
        if(holding.active()) server.execute(()->reportChanges(server));
        else if(returning==null && baseline!=null) server.execute(()->reportOwnChanges(server));
        if(returning!=null) server.execute(()->finishReturn(mc,server));
    }

    // ---- primary ----

    private static void mirror(net.minecraft.server.MinecraftServer server) {
        ServerPlayer player=player(server); if(player==null || lease.leased()) return;
        List<SlotItem> slots=encodeAll(player);
        long now=System.nanoTime();
        if(slots.equals(mirrored) && now-mirroredAt<5_000_000_000L) return;
        mirrored=slots; mirroredAt=now;
        BridgeCore.send("inventory","*",new InventorySnapshot(null,self,lease.nextVersion(),player.getInventory().selected,slots,InventoryLease.MIRROR));
    }
    /** A hosted screen opened: the owner gets the inventory as it is now, which is also the rollback point. */
    static void grant(String owner) {
        var server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{
            ServerPlayer player=player(server); if(player==null) return;
            String id=UUID.randomUUID().toString();
            var current=new InventorySnapshot(id,owner,lease.nextVersion(),player.getInventory().selected,encodeAll(player),InventoryLease.GRANT);
            if(!lease.grant(owner,id,current)) { MmlfLog.warn("inventory","grant_refused",Map.of("owner",owner,"holder",String.valueOf(lease.holder()))); return; }
            BridgeCore.send("inventory",owner,current);
            MmlfLog.info("inventory","lease_granted",Map.of("owner",owner,"leaseId",id,"version",current.version()));
        });
    }
    /** The owner's screen ended; a lease it did not return is rolled back to the last confirmed inventory. */
    static void ownerEnded(String owner,String reason) {
        if(!primary || owner==null) return;
        InventorySnapshot restore=lease.rollback(owner);
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
            var verdict=lease.accept(message.source(),snapshot);
            MmlfLog.debug("inventory","owner_snapshot",Map.of("owner",message.source(),"version",snapshot.version(),"reason",String.valueOf(snapshot.reason()),"verdict",verdict.name()));
            if(verdict==InventoryLease.Verdict.REJECTED) { MmlfLog.warn("inventory","snapshot_rejected",Map.of("owner",message.source(),"version",snapshot.version())); return; }
            if(verdict==InventoryLease.Verdict.RETURNED) MmlfLog.info("inventory","lease_returned",Map.of("owner",message.source(),"version",snapshot.version()));
            applyOnServer(snapshot,message.source());
            return;
        }
        if(!message.source().equals(BridgeCore.primaryId()) || !self.equals(snapshot.authority()) && !InventoryLease.MIRROR.equals(snapshot.reason())) return;
        if(InventoryLease.GRANT.equals(snapshot.reason())) {
            if(!GuiHost.hosting()) { MmlfLog.warn("inventory","grant_without_screen",Map.of("leaseId",String.valueOf(snapshot.leaseId()))); return; }
            holding.begin(snapshot.leaseId(),snapshot.version());
            MmlfLog.info("inventory","lease_held",Map.of("leaseId",snapshot.leaseId(),"version",snapshot.version()));
        } else if(holding.active()) return;
        applyOnServer(snapshot,BridgeCore.primaryId());
    }
    private static void applyOnServer(InventorySnapshot snapshot,String from) {
        var server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{
            ServerPlayer player=player(server); if(player==null) return;
            var inventory=player.getInventory();
            if(!primary) kept.clear();
            int unavailable=0;
            for(SlotItem slot:snapshot.slots()) {
                if(slot.slot()<0 || slot.slot()>=inventory.getContainerSize()) continue;
                ItemStack stack=decode(player,slot,from);
                if(stack.has(DataComponents.CUSTOM_DATA) && stack.get(DataComponents.CUSTOM_DATA).contains(PLACEHOLDER)) unavailable++;
                inventory.setItem(slot.slot(),stack);
            }
            if(!primary && snapshot.selected()>=0 && snapshot.selected()<9) inventory.selected=snapshot.selected();
            player.inventoryMenu.broadcastChanges(); player.containerMenu.broadcastChanges();
            if(!primary && holding.active()) reported=encodeAll(player);
            if(!primary && !holding.active()) baseline=encodeAll(player);
            MmlfLog.debug("inventory","applied",Map.of("from",from,"version",snapshot.version(),"reason",String.valueOf(snapshot.reason()),"placeholders",unavailable));
        });
    }

    // ---- owner ----

    private static void reportChanges(net.minecraft.server.MinecraftServer server) {
        if(!holding.active() || returning!=null) return;
        ServerPlayer player=player(server); if(player==null) return;
        List<SlotItem> slots=encodeAll(player);
        if(slots.equals(reported)) return;
        verifyPlaceholders(slots);
        reported=slots;
        BridgeCore.send("inventory",BridgeCore.primaryId(),new InventorySnapshot(holding.leaseId(),self,holding.next(),player.getInventory().selected,slots,InventoryLease.UPDATE));
    }
    /** Render thread, when the hosted screen ends: the inventory goes back once the server has closed the menu. */
    static void returnLease(Runnable then) {
        if(!holding.active()) { then.run(); return; }
        returning=then; returnDeadline=System.nanoTime()+2_000_000_000L;
    }
    private static void finishReturn(Minecraft mc,net.minecraft.server.MinecraftServer server) {
        Runnable then=returning; if(then==null) return;
        ServerPlayer player=player(server);
        boolean closed=player==null || player.containerMenu==player.inventoryMenu;
        if(!closed && System.nanoTime()-returnDeadline<0) return;
        returning=null;
        if(player!=null && holding.active()) {
            List<SlotItem> slots=encodeAll(player); verifyPlaceholders(slots);
            BridgeCore.send("inventory",BridgeCore.primaryId(),new InventorySnapshot(holding.leaseId(),self,holding.next(),player.getInventory().selected,slots,InventoryLease.RETURN));
            MmlfLog.info("inventory","lease_returning",Map.of("leaseId",holding.leaseId(),"menuClosed",closed));
        }
        holding.end(); reported=null; baseline=null;
        mc.execute(then);
    }
    static void primaryLost() { holding.end(); returning=null; reported=null; baseline=null; }

    /**
     * Owner, outside a lease: this runtime's own game changed the mirrored inventory (picked up an item a machine
     * dropped, used one up). The changed slots go to the primary with what they held before; the primary keeps
     * each change only if that slot still holds the same, so nothing is applied twice or over a newer change.
     */
    private static void reportOwnChanges(net.minecraft.server.MinecraftServer server) {
        ServerPlayer player=player(server); if(player==null || baseline==null || holding.active()) return;
        List<SlotItem> current=encodeAll(player);
        List<SlotItem> after=new ArrayList<>(), before=new ArrayList<>();
        for(int i=0;i<current.size() && i<baseline.size();i++) if(!same(current.get(i),baseline.get(i))) { after.add(current.get(i)); before.add(baseline.get(i)); }
        if(after.isEmpty()) return;
        baseline=current;
        BridgeCore.send("inventory",BridgeCore.primaryId(),new InventorySnapshot(null,self,0,player.getInventory().selected,after,InventoryLease.OWNER_CHANGE,before));
        MmlfLog.info("inventory","owner_change_sent",Map.of("slots",after.size(),"slot",after.getFirst().slot(),"before",before.getFirst().count(),"after",after.getFirst().count()));
    }
    /** Primary: applies an owner's own changes slot by slot, each only over the content the owner saw. */
    private static void applyOwnerChange(String owner,InventorySnapshot change) {
        if(change.before()==null || change.before().size()!=change.slots().size()) { MmlfLog.warn("inventory","owner_change_invalid",Map.of("owner",owner)); return; }
        if(lease.leased()) { MmlfLog.error("inventory","owner_change_during_lease",Map.of("owner",owner,"holder",String.valueOf(lease.holder()))); return; }
        var server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{
            ServerPlayer player=player(server); if(player==null) return;
            var inventory=player.getInventory(); int applied=0;
            for(int i=0;i<change.slots().size();i++) {
                SlotItem after=change.slots().get(i), before=change.before().get(i);
                if(after.slot()!=before.slot() || after.slot()<0 || after.slot()>=inventory.getContainerSize()) continue;
                ItemStack current=inventory.getItem(after.slot());
                SlotItem now=encode(player,after.slot(),current);
                ItemStack next=applyDelta(player,owner,now,current,before,after);
                if(next==null) { MmlfLog.error("inventory","owner_change_conflict",Map.of("owner",owner,"slot",after.slot(),"before",before.count(),"after",after.count(),"now",now.count())); continue; }
                inventory.setItem(after.slot(),next); applied++;
                MmlfLog.debug("inventory","owner_change_slot",Map.of("slot",after.slot(),"before",before.count(),"after",after.count(),"now",inventory.getItem(after.slot()).getCount()));
            }
            player.inventoryMenu.broadcastChanges(); player.containerMenu.broadcastChanges();
            MmlfLog.info("inventory","owner_change_applied",Map.of("owner",owner,"slots",change.slots().size(),"applied",applied));
        });
    }
    /**
     * The primary's new stack for one slot an owner changed, or null when the slot now holds something else. Counts
     * move as differences (a placement takes one, a pickup adds some), so a count both sides saw differently for a
     * moment does not lose the change.
     */
    private static ItemStack applyDelta(ServerPlayer player,String owner,SlotItem now,ItemStack current,SlotItem before,SlotItem after) {
        boolean beforeEmpty=before.count()<=0, afterEmpty=after.count()<=0, nowEmpty=now.count()<=0;
        if(!beforeEmpty && !afterEmpty && sameItem(before,after)) {
            if(nowEmpty || !sameItem(now,before)) return null;
            int count=now.count()+after.count()-before.count();
            return count<=0 ? ItemStack.EMPTY : current.copyWithCount(Math.min(count,current.getMaxStackSize()));
        }
        if(afterEmpty) return !beforeEmpty && !nowEmpty && sameItem(now,before) ? (now.count()>before.count()?current.copyWithCount(now.count()-before.count()):ItemStack.EMPTY) : null;
        if(nowEmpty) return decode(player,after,owner);
        if(sameItem(now,after)) { ItemStack added=decode(player,after,owner); return current.copyWithCount(Math.min(now.count()+added.getCount(),current.getMaxStackSize())); }
        return !beforeEmpty && sameItem(now,before) && now.count()==before.count() ? decode(player,after,owner) : null;
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
    static String heldProblem(ItemStack held,String expected) {
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
            Kept previous=kept.get(slot); UUID token;
            if(previous!=null && ItemStack.matches(previous.stack(),stack)) token=previous.token();
            else { token=RemoteItems.keep(stack); kept.put(slot,new Kept(token,stack.copy())); }
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
                if(real!=null) { real.setCount(slot.count()); if(!primary) kept.put(slot.slot(),new Kept(reference.get().token(),real.copy())); return real; }
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
    public static boolean isPlaceholder(ItemStack stack) {
        CustomData data=stack.get(DataComponents.CUSTOM_DATA); return data!=null && data.contains(PLACEHOLDER);
    }
    private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
        var players=server.getPlayerList().getPlayers(); return players.isEmpty()?null:players.getFirst();
    }
}
