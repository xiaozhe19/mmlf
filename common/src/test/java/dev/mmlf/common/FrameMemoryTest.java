package dev.mmlf.common;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The in-JVM backing of {@link FrameFile}: same protocol as the file, slots in direct memory, CAS slot locks. */
class FrameMemoryTest {
    private static byte[] meta(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String key(String name) { return FrameFile.memoryKey("test-"+System.nanoTime(),name); }

    @Test void newestFrameAndGrowingSlots() throws Exception {
        String key=key("world");
        assertThrows(java.io.IOException.class,()->FrameFile.memoryReader(key,FrameFile.MAX_PIXELS),"no writer yet");
        try(FrameFile writer=FrameFile.memoryWriter(key,FrameFile.MAX_PIXELS); FrameFile reader=FrameFile.memoryReader(key,FrameFile.MAX_PIXELS)) {
            assertTrue(writer.inMemory());
            assertNull(reader.readLatest(null,null));
            ByteBuffer small=ByteBuffer.allocateDirect(8).putLong(0,0x0102030405060708L);
            writer.publish(1,2,1,small,small.duplicate(),meta("one"));
            var first=reader.readLatest(null,null);
            assertEquals(1,first.frameId()); assertEquals("one",new String(first.metadata(),StandardCharsets.UTF_8));
            assertEquals(0x0102030405060708L,first.pixels().color().getLong(0));
            // A bigger frame grows the slots it lands in; frames stay ordered across the growth.
            int w=640,h=360,n=w*h*4;
            ByteBuffer big=ByteBuffer.allocateDirect(n); big.put(n-1,(byte)7);
            for(int id=2;id<=4;id++) writer.publish(id,w,h,big.duplicate(),big.duplicate(),meta("big"));
            var latest=reader.readLatest(first,null);
            assertEquals(4,latest.frameId()); assertEquals(w,latest.width()); assertEquals(7,latest.pixels().depth().get(n-1));
            assertNull(reader.readLatest(latest,null));
        } finally { FrameFile.releaseMemory(key.split(":")[1]); }
        assertNull(InJvm.registry().get(key),"released with its session");
    }

    @Test void restartedWriterWins() throws Exception {
        String key=key("restart");
        ByteBuffer plane=ByteBuffer.allocateDirect(16);
        FrameFile reader;
        FrameFile.Latest seen;
        try(var writer=FrameFile.memoryWriter(key,4)) {
            reader=FrameFile.memoryReader(key,4);
            for(int id=1;id<=50;id++) writer.publish(id,2,2,plane.duplicate(),plane.duplicate());
            seen=reader.readLatest(null,null); assertEquals(50,seen.frameId());
        }
        try(var writer=FrameFile.memoryWriter(key,4)) {
            writer.publish(1,2,2,plane.duplicate(),plane.duplicate());
            var restarted=reader.readLatest(seen,null);
            assertNotNull(restarted); assertEquals(1,restarted.frameId()); assertTrue(restarted.generation()>seen.generation());
        } finally { FrameFile.releaseMemory(key.split(":")[1]); }
    }

    /** Without a checksum the slot lock alone must keep a reader from seeing half of one frame and half of another. */
    @Test void concurrentWriterNeverYieldsATornFrame() throws Exception {
        String key=key("torn");
        int w=256,h=256,n=w*h*4;
        try(FrameFile writer=FrameFile.memoryWriter(key,FrameFile.MAX_PIXELS); FrameFile reader=FrameFile.memoryReader(key,FrameFile.MAX_PIXELS)) {
            AtomicBoolean stop=new AtomicBoolean();
            Thread thread=new Thread(()->{
                ByteBuffer plane=ByteBuffer.allocateDirect(n);
                for(long id=1;!stop.get();id++) {
                    byte value=(byte)id; for(int i=0;i<n;i+=64) plane.put(i,value);
                    plane.put(n-1,value);
                    try { writer.publish(id,w,h,plane.duplicate(),plane.duplicate(),meta(Long.toString(id))); }
                    catch(java.io.IOException e) { throw new RuntimeException(e); }
                }
            });
            thread.start();
            FrameFile.Latest cursor=null; int frames=0;
            long deadline=System.nanoTime()+1_000_000_000L;
            while(System.nanoTime()<deadline) {
                var latest=reader.readLatest(cursor,cursor==null?null:cursor.pixels());
                if(latest==null) continue;
                byte expected=(byte) Long.parseLong(new String(latest.metadata(),StandardCharsets.UTF_8));
                for(int i=0;i<n;i+=64) { assertEquals(expected,latest.pixels().color().get(i)); assertEquals(expected,latest.pixels().depth().get(i)); }
                assertEquals(expected,latest.pixels().color().get(n-1));
                cursor=latest; frames++;
            }
            stop.set(true); thread.join();
            assertTrue(frames>10,"frames read: "+frames);
        } finally { FrameFile.releaseMemory(key.split(":")[1]); }
    }

    /** Writer and reader in different class loaders, as two runtimes in one JVM are: only JDK types meet. */
    @Test void runtimesWithTheirOwnCopiesShareFrames() throws Exception {
        String key=key("loaders");
        URL classes=FrameFile.class.getProtectionDomain().getCodeSource().getLocation();
        try(var a=new URLClassLoader(new URL[]{classes},ClassLoader.getPlatformClassLoader());
            var b=new URLClassLoader(new URL[]{classes},ClassLoader.getPlatformClassLoader())) {
            Class<?> fa=a.loadClass(FrameFile.class.getName()), fb=b.loadClass(FrameFile.class.getName());
            assertNotSame(fa,fb);
            Object writer=fa.getMethod("memoryWriter",String.class,int.class).invoke(null,key,16);
            Object reader=fb.getMethod("memoryReader",String.class,int.class).invoke(null,key,16);
            ByteBuffer plane=ByteBuffer.allocateDirect(16).putInt(0,42);
            fa.getMethod("publish",long.class,int.class,int.class,ByteBuffer.class,ByteBuffer.class,byte[].class).invoke(writer,9L,2,2,plane,plane.duplicate(),meta("x"));
            Class<?> latestType=b.loadClass(FrameFile.class.getName()+"$Latest");
            Object latest=fb.getMethod("readLatest",latestType,b.loadClass(FrameFile.class.getName()+"$Pixels")).invoke(reader,null,null);
            assertEquals(9L,latestType.getMethod("frameId").invoke(latest));
        } finally { FrameFile.releaseMemory(key.split(":")[1]); }
    }
}
