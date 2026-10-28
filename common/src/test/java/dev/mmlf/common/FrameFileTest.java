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
 @Test void concurrentWriterNeverYieldsATornFrame() throws Exception {
  Path file=temp.resolve("torn.bin");
  int w=256,h=256,n=w*h*4;
  try(FrameFile writer=new FrameFile(file); FrameFile reader=new FrameFile(file)) {
   int[] slots=new int[1]; long[] latest=new long[]{0};
   var stop=new java.util.concurrent.atomic.AtomicBoolean();
   Thread thread=new Thread(()->{
    ByteBuffer plane=ByteBuffer.allocateDirect(n);
    for(long id=1;!stop.get();id++) {
     byte value=(byte)id; for(int i=0;i<n;i++) plane.put(i,value);
     try { int slot=writer.publish(id,w,h,plane.duplicate(),plane.duplicate()); synchronized(latest) { latest[0]=id; slots[0]=slot; } }
     catch(java.io.IOException e) { throw new RuntimeException(e); }
    }
   });
   thread.start();
   int accepted=0; long until=System.nanoTime()+1_500_000_000L;
   while(System.nanoTime()<until) {
    long id; int slot; synchronized(latest) { id=latest[0]; slot=slots[0]; }
    if(id==0) continue;
    var pixels=reader.read(new Frame("neo",id,null,slot,w,h,0,true,"opengl-window-depth"));
    if(pixels==null) continue;
    accepted++;
    byte expected=(byte)id;
    for(int i=0;i<n;i+=97) { assertEquals(expected,pixels.color().get(i),"colour byte "+i+" of frame "+id); assertEquals(expected,pixels.depth().get(i),"depth byte "+i+" of frame "+id); }
   }
   stop.set(true); thread.join();
   assertTrue(accepted>0,"reader never obtained a stable frame");
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
