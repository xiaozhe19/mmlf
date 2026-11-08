package dev.mmlf.common;

import dev.mmlf.common.Messages.SignalState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SignalTableTest {
 static SignalState at(int x,int weak) { int[] w=new int[6]; Arrays.fill(w,weak); return new SignalState("overworld",x,100,0,w,new int[6]); }

 @Test void theSenderPublishesOnlyChangesAndSilencesWhatLeft() {
  var out=new SignalTable.Outbox();
  assertEquals(List.of(),out.diff(List.of(at(1,0))),"a quiet position is never published");
  var first=out.diff(List.of(at(1,15),at(2,0)));
  assertEquals(1,first.size()); assertEquals(15,first.get(0).weak()[0]);
  assertEquals(List.of(),out.diff(List.of(at(1,15))),"unchanged");
  var weaker=out.diff(List.of(at(1,7)));
  assertEquals(7,weaker.get(0).weak()[3]);
  var gone=out.diff(List.of());
  assertEquals(1,gone.size()); assertTrue(gone.get(0).silent(),"a position that left the boundary is silenced once");
  assertEquals(List.of(),out.diff(List.of()));
  out.diff(List.of(at(1,15))); out.reset();
  assertEquals(1,out.diff(List.of(at(1,15))).size(),"after a reset everything is published again");
 }

 @Test void theReceiverTakesTheStrongestAndForgetsARuntimeThatLeft() {
  var in=new SignalTable.Inbox();
  assertTrue(in.apply("fabric",at(1,5)));
  assertFalse(in.apply("fabric",at(1,5)),"same value is no change");
  assertTrue(in.apply("forge",at(1,9)));
  assertEquals(9,in.signal("overworld",1,100,0,2,false));
  assertEquals(0,in.signal("overworld",1,100,0,2,true));
  assertEquals(0,in.signal("the_nether",1,100,0,2,false));
  assertEquals(1,in.forget("forge").size());
  assertEquals(5,in.signal("overworld",1,100,0,2,false));
  assertTrue(in.apply("fabric",at(1,0)),"silencing is a change");
  assertFalse(in.apply("fabric",at(1,0)));
  assertTrue(in.isEmpty());
 }

 @Test void aComparatorReadsTheAnalogOutputOnlyWherePublished() {
  var out=new SignalTable.Outbox(); var in=new SignalTable.Inbox();
  var full=new SignalState("overworld",3,100,0,new int[6],new int[6],9);
  var changes=out.diff(List.of(full));
  assertEquals(1,changes.size(),"an analog output alone is published");
  changes.forEach(c->in.apply("neo",c));
  assertEquals(9,in.analog("overworld",3,100,0));
  assertEquals(-1,in.analog("overworld",4,100,0));
  out.diff(List.of(new SignalState("overworld",3,100,0,new int[6],new int[6],0))).forEach(c->in.apply("neo",c));
  assertEquals(-1,in.analog("overworld",3,100,0),"an empty analog output reads as none");
 }

 @Test void neighbourStatesArePublishedOnChangeAndWithdrawnWhenTheyLeave() {
  var out=new SignalTable.NeighborOutbox();
  var lever=new Messages.NeighborState("overworld",0,100,0,"minecraft:lever[powered=false]");
  assertEquals(1,out.diff(List.of(lever)).size());
  assertEquals(List.of(),out.diff(List.of(lever)));
  var on=new Messages.NeighborState("overworld",0,100,0,"minecraft:lever[powered=true]");
  assertEquals("minecraft:lever[powered=true]",out.diff(List.of(on)).get(0).state());
  var gone=out.diff(List.of());
  assertEquals(1,gone.size()); assertNull(gone.get(0).state(),"a position that left is withdrawn with a null state");
  out.diff(List.of(on)); out.reset();
  assertEquals(1,out.diff(List.of(on)).size());
 }

 @Test void signalsOutsideTheRedstoneRangeAreRejected() {
  assertThrows(IllegalArgumentException.class,()->new SignalState("overworld",0,0,0,new int[6],new int[6],16));
  assertThrows(IllegalArgumentException.class,()->at(1,16));
  assertThrows(IllegalArgumentException.class,()->new SignalState("overworld",0,0,0,new int[5],new int[6]));
 }
}
