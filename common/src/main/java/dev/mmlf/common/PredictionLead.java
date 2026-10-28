package dev.mmlf.common;

/** Render-thread estimate of time from world rendering to primary composition. */
public final class PredictionLead {
    private static final long FEEDBACK_TTL=5_000_000_000L;
    private final double fallbackMs;
    private double transferMs=-1, renderMs=-1;
    private long feedbackAt;
    public PredictionLead(double fallbackMs) {
        if(!Double.isFinite(fallbackMs) || fallbackMs<0) throw new IllegalArgumentException("invalid fallback");
        this.fallbackMs=Math.min(150,fallbackMs);
    }
    public void observeTransfer(double milliseconds,long now) {
        if(!Double.isFinite(milliseconds) || milliseconds<0 || milliseconds>1000) return;
        double sample=Math.min(150,milliseconds);
        transferMs=transferMs<0 ? sample : transferMs+0.1*(sample-transferMs);
        feedbackAt=now;
    }
    public void observeRender(double milliseconds) {
        if(!Double.isFinite(milliseconds) || milliseconds<0 || milliseconds>1000) return;
        double sample=Math.min(150,milliseconds);
        renderMs=renderMs<0 ? sample : renderMs+0.1*(sample-renderMs);
    }
    public double milliseconds(long now) {
        long age=now-feedbackAt;
        if(transferMs<0 || age<0 || age>FEEDBACK_TTL) return fallbackMs;
        return Math.max(0,Math.min(150,transferMs+(renderMs<0?0:renderMs)));
    }
    public void reset() { transferMs=-1; renderMs=-1; feedbackAt=0; }
}
