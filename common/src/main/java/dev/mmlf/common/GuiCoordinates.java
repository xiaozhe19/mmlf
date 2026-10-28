package dev.mmlf.common;

/**
 * Pointer coordinates of a hosted screen. The primary normalises its GUI-scaled pointer by its GUI-scaled window size;
 * the owner maps the normalised point onto its own window in GLFW window pixels, which its mouse handler expects.
 * The two windows need not share size, aspect or GUI scale.
 */
public final class GuiCoordinates {
    private GuiCoordinates() {}
    public static double normalize(double scaled,double scaledExtent) {
        if(!(scaledExtent>0) || !Double.isFinite(scaled)) return 0;
        return Math.max(0,Math.min(1,scaled/scaledExtent));
    }
    /** Owner window pixel for a normalised coordinate. */
    public static double toWindow(double normalized,int windowExtent) {
        if(windowExtent<=0 || !Double.isFinite(normalized)) return 0;
        return Math.max(0,Math.min(1,normalized))*windowExtent;
    }
    /**
     * Minecraft's GUI scale for a framebuffer: the largest keeping 320x240 visible, capped by {@code requested} when it is
     * positive, made even for a forced Unicode font. Mirrors {@code Window.calculateScale} without loading game classes.
     */
    public static int scale(int framebufferWidth,int framebufferHeight,int requested,boolean unicode) {
        int scale=1;
        while(scale!=requested && scale<framebufferWidth && scale<framebufferHeight && framebufferWidth/(scale+1)>=320 && framebufferHeight/(scale+1)>=240) scale++;
        if(unicode && scale%2!=0) scale++;
        return scale;
    }
}
