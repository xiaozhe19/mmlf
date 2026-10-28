package dev.mmlf.common;

import dev.mmlf.common.Messages.Pose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PoseFileTest {
    @TempDir(cleanup=CleanupMode.NEVER) Path temp;
    @Test void roundTripAndSessionIsolation() throws Exception {
        Path path=temp.resolve("pose");
        Pose pose=new Pose(4,2,"minecraft:overworld",1,2,3,4,5,70,640,480,6,7,8,new float[16],new float[16]);
        try(var writer=PoseFile.publisher(path,"first"); var reader=PoseFile.reader(path,"first"); var other=PoseFile.reader(path,"other")) {
            assertNull(reader.read());
            assertThrows(java.io.IOException.class,()->PoseFile.publisher(path,"first"));
            assertTrue(writer.publish(9,123,pose));
            var actual=reader.read(); assertNotNull(actual);
            assertEquals(9,actual.sequence()); assertEquals(123,actual.publishedNanos());
            assertEquals(pose.dimension(),actual.pose().dimension()); assertEquals(8,actual.pose().playerZ());
            assertNull(other.read());
        }
    }
    @Test void rejectsNonfiniteFovPlayerAndMatrices() throws Exception {
        Path path=temp.resolve("invalid");
        float[] invalid=new float[16]; invalid[3]=Float.NaN;
        try(var writer=PoseFile.publisher(path,"session")) {
            assertThrows(IllegalArgumentException.class,()->writer.publish(1,1,new Pose(1,0,"world",0,0,0,0,0,Float.NaN,10,10,0,0,0,new float[16],new float[16])));
            assertThrows(IllegalArgumentException.class,()->writer.publish(1,1,new Pose(1,0,"world",0,0,0,0,0,70,10,10,Double.NaN,0,0,new float[16],new float[16])));
            assertThrows(IllegalArgumentException.class,()->writer.publish(1,1,new Pose(1,0,"world",0,0,0,0,0,70,10,10,0,0,0,invalid,new float[16])));
        }
    }
    @Test void publisherRestartInvalidatesOldSnapshot() throws Exception {
        Path path=temp.resolve("restart");
        Pose pose=new Pose(4,2,"minecraft:overworld",1,2,3,4,5,70,640,480,6,7,8,new float[16],new float[16]);
        try(var first=PoseFile.publisher(path,"session")) { assertTrue(first.publish(1,1,pose)); }
        try(var reader=PoseFile.reader(path,"session"); var second=PoseFile.publisher(path,"session")) {
            assertNull(reader.read());
            assertTrue(second.publish(1,2,pose)); assertEquals(2,reader.read().publishedNanos());
        }
    }
    @Test void publisherStartsAfterBriefReaderContention() throws Exception {
        Path path=temp.resolve("retry");
        try(var initial=PoseFile.publisher(path,"session")) { }
        try(var lock=java.nio.channels.FileChannel.open(temp.resolve("retry.lock"),java.nio.file.StandardOpenOption.WRITE)) {
            var held=lock.lock(0,1,false);
            var result=new java.util.concurrent.atomic.AtomicReference<PoseFile>();
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread starter=new Thread(()->{ try { result.set(PoseFile.publisher(path,"session")); } catch(Throwable e) { failure.set(e); } });
            starter.start(); Thread.sleep(20); held.release(); starter.join(2000);
            assertFalse(starter.isAlive()); assertNull(failure.get()); assertNotNull(result.get()); result.get().close();
        }
    }
    @Test void contentionAndCorruptionAreRejected() throws Exception {
        Path path=temp.resolve("corrupt");
        Pose pose=new Pose(4,2,"minecraft:overworld",1,2,3,4,5,70,640,480,6,7,8,new float[16],new float[16]);
        try(var writer=PoseFile.publisher(path,"session"); var reader=PoseFile.reader(path,"session");
            var lock=java.nio.channels.FileChannel.open(temp.resolve("corrupt.lock"),java.nio.file.StandardOpenOption.WRITE)) {
            assertThrows(java.io.IOException.class,()->reader.publish(1,1,pose));
            try(var held=lock.lock(0,1,false)) { assertFalse(writer.publish(1,1,pose)); assertNull(reader.read()); }
            assertTrue(writer.publish(2,2,pose)); assertNotNull(reader.read());
            try(var channel=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.READ,java.nio.file.StandardOpenOption.WRITE)) {
                var map=channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE,0,PoseFile.SIZE);
                map.put(100,(byte)(map.get(100)^1));
            }
            assertNull(reader.read());
        }
    }
    @Test void writerAndTwoReaderProcessesObserveOnlyCompleteSnapshots() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        if(!System.getProperty("os.name").startsWith("Windows")) java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String cp=Path.of(PoseFile.class.getProtectionDomain().getCodeSource().getLocation().toURI())+System.getProperty("path.separator")+
            Path.of(PoseFileProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String path=temp.resolve("concurrent").toString();
        Process a=new ProcessBuilder(java,"-cp",cp,PoseFileProcess.class.getName(),"read",path).redirectErrorStream(true).start();
        Process b=new ProcessBuilder(java,"-cp",cp,PoseFileProcess.class.getName(),"read",path).redirectErrorStream(true).start();
        Process writer=new ProcessBuilder(java,"-cp",cp,PoseFileProcess.class.getName(),"write",path).redirectErrorStream(true).start();
        try {
            for(Process p:new Process[]{writer,a,b}) {
                assertTrue(p.waitFor(20,TimeUnit.SECONDS),"child timed out");
                assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));
            }
        } finally { for(Process p:new Process[]{writer,a,b}) if(p.isAlive()) p.destroyForcibly(); }
    }
}
