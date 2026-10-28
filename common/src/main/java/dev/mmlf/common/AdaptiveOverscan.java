package dev.mmlf.common;

/** Adapts tangent-space view widening to predicted angular motion, with a bounded slow release. */
public final class AdaptiveOverscan {
    private static final double RELEASE_PER_SECOND=0.35, HYSTERESIS=0.02;
    private final double maximum;
    private double current=1;
    private long last;
    public AdaptiveOverscan(double maximum) {
        if(!Double.isFinite(maximum) || maximum<1 || maximum>2) throw new IllegalArgumentException("overscan maximum must be between 1 and 2");
        this.maximum=maximum;
    }
    public double update(double verticalFovDegrees,double aspect,double yawRate,double pitchRate,double horizonSeconds,long now) {
        if(!Double.isFinite(verticalFovDegrees) || verticalFovDegrees<=0 || verticalFovDegrees>=180
            || !Double.isFinite(aspect) || aspect<=0 || !Double.isFinite(yawRate) || !Double.isFinite(pitchRate)
            || !Double.isFinite(horizonSeconds) || horizonSeconds<0) return current;
        double halfV=Math.toRadians(verticalFovDegrees)*0.5;
        double tanV=Math.tan(halfV),tanH=tanV*aspect,halfH=Math.atan(tanH);
        double dy=Math.toRadians(Math.min(30,Math.abs(yawRate)*horizonSeconds));
        double dp=Math.toRadians(Math.min(30,Math.abs(pitchRate)*horizonSeconds));
        double limit=Math.toRadians(89.5);
        double horizontal=Math.tan(Math.min(limit,halfH+dy))/tanH;
        double vertical=Math.tan(Math.min(limit,halfV+dp))/tanV;
        double desired=Math.max(1,Math.min(maximum,Math.max(horizontal,vertical)));
        if(desired>current) current=desired;
        else if((desired<current-HYSTERESIS || (desired==1 && current>1)) && last!=0 && now>last) {
            double seconds=Math.min(0.25,(now-last)/1e9);
            current=Math.max(desired,current-RELEASE_PER_SECOND*seconds);
        }
        last=now;
        return current;
    }
    public void reset() { current=1; last=0; }
}
