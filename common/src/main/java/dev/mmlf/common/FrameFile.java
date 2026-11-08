package dev.mmlf.common;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Three mapped pixel slots protected by nonblocking, cooperative per-slot OS file locks.
 * A separate lock file avoids locking a mapped region. Odd sequences are rejected.
 * Each slot carries its own metadata, so a reader finds the newest frame by polling; no announcement is needed.
 * The protocol targets local file systems; crash-interrupted publication requires further validation.
 * Slot: +0 long sequence, +8 long frame ID, +16 int width, +20 int height, +24 CRC32, +28 format magic,
 * +32 long writer generation, +40 int metadata length, +44 metadata bytes, +HEADER RGBA8 bytes, then float32 depth bytes.
 * The CRC covers metadata, colour and depth.
 *
 * <p>Two backings share that slot layout. A file (above) serves runtimes in different processes. In one JVM the slots
 * are direct buffers kept in {@link InJvm#registry()} under the frame name, locked by compare-and-set: no file
 * system, no OS locks and no checksum (the slot lock alone orders writer and reader). Only JDK types are shared, so
 * each runtime's own copy of this class reads the other's slots. A memory slot grows to the frame it holds.
 */
public final class FrameFile implements AutoCloseable {
    /** Default capacity covers a maximised 2560x1440 window; writer and reader of one file must agree. */
    public static final int MAX_PIXELS = 2560 * 1440;
    public static final int MAX_EDGE = 4096;
    static final int HEADER = 4096;
    public static final int MAX_METADATA = HEADER - 44;
    private static final int MAGIC = 0x4d464633;
    private static final byte[] NO_METADATA = new byte[0];
    private final long stride;
    private final int maxPixels;
    private final FileChannel channel, locks;
    /** A writer's generation is above every generation already in the file, so a restarted writer's frames win. */
    private final long generation;
    private int next;
    private final ByteBuffer[] slots = new ByteBuffer[3];
    /** Memory backing: the shared slots and their locks; null for a file. */
    private final AtomicReferenceArray<ByteBuffer> memory;
    private final AtomicIntegerArray memoryLocks;
    private final String memoryKey;
    /** Whether frames carry and are checked against a CRC (files only). */
    private final boolean checked;
    public record Pixels(ByteBuffer color, ByteBuffer depth) {}
    /** A complete frame; {@link #isNewerThan} orders frames by writer generation, then frame ID. */
    public record Latest(long generation, long frameId, int width, int height, byte[] metadata, Pixels pixels) {
        public boolean isNewerThan(Latest other) {
            return other == null || generation > other.generation || generation == other.generation && frameId > other.frameId;
        }
    }

    /** Creates or reuses the file for publishing. */
    public static FrameFile writer(Path file) throws IOException { return writer(file, MAX_PIXELS); }
    public static FrameFile writer(Path file, int maxPixels) throws IOException { return new FrameFile(file, maxPixels, true); }
    /** Opens a file a writer has already sized; throws while it does not exist yet. */
    public static FrameFile reader(Path file) throws IOException { return reader(file, MAX_PIXELS); }
    public static FrameFile reader(Path file, int maxPixels) throws IOException { return new FrameFile(file, maxPixels, false); }

    /** Registry key of an in-JVM frame channel: one per session and frame name. */
    public static String memoryKey(String sessionIdentity, String name) { return "frames:" + sessionIdentity + ":" + name; }
    /** In-JVM writer: creates the shared slots under {@code key}, or takes them over from an earlier writer. */
    public static FrameFile memoryWriter(String key, int maxPixels) throws IOException { return new FrameFile(key, maxPixels, true); }
    /** In-JVM reader; throws while no writer has created the slots yet. */
    public static FrameFile memoryReader(String key, int maxPixels) throws IOException { return new FrameFile(key, maxPixels, false); }

    /** Shared state of a memory channel, JDK types only: {slots, locks, last generation}. */
    private FrameFile(String key, int maxPixels, boolean writer) throws IOException {
        if (maxPixels < 1 || maxPixels > MAX_PIXELS) throw new IOException("invalid frame capacity");
        this.maxPixels = maxPixels; stride = HEADER + maxPixels * 8L;
        Object shared = writer
            ? InJvm.registry().computeIfAbsent(key, k -> new Object[]{new AtomicReferenceArray<ByteBuffer>(3), new AtomicIntegerArray(3), new java.util.concurrent.atomic.AtomicLong()})
            : InJvm.registry().get(key);
        if (!(shared instanceof Object[] parts) || parts.length != 3) throw new IOException("frame channel " + key + " not available");
        @SuppressWarnings("unchecked") AtomicReferenceArray<ByteBuffer> buffers = (AtomicReferenceArray<ByteBuffer>) parts[0];
        memory = buffers; memoryLocks = (AtomicIntegerArray) parts[1]; memoryKey = key;
        generation = writer ? ((java.util.concurrent.atomic.AtomicLong) parts[2]).incrementAndGet() : 0;
        channel = null; locks = null; checked = false;
    }

    private FrameFile(Path file, int maxPixels, boolean writer) throws IOException {
        if (maxPixels < 1 || maxPixels > MAX_PIXELS) throw new IOException("invalid frame capacity");
        this.maxPixels = maxPixels; stride = HEADER + maxPixels * 8L;
        Path lockPath = file.resolveSibling(file.getFileName()+".lock");
        FileChannel data = null, lock = null;
        try {
            if (writer) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                lock = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                data = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (data.size() < stride * 3) { data.position(stride * 3 - 1); data.write(ByteBuffer.wrap(new byte[]{0})); }
            } else {
                lock = FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
                data = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (data.size() < stride * 3) throw new IOException("frame file incomplete");
            }
            for (int i = 0; i < 3; i++) slots[i] = data.map(FileChannel.MapMode.READ_WRITE, stride * i, stride);
            long highest = 0;
            // Only the single writer changes generations, so reading them without the slot lock cannot tear.
            if (writer) for (ByteBuffer slot : slots) highest = Math.max(highest, slot.getLong(32));
            generation = highest + 1;
            channel=data; locks=lock;
            memory=null; memoryLocks=null; memoryKey=null; checked=true;
        } catch (IOException | RuntimeException e) {
            if (lock != null) lock.close();
            if (data != null) data.close();
            throw e;
        }
    }
    public int publish(long frame, int width, int height, ByteBuffer color, ByteBuffer depth) throws IOException {
        return publish(frame, width, height, color, depth, NO_METADATA);
    }
    /** Returns the published slot, or -1 if every slot is busy; never waits on another process. */
    public synchronized int publish(long frame, int width, int height, ByteBuffer color, ByteBuffer depth, byte[] metadata) throws IOException {
        int n = checkedSize(width, height);
        if (color.remaining() != n || depth.remaining() != n) throw new IOException("pixel buffer size mismatch");
        if (metadata.length > MAX_METADATA) throw new IOException("frame metadata too large");
        int checksum=checked?checksum(ByteBuffer.wrap(metadata),color,depth):0;
        for(int attempt=0;attempt<3;attempt++) {
            int slot=(next++)%3;
            try(SlotLock lock=tryLock(slot)) {
                if(lock==null) continue;
                ByteBuffer bytes=writable(slot,n);
                long seq=bytes.getLong(0); if((seq&1L)!=0) seq++;
                bytes.putLong(0,seq+1);
                java.lang.invoke.VarHandle.releaseFence();
                bytes.putLong(8,frame).putInt(16,width).putInt(20,height).putInt(24,checksum).putInt(28,MAGIC);
                bytes.putLong(32,generation).putInt(40,metadata.length).put(44,metadata);
                bytes.put(HEADER,color,color.position(),n);
                bytes.put(HEADER+n,depth,depth.position(),n);
                java.lang.invoke.VarHandle.releaseFence();
                bytes.putLong(0,seq+2);
                return slot;
            }
        }
        return -1;
    }
    /**
     * Returns the newest complete frame newer than {@code after}, or null when there is none, every newer slot is busy
     * or fails its checksum. Pixel buffers of {@code reuse} are overwritten even when null is returned.
     */
    public synchronized Latest readLatest(Latest after, Pixels reuse) throws IOException {
        Latest[] headers=new Latest[3];
        for(int slot=0;slot<3;slot++) try(SlotLock lock=tryLock(slot)) {
            if(lock!=null) { ByteBuffer bytes=slot(slot); if(bytes!=null) headers[slot]=header(bytes); }
        }
        for(int attempt=0;attempt<3;attempt++) {
            int best=-1;
            for(int slot=0;slot<3;slot++) if(headers[slot]!=null && headers[slot].isNewerThan(after) && (best<0 || headers[slot].isNewerThan(headers[best]))) best=slot;
            if(best<0) return null;
            Latest frame=read(best,headers[best],reuse);
            if(frame!=null) return frame;
            headers[best]=null;
        }
        return null;
    }
    private Latest header(ByteBuffer bytes) {
        long sequence=bytes.getLong(0);
        if((sequence&1L)!=0 || sequence==0 || bytes.getInt(28)!=MAGIC) return null;
        int width=bytes.getInt(16),height=bytes.getInt(20),length=bytes.getInt(40);
        if(!fits(width,height,maxPixels) || length<0 || length>MAX_METADATA) return null;
        if(bytes.capacity()<HEADER+(long)width*height*8) return null;
        return new Latest(bytes.getLong(32),bytes.getLong(8),width,height,null,null);
    }
    private Latest read(int slot,Latest expected,Pixels reuse) throws IOException {
        int n=expected.width()*expected.height()*4;
        ByteBuffer color,depth; byte[] metadata; int checksum;
        try(SlotLock lock=tryLock(slot)) {
            if(lock==null) return null;
            ByteBuffer bytes=slot(slot);
            Latest current=bytes==null?null:header(bytes);
            // The slot may have been rewritten between the header scan and this read.
            if(current==null || current.generation()!=expected.generation() || current.frameId()!=expected.frameId()
                || current.width()!=expected.width() || current.height()!=expected.height()) return null;
            checksum=bytes.getInt(24);
            metadata=new byte[bytes.getInt(40)]; bytes.get(44,metadata);
            color=reuse!=null && reuse.color().capacity()>=n ? reuse.color() : ByteBuffer.allocateDirect(n);
            depth=reuse!=null && reuse.depth().capacity()>=n ? reuse.depth() : ByteBuffer.allocateDirect(n);
            color.clear().limit(n); depth.clear().limit(n);
            color.put(0,bytes,HEADER,n); depth.put(0,bytes,HEADER+n,n);
        }
        if(checked && checksum(ByteBuffer.wrap(metadata),color,depth)!=checksum) return null;
        return new Latest(expected.generation(),expected.frameId(),expected.width(),expected.height(),metadata,new Pixels(color,depth));
    }
    private static int checksum(ByteBuffer metadata,ByteBuffer color,ByteBuffer depth) {
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();
        crc.update(metadata.duplicate()); crc.update(color.duplicate()); crc.update(depth.duplicate());
        return (int)crc.getValue();
    }
    /** A held slot lock; null from {@link #tryLock} means the slot is busy. */
    private interface SlotLock extends AutoCloseable { void close() throws IOException; }
    private SlotLock tryLock(int slot) throws IOException {
        if(memory!=null) {
            if(!memoryLocks.compareAndSet(slot,0,1)) return null;
            return ()->memoryLocks.set(slot,0);
        }
        try { FileLock lock=locks.tryLock(slot,1,false); return lock==null?null:lock::close; }
        catch(OverlappingFileLockException e) { return null; }
    }
    /** The slot's bytes; for memory, whatever buffer it holds now (null before its first frame). Hold its lock. */
    private ByteBuffer slot(int slot) { return memory!=null?memory.get(slot):slots[slot]; }
    /** The slot's bytes able to hold {@code n} colour bytes and as many depth bytes; a memory slot grows. Hold its lock. */
    private ByteBuffer writable(int slot,int n) {
        if(memory==null) return slots[slot];
        ByteBuffer bytes=memory.get(slot);
        long needed=HEADER+2L*n;
        if(bytes==null || bytes.capacity()<needed) {
            // Earlier frames' header values carry over, so sequences keep growing and a stale header is never newer.
            ByteBuffer grown=ByteBuffer.allocateDirect((int) Math.min(stride,Math.max(needed,needed+needed/4)));
            if(bytes!=null) grown.put(0,bytes,0,HEADER);
            memory.set(slot,grown);
            bytes=grown;
        }
        return bytes;
    }
    /** Whether this channel is the in-JVM memory backing. */
    public boolean inMemory() { return memory!=null; }
    public static boolean fits(int w, int h, int maxPixels) { return w >= 1 && h >= 1 && w <= MAX_EDGE && h <= MAX_EDGE && (long) w * h <= maxPixels; }
    private int checkedSize(int w, int h) throws IOException {
        if (!fits(w, h, maxPixels)) throw new IOException("unsupported frame size " + w + "x" + h);
        return Math.multiplyExact(Math.multiplyExact(w,h),4);
    }
    /** A file is closed; memory slots stay with the session (another writer or reader may hold them) until it ends. */
    public void close() throws IOException { if(memory!=null) return; try { channel.close(); } finally { locks.close(); } }
    /** Releases a session's in-JVM frame channels; their slots are freed once no channel object refers to them. */
    public static void releaseMemory(String sessionIdentity) { InJvm.registry().keySet().removeIf(k->k.startsWith("frames:"+sessionIdentity+":")); }
}
