package dev.mmlf.common;

import java.util.*;

/**
 * Positions of another runtime's blocks that are interacting with this world right now, keyed by dimension and packed
 * position, each with the tick it was last active. Bookkeeping only: the caller decides what counts as activity (a
 * native neighbour update at the position, a signal still reaching it) and what an active position gets (signals
 * measured, neighbours published).
 * <p>
 * A position stays active for {@code idleTicks} after its last activity, so a burst of updates opens it once; at most
 * {@code capacity} are active, so a cascade (an explosion, a large machine) cannot grow the boundary without bound: a
 * full table refuses new positions and keeps the ones it has. Not thread-safe.
 */
public final class EndpointTable {
    /** One active position. */
    public record Endpoint(String dimension, long packed) {}

    private final long idleTicks;
    private final int capacity;
    private final Map<Endpoint, Long> lastActive = new LinkedHashMap<>();
    private long refused;

    public EndpointTable(long idleTicks, int capacity) {
        if (idleTicks <= 0 || capacity <= 0) throw new IllegalArgumentException("idleTicks and capacity must be positive");
        this.idleTicks = idleTicks;
        this.capacity = capacity;
    }

    /** Activity at the position: opens it, or keeps an open one. False when the table is full and it was not open. */
    public boolean touch(String dimension, long packed, long tick) {
        Endpoint key = new Endpoint(dimension, packed);
        Long before = lastActive.get(key);
        if (before == null && lastActive.size() >= capacity) { refused++; return false; }
        if (before == null || tick > before) lastActive.put(key, tick);
        return true;
    }

    /** Closes the position at once (its block went away). */
    public void remove(String dimension, long packed) { lastActive.remove(new Endpoint(dimension, packed)); }

    /** Closes positions idle for longer than {@code idleTicks} at {@code tick}; returns them. */
    public List<Endpoint> expire(long tick) {
        List<Endpoint> gone = new ArrayList<>();
        for (var it = lastActive.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (tick - entry.getValue() > idleTicks) { gone.add(entry.getKey()); it.remove(); }
        }
        return gone;
    }

    /** The open positions, as a copy the caller may iterate while touching or removing. */
    public List<Endpoint> active() { return new ArrayList<>(lastActive.keySet()); }
    public boolean isActive(String dimension, long packed) { return lastActive.containsKey(new Endpoint(dimension, packed)); }
    public int size() { return lastActive.size(); }
    /** Positions refused because the table was full, since it was made or last cleared. */
    public long refused() { return refused; }
    public void clear() { lastActive.clear(); refused = 0; }
}
