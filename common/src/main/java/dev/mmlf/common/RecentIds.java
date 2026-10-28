package dev.mmlf.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded set of recently seen identifiers, so a repeated action or event is handled once. Thread safe. */
public final class RecentIds {
    private final int capacity;
    private final LinkedHashMap<String,Boolean> seen;
    public RecentIds(int capacity) {
        if(capacity<1) throw new IllegalArgumentException("invalid capacity");
        this.capacity=capacity;
        seen=new LinkedHashMap<>(16,0.75f,false) { protected boolean removeEldestEntry(Map.Entry<String,Boolean> e) { return size()>RecentIds.this.capacity; } };
    }
    /** True the first time {@code id} is seen; null is never new. */
    public synchronized boolean firstTime(String id) { return id!=null && seen.putIfAbsent(id,Boolean.TRUE)==null; }
    public synchronized boolean contains(String id) { return seen.containsKey(id); }
    public synchronized void clear() { seen.clear(); }
}
