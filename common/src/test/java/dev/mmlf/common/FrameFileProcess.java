package dev.mmlf.common;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Writes frames from a separate JVM; each published frame ID is printed so the parent knows to poll. */
public final class FrameFileProcess {
    public static void main(String[] args) throws Exception {
        int n=128*128*4;
        if(args.length>1 && args[1].equals("crash")) {
            Path path=Path.of(args[0]); int slot=Integer.parseInt(args[2]); long stride=FrameFile.HEADER+128L*128*8;
            try(var locks=java.nio.channels.FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),java.nio.file.StandardOpenOption.WRITE);
                var held=locks.lock(slot,1,false);
                var data=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.READ,java.nio.file.StandardOpenOption.WRITE)) {
                var map=data.map(java.nio.channels.FileChannel.MapMode.READ_WRITE,slot*stride,stride);
                map.putLong(0,map.getLong(0)|1L); map.put(FrameFile.HEADER,(byte)99);
                Runtime.getRuntime().halt(0);
            }
        }
        try(var writer=FrameFile.writer(Path.of(args[0]),128*128)) {
            ByteBuffer plane=ByteBuffer.allocateDirect(n);
            for(long id=1;id<=3000;id++) {
                byte value=(byte)id;
                for(int i=0;i<n;i++) plane.put(i,value);
                int slot=writer.publish(id,128,128,plane.duplicate(),plane.duplicate(),Long.toString(id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if(slot>=0) System.out.println(id);
            }
        }
    }
}
