package dev.mmlf.bridge.render;

import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.common.RenderInstanceStore;

/** Optional transport-only probe; it does not submit drawable geometry. */
final class RenderInstanceProbe {
    private static final boolean ENABLED=Boolean.parseBoolean(System.getenv().getOrDefault("MMLF_RENDER_INSTANCE_PROBE","false"));
    private static final String ID="mmlf:transport-probe";
    private static long scene=-1;
    private static int ticks;
    private static boolean observed,removed;
    private RenderInstanceProbe() {}
    static void tick() {
        if(!ENABLED) return;
        long current=RenderInstanceChannel.generation();
        if(scene!=current) { scene=current; ticks=0; observed=removed=false; }
        ticks++;
        if(!GeometryLayer.isRenderHost()) {
            if(ticks==1) {
                float[] matrix=new float[16]; matrix[0]=matrix[5]=matrix[10]=matrix[15]=1;
                RenderInstanceChannel.submit(scene,new RenderInstanceStore.Instance(ID,"mmlf:probe-mesh","mmlf:probe-material",1,matrix));
            }
            if(ticks==800) RenderInstanceChannel.remove(scene,ID,2);
        } else {
            var snapshot=RenderInstanceChannel.remoteSnapshot();
            if(snapshot==null) return;
            if(!observed && snapshot.instances().containsKey(ID)) {
                observed=true; System.out.println("MMLF RENDER INSTANCE PROBE received=true");
            }
            if(observed && !removed && !snapshot.instances().containsKey(ID)) {
                removed=true; System.out.println("MMLF RENDER INSTANCE PROBE removed=true");
            }
        }
    }
}
