package dev.mmlf.common;

import dev.mmlf.common.Messages.Pose;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;

/** A single publisher and nonblocking readers of a fixed-size, session-bound pose snapshot.
 * All cooperating processes exclusively try-lock a separate lock file before touching the mapping.
 * A failed lock means drop this update/read, never wait on the render thread.
 */
public final class PoseFile implements AutoCloseable {
    public static final int SIZE = 512, VERSION = 1;
    private static final int MAGIC = 0x4d4d4c50, DIMENSION_BYTES = 128, CHECKSUM = SIZE - 4;
    private final FileChannel data, lockChannel;
    private FileLock publisherLock;
    private final MappedByteBuffer mapping;
    private final byte[] sessionHash;
    private final boolean publisher;
    public record Snapshot(long sequence, long publishedNanos, Pose pose) {}

    private PoseFile(Path path, String session, boolean publisher) throws IOException {
        this.publisher = publisher;
        if (session == null || session.isBlank()) throw new IOException("missing session identity");
        try { sessionHash = MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
        FileChannel lock = null, file = null;
        try {
            if (publisher) {
                java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
                lock = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (file.size() < SIZE) { file.position(SIZE - 1); file.write(ByteBuffer.wrap(new byte[1])); }
            } else {
                lock = FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
                file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (file.size() < SIZE) throw new IOException("pose file incomplete");
            }
            mapping = file.map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            data = file; lockChannel = lock;
            if (publisher) {
                try { publisherLock=lockChannel.tryLock(1,1,false); }
                catch (OverlappingFileLockException e) { throw new IOException("pose publisher already exists",e); }
                if (publisherLock==null) throw new IOException("pose publisher already exists");
                // Startup only: a reader may briefly hold the snapshot lock while a publisher restarts.
                // Bound the wait; per-frame publish and read remain strictly nonblocking.
                long deadline=System.nanoTime()+250_000_000L;
                FileLock acquired=tryLock();
                while(acquired==null && System.nanoTime()-deadline<0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                    if(Thread.currentThread().isInterrupted()) throw new IOException("pose initialization interrupted");
                    acquired=tryLock();
                }
                if(acquired==null) throw new IOException("pose initialization lock busy after 250 ms");
                try (FileLock initial=acquired) { mapping.put(0, new byte[SIZE]); }
            }
        } catch (IOException | RuntimeException e) {
            if (publisherLock != null) publisherLock.release();
            if (file != null) file.close();
            if (lock != null) lock.close();
            throw e;
        }
    }
    public static PoseFile publisher(Path path, String session) throws IOException { return new PoseFile(path, session, true); }
    public static PoseFile reader(Path path, String session) throws IOException { return new PoseFile(path, session, false); }

    /** Returns false on contention. The caller may drop the state and publish a newer one next frame. */
    public boolean publish(long sequence, long publishedNanos, Pose pose) throws IOException {
        if (!publisher) throw new IOException("reader cannot publish");
        if (sequence <= 0 || !validPose(pose)) throw new IllegalArgumentException("invalid snapshot");
        byte[] dimension = pose.dimension().getBytes(StandardCharsets.UTF_8);
        if (dimension.length == 0 || dimension.length > DIMENSION_BYTES || pose.view() == null || pose.view().length != 16
            || pose.projection() == null || pose.projection().length != 16) throw new IllegalArgumentException("invalid pose fields");
        ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(VERSION).put(sessionHash).putLong(sequence).putLong(publishedNanos);
        b.putLong(pose.cameraId()).putLong(pose.epoch()).putInt(dimension.length).put(dimension);
        b.position(76 + DIMENSION_BYTES);
        b.putDouble(pose.x()).putDouble(pose.y()).putDouble(pose.z());
        b.putFloat(pose.yaw()).putFloat(pose.pitch()).putFloat(pose.fov()).putInt(pose.width()).putInt(pose.height());
        b.putDouble(pose.playerX()).putDouble(pose.playerY()).putDouble(pose.playerZ());
        for (float v : pose.view()) b.putFloat(v);
        for (float v : pose.projection()) b.putFloat(v);
        CRC32 crc = new CRC32(); crc.update(b.array(), 0, CHECKSUM); b.putInt(CHECKSUM, (int)crc.getValue());
        try (FileLock ignored = tryLock()) {
            if (ignored == null) return false;
            mapping.put(0, b.array());
            return true;
        }
    }
    /** Returns null for contention, incomplete, stale-session, corrupt or incompatible data. */
    public Snapshot read() throws IOException {
        byte[] bytes = new byte[SIZE];
        try (FileLock ignored = tryLock()) {
            if (ignored == null) return null;
            mapping.get(0, bytes);
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        CRC32 crc = new CRC32(); crc.update(bytes, 0, CHECKSUM);
        if (b.getInt(CHECKSUM) != (int)crc.getValue() || b.getInt() != MAGIC || b.getInt() != VERSION) return null;
        byte[] identity = new byte[32]; b.get(identity); if (!Arrays.equals(identity, sessionHash)) return null;
        long sequence = b.getLong(), timestamp = b.getLong(), camera = b.getLong(), epoch = b.getLong();
        int length = b.getInt(); if (sequence <= 0 || length <= 0 || length > DIMENSION_BYTES) return null;
        byte[] dimension = new byte[length]; b.get(dimension); b.position(76 + DIMENSION_BYTES);
        double x=b.getDouble(), y=b.getDouble(), z=b.getDouble();
        float yaw=b.getFloat(), pitch=b.getFloat(), fov=b.getFloat(); int width=b.getInt(), height=b.getInt();
        double px=b.getDouble(), py=b.getDouble(), pz=b.getDouble(); float[] view=new float[16], projection=new float[16];
        for(int i=0;i<16;i++) view[i]=b.getFloat(); for(int i=0;i<16;i++) projection[i]=b.getFloat();
        try {
            Pose pose=new Pose(camera,epoch,new String(dimension,StandardCharsets.UTF_8),x,y,z,yaw,pitch,fov,width,height,px,py,pz,view,projection);
            return validPose(pose) ? new Snapshot(sequence,timestamp,pose) : null;
        } catch (IllegalArgumentException e) { return null; }
    }
    private static boolean validPose(Pose pose) {
        if(pose==null || !Float.isFinite(pose.fov()) || !Double.isFinite(pose.playerX())
            || !Double.isFinite(pose.playerY()) || !Double.isFinite(pose.playerZ())
            || pose.view()==null || pose.view().length!=16 || pose.projection()==null || pose.projection().length!=16) return false;
        for(float value:pose.view()) if(!Float.isFinite(value)) return false;
        for(float value:pose.projection()) if(!Float.isFinite(value)) return false;
        return true;
    }
    private FileLock tryLock() throws IOException {
        try { return lockChannel.tryLock(0, 1, false); }
        catch (OverlappingFileLockException e) { return null; }
    }
    public void close() throws IOException { try { if(publisherLock!=null) publisherLock.release(); } finally { try { data.close(); } finally { lockChannel.close(); } } }
}
