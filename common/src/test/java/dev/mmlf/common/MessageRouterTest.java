package dev.mmlf.common;

import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MessageRouter.Scope;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageRouterTest {
 static final class Roles implements MessageRouter.Roles {
  boolean primary; String primaryId="fabric";
  public boolean isPrimary() { return primary; }
  public String primaryId() { return primaryId; }
 }
 static Wire event(String source,String topic) { return Wire.of("event",source,"*",new Event(topic,Map.of("k","v"))); }
 static Wire message(String source,String type) { return Wire.of(type,source,"*",Map.of()); }

 @Test void routesEachTypeToItsHandler() {
  var router=new MessageRouter(new Roles());
  List<String> seen=new ArrayList<>();
  router.on("a",Scope.ANY,m->seen.add("a:"+m.source()));
  router.on("b",Scope.ANY,m->seen.add("b"));
  router.dispatch(message("x","a"));
  router.dispatch(message("x","b"));
  router.dispatch(message("x","unknown"));
  assertEquals(List.of("a:x","b"),seen);
 }

 @Test void scopesFollowTheRoleAndSource() {
  var roles=new Roles();
  var router=new MessageRouter(roles);
  List<String> seen=new ArrayList<>();
  router.on("p",Scope.ON_PRIMARY,m->seen.add("p"));
  router.on("b",Scope.ON_BACKGROUND,m->seen.add("b"));
  router.on("f",Scope.ON_BACKGROUND_FROM_PRIMARY,m->seen.add("f"));
  roles.primary=true;
  for(String t:List.of("p","b","f")) router.dispatch(message("fabric",t));
  assertEquals(List.of("p"),seen);
  seen.clear(); roles.primary=false;
  for(String t:List.of("p","b","f")) router.dispatch(message("fabric",t));
  assertEquals(List.of("b","f"),seen);
  seen.clear();
  router.dispatch(message("neoforge","f"));
  router.dispatch(message("coordinator","f"));
  assertEquals(List.of(),seen,"only the primary's messages pass");
  roles.primaryId=null;
  router.dispatch(message("fabric","f"));
  assertEquals(List.of(),seen,"no session yet: nothing is from the primary");
 }

 @Test void eventsRouteByExactTopicThenPrefixThenFallback() {
  var router=new MessageRouter(new Roles());
  List<String> seen=new ArrayList<>();
  router.onEvent("mmlf:tick",Scope.ANY,(m,e)->seen.add("tick:"+e.values().get("k")));
  router.onEventPrefix("mmlf:selftest",Scope.ANY,(m,e)->seen.add("selftest:"+e.topic()));
  router.onEventPrefix("mmlf:self",Scope.ANY,(m,e)->seen.add("self"));
  router.onUnroutedEvent((m,e)->seen.add("other:"+e.topic()));
  router.dispatch(event("x","mmlf:tick"));
  router.dispatch(event("x","mmlf:selftest_step"));
  router.dispatch(event("x","mmlf:selfie"));
  router.dispatch(event("x","othermod:thing"));
  assertEquals(List.of("tick:v","selftest:mmlf:selftest_step","self","other:othermod:thing"),seen);
 }

 @Test void aRoutedMessageOutOfScopeIsDroppedNotPassedOn() {
  var roles=new Roles(); roles.primary=true;
  var router=new MessageRouter(roles);
  List<String> seen=new ArrayList<>();
  router.onEvent("mmlf:play",Scope.ON_BACKGROUND_FROM_PRIMARY,(m,e)->seen.add("play"));
  router.onEventPrefix("mmlf:experiment",Scope.ON_BACKGROUND,(m,e)->seen.add("experiment"));
  router.onUnroutedEvent((m,e)->seen.add("fallback"));
  router.dispatch(event("fabric","mmlf:play"));
  router.dispatch(event("fabric","mmlf:experiment_a"));
  assertEquals(List.of(),seen);
 }

 @Test void eachTypeTopicAndPrefixIsRoutedOnce() {
  var router=new MessageRouter(new Roles());
  router.on("a",Scope.ANY,m->{});
  router.onEvent("t",Scope.ANY,(m,e)->{});
  router.onEventPrefix("p",Scope.ANY,(m,e)->{});
  assertThrows(IllegalStateException.class,()->router.on("a",Scope.ON_PRIMARY,m->{}));
  assertThrows(IllegalStateException.class,()->router.onEvent("t",Scope.ANY,(m,e)->{}));
  assertThrows(IllegalStateException.class,()->router.onEventPrefix("p",Scope.ANY,(m,e)->{}));
  assertThrows(IllegalArgumentException.class,()->router.on("event",Scope.ANY,m->{}));
 }
}
