package dev.mmlf.common;

import java.util.Objects;
import java.util.Set;

/** Explicit receiver capabilities; an unknown mesh stays on its producer's native fallback. */
public final class RenderMeshCapabilities {
    public record Layout(String format,String topology,int stride,String indexFormat) {
        public Layout(String format,String topology,int stride) { this(format,topology,stride,"none"); }
        public Layout { Objects.requireNonNull(format); Objects.requireNonNull(topology); Objects.requireNonNull(indexFormat); }
    }
    private final Set<Layout> layouts;
    private final Set<String> passes;
    private final Set<String> materials;
    public RenderMeshCapabilities(Set<Layout> layouts,Set<String> passes,Set<String> materials) {
        this.layouts=Set.copyOf(layouts); this.passes=Set.copyOf(passes); this.materials=Set.copyOf(materials);
    }
    public boolean accepts(RenderSectionStore.Layer mesh) {
        return layouts.contains(new Layout(mesh.vertexFormat(),mesh.topology(),mesh.vertexStride(),mesh.indexFormat())) && passes.contains(mesh.renderType()) && materials.contains(mesh.material());
    }
}
