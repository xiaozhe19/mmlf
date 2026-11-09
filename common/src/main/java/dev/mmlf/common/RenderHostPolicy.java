package dev.mmlf.common;

/** The two peer runtimes; the window owner renders the scene and the other one submits to it. */
public final class RenderHostPolicy {
    private RenderHostPolicy() {}
    public static String other(String runtime) {
        return switch(runtime) { case "fabric" -> "neoforge"; case "neoforge" -> "fabric"; default -> throw new IllegalArgumentException("unknown runtime"); };
    }
}
