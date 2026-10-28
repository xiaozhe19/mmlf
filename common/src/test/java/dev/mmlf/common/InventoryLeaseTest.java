package dev.mmlf.common;
import dev.mmlf.common.Messages.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryLeaseTest {
 static InventorySnapshot snap(String lease,String authority,long version,String reason,int count) {
  return new InventorySnapshot(lease,authority,version,0,List.of(new SlotItem(0,"{\"id\":\"minecraft:stone\"}",null,count,Map.of())),reason);
 }
 @Test void onlyTheLeaseholderMayReportNewerVersionsAndReturn() {
  var lease=new InventoryLease();
  var mine=snap(null,"fabric",5,InventoryLease.MIRROR,10);
  assertTrue(lease.grant("neo","L1",mine)); assertFalse(lease.grant("other","L2",mine),"one lease at a time");
  assertEquals(InventoryLease.Verdict.REJECTED,lease.accept("other",snap("L1","other",6,"update",1)));
  assertEquals(InventoryLease.Verdict.REJECTED,lease.accept("neo",snap("L0","neo",6,"update",1)),"wrong lease");
  assertEquals(InventoryLease.Verdict.REJECTED,lease.accept("neo",snap("L1","neo",5,"update",1)),"not newer");
  assertEquals(InventoryLease.Verdict.REJECTED,lease.accept("neo",snap("L1","fabric",6,"update",1)),"spoofed authority");
  assertEquals(InventoryLease.Verdict.CONFIRMED,lease.accept("neo",snap("L1","neo",6,"update",7)));
  assertEquals(InventoryLease.Verdict.RETURNED,lease.accept("neo",snap("L1","neo",6,"return",7)),"return may repeat the last version");
  assertFalse(lease.leased());
  assertEquals(InventoryLease.Verdict.REJECTED,lease.accept("neo",snap("L1","neo",8,"update",9)),"no reports after return");
  assertTrue(lease.nextVersion()>6);
 }
 @Test void failedHolderRollsBackToLastConfirmed() {
  var lease=new InventoryLease();
  lease.grant("neo","L1",snap(null,"fabric",1,InventoryLease.MIRROR,10));
  lease.accept("neo",snap("L1","neo",2,"update",4));
  assertNull(lease.rollback("fabric"),"only the holder's failure ends its lease");
  var restored=lease.rollback("neo");
  assertEquals(4,restored.slots().getFirst().count()); assertFalse(lease.leased());
  assertNull(lease.rollback("neo"));
 }
 @Test void holderNumbersItsSnapshots() {
  var holding=new InventoryLease.Holding();
  assertFalse(holding.active()); holding.begin("L1",3);
  assertEquals(4,holding.next()); assertEquals(5,holding.next()); holding.end(); assertFalse(holding.active());
  var ids=new RecentIds(2);
  assertTrue(ids.firstTime("a")); assertFalse(ids.firstTime("a")); ids.firstTime("b"); ids.firstTime("c");
  assertTrue(ids.firstTime("a"),"oldest evicted"); assertFalse(ids.firstTime(null));
 }
}
