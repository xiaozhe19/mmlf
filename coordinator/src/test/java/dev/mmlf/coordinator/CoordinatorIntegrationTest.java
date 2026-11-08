package dev.mmlf.coordinator;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import static dev.mmlf.common.Messages.objectId;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordinatorIntegrationTest {
 @Test void threeRuntimesExchangeEventsAndRejectConflictingObjects() throws Exception {
  Coordinator server=new Coordinator(0,"secret"); server.start();
  for(int i=0;i<200 && server.getPort()==0;i++) Thread.sleep(10);
  assertTrue(server.getPort()>0);
  List<Client> clients=new ArrayList<>();
  try {
   Client bad=new Client(server.getPort()); clients.add(bad); assertTrue(bad.connectBlocking(5,TimeUnit.SECONDS));
   bad.send(Wire.of("hello","intruder","coordinator",new Hello("wrong","fabric","1.21.1",false)).encode());
   assertEquals("error",bad.await("error").type());
   for(int i=0;i<100 && !bad.isClosed();i++) Thread.sleep(10);
   assertTrue(bad.isClosed()); clients.remove(bad);
   for(String id:List.of("fabric","neo","forge")) {
    Client c=new Client(server.getPort()); clients.add(c); assertTrue(c.connectBlocking(5,TimeUnit.SECONDS));
    c.send(Wire.of("hello",id,"coordinator",new Hello("secret",id,"1.21.1",id.equals("fabric"))).encode());
    assertEquals("welcome",c.await("welcome").type());
   }
   clients.get(0).send(Wire.of("event","fabric","neo",new Event("hello",Map.of("value","ok"))).encode());
   assertEquals("ok",clients.get(1).await("event").body(Event.class).values().get("value"));
   clients.get(1).send(Wire.of("event","neo","fabric",new Event("reply",Map.of())).encode());
   assertEquals("reply",clients.get(0).await("event").body(Event.class).topic());
   var a=new ObjectState(objectId("fabric","overworld",0,64,0),"fabric","overworld",0,64,0,"test:block",1,Map.of());
   clients.get(0).send(Wire.of("object","fabric","*",a).encode());
   assertEquals(a.id(),clients.get(1).await("object").body(ObjectState.class).id());
   var b=new ObjectState(objectId("neo","overworld",0,64,0),"neo","overworld",0,64,0,"test:block",1,Map.of());
   clients.get(1).send(Wire.of("object","neo","*",b).encode());
   assertTrue(clients.get(1).await("error").data().get("message").getAsString().contains("occupied"));
   Pose pose=new Pose(1,0,"overworld",0,65,0,0,0,70,1280,720,0,64,0,null,null);
   RayRequest query=new RayRequest("action-1",pose,5,"attack","minecraft:stone",true);
   clients.get(0).send(Wire.of("ray_query","fabric","coordinator",query).encode());
   for(Client client:clients) assertEquals("action-1",client.await("ray_request").body(RayRequest.class).actionId());
   RayHit hit=new RayHit("action-1",a.id(),1,2,0,64,0,"UP",0.5,65,0.5,false);
   RayHit miss=new RayHit("action-1",null,0,0,0,0,0,"UP",0,0,0,true);
   clients.get(0).send(Wire.of("ray_hit","fabric","coordinator",hit).encode());
   clients.get(1).send(Wire.of("ray_hit","neo","coordinator",miss).encode());
   clients.get(2).send(Wire.of("ray_hit","forge","coordinator",miss).encode());
   Interaction routed=clients.get(0).await("interaction").body(Interaction.class);
   assertEquals(a.id(),routed.hit().objectId()); assertEquals("attack",routed.action()); assertEquals("minecraft:stone",routed.heldItem()); assertTrue(routed.secondaryUse());
   clients.get(0).send(Wire.of("ray_query","fabric","coordinator",query).encode());
   assertTrue(clients.get(0).await("error").data().get("message").getAsString().contains("duplicate"));
   clients.get(2).send(Wire.of("ping","forge","coordinator",new Ping(123,0)).encode());
   assertEquals(123,clients.get(2).await("pong").body(Ping.class).sent());
  } finally { for(Client c:clients) c.closeBlocking(); server.stop(); }
 }
 @Test void hostedScreenTrafficOnlyFlowsBetweenPrimaryAndOwner() throws Exception {
  Coordinator server=new Coordinator(0,"secret"); server.start();
  for(int i=0;i<200 && server.getPort()==0;i++) Thread.sleep(10);
  List<Client> clients=new ArrayList<>();
  try {
   for(String id:List.of("fabric","neo","forge")) {
    Client c=new Client(server.getPort()); clients.add(c); assertTrue(c.connectBlocking(5,TimeUnit.SECONDS));
    c.send(Wire.of("hello",id,"coordinator",new Hello("secret",id,"1.21.1",id.equals("fabric"))).encode());
    c.await("welcome");
   }
   Client primary=clients.get(0),neo=clients.get(1),forge=clients.get(2);
   var state=new GuiState("g1","a1","neo","ACTIVE","Screen",true,2,854,480,1,null);
   neo.send(Wire.of("gui","neo","fabric",state).encode());
   assertEquals("g1",primary.await("gui").body(GuiState.class).instanceId());
   neo.send(Wire.of("gui","neo","forge",state).encode());
   assertTrue(neo.await("error").data().get("message").getAsString().contains("primary"));
   var input=new GuiInput("g1",1,"press",0.5,0.5,0,0,0,-1,-1,0,null,2,854,480,1);
   primary.send(Wire.of("gui_input","fabric","neo",input).encode());
   assertEquals(1,neo.await("gui_input").body(GuiInput.class).sequence());
   forge.send(Wire.of("gui_input","forge","neo",input).encode());
   assertTrue(forge.await("error").data().get("message").getAsString().contains("only primary"));
   var grant=new InventorySnapshot("L1","neo",1,0,List.of(),"grant");
   primary.send(Wire.of("inventory","fabric","neo",grant).encode());
   assertEquals("L1",neo.await("inventory").body(InventorySnapshot.class).leaseId());
   neo.send(Wire.of("inventory","neo","fabric",new InventorySnapshot("L1","neo",2,0,List.of(),"return")).encode());
   assertEquals("return",primary.await("inventory").body(InventorySnapshot.class).reason());
   neo.send(Wire.of("interaction_result","neo","fabric",new InteractionResult("a1","neo",true,"CONSUME","g1",null)).encode());
   assertTrue(primary.await("interaction_result").body(InteractionResult.class).success());
   primary.send(Wire.of("gui_input","fabric","nobody",input).encode());
   assertTrue(primary.await("error").data().get("message").getAsString().contains("offline"));
   neo.closeBlocking(); clients.remove(neo);
   assertEquals("neo",primary.await("runtime_left").data().get("runtime").getAsString());
  } finally { for(Client c:clients) c.closeBlocking(); server.stop(); }
 }
 @Test void reliableDeliveriesAreCheckedAndThePlayerBelongsToThePrimary() throws Exception {
  Coordinator server=new Coordinator(0,"secret"); server.start();
  for(int i=0;i<200 && server.getPort()==0;i++) Thread.sleep(10);
  List<Client> clients=new ArrayList<>();
  try {
   Map<String,String> incarnations=new HashMap<>();
   Welcome last=null;
   for(String id:List.of("fabric","neo")) {
    Client c=new Client(server.getPort()); clients.add(c); assertTrue(c.connectBlocking(5,TimeUnit.SECONDS));
    Hello hello=new Hello("secret",id,"1.21.1",id.equals("fabric")); incarnations.put(id,hello.incarnation());
    c.send(Wire.of("hello",id,"coordinator",hello).encode());
    last=c.await("welcome").body(Welcome.class);
   }
   assertEquals(incarnations,last.incarnations(),"a newcomer learns every incarnation");
   Client fabric=clients.get(0),neo=clients.get(1);
   Wire joined=fabric.await("runtime_joined");
   if(joined.data().get("runtime").getAsString().equals("fabric")) joined=fabric.await("runtime_joined");
   assertEquals(incarnations.get("neo"),joined.data().get("incarnation").getAsString());
   var event=Wire.of("event","fabric","neo",new Event("e",Map.of()));
   fabric.send(event.withDelivery(new ReliableLink.Delivery(incarnations.get("fabric"),incarnations.get("neo"),1,1)).encode());
   assertEquals(1,neo.await("event").delivery().seq(),"the delivery header reaches the receiver");
   fabric.send(event.withDelivery(new ReliableLink.Delivery("someone-else",incarnations.get("neo"),2,1)).encode());
   assertTrue(fabric.await("error").data().get("message").getAsString().contains("reliable"),"a stream must be the sender's own");
   var broadcast=Wire.of("event","fabric","*",new Event("e",Map.of()));
   fabric.send(broadcast.withDelivery(new ReliableLink.Delivery(incarnations.get("fabric"),incarnations.get("neo"),2,1)).encode());
   assertTrue(fabric.await("error").data().get("message").getAsString().contains("reliable"),"reliable messages have one receiver");
   neo.send(Wire.of("ack","neo","fabric",new ReliableLink.Ack(incarnations.get("fabric"),1)).encode());
   assertEquals(1,fabric.await("ack").body(ReliableLink.Ack.class).seq());
   var state=new PlayerState(1,3,"minecraft:stone",true,"survival","zh_cn");
   fabric.send(Wire.of("player","fabric","*",state).encode());
   assertEquals(3,neo.await("player").body(PlayerState.class).selected());
   neo.send(Wire.of("player","neo","*",state).encode());
   assertTrue(neo.await("error").data().get("message").getAsString().contains("primary"));
  } finally { for(Client c:clients) c.closeBlocking(); server.stop(); }
 }
 /** Two real runtime links: what is sent while the links are down arrives once, in order, after they reconnect. */
 @Test void reliableMessagesSurviveAReconnectExactlyOnce() throws Exception {
  Coordinator server=new Coordinator(0,"secret"); server.start();
  for(int i=0;i<200 && server.getPort()==0;i++) Thread.sleep(10);
  Path dir=Files.createTempDirectory("mmlf-reliable");
  Session session=new Session("ws://127.0.0.1:"+server.getPort(),"secret",dir.toString(),"fabric");
  BlockingQueue<Wire> neoEvents=new LinkedBlockingQueue<>();
  RuntimeClient fabric=new RuntimeClient(session,"fabric","fabric",true,w->{});
  RuntimeClient neo=new RuntimeClient(session,"neo","neoforge",false,w->{ if(w.type().equals("event")) neoEvents.add(w); });
  try {
   fabric.start(); neo.start();
   awaitTrue(()->fabric.ready() && neo.ready());
   fabric.sendReliable("event","neo",new Event("n",Map.of("n","1")));
   assertEquals("1",neoEvents.poll(5,TimeUnit.SECONDS).body(Event.class).values().get("n"));
   for(var connection:new ArrayList<>(server.getConnections())) connection.closeConnection(1001,"test disconnect");
   awaitTrue(()->!fabric.ready() && !neo.ready());
   fabric.sendReliable("event","neo",new Event("n",Map.of("n","2")));
   fabric.sendReliable("event","*",new Event("n",Map.of("n","3")));
   awaitTrue(()->fabric.ready() && neo.ready());
   fabric.sendReliable("event","neo",new Event("n",Map.of("n","4")));
   List<String> received=new ArrayList<>();
   for(int i=0;i<3;i++) { Wire w=neoEvents.poll(5,TimeUnit.SECONDS); assertNotNull(w,"missing message after "+received); received.add(w.body(Event.class).values().get("n")); }
   assertEquals(List.of("2","3","4"),received);
   assertNull(neoEvents.poll(1500,TimeUnit.MILLISECONDS),"no duplicate after the reconnect");
  } finally { fabric.close(); neo.close(); server.stop(); }
 }
 static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
  for(int i=0;i<1000;i++) { if(condition.getAsBoolean()) return; Thread.sleep(10); }
  throw new AssertionError("condition not reached");
 }
 static final class Client extends WebSocketClient {
  final BlockingQueue<Wire> queue=new LinkedBlockingQueue<>();
  Client(int port) { super(URI.create("ws://127.0.0.1:"+port)); }
  public void onOpen(ServerHandshake h) {} public void onMessage(String s) { queue.add(Wire.parse(s)); }
  public void onClose(int c,String s,boolean r) {} public void onError(Exception e) {}
  Wire await(String type) throws Exception {
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<deadline) { Wire w=queue.poll(100,TimeUnit.MILLISECONDS); if(w!=null && w.type().equals(type)) return w; }
   throw new AssertionError("missing "+type);
  }
 }
}

