package dev.mmlf.common;

import java.util.*;

/** Thread-safe, loader-independent staging area for section mesh submissions from one runtime.
 * This is a capture buffer, not evidence that all scene content can be rendered centrally. */
public final class RenderSectionStore {
    public record Key(String producer,long section) {
        public Key { if(producer==null || producer.isBlank()) throw new IllegalArgumentException("missing producer"); }
    }
    private static final String DEFAULT_PRODUCER="minecraft:sections";
    public record Layer(String renderType, String material, String vertexFormat, String topology, int vertexStride, int vertexCount, byte[] vertices, String indexFormat, byte[] indices, String meshId) {
        public Layer(String renderType,int vertexCount,byte[] vertices) {
            this(renderType,"minecraft:block-atlas","minecraft:block-v1","quads",32,vertexCount,vertices,"none",new byte[0],"");
        }
        public Layer(String renderType,String vertexFormat,String topology,int vertexStride,int vertexCount,byte[] vertices) {
            this(renderType,"minecraft:block-atlas",vertexFormat,topology,vertexStride,vertexCount,vertices,"none",new byte[0],"");
        }
        public Layer(String renderType,String material,String vertexFormat,String topology,int vertexStride,int vertexCount,byte[] vertices) {
            this(renderType,material,vertexFormat,topology,vertexStride,vertexCount,vertices,"none",new byte[0],"");
        }
        public Layer(String renderType,String material,String vertexFormat,String topology,int vertexStride,int vertexCount,byte[] vertices,String indexFormat,byte[] indices) {
            this(renderType,material,vertexFormat,topology,vertexStride,vertexCount,vertices,indexFormat,indices,"");
        }
        public Layer {
            Objects.requireNonNull(renderType);
            Objects.requireNonNull(material); Objects.requireNonNull(vertexFormat); Objects.requireNonNull(topology); Objects.requireNonNull(indexFormat); Objects.requireNonNull(meshId);
            if (renderType.isBlank() || material.isBlank() || vertexFormat.isBlank() || topology.isBlank() || indexFormat.isBlank() || vertexStride<1 || vertexStride>256 || vertexCount < 0)
                throw new IllegalArgumentException("invalid mesh layout");
            vertices = Objects.requireNonNull(vertices).clone();
            indices = Objects.requireNonNull(indices).clone();
            if ((long)vertexCount*vertexStride!=vertices.length) throw new IllegalArgumentException("invalid vertex data size");
            if(indexFormat.equals("none") && indices.length!=0) throw new IllegalArgumentException("indices require a format");
            if(indexFormat.equals("u16le") && indices.length%2!=0 || indexFormat.equals("u32le") && indices.length%4!=0)
                throw new IllegalArgumentException("misaligned indices");
        }
        @Override public byte[] vertices() { return vertices.clone(); }
        @Override public byte[] indices() { return indices.clone(); }
    }
    public record Section(long capturedMs, List<Layer> layers) {
        public Section { layers = List.copyOf(layers); }
    }
    public record Snapshot(long generation, long revision, Map<Key, Section> sections) {
        public Snapshot { sections = Map.copyOf(sections); }
    }

    private final Map<Key, Section> sections = new HashMap<>();
    private final Map<Key,Long> producerRevisions = new HashMap<>();
    private long revision;
    private long generation;

    /** Starts a new scene. Work begun in an older scene must retain its original token. */
    public synchronized long resetScene() {
        sections.clear(); producerRevisions.clear();
        revision++;
        return ++generation;
    }

    public synchronized long generation() { return generation; }

    /** Rejects a delayed submission after the scene that produced it was replaced. */
    public synchronized boolean submit(long scene, long key, long capturedMs, List<Layer> layers) {
        return submit(scene,DEFAULT_PRODUCER,key,capturedMs,layers);
    }

    public synchronized boolean submit(long scene,String producer,long key,long capturedMs,List<Layer> layers) {
        if (scene != generation) return false;
        submit(new Key(producer,key), capturedMs, layers);
        return true;
    }

    public synchronized boolean remove(long scene, long key) {
        return remove(scene,DEFAULT_PRODUCER,key);
    }
    public synchronized boolean remove(long scene,String producer,long key) {
        if (scene != generation) return false;
        remove(new Key(producer,key));
        return true;
    }

    /** A producer assigns monotonically increasing revisions before starting asynchronous work. */
    public synchronized boolean submitVersioned(long scene,String producer,long key,long producerRevision,long capturedMs,List<Layer> layers) {
        Key identity=new Key(producer,key);
        if(scene!=generation || producerRevision<0 || producerRevision<=producerRevisions.getOrDefault(identity,-1L)) return false;
        submit(identity,capturedMs,layers); producerRevisions.put(identity,producerRevision); return true;
    }
    /** Retain the deletion revision to prevent late work resurrecting a removed mesh. */
    public synchronized boolean removeVersioned(long scene,String producer,long key,long producerRevision) {
        Key identity=new Key(producer,key);
        if(scene!=generation || producerRevision<0 || producerRevision<=producerRevisions.getOrDefault(identity,-1L)) return false;
        remove(identity); producerRevisions.put(identity,producerRevision); return true;
    }

    public synchronized void submit(long key, long capturedMs, List<Layer> layers) {
        submit(new Key(DEFAULT_PRODUCER,key),capturedMs,layers);
    }
    private void submit(Key key, long capturedMs, List<Layer> layers) {
        Section previous = sections.get(key);
        if (previous != null && sameLayers(previous.layers(), layers)) return;
        sections.put(key, new Section(capturedMs, layers));
        revision++;
    }

    /** Compare render content, excluding capture timestamps and source revisions. */
    public static boolean sameLayers(List<Layer> left, List<Layer> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            Layer a = left.get(i), b = right.get(i);
            if (a.vertexCount != b.vertexCount || a.vertexStride!=b.vertexStride || !a.renderType.equals(b.renderType)
                || !a.material.equals(b.material) || !a.vertexFormat.equals(b.vertexFormat) || !a.topology.equals(b.topology)
                || !a.indexFormat.equals(b.indexFormat) || !a.meshId.equals(b.meshId) || !Arrays.equals(a.vertices, b.vertices) || !Arrays.equals(a.indices,b.indices)) return false;
        }
        return true;
    }

    public synchronized void remove(long key) {
        remove(new Key(DEFAULT_PRODUCER,key));
    }
    private void remove(Key key) {
        if (sections.remove(key) != null) revision++;
    }

    public synchronized void clear() {
        if (!sections.isEmpty() || !producerRevisions.isEmpty()) { sections.clear(); producerRevisions.clear(); revision++; }
    }

    public synchronized Snapshot snapshot() { return new Snapshot(generation, revision, sections); }
    /** Avoid copying the section map when a producer has nothing new to publish. */
    public synchronized Snapshot snapshotIfChanged(long previousRevision) {
        return revision==previousRevision ? null : new Snapshot(generation,revision,sections);
    }
}
