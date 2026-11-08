package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mmlf.common.Messages.*;
class WorldIndexTest {
 @Test void ownerRevisionAndPositionAreEnforced() {
  WorldIndex index=new WorldIndex();
  var a=new ObjectState(objectId("fabric","overworld",1,2,3),"fabric","overworld",1,2,3,"test:block",1,Map.of());
  index.update("fabric",a);
  assertThrows(IllegalArgumentException.class,()->index.update("fabric",a));
  assertThrows(IllegalArgumentException.class,()->index.update("neo",a));
  var b=new ObjectState(objectId("neo","overworld",1,2,3),"neo","overworld",1,2,3,"test:block",1,Map.of());
  assertThrows(IllegalArgumentException.class,()->index.update("neo",b));
  index.removeOwner("fabric"); index.update("neo",b); assertEquals(1,index.snapshot().size());
 }
 @Test void aBlockPlacedAgainIsANewObjectAndOldHitsAreStale() {
  WorldIndex index=new WorldIndex();
  var first=new ObjectState(objectId("neo","overworld",1,2,3,7),"neo","overworld",1,2,3,"test:block",1,Map.of(),7);
  index.update("neo",first);
  var hit=new RayHit("a",first.id(),1,2,1,2,3,"UP",0,0,0,false);
  assertTrue(index.valid("neo",hit));
  assertThrows(IllegalArgumentException.class,()->index.update("neo",new ObjectState(objectId("neo","overworld",1,2,3),"neo","overworld",1,2,3,"test:block",2,Map.of(),7)),"the id must match the generation");
  index.update("neo",new ObjectState(first.id(),"neo","overworld",1,2,3,"minecraft:air",2,Map.of(),7));
  var second=new ObjectState(objectId("neo","overworld",1,2,3,8),"neo","overworld",1,2,3,"test:block",1,Map.of(),8);
  index.update("neo",second);
  assertNotEquals(first.id(),second.id());
  assertFalse(index.valid("neo",hit),"a hit on the broken block does not reach the new one");
 }
 @Test void depthUsesStandardOpenGlConvention() {
  assertEquals(0.1,Depth.linear(0,0.1,100),1e-9);
  assertEquals(100,Depth.linear(1,0.1,100),1e-8);
  assertTrue(Depth.remoteWins(.8f,.2f)); assertFalse(Depth.remoteWins(.2f,.8f));
 }
}
