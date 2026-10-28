package dev.mmlf.common;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DepthReprojectionTest {
 private static ByteBuffer plane(float a,float b) {var bytes=ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());bytes.putFloat(a).putFloat(b).flip();return bytes;}
 @Test void identityOcclusionAndTranslatedView() {
  float[] m={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
  var source=ByteBuffer.wrap(new byte[]{9,8,7,(byte)255,6,5,4,(byte)255});
  var base=ByteBuffer.allocate(8);
  assertEquals(1,DepthReprojection.composite(base,plane(1,.1f),source,plane(.5f,.5f),2,1,m));
  assertEquals(9,base.get(0));assertEquals(0,base.get(4));
  m[12]=1;
  base=ByteBuffer.allocate(8);
  assertEquals(1,DepthReprojection.composite(base,plane(1,1),source,plane(.5f,1),2,1,m));
  assertEquals(0,base.get(0));assertEquals(9,base.get(4));
 }
}
