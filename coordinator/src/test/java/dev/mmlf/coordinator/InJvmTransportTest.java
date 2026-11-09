package dev.mmlf.coordinator;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mmlf.coordinator.CoordinatorIntegrationTest.awaitTrue;

/** The in-JVM transport: the protocol's guarantees across class loaders, without leaks. */
class InJvmTransportTest {
 static Session session(Path dir) { return new Session(Session.IN_JVM,"secret-"+UUID.randomUUID(),dir.toString(),"fabric"); }

 @Test void reliableMessagesSurviveAReconnectExactlyOnce() throws Exception {
  Session session=session(Files.createTempDirectory("mmlf-injvm"));
  CoordinatorCore core=new CoordinatorCore(session.token());
  BlockingQueue<Wire> neoEvents=new LinkedBlockingQueue<>();
  try(InJvmPort port=new InJvmPort(core,session.identity());
      RuntimeClient fabric=new RuntimeClient(session,"fabric","fabric",true,w->{});
      RuntimeClient neo=new RuntimeClient(session,"neo","neoforge",false,w->{ if(w.type().equals("event")) neoEvents.add(w); })) {
   fabric.start(); neo.start();
   awaitTrue(()->fabric.ready() && neo.ready());
   assertEquals(Set.of("fabric","neo"),core.runtimes());
   assertTrue(core.hadPrimary());
   fabric.sendReliable("event","neo",new Event("n",Map.of("n","1")));
   assertEquals("1",neoEvents.poll(5,TimeUnit.SECONDS).body(Event.class).values().get("n"));
   port.dropConnections();
   awaitTrue(()->!fabric.ready() && !neo.ready());
   fabric.sendReliable("event","neo",new Event("n",Map.of("n","2")));
   fabric.sendReliable("event","*",new Event("n",Map.of("n","3")));
   awaitTrue(()->fabric.ready() && neo.ready());
   fabric.sendReliable("event","neo",new Event("n",Map.of("n","4")));
   List<String> received=new ArrayList<>();
   for(int i=0;i<3;i++) { Wire w=neoEvents.poll(5,TimeUnit.SECONDS); assertNotNull(w,"missing message after "+received); received.add(w.body(Event.class).values().get("n")); }
   assertEquals(List.of("2","3","4"),received);
   assertNull(neoEvents.poll(1500,TimeUnit.MILLISECONDS),"no duplicate after the reconnect");
  }
 }

 @Test void secondCoordinatorForTheSameSessionIsRefused() throws Exception {
  Session session=session(Files.createTempDirectory("mmlf-injvm"));
  try(InJvmPort first=new InJvmPort(new CoordinatorCore(session.token()),session.identity())) {
   assertThrows(IllegalStateException.class,()->new InJvmPort(new CoordinatorCore(session.token()),session.identity()));
  }
  // Once closed, the session's port is gone and a new coordinator may serve it.
  new InJvmPort(new CoordinatorCore(session.token()),session.identity()).close();
 }

 @Test void runtimeLeavingIsBroadcast() throws Exception {
  Session session=session(Files.createTempDirectory("mmlf-injvm"));
  CoordinatorCore core=new CoordinatorCore(session.token());
  BlockingQueue<Wire> seen=new LinkedBlockingQueue<>();
  try(InJvmPort port=new InJvmPort(core,session.identity());
      RuntimeClient fabric=new RuntimeClient(session,"fabric","fabric",true,w->{ if(w.type().startsWith("runtime_")) seen.add(w); })) {
   RuntimeClient neo=new RuntimeClient(session,"neo","neoforge",false,w->{});
   fabric.start(); neo.start();
   awaitTrue(()->fabric.ready() && neo.ready());
   neo.close();
   awaitTrue(()->core.runtimes().equals(Set.of("fabric")));
   Wire left=null;
   for(Wire w; (w=seen.poll(5,TimeUnit.SECONDS))!=null;) if(w.type().equals("runtime_left")) { left=w; break; }
   assertNotNull(left);
   assertEquals("neo",left.data().get("runtime").getAsString());
  }
 }

 /**
  * Two runtimes whose protocol classes come from two separate class loaders (as Fabric and NeoForge each carry their
  * own copy) talk through the coordinator in a third. Afterwards both loaders can be collected.
  */
 @Test void isolatedLoadersExchangeMessagesAndAreCollectedAfterwards() throws Exception {
  Session session=session(Files.createTempDirectory("mmlf-injvm"));
  CoordinatorCore core=new CoordinatorCore(session.token());
  List<WeakReference<ClassLoader>> loaders=new ArrayList<>();
  try(InJvmPort port=new InJvmPort(core,session.identity())) {
   exchange(session,loaders);
   awaitTrue(()->port.connections()==0);
  }
  for(int i=0;i<100 && loaders.stream().anyMatch(r->r.get()!=null);i++) { System.gc(); Thread.sleep(50); }
  for(WeakReference<ClassLoader> r:loaders) assertNull(r.get(),"isolated runtime class loader leaked");
 }
 private static void exchange(Session session,List<WeakReference<ClassLoader>> loaders) throws Exception {
  URL[] classpath=classpath(Wire.class,com.google.gson.Gson.class);
  BlockingQueue<String> fabricSeen=new LinkedBlockingQueue<>(), neoSeen=new LinkedBlockingQueue<>();
  try(URLClassLoader a=new URLClassLoader("fabric-runtime",classpath,ClassLoader.getPlatformClassLoader());
      URLClassLoader b=new URLClassLoader("neoforge-runtime",classpath,ClassLoader.getPlatformClassLoader())) {
   loaders.add(new WeakReference<>(a)); loaders.add(new WeakReference<>(b));
   assertNotSame(a.loadClass(Wire.class.getName()),b.loadClass(Wire.class.getName()));
   assertNotSame(Wire.class,a.loadClass(Wire.class.getName()));
   Object fabric=client(a,session,"fabric",true,fabricSeen), neo=client(b,session,"neo",false,neoSeen);
   try {
    awaitTrue(()->ready(fabric) && ready(neo));
    sendReliable(fabric,"neo",a,"ping-from-fabric");
    assertEquals("event:ping-from-fabric",awaitEvent(neoSeen));
    sendReliable(neo,"fabric",b,"reply-from-neo");
    assertEquals("event:reply-from-neo",awaitEvent(fabricSeen));
   } finally { close(fabric); close(neo); }
  }
 }
 private static String awaitEvent(BlockingQueue<String> q) throws InterruptedException {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(System.nanoTime()<deadline) { String s=q.poll(100,TimeUnit.MILLISECONDS); if(s!=null && s.startsWith("event:")) return s; }
  throw new AssertionError("no event");
 }
 private static URL[] classpath(Class<?>... types) {
  Set<URL> urls=new LinkedHashSet<>();
  for(Class<?> t:types) urls.add(t.getProtectionDomain().getCodeSource().getLocation());
  return urls.toArray(URL[]::new);
 }
 /** Builds a RuntimeClient inside the isolated loader; only JDK types cross into this test's loader. */
 private static Object client(ClassLoader loader,Session session,String id,boolean primary,BlockingQueue<String> seen) throws Exception {
  Class<?> sessionType=loader.loadClass(Session.class.getName());
  Object isolatedSession=sessionType.getConstructor(String.class,String.class,String.class,String.class).newInstance(session.uri(),session.token(),session.directory(),session.primary());
  Class<?> wireType=loader.loadClass(Wire.class.getName());
  Method type=wireType.getMethod("type"), encode=wireType.getMethod("encode");
  Consumer<Object> receiver=w->{ try {
   String t=(String) type.invoke(w);
   if(t.equals("event")) { String text=(String) encode.invoke(w); seen.add("event:"+text.replaceAll(".*\"topic\":\"([^\"]+)\".*","$1")); }
   else seen.add(t);
  } catch(ReflectiveOperationException e) { throw new RuntimeException(e); } };
  Class<?> clientType=loader.loadClass(RuntimeClient.class.getName());
  Object client=clientType.getConstructor(sessionType,String.class,String.class,boolean.class,Consumer.class).newInstance(isolatedSession,id,id.equals("neo")?"neoforge":"fabric",primary,receiver);
  clientType.getMethod("start").invoke(client);
  return client;
 }
 private static boolean ready(Object client) { try { return (boolean) client.getClass().getMethod("ready").invoke(client); } catch(ReflectiveOperationException e) { throw new RuntimeException(e); } }
 private static void sendReliable(Object client,String target,ClassLoader loader,String topic) throws Exception {
  Object event=loader.loadClass(Event.class.getName()).getConstructor(String.class,Map.class).newInstance(topic,Map.of());
  client.getClass().getMethod("sendReliable",String.class,String.class,Object.class).invoke(client,"event",target,event);
 }
 private static void close(Object client) throws Exception { client.getClass().getMethod("close").invoke(client); }
}
