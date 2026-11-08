package dev.mmlf.common;

/**
 * When the primary published each recent camera pose, by pose sequence, on its own clock. A remote frame names the
 * sequence it was rendered for, so its end-to-end latency (pose published → frame composited) is measured on one clock,
 * without the cross-runtime clock calibration frame ages need, and includes the remote's render, read-back and transfer.
 */
public final class PoseHistory {
    private final long[] sequences, nanos;
    private long newest;
    public PoseHistory(int capacity) {
        if(capacity<1) throw new IllegalArgumentException("capacity");
        sequences=new long[capacity]; nanos=new long[capacity];
    }
    public synchronized void published(long sequence,long nanoTime) {
        int i=(int) Math.floorMod(sequence,(long) sequences.length);
        sequences[i]=sequence; nanos[i]=nanoTime;
        newest=Math.max(newest,sequence);
    }
    /** When {@code sequence} was published, or {@code Long.MIN_VALUE} when it is unknown or already overwritten. */
    public synchronized long publishedAt(long sequence) {
        if(sequence<=0) return Long.MIN_VALUE;
        int i=(int) Math.floorMod(sequence,(long) sequences.length);
        return sequences[i]==sequence?nanos[i]:Long.MIN_VALUE;
    }
    /** How many poses the primary published after {@code sequence}; 0 for the newest. */
    public synchronized long behind(long sequence) { return Math.max(0,newest-sequence); }
    public synchronized long newest() { return newest; }
}
