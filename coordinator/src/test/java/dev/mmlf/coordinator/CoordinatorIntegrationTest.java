package dev.mmlf.coordinator;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import static dev.mmlf.common.Messages.objectId;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordinatorIntegrationTest {
 @Test void threeRuntimesExchangeEventsAndRejectConflictingObjects() throws Exception {
  String identity=UUID.randomUUID().toString();
  InJvmPort server=new InJvmPort(new CoordinatorCore("secret"),identity);
  List<Client> clients=new ArrayList<>();
  try {
   Client bad=new Client(identity); clients.add(bad);
   bad.send(Wire.of("hello","intruder","coordinator",new Hello("wrong","fabric","1.21.1",false)).encode());
   assertEquals("error",bad.await("error").type());
   for(int i=0;i<100 && !bad.isClosed();i++) Thread.sleep(10);
   assertTrue(bad.isClosed()); clients.remove(bad);
   for(String id:List.of("fabric","neo","forge")) {
    Client c=new Client(identity); clients.add(c);
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
  } finally { for(Client c:clients) c.closeBlocking(); server.close(); }
 }
 @Test void hostedScreenTrafficOnlyFlowsBetweenPrimaryAndOwner() throws Exception {
  String identity=UUID.randomUUID().toString();
  InJvmPort server=new InJvmPort(new CoordinatorCore("secret"),identity);
  List<Client> clients=new ArrayList<>();
  try {
   for(String id:List.of("fabric","neo","forge")) {
    Client c=new Client(identity); clients.add(c);
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
  } finally { for(Client c:clients) c.closeBlocking(); server.close(); }
 }
 @Test void reliableDeliveriesAreCheckedAndThePlayerBelongsToThePrimary() throws Exception {
  String identity=UUID.randomUUID().toString();
  InJvmPort server=new InJvmPort(new CoordinatorCore("secret"),identity);
  List<Client> clients=new ArrayList<>();
  try {
   Map<String,String> incarnations=new HashMap<>();
   Welcome last=null;
   for(String id:List.of("fabric","neo")) {
    Client c=new Client(identity); clients.add(c);
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
  } finally { for(Client c:clients) c.closeBlocking(); server.close(); }
 }
 /** Entities: the nearest owner's entity wins the ray, an entity the sender never reported is no hit, damage reports name one's own entity. */
 @Test void entityHitsAndDamageBelongToTheEntitysOwner() throws Exception {
  String identity=UUID.randomUUID().toString();
  InJvmPort server=new InJvmPort(new CoordinatorCore("secret"),identity);
  List<Client> clients=new ArrayList<>();
  try {
   for(String id:List.of("fabric","neo","forge")) {
    Client c=new Client(identity); clients.add(c);
    c.send(Wire.of("hello",id,"coordinator",new Hello("secret",id,"1.21.1",id.equals("fabric"))).encode());
    c.await("welcome");
   }
   Client primary=clients.get(0),neo=clients.get(1),forge=clients.get(2);
   var zombie=new EntityState("e-zombie","minecraft:zombie",3.5,100,2.5,0.6,1.95,true);
   neo.send(Wire.of("entities","neo","*",new Entities("neo","minecraft:overworld",1,List.of(zombie))).encode());
   assertEquals("e-zombie",primary.await("entities").body(Entities.class).entities().getFirst().id());
   forge.send(Wire.of("entities","forge","*",new Entities("neo","minecraft:overworld",1,List.of())).encode());
   assertTrue(forge.await("error").data().get("message").getAsString().contains("own"),"a snapshot names its sender");

   Pose pose=new Pose(1,0,"minecraft:overworld",0,101.6,0,0,0,70,1280,720,0,100,0,null,null);
   primary.send(Wire.of("ray_query","fabric","coordinator",new RayRequest("a-1",pose,5,"attack","minecraft:air",false)).encode());
   for(Client c:clients) c.await("ray_request");
   primary.send(Wire.of("ray_hit","fabric","coordinator",new RayHit("a-1",null,0,4.0,0,100,-4,"UP",0.5,100.5,-3.5,false)).encode());
   neo.send(Wire.of("ray_hit","neo","coordinator",new RayHit("a-1",null,0,2.0,3,100,2,"UP",3.5,101,2.5,false,"e-zombie")).encode());
   forge.send(Wire.of("ray_hit","forge","coordinator",new RayHit("a-1",null,0,1.0,0,100,1,"UP",0.5,101,1.5,false,"e-unknown")).encode());
   Interaction routed=neo.await("interaction").body(Interaction.class);
   assertEquals("e-zombie",routed.hit().entity(),"the nearest reported entity wins; an unreported one is no hit");

   neo.send(Wire.of("player_damage","neo","fabric",new PlayerDamage("e-zombie","minecraft:zombie","mob",3f,3.5,2.5)).encode());
   assertEquals(3f,primary.await("player_damage").body(PlayerDamage.class).amount());
   forge.send(Wire.of("player_damage","forge","fabric",new PlayerDamage("e-zombie","minecraft:zombie","mob",3f,3.5,2.5)).encode());
   assertTrue(forge.await("error").data().get("message").getAsString().contains("own entity"));
  } finally { for(Client c:clients) c.closeBlocking(); server.close(); }
 }
 static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
  for(int i=0;i<1000;i++) { if(condition.getAsBoolean()) return; Thread.sleep(10); }
  throw new AssertionError("condition not reached");
 }
 /** A raw connection to the in-JVM port: sends frames as they are, keeps what arrives. */
 static final class Client {
  final BlockingQueue<Wire> queue=new LinkedBlockingQueue<>();
  private volatile boolean closed;
  private final Consumer<String> out;
  Client(String identity) { out=InJvm.port(identity).apply("test-"+UUID.randomUUID(),f->{ if(f==null) closed=true; else queue.add(Wire.parse(f)); }); }
  void send(String frame) { out.accept(frame); }
  boolean isClosed() { return closed; }
  void closeBlocking() { if(!closed) { closed=true; out.accept(null); } }
  Wire await(String type) throws Exception {
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<deadline) { Wire w=queue.poll(100,TimeUnit.MILLISECONDS); if(w!=null && w.type().equals(type)) return w; }
   throw new AssertionError("missing "+type);
  }
 }
}

