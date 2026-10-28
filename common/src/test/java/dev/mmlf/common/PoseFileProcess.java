package dev.mmlf.common;

import dev.mmlf.common.Messages.Pose;
import java.nio.file.Path;

/** Child JVM used by PoseFileTest; no test-framework dependency in the child process. */
public final class PoseFileProcess {
    public static void main(String[] args) throws Exception {
        Path path=Path.of(args[1]);
        if (args[0].equals("write")) {
            try (PoseFile file=PoseFile.publisher(path,"session")) {
                for(long i=1;i<=15000;i++) {
                    Pose p=new Pose(i,0,"minecraft:overworld",i,i,i,(float)i,0,70,640,480,i,i,i,new float[16],new float[16]);
                    while (!file.publish(i,i,p)) Thread.yield();
                    if (i%20==0) Thread.sleep(1);
                }
            }
        } else {
            long deadline=System.nanoTime()+15_000_000_000L;
            PoseFile file=null;
            while(file==null && System.nanoTime()<deadline) {
                try { file=PoseFile.reader(path,"session"); } catch(java.io.IOException e) { Thread.sleep(5); }
            }
            if(file==null) throw new AssertionError("publisher did not start");
            try(PoseFile opened=file) {
                long last=0; int accepted=0;
                while(System.nanoTime()<deadline) {
                    var snapshot=opened.read();
                    if(snapshot==null) continue;
                    long id=snapshot.sequence(); var p=snapshot.pose();
                    if(id<last || id!=p.cameraId() || id!=snapshot.publishedNanos() || p.x()!=id || p.y()!=id || p.playerZ()!=id)
                        throw new AssertionError("torn or regressing snapshot: "+id);
                    last=id; accepted++;
                    if(id==15000) break;
                }
                if(last!=15000 || accepted<10) throw new AssertionError("insufficient snapshots: "+accepted+" last="+last);
            }
        }
    }
}

