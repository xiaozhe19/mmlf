package dev.mmlf.bridge.render;

import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.common.InJvm;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Latest-value channels for render submissions (geometry, atlas, dynamic frames, textures). Runtimes in one JVM hand
 * over the encoded bytes through the JVM-wide registry ({@link InJvm}: a JDK {@code AtomicReference<byte[]>} per
 * channel), with no file I/O, so dynamic content can follow every frame. Runtimes in separate processes use one file
 * per channel, replaced atomically. A reader only ever sees complete values.
 */
public final class RenderChannel {
    private RenderChannel() {}
    /** Per-reader state: which value of a channel was last returned. */
    public static final class Cursor {
        private Object seen; private FileTime modified; private long size=-1;
        public void reset() { seen=null; modified=null; size=-1; }
    }

    private static boolean memory() { return BridgeCore.session().inJvm(); }
    private static String key(String runtime,String name) { return "render:"+BridgeCore.session().identity()+":"+runtime+":"+name; }
    @SuppressWarnings("unchecked")
    private static AtomicReference<byte[]> slot(String runtime,String name) {
        return (AtomicReference<byte[]>)InJvm.registry().computeIfAbsent(key(runtime,name),k->new AtomicReference<byte[]>());
    }
    private static Path file(String runtime,String name) { return BridgeCore.session().root().resolve(runtime+"."+name.replace('/','-')); }

    /** This runtime's value of {@code name}; replaces the previous one. */
    public static void publish(String name,byte[] data) throws IOException {
        String runtime=BridgeCore.runtimeId();
        if(memory()) { slot(runtime,name).set(data); return; }
        Path target=file(runtime,name),temp=target.resolveSibling(target.getFileName()+".tmp");
        Files.write(temp,data);
        Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }

    /** {@code runtime}'s newest value of {@code name} if it changed since {@code cursor} last returned one, else null. */
    public static byte[] poll(String runtime,String name,Cursor cursor,int maxBytes) throws IOException {
        if(memory()) {
            byte[] value=slot(runtime,name).get();
            if(value==null || value==cursor.seen) return null;
            if(value.length>maxBytes) throw new IOException(name+" too large");
            cursor.seen=value; return value;
        }
        Path path=file(runtime,name);
        if(!Files.isRegularFile(path)) return null;
        FileTime modified=Files.getLastModifiedTime(path); long size=Files.size(path);
        if(modified.equals(cursor.modified) && size==cursor.size) return null;
        if(size>maxBytes) throw new IOException(name+" too large");
        byte[] data=Files.readAllBytes(path);
        cursor.modified=modified; cursor.size=size;
        return data;
    }
}
