package dev.mmlf.common;

import java.util.Set;

/** Window/input ownership and scene-rendering ownership are separate decisions. */
public final class RenderHostPolicy {
    private static final Set<String> RUNTIMES=Set.of("fabric","neoforge");
    private RenderHostPolicy() {}
    public static String configured(String primary,String requested) {
        if(!RUNTIMES.contains(primary)) throw new IllegalArgumentException("unknown primary");
        if(requested==null || requested.isBlank() || requested.equals("primary")) return primary;
        if(requested.equals("remote")) return other(primary);
        if(RUNTIMES.contains(requested)) return requested;
        throw new IllegalArgumentException("unknown render host: "+requested);
    }
    /** Candidate only: callers must still verify scene coverage and presentation before switching. */
    public static String shaderCandidate(String primary,Set<String> activeShaders) {
        if(!RUNTIMES.contains(primary)) throw new IllegalArgumentException("unknown primary");
        if(activeShaders.contains(primary)) return primary;
        String other=other(primary);
        return activeShaders.contains(other)?other:primary;
    }
    public static String other(String runtime) {
        return switch(runtime) { case "fabric" -> "neoforge"; case "neoforge" -> "fabric"; default -> throw new IllegalArgumentException("unknown runtime"); };
    }
}
