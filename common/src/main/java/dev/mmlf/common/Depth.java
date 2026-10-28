package dev.mmlf.common;

public final class Depth {
    private Depth() {}
    /** Standard OpenGL window depth, near=0 far=1; not the reference project's reversed Z. */
    public static double linear(double d, double near, double far) {
        return near * far / (far - d * (far - near));
    }
    public static boolean remoteWins(float local, float remote) { return remote < 1 && remote < local; }
}
