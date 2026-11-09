package dev.mmlf.common;

import java.util.*;

/** Dynamic render instances for one source and one scene. Resource identifiers are source-scoped. */
public final class RenderInstanceStore {
    public record Instance(String id,String mesh,String material,long revision,float[] transform) {
        public Instance {
            Objects.requireNonNull(id); Objects.requireNonNull(mesh); Objects.requireNonNull(material);
            if(id.isBlank() || mesh.isBlank() || material.isBlank() || revision<0 || transform==null || transform.length!=16)
                throw new IllegalArgumentException("invalid render instance");
            transform=transform.clone();
            for(float value:transform) if(!Float.isFinite(value)) throw new IllegalArgumentException("invalid transform");
        }
        @Override public float[] transform() { return transform.clone(); }
    }
    private final Map<String,Instance> instances=new HashMap<>();
    private final Map<String,Long> revisions=new HashMap<>();
    private long generation,version;

    public synchronized long resetScene() { return resetScene(generation+1); }
    /** Starts a scene with an externally coordinated generation, which must increase so receivers can order snapshots. */
    public synchronized long resetScene(long scene) {
        if(scene<=generation) throw new IllegalArgumentException("scene generation must increase");
        instances.clear(); revisions.clear(); version++; generation=scene; return generation;
    }
    public synchronized long generation() { return generation; }

    public synchronized boolean submit(long scene,Instance instance) {
        Objects.requireNonNull(instance);
        if(scene!=generation || instance.revision()<=revisions.getOrDefault(instance.id(),-1L)) return false;
        Instance old=instances.put(instance.id(),instance); revisions.put(instance.id(),instance.revision());
        if(old==null || !old.mesh.equals(instance.mesh) || !old.material.equals(instance.material) || !Arrays.equals(old.transform,instance.transform)) version++;
        return true;
    }

    /** Keep the deletion revision so a late update cannot resurrect the removed object. */
    public synchronized boolean remove(long scene,String id,long revision) {
        Objects.requireNonNull(id);
        if(id.isBlank() || revision<0 || scene!=generation || revision<=revisions.getOrDefault(id,-1L)) return false;
        instances.remove(id); revisions.put(id,revision); version++;
        return true;
    }
    public record Snapshot(long generation,long version,Map<String,Instance> instances) {
        public Snapshot { instances=Map.copyOf(instances); }
    }
    public synchronized Snapshot snapshot() { return new Snapshot(generation,version,instances); }
}
