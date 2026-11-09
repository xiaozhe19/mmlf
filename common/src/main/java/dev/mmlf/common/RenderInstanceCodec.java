package dev.mmlf.common;

import java.io.*;
import java.util.*;

/** Full dynamic-instance snapshot: missing instance IDs mean removal. Resource IDs are scoped to runtime. */
public final class RenderInstanceCodec {
    private static final int MAGIC=0x4d4d5249, VERSION=1, MAX_INSTANCES=65536;
    public static final int MAX_BYTES=16*1024*1024;
    public record Batch(String runtime,String dimension,RenderInstanceStore.Snapshot snapshot) {}
    private RenderInstanceCodec() {}

    public static byte[] encode(Batch batch) throws IOException {
        if(batch.runtime()==null || batch.runtime().isBlank() || batch.dimension()==null || batch.dimension().isBlank()) throw new IOException("missing scene identity");
        var instances=new TreeMap<>(batch.snapshot().instances());
        if(instances.size()>MAX_INSTANCES) throw new IOException("too many instances");
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(batch.runtime()); out.writeUTF(batch.dimension());
        out.writeLong(batch.snapshot().generation()); out.writeLong(batch.snapshot().version()); out.writeInt(instances.size());
        for(var entry:instances.entrySet()) {
            var instance=entry.getValue();
            if(!entry.getKey().equals(instance.id())) throw new IOException("instance key mismatch");
            out.writeUTF(instance.id()); out.writeUTF(instance.mesh()); out.writeUTF(instance.material()); out.writeLong(instance.revision());
            for(float value:instance.transform()) out.writeFloat(value);
            if(bytes.size()>MAX_BYTES) throw new IOException("instance batch too large");
        }
        out.flush(); return bytes.toByteArray();
    }

    public static Batch decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES) throw new IOException("instance batch too large");
        var in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("unsupported instance format");
        String runtime=in.readUTF(),dimension=in.readUTF(); long generation=in.readLong(),version=in.readLong(); int count=in.readInt();
        if(runtime.isBlank() || dimension.isBlank() || generation<0 || version<0 || count<0 || count>MAX_INSTANCES) throw new IOException("invalid instance batch");
        Map<String,RenderInstanceStore.Instance> instances=new HashMap<>();
        try {
            for(int i=0;i<count;i++) {
                String id=in.readUTF(),mesh=in.readUTF(),material=in.readUTF(); long revision=in.readLong();
                float[] transform=new float[16]; for(int j=0;j<transform.length;j++) transform[j]=in.readFloat();
                if(instances.put(id,new RenderInstanceStore.Instance(id,mesh,material,revision,transform))!=null) throw new IOException("duplicate instance");
            }
        } catch(IllegalArgumentException e) { throw new IOException("invalid instance",e); }
        if(in.available()!=0) throw new IOException("trailing instance bytes");
        return new Batch(runtime,dimension,new RenderInstanceStore.Snapshot(generation,version,instances));
    }
}
