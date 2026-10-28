package dev.mmlf.bridge;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import java.util.*;

/**
 * Remote mod items in the primary's creative inventory, laid out as their owner lays them out. Each owner lists its
 * creative tabs: its mods' own tabs appear on the primary under their own names and icons, and its items in vanilla
 * tabs are added to the same vanilla tabs; items in no tab get one tab per owner. Every icon is rendered natively by
 * the owner into one atlas before the list is sent, so the tabs open with all icons in place.
 *
 * Tabs cannot be registered once the game has started, so the primary registers {@link #POOL} empty tabs up front and
 * names them when a list arrives; an empty tab is not shown. A proxy taken from a tab reaches the owner through the
 * mirrored inventory, where it is the owner's real item again.
 */
public final class RemoteCatalog {
    public static final int POOL=16;
    private static final int CELL=32, COLUMNS=32, MAX_ICONS=2048;
    /** {@code reference} is null for a vanilla stack, which the primary loads from {@code payload} itself. */
    private record Entry(String reference,Map<String,String> display,String payload,int atlas) {}
    private record Tab(String key,String title,int icon,List<Integer> items) {}
    private record Catalog(String atlasFile,int atlasWidth,int atlasHeight,List<Entry> entries,List<Tab> tabs) {}

    // owner: one reference per distinct stack, so repeated requests do not keep new stacks
    private static final Map<String,UUID> tokens=new HashMap<>();
    private static int atlasSequence;

    // primary
    private record Shown(String title,ItemStack icon,List<ItemStack> items) {}
    private static final class Owned {
        final Map<String,Integer> atlasIndex=new HashMap<>(); ResourceLocation atlas; int width, height;
        final List<Tab> tabs=new ArrayList<>(); final List<ItemStack> stacks=new ArrayList<>();
    }
    private static final Map<String,Owned> owners=new TreeMap<>();
    private static final Shown[] pool=new Shown[POOL];
    private static final MutableComponent[] titles=new MutableComponent[POOL];
    private static final Map<ResourceLocation,List<ItemStack>> vanillaAdditions=new HashMap<>();
    private static long lastRequest;
    private RemoteCatalog() {}

    public static ResourceLocation poolKey(int index) { return ResourceLocation.fromNamespaceAndPath("mmlf_bridge","remote_"+index); }
    /** Loader entry points register pool tab {@code index} with this configuration. */
    public static CreativeModeTab buildPool(int index,CreativeModeTab.Builder builder) {
        titles[index]=Component.empty();
        return builder.title(titles[index]).icon(()->{ Shown s=pool[index]; return s==null?new ItemStack(Items.PAPER):s.icon().copy(); })
            .displayItems((parameters,output)->{ Shown s=pool[index]; if(s!=null) for(ItemStack stack:s.items()) output.accept(stack.copy()); })
            .build();
    }
    /** Remote items an owner placed in this vanilla tab; called after the tab builds its own contents. */
    public static List<ItemStack> additions(CreativeModeTab tab) {
        if(!BridgeCore.isPrimary() || vanillaAdditions.isEmpty()) return List.of();
        var key=BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
        return key==null ? List.of() : vanillaAdditions.getOrDefault(key,List.of());
    }

    /** Primary, every tick: asks each peer without a list, at most every five seconds. */
    static void tick(Minecraft mc,Collection<String> peers) {
        if(mc.player==null) return;
        long now=System.nanoTime(); if(now-lastRequest<5_000_000_000L) return; lastRequest=now;
        for(String peer:peers) if(!peer.equals(BridgeCore.runtimeId()) && !owners.containsKey(peer))
            BridgeCore.send("event",peer,new Event("mmlf:catalog_request",Map.of()));
    }
    static void forget(String runtime) {
        Owned removed=owners.remove(runtime);
        if(removed!=null && removed.atlas!=null) Minecraft.getInstance().getTextureManager().release(removed.atlas);
        layout();
    }

    // ---- owner ----

    /** Owner, render thread: lists this runtime's creative tabs and renders their icons, once its world is loaded. */
    static void answer(String primary) {
        Minecraft mc=Minecraft.getInstance(); if(mc.level==null || mc.player==null) return;
        var registries=mc.level.registryAccess();
        CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(),mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks(),registries);
        List<Entry> entries=new ArrayList<>(); List<ItemStack> iconStacks=new ArrayList<>();
        Map<String,Integer> byPayload=new HashMap<>(); Set<Item> listed=new HashSet<>();
        List<Tab> tabs=new ArrayList<>();
        for(CreativeModeTab tab:CreativeModeTabs.allTabs()) {
            if(tab.getType()!=CreativeModeTab.Type.CATEGORY) continue;
            var key=BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if(key==null || key.getNamespace().equals("mmlf_bridge")) continue;
            boolean vanillaTab=key.getNamespace().equals("minecraft");
            List<Integer> items=new ArrayList<>();
            for(ItemStack stack:tab.getDisplayItems()) {
                if(vanillaTab && InventoryBridge.portable(stack)) continue;
                int index=entry(stack,entries,iconStacks,byPayload,registries,mc);
                if(index>=0) { items.add(index); listed.add(stack.getItem()); }
            }
            if(items.isEmpty()) continue;
            int icon=vanillaTab?-1:entry(tab.getIconItem(),entries,iconStacks,byPayload,registries,mc);
            tabs.add(new Tab(key.toString(),tab.getDisplayName().getString(),icon,items));
        }
        // Items no tab shows, such as the test mods' blocks, are still reachable in one tab of their own.
        List<Integer> rest=new ArrayList<>();
        for(Item item:BuiltInRegistries.ITEM) {
            var key=BuiltInRegistries.ITEM.getKey(item);
            if(item==Items.AIR || listed.contains(item) || key.getNamespace().equals("minecraft") || key.getNamespace().equals("mmlf_bridge")) continue;
            int index=entry(item.getDefaultInstance(),entries,iconStacks,byPayload,registries,mc);
            if(index>=0) rest.add(index);
        }
        if(!rest.isEmpty()) tabs.add(new Tab("mmlf_bridge:"+BridgeCore.runtimeId(),"MMLF: "+BridgeCore.runtimeId(),rest.getFirst(),rest));

        String atlasFile=null; int width=0, height=0;
        if(!iconStacks.isEmpty()) {
            width=CELL*COLUMNS; height=CELL*((iconStacks.size()+COLUMNS-1)/COLUMNS);
            try {
                var pixels=VisualLayers.renderAtlas(iconStacks,CELL,COLUMNS,width,height);
                String name=BridgeCore.runtimeId()+"-atlas-"+(++atlasSequence);
                try(FrameFile file=FrameFile.writer(BridgeCore.session().framePath(name),width*height)) {
                    var depth=org.lwjgl.BufferUtils.createByteBuffer(width*height*4);
                    if(file.publish(1,width,height,pixels,depth,new byte[0])<0) throw new java.io.IOException("atlas slots busy");
                }
                atlasFile=name;
            } catch(Exception e) { MmlfLog.error("items","atlas_failed",Map.of("exception",e.toString())); }
        }
        String encoded=Wire.JSON.toJson(new Catalog(atlasFile,width,height,entries,tabs));
        if(encoded.length()>900_000) {
            // Very large mod sets: tooltips are dropped before the control message limit is reached.
            List<Entry> lean=new ArrayList<>();
            for(Entry e:entries) { var d=e.display()==null?null:new HashMap<>(e.display()); if(d!=null) d.remove("tooltip"); lean.add(new Entry(e.reference(),d,e.payload(),e.atlas())); }
            encoded=Wire.JSON.toJson(new Catalog(atlasFile,width,height,lean,tabs));
            MmlfLog.warn("items","catalog_tooltips_dropped",Map.of("length",encoded.length()));
        }
        BridgeCore.send("event",primary,new Event("mmlf:catalog",Map.of("catalog",encoded)));
        MmlfLog.info("items","catalog_sent",Map.of("entries",entries.size(),"tabs",tabs.size(),"icons",iconStacks.size(),"bytes",encoded.length()));
    }
    private static int entry(ItemStack stack,List<Entry> entries,List<ItemStack> icons,Map<String,Integer> byPayload,net.minecraft.core.HolderLookup.Provider registries,Minecraft mc) {
        if(stack==null || stack.isEmpty()) return -1;
        ItemStack one=stack.copyWithCount(1);
        String payload=RemoteItems.payload(one,registries); if(payload==null) return -1;
        Integer known=byPayload.get(payload); if(known!=null) return known;
        Entry entry;
        if(InventoryBridge.portable(one)) entry=new Entry(null,null,payload,-1);
        else {
            UUID token=tokens.computeIfAbsent(payload,p->RemoteItems.keep(one));
            int atlas=icons.size()<MAX_ICONS?icons.size():-1;
            if(atlas>=0) icons.add(one);
            entry=new Entry(new RemoteItemRef(BridgeCore.runtimeId(),token).encode(),RemoteItems.describe(one,mc.level,mc.player),payload,atlas);
        }
        entries.add(entry); byPayload.put(payload,entries.size()-1);
        return entries.size()-1;
    }

    // ---- primary ----

    /** Network thread: a peer's list replaces its previous one once its atlas is loaded on the render thread. */
    static void receive(String owner,String encoded) {
        Catalog catalog;
        try { catalog=Wire.JSON.fromJson(encoded,Catalog.class); } catch(RuntimeException e) { MmlfLog.warn("items","catalog_invalid",Map.of("owner",owner)); return; }
        if(catalog==null || catalog.entries()==null || catalog.tabs()==null) return;
        Minecraft mc=Minecraft.getInstance();
        mc.execute(()->install(mc,owner,catalog));
    }
    private static void install(Minecraft mc,String owner,Catalog catalog) {
        if(mc.level==null) return;
        Owned owned=new Owned(); var registries=mc.level.registryAccess();
        for(Entry entry:catalog.entries()) {
            ItemStack stack=ItemStack.EMPTY;
            try {
                if(entry.reference()==null) stack=ItemStack.CODEC.parse(registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),com.google.gson.JsonParser.parseString(entry.payload())).result().orElse(ItemStack.EMPTY);
                else {
                    var reference=RemoteItemRef.parse(entry.reference());
                    if(reference.isPresent() && reference.get().owner().equals(owner)) {
                        stack=RemoteItems.proxy(reference.get(),entry.display(),1,entry.payload());
                        if(entry.atlas()>=0) owned.atlasIndex.put(entry.reference(),entry.atlas());
                    }
                }
            } catch(RuntimeException e) { MmlfLog.debug("items","catalog_entry_skipped",Map.of("owner",owner,"exception",e.toString())); }
            owned.stacks.add(stack);
        }
        owned.tabs.addAll(catalog.tabs());
        if(catalog.atlasFile()!=null) loadAtlas(mc,owner,owned,catalog);
        Owned previous=owners.put(owner,owned);
        if(previous!=null && previous.atlas!=null) mc.getTextureManager().release(previous.atlas);
        MmlfLog.info("items","catalog_received",Map.of("owner",owner,"entries",owned.stacks.size(),"tabs",owned.tabs.size(),"atlas",owned.atlas!=null));
        layout();
        refreshInventory(mc,owned);
    }
    private static void loadAtlas(Minecraft mc,String owner,Owned owned,Catalog catalog) {
        int w=catalog.atlasWidth(), h=catalog.atlasHeight();
        try(FrameFile file=FrameFile.reader(BridgeCore.session().framePath(catalog.atlasFile()),w*h)) {
            var latest=file.readLatest(null,null);
            if(latest==null || latest.width()!=w || latest.height()!=h) throw new java.io.IOException("atlas unavailable");
            var bytes=latest.pixels().color(); var image=new NativeImage(w,h,false);
            for(int row=0;row<h;row++) for(int col=0;col<w;col++) {
                int i=((h-1-row)*w+col)*4;
                image.setPixelRGBA(col,row,(bytes.get(i)&255)|((bytes.get(i+1)&255)<<8)|((bytes.get(i+2)&255)<<16)|((bytes.get(i+3)&255)<<24));
            }
            owned.atlas=mc.getTextureManager().register("mmlf-atlas-"+owner,new DynamicTexture(image));
            owned.width=w; owned.height=h;
        } catch(Exception e) { MmlfLog.warn("items","atlas_unavailable",Map.of("owner",owner,"exception",e.toString())); }
    }
    /** Proxies already held take the new display data (a language change renames them); counts are kept. */
    private static void refreshInventory(Minecraft mc,Owned owned) {
        Map<String,ItemStack> byReference=new HashMap<>();
        for(ItemStack stack:owned.stacks) { if(stack.isEmpty()) continue; String held=RemoteItems.held(stack); if(RemoteItemRef.parse(held).isPresent()) byReference.put(held,stack); }
        var server=mc.getSingleplayerServer(); if(server==null || byReference.isEmpty()) return;
        server.execute(()->{
            var players=server.getPlayerList().getPlayers(); if(players.isEmpty() || InventoryBridge.locked(players.getFirst())) return;
            var inventory=players.getFirst().getInventory(); int renamed=0;
            for(int i=0;i<inventory.getContainerSize();i++) {
                ItemStack current=inventory.getItem(i); ItemStack fresh=byReference.get(RemoteItems.held(current));
                if(current.isEmpty() || fresh==null || ItemStack.isSameItemSameComponents(current,fresh)) continue;
                inventory.setItem(i,fresh.copyWithCount(Math.min(current.getCount(),fresh.getMaxStackSize()))); renamed++;
            }
            if(renamed>0) { players.getFirst().inventoryMenu.broadcastChanges(); MmlfLog.info("items","proxies_refreshed",Map.of("slots",renamed)); }
        });
    }
    /** Assigns owners' mod tabs to pool slots, collects vanilla-tab additions, and lets vanilla rebuild every tab. */
    private static void layout() {
        Arrays.fill(pool,null); vanillaAdditions.clear(); int slot=0;
        for(var entry:owners.entrySet()) {
            Owned owned=entry.getValue();
            for(Tab tab:owned.tabs) {
                List<ItemStack> items=new ArrayList<>();
                for(int index:tab.items()) if(index>=0 && index<owned.stacks.size() && !owned.stacks.get(index).isEmpty()) items.add(owned.stacks.get(index));
                if(items.isEmpty()) continue;
                ResourceLocation key=ResourceLocation.tryParse(tab.key());
                if(key!=null && key.getNamespace().equals("minecraft")) { vanillaAdditions.computeIfAbsent(key,k->new ArrayList<>()).addAll(items); continue; }
                if(slot>=POOL) { MmlfLog.warn("items","catalog_tab_pool_full",Map.of("owner",entry.getKey(),"tab",tab.key())); continue; }
                ItemStack icon=tab.icon()>=0 && tab.icon()<owned.stacks.size() && !owned.stacks.get(tab.icon()).isEmpty() ? owned.stacks.get(tab.icon()) : items.getFirst();
                pool[slot]=new Shown(tab.title(),icon,items);
                if(titles[slot]!=null) { titles[slot].getSiblings().clear(); titles[slot].append(Component.literal(tab.title())); }
                slot++;
            }
        }
        for(int i=slot;i<POOL;i++) if(titles[i]!=null) titles[i].getSiblings().clear();
        // Vanilla rebuilds tabs and the creative search index only when its parameters change; forgetting them
        // makes the creative screen rebuild everything natively the next time it opens or ticks.
        dev.mmlf.bridge.mixin.CreativeTabsAccessor.mmlf$setCachedParameters(null);
    }
    /** Primary: the catalogue proxy for an owner's item type, or null. */
    static ItemStack proxyFor(String itemId) {
        for(Owned owned:owners.values()) for(ItemStack stack:owned.stacks) {
            var display=RemoteItems.display(stack);
            if(display!=null && itemId.equals(display.get("id"))) return stack.copy();
        }
        return null;
    }
    /** Draws a proxy's icon from its owner's atlas; false when the atlas has no icon for it. */
    static boolean drawAtlasIcon(GuiGraphics graphics,String owner,String reference,int x,int y) {
        Owned owned=owners.get(owner); if(owned==null || owned.atlas==null) return false;
        Integer index=owned.atlasIndex.get(reference); if(index==null) return false;
        int u=(index%COLUMNS)*CELL, v=(index/COLUMNS)*CELL;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend(); com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        graphics.blit(owned.atlas,x,y,16,16,u,v,CELL,CELL,owned.width,owned.height);
        return true;
    }
}
