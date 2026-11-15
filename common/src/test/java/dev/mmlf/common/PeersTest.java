package dev.mmlf.common;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PeersTest {
 /** Runs queued client-thread work only when asked, as the game's client thread would at its next task. */
 static final class Queue implements java.util.concurrent.Executor {
  final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
  public void execute(Runnable r) { tasks.add(r); }
  void drain() { for(Runnable r;(r=tasks.poll())!=null;) r.run(); }
 }

 @Test void onlineChangesOnTheClientThreadBeforeListenersWork() {
  var client=new Queue();
  var peers=new Peers(client);
  List<String> seen=new ArrayList<>();
  peers.listen(new Peers.Listener() {
   public void joined(String id) { client.execute(()->seen.add("joined "+id+" online="+peers.online().contains(id))); }
   public void left(String id) { client.execute(()->seen.add("left "+id+" online="+peers.online().contains(id))); }
  });
  peers.welcomed(List.of("fabric"));
  assertTrue(peers.online().isEmpty(),"not before the client thread runs");
  client.drain();
  assertEquals(Set.of("fabric"),peers.online());
  peers.joined("neoforge");
  client.drain();
  peers.left("neoforge");
  client.drain();
  assertEquals(List.of("joined neoforge online=true","left neoforge online=false"),seen);
  assertTrue(peers.anyOtherThan("neoforge"));
  assertFalse(peers.anyOtherThan("fabric"));
 }

 @Test void listenersRunInOrderOnTheReportingThread() {
  var client=new Queue();
  var peers=new Peers(client);
  List<String> seen=new ArrayList<>();
  peers.listen(new Peers.Listener() { public void disconnected() { seen.add("first"); } });
  peers.listen(new Peers.Listener() { public void disconnected() { seen.add("second"); } });
  peers.disconnected();
  assertEquals(List.of("first","second"),seen,"without waiting for the client thread");
 }

 @Test void worldReadinessIsDroppedWithTheRuntimeOrTheLink() {
  var client=new Queue();
  var peers=new Peers(client);
  peers.worldReady("neoforge"); peers.worldReady("forge");
  assertTrue(peers.anyWorldReady());
  peers.left("forge");
  assertEquals(Set.of("neoforge"),peers.worldReadyRuntimes());
  peers.worldsClosed();
  assertFalse(peers.anyWorldReady());
  peers.worldReady("neoforge");
  peers.disconnected();
  assertFalse(peers.anyWorldReady());
 }

 @Test void onlineIsReadOnly() {
  var client=new Queue();
  var peers=new Peers(client);
  assertThrows(UnsupportedOperationException.class,()->peers.online().add("x"));
 }
}
