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

/** Native ItemStacks remain in the owner JVM. A labelled proxy represents them in the main hotbar. */
public final class RemoteItems {
 private static RuntimeClient link; private static boolean primary;
 private static String selectedReference="minecraft:air";
 public static String selectedReference() {return selectedReference;}
 public static void updateHeld(String encoded) {
  selectedReference=encoded;
  var stack=resolve(encoded);var mc=Minecraft.getInstance();
  if(mc.player!=null) mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stack==null?ItemStack.EMPTY:stack);
 }
 public static boolean nativeHandSelected() {return !primary && RemoteItemRef.parse(selectedReference).map(ref->ref.owner().equals(link.id)).orElse(false);}
 public static void sendVisual(String kind,Frame frame,String reference) {
  sendVisual(kind,frame,reference,0,0);
 }
 public static void sendVisual(String kind,Frame frame,String reference,int x,int y) {
  link.send("event","*",new Event("mmlf:visual",Map.of("kind",kind,"frame",Wire.JSON.toJson(frame),"reference",reference,"x",Integer.toString(x),"y",Integer.toString(y))));
 }

 private static final Map<UUID,ItemStack> nativeItems=java.util.Collections.synchronizedMap(new LinkedHashMap<>());
 public static void initialize(RuntimeClient client,boolean main) {link=client;primary=main;}
 public static void pick(ServerPlayer player,BlockPos pos) {
  var state=player.level().getBlockState(pos); ItemStack item=state.getBlock().getCloneItemStack(player.level(),pos,state);
  if(item.isEmpty()) return;
  if(primary) {give(item.copy());return;}
  UUID token=UUID.randomUUID();synchronized(nativeItems) {nativeItems.put(token,item.copy());
  while(nativeItems.size()>256) nativeItems.remove(nativeItems.keySet().iterator().next());}
  String id=BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
  link.send("event","*",new Event("mmlf:picked",Map.of("owner",link.id,"token",token.toString(),"item",id,"name",item.getHoverName().getString())));
  MmlfLog.info("items","native_picked",Map.of("item",id,"position",pos.toShortString(),"reference",token.toString()));
 }
 public static void receive(Wire message) {
  if(!primary) return;
  var values=message.body(Event.class).values();
  if(!message.source().equals(values.get("owner"))) throw new IllegalArgumentException("picked item owner mismatch");
  RemoteItemRef reference=new RemoteItemRef(values.get("owner"),UUID.fromString(values.get("token")));
  ItemStack proxy=new ItemStack(Items.PAPER);
  proxy.set(DataComponents.CUSTOM_NAME,Component.literal(values.get("name")+" [远端物品 / "+reference.owner()+"]"));
  CompoundTag tag=new CompoundTag();tag.putString("mmlf_item",reference.encode());
  proxy.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
  give(proxy);
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
 public static String held(ItemStack item) {
  var data=item.get(DataComponents.CUSTOM_DATA);
  if(data!=null) {String encoded=data.copyTag().getString("mmlf_item");if(RemoteItemRef.parse(encoded).isPresent()) return encoded;}
  return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
 }
 public static ItemStack resolve(String encoded) {
  var remote=RemoteItemRef.parse(encoded);
  if(remote.isPresent()) {
   var ref=remote.get();if(!ref.owner().equals(link.id)) return null;
   var stack=nativeItems.get(ref.token());return stack==null?null:stack.copy();
  }
  var key=net.minecraft.resources.ResourceLocation.parse(encoded==null?"minecraft:air":encoded);
  return BuiltInRegistries.ITEM.containsKey(key)?new ItemStack(BuiltInRegistries.ITEM.get(key)):null;
 }
}
