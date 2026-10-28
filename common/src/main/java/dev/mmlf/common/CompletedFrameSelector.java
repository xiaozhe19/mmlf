package dev.mmlf.common;

/** Chooses the newest completed readback that is newer than the last announced frame. */
public final class CompletedFrameSelector {
    private CompletedFrameSelector() {}
    public static int newest(long[] ids,boolean[] ready,long lastPublished) {
        if(ids.length!=ready.length) throw new IllegalArgumentException("mismatched slot arrays");
        int selected=-1; long newest=lastPublished;
        for(int i=0;i<ids.length;i++) if(ready[i] && ids[i]>newest) { newest=ids[i]; selected=i; }
        return selected;
    }
}
