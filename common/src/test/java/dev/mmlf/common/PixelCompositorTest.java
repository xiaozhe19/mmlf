package dev.mmlf.common;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PixelCompositorTest {
 @Test void respectsDepthAndEmptyRemotePixels() {
  ByteBuffer local=ByteBuffer.allocate(12).put(new byte[]{1,1,1,1,2,2,2,2,3,3,3,3}); local.flip();
  ByteBuffer remote=ByteBuffer.allocate(12).put(new byte[]{9,9,9,9,8,8,8,8,7,7,7,7}); remote.flip();
  ByteBuffer ld=ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putFloat(.5f).putFloat(.2f).putFloat(.9f); ld.flip();
  ByteBuffer rd=ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putFloat(.1f).putFloat(.8f).putFloat(1f); rd.flip();
  assertEquals(1,PixelCompositor.composite(local,ld,remote,rd,3));
  assertArrayEquals(new byte[]{9,9,9,9,2,2,2,2,3,3,3,3},local.array());
  assertThrows(IllegalArgumentException.class,()->PixelCompositor.composite(local,ld,remote,rd,4));
 }
 @Test void premultipliedHandOverlayPreservesWorldOutsideCoverage() {
  ByteBuffer base=ByteBuffer.wrap(new byte[]{20,40,60,(byte)255,10,20,30,(byte)255});
  ByteBuffer overlay=ByteBuffer.wrap(new byte[]{100,0,0,(byte)128,0,0,0,0});
  PixelCompositor.overlay(base,overlay);
  assertArrayEquals(new byte[]{110,20,30,(byte)255,10,20,30,(byte)255},base.array());
 }
}
