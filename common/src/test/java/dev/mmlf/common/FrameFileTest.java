package dev.mmlf.common;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mmlf.common.Messages.*;
class FrameFileTest {
 @TempDir(cleanup=CleanupMode.NEVER) Path temp;
 private static byte[] meta(String text) { return text.getBytes(StandardCharsets.UTF_8); }
 @Test void newestFrameCarriesMetadataAndBothPlanes() throws Exception {
  Path file=temp.resolve("frames.bin");
  try(FrameFile writer=FrameFile.writer(file); FrameFile reader=FrameFile.reader(file)) {
   ByteBuffer color=ByteBuffer.allocate(8).putLong(0x0102030405060708L); color.flip();
   ByteBuffer depth=ByteBuffer.allocate(8).putLong(0x090a0b0c0d0e0f10L); depth.flip();
   assertNull(reader.readLatest(null,null));
   assertTrue(writer.publish(4,2,1,color,depth,meta("four"))>=0);
   assertTrue(writer.publish(5,2,1,color,depth,meta("five"))>=0);
   var latest=reader.readLatest(null,null);
   assertNotNull(latest); assertEquals(5,latest.frameId()); assertEquals("five",new String(latest.metadata(),StandardCharsets.UTF_8));
   assertEquals(0x0102030405060708L,latest.pixels().color().getLong(0)); assertEquals(0x090a0b0c0d0e0f10L,latest.pixels().depth().getLong(0));
   assertNull(reader.readLatest(latest,null),"nothing newer than the cursor");
   assertTrue(writer.publish(6,2,1,color,depth,meta("six"))>=0);
   var reused=reader.readLatest(latest,latest.pixels());
   assertEquals(6,reused.frameId()); assertSame(latest.pixels().color(),reused.pixels().color()); assertSame(latest.pixels().depth(),reused.pixels().depth());
   assertThrows(java.io.IOException.class,()->writer.publish(7,0,1,color,depth));
   assertThrows(java.io.IOException.class,()->writer.publish(7,2,1,color,depth,new byte[FrameFile.MAX_METADATA+1]));
  }
 }
 @Test void readerNeedsAnExistingFileAndARestartedWriterWins() throws Exception {
  Path file=temp.resolve("restart.bin");
  assertThrows(java.io.IOException.class,()->FrameFile.reader(file,4));
  ByteBuffer plane=ByteBuffer.allocate(16);
  FrameFile.Latest seen;
  try(var writer=FrameFile.writer(file,4); var reader=FrameFile.reader(file,4)) {
   for(int id=1;id<=100;id++) writer.publish(id,2,2,plane,plane.duplicate());
   seen=reader.readLatest(null,null); assertEquals(100,seen.frameId());
  }
  // The restarted process counts frames from one again; its higher generation still orders after the old frames.
  try(var writer=FrameFile.writer(file,4); var reader=FrameFile.reader(file,4)) {
   writer.publish(1,2,2,plane,plane.duplicate());
   var restarted=reader.readLatest(seen,null);
   assertNotNull(restarted); assertEquals(1,restarted.frameId()); assertTrue(restarted.generation()>seen.generation());
   assertNull(reader.readLatest(restarted,null),"old-generation slots must not win over the restarted writer");
  }
 }
 @Test void concurrentWriterNeverYieldsATornFrame() throws Exception {
  Path file=temp.resolve("torn.bin");
  int w=256,h=256,n=w*h*4;
  try(FrameFile writer=FrameFile.writer(file); FrameFile reader=FrameFile.reader(file)) {
   var stop=new java.util.concurrent.atomic.AtomicBoolean();
   Thread thread=new Thread(()->{
    ByteBuffer plane=ByteBuffer.allocateDirect(n);
    for(long id=1;!stop.get();id++) {
     byte value=(byte)id; for(int i=0;i<n;i++) plane.put(i,value);
     try { writer.publish(id,w,h,plane.duplicate(),plane.duplicate(),meta(Long.toString(id))); }
     catch(java.io.IOException e) { throw new RuntimeException(e); }
    }
   });
   thread.start();
   int accepted=0; long until=System.nanoTime()+1_500_000_000L; FrameFile.Latest cursor=null;
   while(System.nanoTime()<until) {
    var latest=reader.readLatest(cursor,cursor==null?null:cursor.pixels());
    if(latest==null) continue;
    accepted++; long id=latest.frameId(); byte expected=(byte)id;
    if(cursor!=null) assertTrue(id>cursor.frameId());
    assertEquals(Long.toString(id),new String(latest.metadata(),StandardCharsets.UTF_8));
    for(int i=0;i<n;i+=97) { assertEquals(expected,latest.pixels().color().get(i),"colour byte "+i+" of frame "+id); assertEquals(expected,latest.pixels().depth().get(i),"depth byte "+i+" of frame "+id); }
    cursor=latest;
   }
   stop.set(true); thread.join();
   assertTrue(accepted>0,"reader never obtained a stable frame");
  }
 }
 @Test void capacityCoversMaximisedHighResolutionWindowAndSmallFilesRejectLargeFrames() throws Exception {
  assertTrue(FrameFile.fits(2560,1369,FrameFile.MAX_PIXELS));
  assertTrue(FrameFile.fits(2560,1440,FrameFile.MAX_PIXELS));
  assertFalse(FrameFile.fits(3840,2160,FrameFile.MAX_PIXELS));
  try(FrameFile icons=FrameFile.writer(temp.resolve("icons.bin"),32*32)) {
   ByteBuffer plane=ByteBuffer.allocate(33*32*4);
   assertThrows(java.io.IOException.class,()->icons.publish(1,33,32,plane,plane.duplicate()));
   ByteBuffer ok=ByteBuffer.allocate(32*32*4);
   assertTrue(icons.publish(1,32,32,ok,ok.duplicate())>=0);
  }
  assertDoesNotThrow(()->new Pose(1,0,"minecraft:overworld",0,0,0,0,0,70,2560,1369,0,0,0,new float[16],new float[16]));
 }
 @Test void slotLocksNeverBlockAndCrashMarkerOrCorruptionIsRejected() throws Exception {
  Path file=temp.resolve("locking.bin"); long stride=FrameFile.HEADER+4*8L;
  try(var writer=FrameFile.writer(file,4); var reader=FrameFile.reader(file,4);
      var locks=java.nio.channels.FileChannel.open(temp.resolve("locking.bin.lock"),StandardOpenOption.WRITE)) {
   ByteBuffer plane=ByteBuffer.allocate(16);
   try(var held=locks.lock(0,3,false)) { assertEquals(-1,writer.publish(1,2,2,plane,plane.duplicate())); }
   int slot=writer.publish(2,2,2,plane,plane.duplicate());
   try(var held=locks.lock(slot,1,false)) { assertNull(reader.readLatest(null,null)); }
   assertEquals(2,reader.readLatest(null,null).frameId());
   try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
    var map=channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE,slot*stride,stride);
    map.putLong(0,map.getLong(0)|1L);
   }
   assertNull(reader.readLatest(null,null));
   int next=writer.publish(3,2,2,plane,plane.duplicate(),meta("three"));
   try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
    var map=channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE,next*stride,stride);
    map.put(FrameFile.HEADER,(byte)7);
   }
   assertNull(reader.readLatest(null,null),"corrupt pixels");
   int metadataSlot=writer.publish(4,2,2,plane,plane.duplicate(),meta("four"));
   try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
    var map=channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE,metadataSlot*stride,stride);
    map.put(44,(byte)'F');
   }
   assertNull(reader.readLatest(null,null),"corrupt metadata");
  }
 }
 @Test void crashedProcessReleasesSlotAndIncompleteFrameIsRejected() throws Exception {
  String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  String cp=Path.of(FrameFile.class.getProtectionDomain().getCodeSource().getLocation().toURI())+System.getProperty("path.separator")+
   Path.of(FrameFileProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  Path path=temp.resolve("crash.bin"); int n=128*128*4; ByteBuffer plane=ByteBuffer.allocate(n);
  try(var writer=FrameFile.writer(path,128*128); var reader=FrameFile.reader(path,128*128)) {
   int slot=writer.publish(1,128,128,plane,plane.duplicate());
   Process child=new ProcessBuilder(javaExecutable,"-cp",cp,FrameFileProcess.class.getName(),path.toString(),"crash",Integer.toString(slot)).redirectErrorStream(true).start();
   try { assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes())); }
   finally { if(child.isAlive()) child.destroyForcibly(); }
   assertNull(reader.readLatest(null,null));
   boolean reused=false;
   for(int i=2;i<8;i++) { int written=writer.publish(i,128,128,plane,plane.duplicate()); if(written==slot) reused=true; }
   assertTrue(reused,"OS must release lock after child exits");
   assertEquals(7,reader.readLatest(null,null).frameId());
  }
 }
 @Test void separateProcessWriterNeverExposesMixedPlanes() throws Exception {
  String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  String cp=Path.of(FrameFile.class.getProtectionDomain().getCodeSource().getLocation().toURI())+System.getProperty("path.separator")+
   Path.of(FrameFileProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  Path path=temp.resolve("process.bin");
  Process child=new ProcessBuilder(javaExecutable,"-cp",cp,FrameFileProcess.class.getName(),path.toString()).redirectErrorStream(true).start();
  FrameFile reader=null;
  try(var lines=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()))) {
   int accepted=0; String line; FrameFile.Latest cursor=null;
   // Each printed line means at least one frame exists; the reader polls rather than reading an announced slot.
   while((line=lines.readLine())!=null) {
    assertTrue(line.matches("\\d+"),line);
    if(reader==null) reader=FrameFile.reader(path,128*128);
    var latest=reader.readLatest(cursor,null);
    if(latest==null) continue;
    accepted++; byte expected=(byte)latest.frameId();
    assertEquals(Long.toString(latest.frameId()),new String(latest.metadata(),StandardCharsets.UTF_8));
    for(int i=0;i<128*128*4;i+=97) { assertEquals(expected,latest.pixels().color().get(i)); assertEquals(expected,latest.pixels().depth().get(i)); }
    cursor=latest;
   }
   assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
   assertEquals(0,child.exitValue()); assertTrue(accepted>0);
  } finally { if(reader!=null) reader.close(); if(child.isAlive()) child.destroyForcibly(); }
 }
}
