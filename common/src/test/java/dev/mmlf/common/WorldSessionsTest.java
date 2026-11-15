package dev.mmlf.common;

import dev.mmlf.common.WorldSession.Mode;
import dev.mmlf.common.WorldSession.State;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldSessionsTest {
 static final class Counter implements WorldSession.Scoped {
  final List<String> log; final String name; int value;
  Counter(List<String> log,String name) { this.log=log; this.name=name; }
  @Override public void close() { log.add("close "+name); }
 }

 @Test void aWorldGoesThroughItsStatesOnceAndInOrder() {
  var worlds=new WorldSessions();
  List<String> seen=new ArrayList<>();
  worlds.listen(new WorldSessions.Listener() {
   public void opened(WorldSession w) { seen.add("opened "+w.epoch()); }
   public void ready(WorldSession w) { seen.add("ready"); }
   public void leaving(WorldSession w) { seen.add("leaving"); }
   public void closed(WorldSession w) { seen.add("closed"); }
  });
  var w=worlds.open(1,Mode.FOLLOWED_SAVE,"save",null,"test");
  assertSame(w,worlds.current()); assertEquals(State.OPENING,w.state());
  worlds.ready(w); worlds.ready(w);
  worlds.leaving(w,"test"); worlds.ready(w);
  worlds.close(w,"test"); worlds.close(w,"again");
  assertNull(worlds.current());
  assertEquals(List.of("opened 1","ready","leaving","closed"),seen);
  assertEquals(State.CLOSED,w.state());
 }

 @Test void openingANewWorldClosesThePreviousOne() {
  var worlds=new WorldSessions();
  var a=worlds.open(1,Mode.SCENE,"scene",null,"test");
  var b=worlds.open(2,Mode.FOLLOWED_SAVE,"save",null,"test");
  assertTrue(a.closed()); assertSame(b,worlds.current());
  assertEquals(Mode.SCENE,a.mode()); assertEquals(Mode.FOLLOWED_SAVE,b.mode());
  worlds.leaving(a,"late"); worlds.ready(a);
  assertEquals(State.CLOSED,a.state(),"an old world never comes back");
  assertEquals(State.OPENING,b.state());
 }

 @Test void scopedStateBelongsToItsWorldAndClosesWithIt() {
  var worlds=new WorldSessions();
  List<String> log=new ArrayList<>();
  var first=new WorldSession.Key<>("first",()->new Counter(log,"first"));
  var second=new WorldSession.Key<>("second",()->new Counter(log,"second"));
  var a=worlds.open(1,Mode.FOLLOWED_SAVE,"s",null,"test");
  a.get(first).value=5; a.get(second).value=7;
  assertEquals(5,a.get(first).value,"the same state while the world is open");
  worlds.close(a,"test");
  assertEquals(List.of("close second","close first"),log,"latest made, first closed");
  var late=a.get(first);
  late.value=9;
  assertEquals(0,a.get(first).value,"a closed world keeps nothing written to it");
  var b=worlds.open(2,Mode.FOLLOWED_SAVE,"s",null,"test");
  assertEquals(0,b.get(first).value,"nothing of the old world reaches the new one");
 }

 @Test void oneFailingCloseDoesNotKeepTheOthersOpen() {
  var worlds=new WorldSessions();
  List<String> log=new ArrayList<>();
  var good=new WorldSession.Key<>("good",()->new Counter(log,"good"));
  var bad=new WorldSession.Key<WorldSession.Scoped>("bad",()->new WorldSession.Scoped() { public void close() { throw new IllegalStateException("boom"); } });
  var w=worlds.open(1,Mode.SCENE,"s",null,"test");
  w.get(good); w.get(bad);
  worlds.close(w,"test");
  assertEquals(List.of("close good"),log);
  assertNull(worlds.current());
 }
}
