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
 @Test void depthUsesStandardOpenGlConvention() {
  assertEquals(0.1,Depth.linear(0,0.1,100),1e-9);
  assertEquals(100,Depth.linear(1,0.1,100),1e-8);
  assertTrue(Depth.remoteWins(.8f,.2f)); assertFalse(Depth.remoteWins(.2f,.8f));
 }
}
