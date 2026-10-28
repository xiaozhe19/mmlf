package dev.mmlf.common;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import static dev.mmlf.common.Messages.*;

/** Three fixed-size slots. Cross-process file locks, not Java memory fences, protect pixels. */
public final class FrameFile implements AutoCloseable {
    /** Default capacity covers a maximised 2560x1440 window; writer and reader of one file must agree. */
    public static final int MAX_PIXELS = 2560 * 1440;
    public static final int MAX_EDGE = 4096;
    private final long stride;
    private final int maxPixels;
    private final FileChannel channel;
    private int next;
    private final MappedByteBuffer[] slots = new MappedByteBuffer[3];
    public record Pixels(ByteBuffer color, ByteBuffer depth) {}
    public FrameFile(Path file) throws IOException { this(file, MAX_PIXELS); }
    public FrameFile(Path file, int maxPixels) throws IOException {
        if (maxPixels < 1 || maxPixels > MAX_PIXELS) throw new IOException("invalid frame capacity");
        this.maxPixels = maxPixels; stride = 16L + maxPixels * 8L;
        Files.createDirectories(file.toAbsolutePath().getParent());
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (channel.size() < stride * 3) { channel.position(stride * 3 - 1); channel.write(ByteBuffer.wrap(new byte[]{0})); }
        for (int i = 0; i < 3; i++) slots[i] = channel.map(FileChannel.MapMode.READ_WRITE, stride * i, stride);
    }
    public synchronized int publish(long frame, int width, int height, ByteBuffer color, ByteBuffer depth) throws IOException {
        int n = checkedSize(width, height);
        if (color.remaining() != n || depth.remaining() != n) throw new IOException("pixel buffer size mismatch");
        for (int i = 0; i < 3; i++) {
            int slot = (next++) % 3; long offset = stride * slot;
            try (FileLock lock = channel.tryLock(offset, stride, false)) {
                if (lock == null) continue;
                ByteBuffer bytes = slots[slot].duplicate();
                bytes.putLong(frame).putInt(width).putInt(height).put(color.duplicate()).put(depth.duplicate());
                return slot;
            } catch (OverlappingFileLockException ignored) { }
        }
        return -1;
    }
    public synchronized Pixels read(Frame meta) throws IOException { return read(meta,null); }
    public synchronized Pixels read(Frame meta,Pixels reuse) throws IOException {
        int n = checkedSize(meta.width(), meta.height());
        if (meta.slot() < 0 || meta.slot() > 2) throw new IOException("invalid slot");
        long offset = stride * meta.slot();
        try (FileLock lock = channel.tryLock(offset, stride, true)) {
            if (lock == null) return null;
            ByteBuffer bytes = slots[meta.slot()].asReadOnlyBuffer();
            bytes.limit(16 + 2*n);
            if (bytes.getLong() != meta.frameId() || bytes.getInt() != meta.width() || bytes.getInt() != meta.height()) return null;
            ByteBuffer color = reuse!=null && reuse.color().capacity()>=n ? reuse.color() : ByteBuffer.allocateDirect(n);
            ByteBuffer depth = reuse!=null && reuse.depth().capacity()>=n ? reuse.depth() : ByteBuffer.allocateDirect(n);
            color.clear().limit(n); depth.clear().limit(n);
            ByteBuffer c = bytes.slice(); c.limit(n); color.put(c).flip(); bytes.position(16+n);
            depth.put(bytes.slice()).flip(); return new Pixels(color, depth);
        } catch (OverlappingFileLockException ignored) { return null; }
    }
    public static boolean fits(int w, int h, int maxPixels) { return w >= 1 && h >= 1 && w <= MAX_EDGE && h <= MAX_EDGE && (long) w * h <= maxPixels; }
    private int checkedSize(int w, int h) throws IOException {
        if (!fits(w, h, maxPixels)) throw new IOException("unsupported frame size " + w + "x" + h);
        return Math.multiplyExact(Math.multiplyExact(w,h),4);
    }
    public void close() throws IOException { channel.close(); }
}
