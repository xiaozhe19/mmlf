package dev.mmlf.common;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Extensible shader-backend reports; an unrecognized backend never becomes an assumed negative. */
public final class RenderShaderRegistry {
    public enum State { ACTIVE, INACTIVE, UNKNOWN }
    private final Map<String,Supplier<State>> probes=new ConcurrentHashMap<>();
    public void register(String id,Supplier<State> probe) {
        if(id==null || id.isBlank()) throw new IllegalArgumentException("missing provider id");
        probes.put(id,Objects.requireNonNull(probe));
    }
    public State probe() {
        if(probes.isEmpty()) return State.UNKNOWN;
        boolean unknown=false;
        for(var probe:probes.values()) {
            State state;
            try { state=probe.get(); } catch(RuntimeException | LinkageError e) { state=State.UNKNOWN; }
            if(state==State.ACTIVE) return State.ACTIVE;
            if(state!=State.INACTIVE) unknown=true;
        }
        return unknown?State.UNKNOWN:State.INACTIVE;
    }
}
