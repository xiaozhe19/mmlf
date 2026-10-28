package dev.mmlf.common;
import dev.mmlf.common.Messages.GuiState;
import dev.mmlf.common.GuiLifecycle.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GuiLifecycleTest {
 static GuiState report(String id,String runtime,String state,long seq) { return new GuiState(id,"action",runtime,state,"Screen",false,2,854,480,seq,null); }
 @Test void opensOnlyForOwnActionAndFollowsStates() {
  var gui=new GuiLifecycle();
  assertEquals(Outcome.IGNORED,gui.apply(report("a","neo","OPENING",1),false,0));
  assertEquals(Outcome.OPENED,gui.apply(report("a","neo","OPENING",1),true,0));
  assertEquals(State.OPENING,gui.state()); assertFalse(gui.accepts("a"));
  assertEquals(Outcome.UPDATED,gui.apply(report("a","neo","ACTIVE",2),true,10));
  assertTrue(gui.accepts("a")); assertFalse(gui.accepts("b"));
  assertEquals(Outcome.IGNORED,gui.apply(report("a","neo","CLOSED",2),true,20),"stale sequence");
  assertEquals(Outcome.CLOSED,gui.apply(report("a","neo","CLOSED",3),true,20));
  assertFalse(gui.isOpen()); assertFalse(gui.accepts("a"));
 }
 @Test void ownerSwitchReplacesInstanceButOthersCannotIntrude() {
  var gui=new GuiLifecycle();
  gui.apply(report("a","neo","ACTIVE",1),true,0);
  assertEquals(Outcome.IGNORED,gui.apply(report("x","fabric","ACTIVE",1),true,0),"another runtime may not replace an open screen");
  assertEquals(Outcome.SWITCHED,gui.apply(report("b","neo","ACTIVE",1),false,0));
  assertTrue(gui.accepts("b")); assertFalse(gui.accepts("a"));
  assertEquals(Outcome.IGNORED,gui.apply(report("a","neo","CLOSED",9),true,0),"closing the old instance does not close the new one");
  assertTrue(gui.accepts("b"));
 }
 @Test void timeoutsFailOrCloseTheScreen() {
  var gui=new GuiLifecycle();
  gui.apply(report("a","neo","OPENING",1),true,0);
  assertNull(gui.expire(GuiLifecycle.OPEN_TIMEOUT_NANOS));
  assertEquals(State.FAILED,gui.expire(GuiLifecycle.OPEN_TIMEOUT_NANOS+1)); assertEquals("open_timeout",gui.reason());
  gui.reset(); gui.apply(report("b","neo","ACTIVE",1),true,0);
  assertTrue(gui.stalled(GuiLifecycle.STALL_NOTICE_NANOS+1));
  gui.frame("b",GuiLifecycle.STALL_NOTICE_NANOS+1); assertFalse(gui.stalled(GuiLifecycle.STALL_NOTICE_NANOS+2));
  assertEquals(State.FAILED,gui.expire(GuiLifecycle.STALL_NOTICE_NANOS+2+GuiLifecycle.STALL_FAIL_NANOS));
  gui.reset(); gui.apply(report("c","neo","ACTIVE",1),true,0);
  assertTrue(gui.requestClose("escape",0)); assertFalse(gui.accepts("c"));
  assertEquals(State.CLOSED,gui.expire(GuiLifecycle.CLOSE_TIMEOUT_NANOS+1));
  gui.reset(); gui.apply(report("d","neo","ACTIVE",1),true,0);
  assertTrue(gui.fail("runtime_left",0)); assertEquals(State.FAILED,gui.state());
 }
}
