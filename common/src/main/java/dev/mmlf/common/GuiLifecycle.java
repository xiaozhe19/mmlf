package dev.mmlf.common;

import dev.mmlf.common.Messages.GuiState;

/**
 * Lifecycle of the one hosted screen a primary shows: CLOSED → OPENING → ACTIVE → CLOSING → CLOSED, or FAILED from any
 * open state. Reports of an older instance or an older sequence never change the current screen, so input can only reach
 * the instance that is active now. Times are monotonic nanoseconds supplied by the caller.
 */
public final class GuiLifecycle {
    public enum State { CLOSED, OPENING, ACTIVE, CLOSING, FAILED }
    public enum Outcome { OPENED, SWITCHED, UPDATED, CLOSED, FAILED, IGNORED }
    public static final long OPEN_TIMEOUT_NANOS=3_000_000_000L, CLOSE_TIMEOUT_NANOS=2_000_000_000L;
    /** Without a new frame for this long the primary shows that it waits; for the longer one it gives the screen up. */
    public static final long STALL_NOTICE_NANOS=2_000_000_000L, STALL_FAIL_NANOS=10_000_000_000L;
    private State state=State.CLOSED;
    private String instanceId, runtime, actionId, reason;
    private long since, lastFrame, sequence=-1;

    public synchronized State state() { return state; }
    public synchronized String instanceId() { return instanceId; }
    public synchronized String runtime() { return runtime; }
    public synchronized String actionId() { return actionId; }
    public synchronized String reason() { return reason; }
    public synchronized boolean isOpen() { return state==State.OPENING || state==State.ACTIVE || state==State.CLOSING; }
    /** Input may be sent only to the active instance. */
    public synchronized boolean accepts(String instance) { return state==State.ACTIVE && instance!=null && instance.equals(instanceId); }

    /** Applies an owner's report. {@code expected} tells whether its action was started by this primary. */
    public synchronized Outcome apply(GuiState report,boolean expected,long now) {
        if(report==null || report.instanceId()==null || report.runtime()==null || report.state()==null) return Outcome.IGNORED;
        State reported;
        try { reported=State.valueOf(report.state()); } catch(IllegalArgumentException e) { return Outcome.IGNORED; }
        boolean current=report.instanceId().equals(instanceId) && report.runtime().equals(runtime);
        if(current) {
            if(report.sequence()<=sequence) return Outcome.IGNORED;
            sequence=report.sequence();
            switch(reported) {
                case ACTIVE -> { if(state==State.OPENING) { state=State.ACTIVE; since=now; lastFrame=now; } return state==State.ACTIVE?Outcome.UPDATED:Outcome.IGNORED; }
                case OPENING -> { return Outcome.IGNORED; }
                case CLOSING, CLOSED -> { finish(State.CLOSED,report.reason(),now); return Outcome.CLOSED; }
                case FAILED -> { finish(State.FAILED,report.reason(),now); return Outcome.FAILED; }
            }
            return Outcome.IGNORED;
        }
        if(reported!=State.OPENING && reported!=State.ACTIVE) return Outcome.IGNORED;
        // A new screen either answers this primary's own action, or replaces the screen the same owner is showing now
        // (a mod switching screens); anything else was not asked for.
        boolean switching=isOpen() && report.runtime().equals(runtime);
        if(!expected && !switching) return Outcome.IGNORED;
        if(isOpen() && !switching) return Outcome.IGNORED;
        instanceId=report.instanceId(); runtime=report.runtime(); actionId=report.actionId(); reason=null;
        sequence=report.sequence(); state=reported; since=now; lastFrame=now;
        return switching?Outcome.SWITCHED:Outcome.OPENED;
    }
    /** A frame of the current instance arrived. */
    public synchronized void frame(String instance,long now) { if(instance!=null && instance.equals(instanceId) && isOpen()) lastFrame=now; }
    /** The primary asks the owner to close; the screen stays until the owner confirms or the close times out. */
    public synchronized boolean requestClose(String why,long now) {
        if(state!=State.OPENING && state!=State.ACTIVE) return false;
        state=State.CLOSING; since=now; reason=why; return true;
    }
    /** Ends the screen locally, e.g. when its runtime left. */
    public synchronized boolean fail(String why,long now) {
        if(!isOpen()) return false;
        finish(State.FAILED,why,now); return true;
    }
    /** True while the active screen has not delivered a frame recently. */
    public synchronized boolean stalled(long now) { return state==State.ACTIVE && now-lastFrame>STALL_NOTICE_NANOS; }
    /** Applies timeouts; returns the state it moved to, or null. */
    public synchronized State expire(long now) {
        if(state==State.OPENING && now-since>OPEN_TIMEOUT_NANOS) { finish(State.FAILED,"open_timeout",now); return state; }
        if(state==State.ACTIVE && now-lastFrame>STALL_FAIL_NANOS) { finish(State.FAILED,"frames_stopped",now); return state; }
        if(state==State.CLOSING && now-since>CLOSE_TIMEOUT_NANOS) { finish(State.CLOSED,"close_timeout",now); return state; }
        return null;
    }
    /** Forgets a finished screen so the next one may open. */
    public synchronized void reset() { if(!isOpen()) { state=State.CLOSED; instanceId=null; runtime=null; actionId=null; sequence=-1; } }
    private void finish(State end,String why,long now) { state=end; since=now; if(why!=null) reason=why; }
}
