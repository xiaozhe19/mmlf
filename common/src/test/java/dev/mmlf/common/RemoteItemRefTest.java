package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class RemoteItemRefTest {
 @Test void preservesOwnerAndRejectsMalformedReferences() {
  var reference=new RemoteItemRef("neoforge",UUID.randomUUID());
  assertEquals(reference,RemoteItemRef.parse(reference.encode()).orElseThrow());
  assertTrue(RemoteItemRef.parse("minecraft:stone").isEmpty());
  assertThrows(IllegalArgumentException.class,()->RemoteItemRef.parse("mmlf@../other:invalid"));
 }
}
