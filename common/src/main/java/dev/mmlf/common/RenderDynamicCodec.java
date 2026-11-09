package dev.mmlf.common;

import java.io.*;
import java.util.*;

/**
 * One frame of a runtime's dynamic content (block entities, entities): vertex batches recorded from the vanilla render
 * dispatchers, positioned relative to a double-precision origin. Each batch names the render type it was recorded
 * with, the texture that type binds, its vertex format and primitive mode, so the receiver can rebuild the batch in
 * an equivalent render type of its own instead of reinterpreting bytes.
 */
public final class RenderDynamicCodec {
    private RenderDynamicCodec() {}
    private static final int MAGIC=0x4d4d4459, VERSION=2;
    public static final int MAX_BYTES=64*1024*1024;
    private static final int MAX_BATCHES=4096;
    public record Batch(String renderType,String texture,String format,String mode,int vertexCount,byte[] vertices) {
        public Batch {
            Objects.requireNonNull(renderType); Objects.requireNonNull(texture); Objects.requireNonNull(format); Objects.requireNonNull(mode);
            if(renderType.isBlank() || format.isBlank() || mode.isBlank() || vertexCount<0 || vertices==null) throw new IllegalArgumentException("invalid dynamic batch");
        }
    }
    /**
     * {@code generation} is the source's scene generation; {@code frame} increases with every capture;
     * {@code cameraSequence} is the render host's camera pose the capture followed (0 when unknown), for frame alignment.
     */
    public record Frame(String runtime,String dimension,long generation,long frame,long cameraSequence,double originX,double originY,double originZ,List<Batch> batches) {
        public Frame { batches=List.copyOf(batches); }
    }

    public static byte[] encode(Frame frame) throws IOException {
        if(frame.runtime()==null || frame.runtime().isBlank() || frame.dimension()==null || frame.dimension().isBlank()) throw new IOException("missing scene identity");
        if(frame.batches().size()>MAX_BATCHES) throw new IOException("too many dynamic batches");
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION);
        out.writeUTF(frame.runtime()); out.writeUTF(frame.dimension()); out.writeLong(frame.generation()); out.writeLong(frame.frame()); out.writeLong(frame.cameraSequence());
        out.writeDouble(frame.originX()); out.writeDouble(frame.originY()); out.writeDouble(frame.originZ());
        out.writeInt(frame.batches().size());
        for(var batch:frame.batches()) {
            if((long)bytes.size()+batch.vertices().length>MAX_BYTES) throw new IOException("dynamic frame too large");
            out.writeUTF(batch.renderType()); out.writeUTF(batch.texture()); out.writeUTF(batch.format()); out.writeUTF(batch.mode());
            out.writeInt(batch.vertexCount()); out.writeInt(batch.vertices().length); out.write(batch.vertices());
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static Frame decode(byte[] data) throws IOException {
        if(data.length>MAX_BYTES+65536) throw new IOException("dynamic frame too large");
        var in=new DataInputStream(new ByteArrayInputStream(data));
        if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("unsupported dynamic frame");
        String runtime=in.readUTF(),dimension=in.readUTF(); long generation=in.readLong(),frame=in.readLong(),camera=in.readLong();
        double x=in.readDouble(),y=in.readDouble(),z=in.readDouble();
        int count=in.readInt();
        if(count<0 || count>MAX_BATCHES) throw new IOException("invalid batch count");
        var batches=new ArrayList<Batch>(count);
        for(int i=0;i<count;i++) {
            String type=in.readUTF(),texture=in.readUTF(),format=in.readUTF(),mode=in.readUTF();
            int vertices=in.readInt(),length=in.readInt();
            if(vertices<0 || length<0 || length>in.available()) throw new IOException("invalid batch data");
            try { batches.add(new Batch(type,texture,format,mode,vertices,in.readNBytes(length))); }
            catch(IllegalArgumentException e) { throw new IOException("invalid dynamic batch",e); }
        }
        if(in.available()!=0) throw new IOException("trailing dynamic frame data");
        return new Frame(runtime,dimension,generation,frame,camera,x,y,z,batches);
    }
}
