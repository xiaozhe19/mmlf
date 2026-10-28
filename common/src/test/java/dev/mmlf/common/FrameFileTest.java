package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import java.nio.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mmlf.common.Messages.*;
class FrameFileTest {
 @TempDir(cleanup=CleanupMode.NEVER) Path temp;
 @Test void frameSlotsRejectOverwrittenMetadataAndPreserveBothPlanes() throws Exception {
  Path file=temp.resolve("frames.bin");
  try(FrameFile writer=new FrameFile(file); FrameFile reader=new FrameFile(file)) {
   ByteBuffer color=ByteBuffer.allocate(8).putLong(0x0102030405060708L); color.flip();
   ByteBuffer depth=ByteBuffer.allocate(8).putLong(0x090a0b0c0d0e0f10L); depth.flip();
   int slot=writer.publish(5,2,1,color,depth);
   Frame frame=new Frame("neo",5,null,slot,2,1,0,true,"opengl-window-depth");
   FrameFile.Pixels pixels=reader.read(frame);
   assertNotNull(pixels);
   var reused=reader.read(frame,pixels); assertSame(pixels.color(),reused.color()); assertSame(pixels.depth(),reused.depth()); assertEquals(0x0102030405060708L,pixels.color().getLong());
   assertEquals(0x090a0b0c0d0e0f10L,pixels.depth().getLong());
   assertNull(reader.read(new Frame("neo",6,null,slot,2,1,0,true,"opengl-window-depth")));
   assertThrows(java.io.IOException.class,()->writer.publish(6,0,1,color,depth));
  }
 }
 @Test void capacityCoversMaximisedHighResolutionWindowAndSmallFilesRejectLargeFrames() throws Exception {
  assertTrue(FrameFile.fits(2560,1369,FrameFile.MAX_PIXELS));
  assertTrue(FrameFile.fits(2560,1440,FrameFile.MAX_PIXELS));
  assertFalse(FrameFile.fits(3840,2160,FrameFile.MAX_PIXELS));
  try(FrameFile icons=new FrameFile(temp.resolve("icons.bin"),32*32)) {
   ByteBuffer plane=ByteBuffer.allocate(33*32*4);
   assertThrows(java.io.IOException.class,()->icons.publish(1,33,32,plane,plane.duplicate()));
   ByteBuffer ok=ByteBuffer.allocate(32*32*4);
   assertTrue(icons.publish(1,32,32,ok,ok.duplicate())>=0);
  }
  assertDoesNotThrow(()->new Pose(1,0,"minecraft:overworld",0,0,0,0,0,70,2560,1369,0,0,0,new float[16],new float[16]));
 }
}
