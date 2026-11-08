package dev.mmlf.common;

import dev.mmlf.common.Messages.Event;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReliableLinkTest {
 static final long SECOND=1_000_000_000L;
 static ReliableLink link(String id,String incarnation) { return new ReliableLink(id,incarnation,8,60*SECOND); }
 static Event event(int n) { return new Event("t",Map.of("n",Integer.toString(n))); }
 static List<Integer> numbers(List<Wire> wires) { return wires.stream().map(w->Integer.parseInt(w.body(Event.class).values().get("n"))).toList(); }
 /** Delivers each wire as encoded text, as the socket would, and returns what the receiver hands on. */
 static List<Wire> deliver(ReliableLink receiver,List<Wire> wires) {
  List<Wire> out=new ArrayList<>();
  for(Wire w:wires) out.addAll(receiver.receive(Wire.parse(w.encode())).deliver());
  return out;
 }
 static void ack(ReliableLink sender,ReliableLink receiver,List<Wire> wires) {
  for(Wire w:wires) { Wire ack=receiver.receive(Wire.parse(w.encode())).ack(); if(ack!=null) sender.acknowledged(ack.source(),ack.body(ReliableLink.Ack.class)); }
 }

 @Test void deliversOnceInOrderDespiteDuplicatesAndReordering() {
  var a=link("a","A1"); var b=link("b","B1");
  a.peerOnline("b","B1",0);
  List<Wire> sent=new ArrayList<>();
  for(int i=1;i<=4;i++) sent.addAll(a.send("event","b",event(i),0));
  assertEquals(4,sent.size());
  var shuffled=List.of(sent.get(1),sent.get(0),sent.get(0),sent.get(3),sent.get(2),sent.get(1));
  assertEquals(List.of(1,2,3,4),numbers(deliver(b,shuffled)));
  assertEquals(2,b.duplicates());
  assertEquals(List.of(),deliver(b,sent),"a full resend changes nothing");
  assertEquals(0,b.lost());
 }

 @Test void keepsMessagesWhileThePeerIsAwayAndResendsUntilAcknowledged() {
  var a=link("a","A1"); var b=link("b","B1");
  a.peerOnline("b","B1",0);
  var first=a.send("event","b",event(1),0);
  ack(a,b,first);
  assertEquals(0,a.pending("b"),"acknowledged messages are released");
  a.peerOffline("b");
  assertEquals(List.of(),a.send("event","b",event(2),0),"nothing goes out while the peer is offline");
  assertEquals(List.of(),a.send("event","b",event(3),0));
  assertEquals(2,a.pending("b"));
  var resent=a.peerOnline("b","B1",SECOND);
  assertEquals(List.of(2,3),numbers(deliver(b,resent)));
  // The acknowledgement was lost: the next rejoin resends, and the receiver drops the copies.
  assertEquals(List.of(),deliver(b,a.peerOnline("b","B1",2*SECOND)));
  ack(a,b,resent);
  assertEquals(0,a.pending("b"));
 }

 @Test void aRestartedPeerNeverReceivesWhatWasMeantForItsPredecessor() {
  var a=link("a","A1"); var oldB=link("b","B1"); var newB=link("b","B2");
  a.peerOnline("b","B1",0);
  var meantForOld=a.send("event","b",event(1),0);
  a.peerOffline("b");
  a.send("event","b",event(2),0);
  assertEquals(List.of(),a.peerOnline("b","B2",SECOND),"pending messages of the old incarnation are dropped");
  assertEquals(2,a.lost());
  assertEquals(List.of(),deliver(newB,meantForOld),"misaddressed messages are not delivered");
  var fresh=a.send("event","b",event(3),SECOND);
  assertEquals(1,fresh.get(0).delivery().seq(),"the new stream starts again at 1");
  assertEquals(List.of(3),numbers(deliver(newB,fresh)));
  assertEquals(List.of(1),numbers(deliver(oldB,meantForOld)));
 }

 @Test void lossBeyondTheBoundsIsReportedAsAGapNotAWait() {
  var a=new ReliableLink("a","A1",2,10*SECOND); var b=link("b","B1");
  a.peerOnline("b","B1",0); a.peerOffline("b");
  a.send("event","b",event(1),0); a.send("event","b",event(2),0);
  a.send("event","b",event(3),0);
  assertEquals(1,a.lost(),"a full outbox drops its oldest message");
  var resent=a.peerOnline("b","B1",SECOND);
  assertEquals(List.of(2,3),numbers(deliver(b,resent)),"the receiver skips the lost message because the floor says so");
  assertEquals(1,b.lost());
  a.peerOffline("b");
  a.send("event","b",event(4),SECOND);
  assertEquals(List.of(),a.peerOnline("b","B1",20*SECOND),"expired messages are not sent again");
  var next=a.send("event","b",event(5),20*SECOND);
  assertEquals(List.of(5),numbers(deliver(b,next)));
  assertEquals(2,b.lost());
 }

 @Test void broadcastsGoToEveryKnownPeerOnItsOwnStream() {
  var a=link("a","A1"); var b=link("b","B1"); var c=link("c","C1");
  a.peerOnline("b","B1",0); a.peerOnline("c","C1",0); a.peerOnline("a","A1",0);
  var wires=a.send("event","*",event(1),0);
  assertEquals(List.of("b","c"),wires.stream().map(Wire::target).toList());
  assertEquals(List.of(1),numbers(deliver(b,List.of(wires.get(0)))));
  assertEquals(List.of(),deliver(b,List.of(wires.get(1))),"one peer's message is not another's");
  assertEquals(List.of(1),numbers(deliver(c,List.of(wires.get(1)))));
  assertThrows(IllegalArgumentException.class,()->a.send("event","a",event(1),0));
 }

 @Test void unsequencedMessagesPassThrough() {
  var b=link("b","B1");
  var plain=Wire.of("player","a","*",Map.of("version",1));
  var in=b.receive(plain);
  assertEquals(List.of(plain),in.deliver()); assertNull(in.ack());
 }
}
