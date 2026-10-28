package dev.mmlf.coordinator;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import static dev.mmlf.common.Messages.objectId;
import java.net.URI;
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

