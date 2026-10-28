package dev.mmlf.common;

import java.io.*;
import java.lang.invoke.VarHandle;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import static dev.mmlf.common.Messages.*;

/**
 * Three fixed-size slots in a file mapping shared by one writer and its readers. Each slot starts with a sequence
 * number that is odd while the writer fills it (a seqlock): a reader copies the pixels and accepts them only if the
 * sequence was even and unchanged, so the writer never waits and a torn copy is never used.
 *
 * <pre>slot: +0 long seq  +8 long frame  +16 int width  +20 int height  +24 colour RGBA8  then depth float32</pre>
 */
public final class FrameFile implements AutoCloseable {
    /** Default capacity covers a maximised 2560x1440 window; writer and reader of one file must agree. */
    public static final int MAX_PIXELS = 2560 * 1440;
    public static final int MAX_EDGE = 4096;
    private static final int HEADER = 24;
    private final long stride;
    private final int maxPixels;
    private final FileChannel channel;
    private int next;
    private final MappedByteBuffer[] slots = new MappedByteBuffer[3];
    public record Pixels(ByteBuffer color, ByteBuffer depth) {}
    public FrameFile(Path file) throws IOException { this(file, MAX_PIXELS); }
    public FrameFile(Path file, int maxPixels) throws IOException {
        if (maxPixels < 1 || maxPixels > MAX_PIXELS) throw new IOException("invalid frame capacity");
        this.maxPixels = maxPixels; stride = HEADER + maxPixels * 8L;
        Files.createDirectories(file.toAbsolutePath().getParent());
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (channel.size() < stride * 3) { channel.position(stride * 3 - 1); channel.write(ByteBuffer.wrap(new byte[]{0})); }
        for (int i = 0; i < 3; i++) slots[i] = channel.map(FileChannel.MapMode.READ_WRITE, stride * i, stride);
    }
    /** Writes the oldest slot and returns its index. Colour and depth must each hold width*height*4 bytes. */
    public synchronized int publish(long frame, int width, int height, ByteBuffer color, ByteBuffer depth) throws IOException {
        int n = checkedSize(width, height);
        if (color.remaining() != n || depth.remaining() != n) throw new IOException("pixel buffer size mismatch");
        int slot = (next++) % 3;
        MappedByteBuffer bytes = slots[slot];
        long seq = bytes.getLong(0);
        if ((seq & 1L) != 0L) seq++;
        bytes.putLong(0, seq + 1);
        VarHandle.fullFence();
        bytes.putLong(8, frame).putInt(16, width).putInt(20, height);
        bytes.put(HEADER, color, color.position(), n);
        bytes.put(HEADER + n, depth, depth.position(), n);
        VarHandle.fullFence();
        bytes.putLong(0, seq + 2);
        return slot;
    }
    public synchronized Pixels read(Frame meta) throws IOException { return read(meta,null); }
    /** Returns null when the slot no longer (or not yet) holds this frame, or was rewritten during the copy. */
    public synchronized Pixels read(Frame meta,Pixels reuse) throws IOException {
        int n = checkedSize(meta.width(), meta.height());
        if (meta.slot() < 0 || meta.slot() > 2) throw new IOException("invalid slot");
        MappedByteBuffer bytes = slots[meta.slot()];
        long before = bytes.getLong(0);
        if ((before & 1L) != 0L) return null;
        VarHandle.fullFence();
        if (bytes.getLong(8) != meta.frameId() || bytes.getInt(16) != meta.width() || bytes.getInt(20) != meta.height()) return null;
        ByteBuffer color = reuse!=null && reuse.color().capacity()>=n ? reuse.color() : ByteBuffer.allocateDirect(n);
        ByteBuffer depth = reuse!=null && reuse.depth().capacity()>=n ? reuse.depth() : ByteBuffer.allocateDirect(n);
        color.clear().limit(n); depth.clear().limit(n);
        color.put(0, bytes, HEADER, n); depth.put(0, bytes, HEADER + n, n);
        VarHandle.fullFence();
        if (bytes.getLong(0) != before) return null;
        return new Pixels(color, depth);
    }
    public static boolean fits(int w, int h, int maxPixels) { return w >= 1 && h >= 1 && w <= MAX_EDGE && h <= MAX_EDGE && (long) w * h <= maxPixels; }
    private int checkedSize(int w, int h) throws IOException {
        if (!fits(w, h, maxPixels)) throw new IOException("unsupported frame size " + w + "x" + h);
        return Math.multiplyExact(Math.multiplyExact(w,h),4);
    }
    public void close() throws IOException { channel.close(); }
}
