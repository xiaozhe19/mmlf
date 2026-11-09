package dev.mmlf.common;

import java.io.*;
import java.util.*;

/**
 * Current frames of a runtime's animated atlas sprites: RGBA8 rectangles per mip level, to be written into the copy
 * of that atlas the other runtime holds. Tied to the atlas's scene generation and size, so a patch never lands on a
 * different atlas.
 */
public final class RenderAtlasPatchCodec {
    private RenderAtlasPatchCodec() {}
    private static final int MAGIC=0x4d4d4150, VERSION=1;
    public static final int MAX_BYTES=32*1024*1024;
    private static final int MAX_PATCHES=1<<16;
    public record Patch(int level,int x,int y,int width,int height,byte[] rgba) {
        public Patch {
            if(level<0 || level>16 || x<0 || y<0 || width<1 || height<1 || rgba==null || (long)width*height*4!=rgba.length) throw new IllegalArgumentException("invalid atlas patch");
        }
    }
    public record Batch(String runtime,long generation,int atlasWidth,int atlasHeight,List<Patch> patches) {
        public Batch { patches=List.copyOf(patches); }
    }

    public static byte[] encode(Batch batch) throws IOException {
        if(batch.runtime()==null || batch.runtime().isBlank() || batch.patches().size()>MAX_PATCHES) throw new IOException("invalid atlas patch batch");
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(batch.runtime()); out.writeLong(batch.generation());
        out.writeInt(batch.atlasWidth()); out.writeInt(batch.atlasHeight()); out.writeInt(batch.patches().size());
        for(var p:batch.patches()) {
            if((long)bytes.size()+p.rgba().length>MAX_BYTES) throw new IOException("atlas patch batch too large");
            out.writeByte(p.level()); out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.width()); out.writeInt(p.height()); out.write(p.rgba());
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static Batch decode(byte[] data) throws IOException {
        if(data.length>MAX_BYTES+65536) throw new IOException("atlas patch batch too large");
        var in=new DataInputStream(new ByteArrayInputStream(data));
        if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("unsupported atlas patch batch");
        String runtime=in.readUTF(); long generation=in.readLong(); int width=in.readInt(),height=in.readInt(),count=in.readInt();
        if(count<0 || count>MAX_PATCHES || width<1 || height<1) throw new IOException("invalid atlas patch batch");
        var patches=new ArrayList<Patch>(count);
        for(int i=0;i<count;i++) {
            int level=in.readUnsignedByte(),x=in.readInt(),y=in.readInt(),w=in.readInt(),h=in.readInt();
            long length=(long)w*h*4;
            if(w<1 || h<1 || length>in.available() || x+w>width || y+h>height) throw new IOException("invalid atlas patch");
            try { patches.add(new Patch(level,x,y,w,h,in.readNBytes((int)length))); }
            catch(IllegalArgumentException e) { throw new IOException("invalid atlas patch",e); }
        }
        if(in.available()!=0) throw new IOException("trailing atlas patch data");
        return new Batch(runtime,generation,width,height,patches);
    }
}
