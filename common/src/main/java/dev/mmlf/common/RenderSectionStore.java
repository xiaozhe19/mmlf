package dev.mmlf.common;

import java.util.*;

/** Thread-safe, loader-independent staging area for section mesh submissions from one runtime.
 * This is a capture buffer, not evidence that all scene content can be rendered centrally. */
public final class RenderSectionStore {
    public record Layer(String renderType, int vertexCount, byte[] vertices) {
        public Layer {
            Objects.requireNonNull(renderType);
            if (vertexCount < 0) throw new IllegalArgumentException("negative vertex count");
            vertices = Objects.requireNonNull(vertices).clone();
        }
        @Override public byte[] vertices() { return vertices.clone(); }
    }
    public record Section(long capturedMs, List<Layer> layers) {
        public Section { layers = List.copyOf(layers); }
    }
    public record Snapshot(long revision, Map<Long, Section> sections) {
        public Snapshot { sections = Map.copyOf(sections); }
    }

    private final Map<Long, Section> sections = new HashMap<>();
    private long revision;
    private long generation;

    /** Starts a new scene. Work begun in an older scene must retain its original token. */
    public synchronized long resetScene() {
        sections.clear();
        revision++;
        return ++generation;
    }

    public synchronized long generation() { return generation; }

    /** Rejects a delayed submission after the scene that produced it was replaced. */
    public synchronized boolean submit(long scene, long key, long capturedMs, List<Layer> layers) {
        if (scene != generation) return false;
        submit(key, capturedMs, layers);
        return true;
    }

    public synchronized void submit(long key, long capturedMs, List<Layer> layers) {
        sections.put(key, new Section(capturedMs, layers));
        revision++;
    }

    public synchronized void remove(long key) {
        if (sections.remove(key) != null) revision++;
    }

    public synchronized void clear() {
        if (!sections.isEmpty()) { sections.clear(); revision++; }
    }

    public synchronized Snapshot snapshot() { return new Snapshot(revision, sections); }
}
