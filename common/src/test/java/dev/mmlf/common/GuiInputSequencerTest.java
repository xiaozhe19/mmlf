package dev.mmlf.common;
import dev.mmlf.common.GuiInputSequencer.*;
import dev.mmlf.common.Messages.GuiInput;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GuiInputSequencerTest {
 @Test void movementIsCoalescedButFlushedBeforeEdgesInOrder() {
  var sender=new Sender(); sender.begin("a");
  sender.move(0.1,0.1,1); sender.move(0.2,0.2,2);
  List<GuiInput> press=sender.edge(GuiInputSequencer.PRESS,0.2,0.2,0,0,0,-1,-1,0,null,3);
  assertEquals(List.of("move","press"),press.stream().map(GuiInput::kind).toList());
  assertEquals(0.2,press.get(0).x()); assertEquals(1,press.get(0).sequence()); assertEquals(2,press.get(1).sequence());
  sender.move(0.5,0.5,4);
  List<GuiInput> release=sender.edge(GuiInputSequencer.RELEASE,0.5,0.5,0,0,0,-1,-1,0,null,5);
  assertEquals(List.of("move","release"),release.stream().map(GuiInput::kind).toList());
  assertTrue(sender.edge(GuiInputSequencer.RELEASE,0.5,0.5,0,0,0,-1,-1,0,null,6).isEmpty(),"no release without press");
 }
 @Test void releaseAllCompensatesHeldButtonsAndKeys() {
  var sender=new Sender(); sender.begin("a");
  sender.edge(GuiInputSequencer.PRESS,0,0,1,0,0,-1,-1,0,null,0);
  sender.edge(GuiInputSequencer.KEY_DOWN,0,0,-1,0,0,340,42,0,null,0);
  assertTrue(sender.holding());
  var kinds=sender.releaseAll(0,0,0).stream().map(GuiInput::kind).toList();
  assertEquals(List.of("release","key_up","release_all"),kinds); assertFalse(sender.holding());
 }
 @Test void receiverRejectsDuplicatesOldInstancesAndInvalidValues() {
  var sender=new Sender(); sender.begin("a");
  var receiver=new Receiver(); receiver.begin("a");
  var events=new ArrayList<GuiInput>();
  events.addAll(sender.edge(GuiInputSequencer.PRESS,0.3,0.3,0,0,0,-1,-1,0,null,0));
  events.addAll(sender.edge(GuiInputSequencer.KEY_DOWN,0.3,0.3,-1,0,0,65,30,0,null,0));
  for(var e:events) assertEquals(Receiver.Verdict.ACCEPT,receiver.accept(e));
  assertEquals(Receiver.Verdict.DUPLICATE,receiver.accept(events.get(0)));
  var old=new GuiInput("old",9,"press",0,0,0,0,0,-1,-1,0,null,2,1,1,0);
  assertEquals(Receiver.Verdict.WRONG_INSTANCE,receiver.accept(old));
  assertEquals(Receiver.Verdict.INVALID,receiver.accept(new GuiInput("a",9,"teleport",0,0,0,0,0,-1,-1,0,null,2,1,1,0)));
  assertEquals(Receiver.Verdict.INVALID,receiver.accept(new GuiInput("a",9,"move",Double.NaN,0,0,0,0,-1,-1,0,null,2,1,1,0)));
  assertEquals(1,receiver.heldKeys().size()); assertEquals(List.of(0),receiver.drainButtons());
  assertTrue(receiver.heldKeys().isEmpty());
  receiver.begin("b"); assertEquals(Receiver.Verdict.WRONG_INSTANCE,receiver.accept(events.get(1)));
 }
 @Test void coordinatesMapBetweenDifferentWindows() {
  double nx=GuiCoordinates.normalize(213.5,427),ny=GuiCoordinates.normalize(120,240);
  assertEquals(0.5,nx,1e-9); assertEquals(0.5,ny,1e-9);
  assertEquals(960,GuiCoordinates.toWindow(nx,1920),1e-9);
  assertEquals(1,GuiCoordinates.toWindow(3,1)); assertEquals(0,GuiCoordinates.normalize(5,0));
  assertEquals(4,GuiCoordinates.scale(1920,1080,0,false)); assertEquals(2,GuiCoordinates.scale(1920,1080,2,false));
  assertEquals(2,GuiCoordinates.scale(854,480,0,false)); assertEquals(4,GuiCoordinates.scale(1920,1080,3,true));
 }
}
