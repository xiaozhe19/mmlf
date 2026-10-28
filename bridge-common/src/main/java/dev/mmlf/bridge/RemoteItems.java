package dev.mmlf.bridge;
import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

/**
 * Native ItemStacks remain in the owner JVM. A labelled proxy represents them on the primary; it carries the owner's
 * display data (name, stack size, durability, tooltip), never the item's behaviour.
 */
public final class RemoteItems {
 private static RuntimeClient link; private static boolean primary;
 private static String selectedReference="minecraft:air";
 public static String selectedReference() {return selectedReference;}
 /**
  * Owner: the primary selected another hotbar slot. Only the selection follows; the slot's contents come from the
  * mirrored inventory, so this runtime's hand and the primary's always show the same stack.
  */
 public static void updateHeld(String encoded,String slot) {
  selectedReference=encoded;
  var mc=Minecraft.getInstance();
  try {int index=Integer.parseInt(slot);if(mc.player!=null && index>=0 && index<9) mc.player.getInventory().selected=index;}
  catch(NumberFormatException | NullPointerException ignored) {}
 }
 public static boolean nativeHandSelected() {return !primary && RemoteItemRef.parse(selectedReference).map(ref->ref.owner().equals(link.id)).orElse(false);}

 private static final Map<UUID,ItemStack> nativeItems=java.util.Collections.synchronizedMap(new LinkedHashMap<>());
 public static void initialize(RuntimeClient client,boolean main) {link=client;primary=main;}
 public static void pick(ServerPlayer player,BlockPos pos) {
  var state=player.level().getBlockState(pos); ItemStack item=state.getBlock().getCloneItemStack(player.level(),pos,state);
  if(item.isEmpty()) return;
  if(primary) {give(item.copy());return;}
  UUID token=keep(item);
  String id=BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
  var values=new HashMap<String,String>(Map.of("owner",link.id,"token",token.toString(),"item",id,"name",item.getHoverName().getString(),"display",Wire.JSON.toJson(describe(item,player))));
  String payload=payload(item,player.registryAccess()); if(payload!=null) values.put("payload",payload);
  link.send("event","*",new Event("mmlf:picked",values));
  MmlfLog.info("items","native_picked",Map.of("item",id,"position",pos.toShortString(),"reference",token.toString()));
 }
 public static void receive(Wire message) {
  if(!primary) return;
  var values=message.body(Event.class).values();
  if(!message.source().equals(values.get("owner"))) throw new IllegalArgumentException("picked item owner mismatch");
  RemoteItemRef reference=new RemoteItemRef(values.get("owner"),UUID.fromString(values.get("token")));
  Map<String,String> display=new HashMap<>();
  try {if(values.get("display")!=null) display.putAll(Wire.JSON.fromJson(values.get("display"),DISPLAY_TYPE));} catch(RuntimeException e) {MmlfLog.warn("items","invalid_display",Map.of("owner",reference.owner()));}
  display.putIfAbsent("name",values.get("name"));
  give(proxy(reference,display,1,values.get("payload")));
  MmlfLog.info("items","proxy_selected",Map.of("item",values.get("item"),"owner",reference.owner()));
 }
 private static void give(ItemStack item) {
  var mc=Minecraft.getInstance();mc.execute(()->{
   if(mc.player==null || mc.getSingleplayerServer()==null || !mc.player.isCreative()) return;
   int slot=mc.player.getInventory().selected;
   mc.player.getInventory().setItem(slot,item.copy());
   var server=mc.getSingleplayerServer();server.execute(()->{
    if(server.getPlayerList().getPlayers().isEmpty()) return;
    var player=server.getPlayerList().getPlayers().getFirst();player.getInventory().setItem(slot,item.copy());player.inventoryMenu.broadcastChanges();
   });
  });
 }
 private static final java.lang.reflect.Type DISPLAY_TYPE=new com.google.gson.reflect.TypeToken<Map<String,String>>(){}.getType();
 /** Keeps a native stack for a reference. Bounded generously: a stack still referenced by an inventory must stay. */
 static UUID keep(ItemStack stack) {
  UUID token=UUID.randomUUID();
  synchronized(nativeItems) {
   nativeItems.put(token,stack.copy());
   while(nativeItems.size()>8192) {UUID oldest=nativeItems.keySet().iterator().next();nativeItems.remove(oldest);MmlfLog.warn("items","reference_evicted",Map.of("token",oldest.toString()));}
  }
  return token;
 }
 /** A copy of the native stack behind {@code token}, or null. */
 static ItemStack nativeStack(UUID token) {var stack=nativeItems.get(token);return stack==null?null:stack.copy();}
 /** Owner: the stack serialised with this runtime's registries, so it can be rebuilt after this process restarts. */
 static String payload(ItemStack stack,net.minecraft.core.HolderLookup.Provider registries) {
  // One item: the count travels separately, so the same item always has the same payload.
  try {return ItemStack.CODEC.encodeStart(registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),stack.copyWithCount(1)).result().map(Object::toString).orElse(null);}
  catch(RuntimeException e) {MmlfLog.warn("items","payload_failed",Map.of("exception",e.toString()));return null;}
 }
 /**
  * Owner: the stack behind one of this runtime's references. A reference made before a restart is no longer in memory;
  * its payload rebuilds it, and the token is filled again so later uses find it.
  */
 static ItemStack resolveOwned(UUID token,String payload,net.minecraft.core.HolderLookup.Provider registries) {
  ItemStack stack=nativeStack(token);if(stack!=null) return stack;
  if(payload==null) return null;
  try {
   var parsed=ItemStack.CODEC.parse(registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),com.google.gson.JsonParser.parseString(payload)).result();
   if(parsed.isEmpty()) return null;
   nativeItems.put(token,parsed.get().copy());
   MmlfLog.info("items","reference_restored",Map.of("token",token.toString()));
   return parsed.get();
  } catch(RuntimeException e) {MmlfLog.warn("items","payload_invalid",Map.of("token",token.toString(),"exception",e.toString()));return null;}
 }
 /** Owner: what the primary needs to show a stack without loading it. */
 static Map<String,String> describe(ItemStack stack,ServerPlayer player) { return describe(stack,player.level(),player); }
 static Map<String,String> describe(ItemStack stack,net.minecraft.world.level.Level level,net.minecraft.world.entity.player.Player player) {
  Map<String,String> d=new HashMap<>();
  d.put("id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
  d.put("name",stack.getHoverName().getString());
  d.put("maxStack",Integer.toString(stack.getMaxStackSize()));
  if(stack.isDamageableItem()) {d.put("damage",Integer.toString(stack.getDamageValue()));d.put("maxDamage",Integer.toString(stack.getMaxDamage()));}
  try {
   var lines=stack.getTooltipLines(Item.TooltipContext.of(level),player,TooltipFlag.NORMAL);
   d.put("tooltip",String.join("\n",lines.stream().map(Component::getString).toList()));
  } catch(RuntimeException | LinkageError e) {MmlfLog.sampled("items","tooltip_unavailable",5000,Map.of("item",d.get("id"),"exception",e.toString()));}
  return d;
 }
 /** Primary: a proxy for an owner's stack. Stack size and durability follow the owner's item. */
 static ItemStack proxy(RemoteItemRef reference,Map<String,String> display,int count,String payload) {
  Map<String,String> d=display==null?Map.of():display;
  ItemStack proxy=new ItemStack(Items.PAPER);
  int maxDamage=parse(d.get("maxDamage"),0);
  int maxStack=maxDamage>0?1:Math.max(1,Math.min(99,parse(d.get("maxStack"),64)));
  proxy.set(DataComponents.MAX_STACK_SIZE,maxStack);
  if(maxDamage>0) {proxy.set(DataComponents.MAX_DAMAGE,maxDamage);proxy.set(DataComponents.DAMAGE,Math.max(0,Math.min(maxDamage,parse(d.get("damage"),0))));}
  proxy.setCount(Math.max(1,Math.min(maxStack,count)));
  proxy.set(DataComponents.CUSTOM_NAME,Component.literal(d.getOrDefault("name","?")));
  CompoundTag tag=new CompoundTag();tag.putString("mmlf_item",reference.encode());tag.putString("mmlf_display",Wire.JSON.toJson(d));
  if(payload!=null) tag.putString("mmlf_payload",payload);
  proxy.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
  return proxy;
 }
 /** The owner's serialised stack a proxy carries, or null. */
 static String payload(ItemStack proxy) {
  var data=proxy.get(DataComponents.CUSTOM_DATA);if(data==null) return null;
  var tag=data.copyTag();return tag.contains("mmlf_payload")?tag.getString("mmlf_payload"):null;
 }
 /** The display data a proxy carries, or null for any other stack. */
 public static Map<String,String> display(ItemStack stack) {
  var data=stack.get(DataComponents.CUSTOM_DATA);if(data==null) return null;
  var tag=data.copyTag();if(!tag.contains("mmlf_display")) return null;
  try {return Wire.JSON.fromJson(tag.getString("mmlf_display"),DISPLAY_TYPE);} catch(RuntimeException e) {return null;}
 }
 /** Primary: the owner's tooltip in place of the proxy paper's, with the owner named. */
 public static List<Component> tooltip(ItemStack stack) {
  var d=display(stack);if(d==null || d.get("tooltip")==null) return null;
  var reference=RemoteItemRef.parse(held(stack));
  List<Component> lines=new ArrayList<>();
  for(String line:d.get("tooltip").split("\n",-1)) lines.add(Component.literal(line));
  reference.ifPresent(r->lines.add(Component.literal("远端物品 / "+r.owner()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)));
  return lines;
 }
 private static int parse(String value,int fallback) {try {return value==null?fallback:Integer.parseInt(value);} catch(NumberFormatException e) {return fallback;}}
 public static String held(ItemStack item) {
  var data=item.get(DataComponents.CUSTOM_DATA);
  if(data!=null) {String encoded=data.copyTag().getString("mmlf_item");if(RemoteItemRef.parse(encoded).isPresent()) return encoded;}
  return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
 }
}
