package dev.mmlf.common;

/** OpenGL perspective depth coefficients: clip z = a*z+b, clip w = -z. */
public final class ProjectionPlanes {
    private ProjectionPlanes() {}
    public static float far(float a,float b) {
        float denominator=a+1f;
        if(!Float.isFinite(a) || !Float.isFinite(b) || Math.abs(denominator)<1e-6f) return 1024f;
        float far=b/denominator;
        return Float.isFinite(far) && far>0 ? far : 1024f;
    }
}
