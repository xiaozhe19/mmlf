package dev.mmlf.common;

import java.io.*;
import java.util.Objects;

/** Versioned texture resource; format and dimensions are explicit rather than inferred from a Mod. */
public final class RenderTextureCodec {
    private static final int MAGIC=0x4d4d5254, VERSION=1;
    public static final int MAX_BYTES=64*1024*1024;
    public record Texture(String runtime,String dimension,long generation,String resource,String format,int width,int height,byte[] data) {
        public Texture {
            Objects.requireNonNull(runtime); Objects.requireNonNull(dimension); Objects.requireNonNull(resource); Objects.requireNonNull(format);
            if(runtime.isBlank() || dimension.isBlank() || resource.isBlank() || format.isBlank() || generation<0 || width<1 || height<1 || width>8192 || height>8192 || data==null || data.length>MAX_BYTES)
                throw new IllegalArgumentException("invalid texture resource");
            data=data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
    }
    private RenderTextureCodec() {}
    public static byte[] encode(Texture texture) throws IOException {
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(texture.runtime()); out.writeUTF(texture.dimension());
        out.writeLong(texture.generation()); out.writeUTF(texture.resource()); out.writeUTF(texture.format());
        out.writeInt(texture.width()); out.writeInt(texture.height()); byte[] data=texture.data(); out.writeInt(data.length); out.write(data);
        out.flush();
        if(bytes.size()>MAX_BYTES+65536) throw new IOException("texture batch too large");
        return bytes.toByteArray();
    }
    public static Texture decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES+65536) throw new IOException("texture batch too large");
        var in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("unsupported texture format");
        String runtime=in.readUTF(),dimension=in.readUTF(); long generation=in.readLong();
        String resource=in.readUTF(),format=in.readUTF(); int width=in.readInt(),height=in.readInt(),length=in.readInt();
        if(length<0 || length>MAX_BYTES || length!=in.available()) throw new IOException("invalid texture data");
        try { return new Texture(runtime,dimension,generation,resource,format,width,height,in.readNBytes(length)); }
        catch(IllegalArgumentException e) { throw new IOException("invalid texture resource",e); }
    }
}
