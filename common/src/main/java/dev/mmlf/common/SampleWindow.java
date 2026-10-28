package dev.mmlf.common;

import java.util.Arrays;

/** Bounded recent-sample window. Used by one render thread; sorting occurs only when drained. */
public final class SampleWindow {
    public record Percentiles(int count,double p50,double p95,double p99) {}
    private final double[] values;
    private int next,size;
    public SampleWindow(int capacity) {
        if(capacity<1) throw new IllegalArgumentException("invalid capacity");
        values=new double[capacity];
    }
    public void add(double value) {
        if(!Double.isFinite(value) || value<0) return;
        values[next]=value; next=(next+1)%values.length; size=Math.min(size+1,values.length);
    }
    public Percentiles drain() {
        if(size==0) return new Percentiles(0,0,0,0);
        double[] copy=Arrays.copyOf(values,size); Arrays.sort(copy);
        Percentiles p=new Percentiles(size,at(copy,0.50),at(copy,0.95),at(copy,0.99));
        size=0; next=0; return p;
    }
    private static double at(double[] sorted,double p) { return sorted[Math.max(0,(int)Math.ceil(p*sorted.length)-1)]; }
}
