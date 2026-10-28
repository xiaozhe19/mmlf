package dev.mmlf.common;

import dev.mmlf.common.Messages.Frame;

/** Coordinator ping estimate. Uncertainty is half the observed RTT under monotonic clocks. */
public final class FrameTiming {
    public static final long MAX_CALIBRATION_AGE=10_000_000_000L;
    public static final double MAX_UNCERTAINTY=100_000_000.0;
    private FrameTiming() {}
    public record Clock(double offsetNanos,double uncertaintyNanos,long sampledNanos) {
        public boolean validAt(long now) {
            long elapsed=now-sampledNanos;
            return Double.isFinite(offsetNanos) && Double.isFinite(uncertaintyNanos) && uncertaintyNanos>=0
                && uncertaintyNanos<=MAX_UNCERTAINTY && elapsed>=0 && elapsed<=MAX_CALIBRATION_AGE;
        }
    }
    public record Age(double estimatedNanos,double uncertaintyNanos) {}
    public static Clock fromPing(long sent,long remote,long received) {
        long roundTrip=received-sent;
        if(roundTrip<0) throw new IllegalArgumentException("negative ping round trip");
        return new Clock((double)remote-((double)sent+roundTrip/2.0),roundTrip/2.0,received);
    }
    /** Null means uncalibrated, invalid or definitely from the future. */
    public static Age age(Frame frame,Clock local,long now) {
        if(frame==null || local==null || !local.validAt(now) || !Double.isFinite(frame.coordinatorOffsetNanos())
            || !Double.isFinite(frame.clockUncertaintyNanos()) || frame.clockUncertaintyNanos()<0
            || frame.clockUncertaintyNanos()>MAX_UNCERTAINTY) return null;
        double age=((double)now-frame.capturedNanos())+local.offsetNanos()-frame.coordinatorOffsetNanos();
        double uncertainty=local.uncertaintyNanos()+frame.clockUncertaintyNanos();
        if(!Double.isFinite(age) || age < -uncertainty) return null;
        return new Age(age,uncertainty);
    }
    /**
     * Keeps recent ping samples and offers the tightest one still valid, so a single slow round trip (GC pause,
     * coordinator stall) does not invalidate the calibration until the next ping.
     */
    public static final class ClockFilter {
        private final java.util.ArrayDeque<Clock> samples=new java.util.ArrayDeque<>();
        private final int capacity;
        public ClockFilter(int capacity) { if(capacity<1) throw new IllegalArgumentException("capacity"); this.capacity=capacity; }
        public synchronized Clock add(Clock sample) {
            samples.addLast(sample);
            while(samples.size()>capacity) samples.removeFirst();
            return best(sample.sampledNanos());
        }
        public synchronized Clock best(long now) {
            Clock best=null;
            for(Clock c:samples) if(c.validAt(now) && (best==null || c.uncertaintyNanos()<best.uncertaintyNanos())) best=c;
            return best;
        }
        public synchronized void clear() { samples.clear(); }
    }
    public static boolean isFresh(Frame frame,Clock local,long now,long maxAge) {
        Age age=age(frame,local,now);
        return age!=null && maxAge>=0 && age.estimatedNanos()+age.uncertaintyNanos()<=maxAge;
    }
}
