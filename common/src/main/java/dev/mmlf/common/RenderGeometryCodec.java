package dev.mmlf.common;

import java.io.*;
import java.util.*;

/**
 * Versioned transport of explicitly described mesh layouts. Unsupported layouts must not be reinterpreted.
 * <p>
 * A batch is either the whole scene ({@code full}) or a change to the previous batch of the same scene generation: its
 * snapshot then holds only the sections that changed, and {@code removed} the sections that went away.
 */
public final class RenderGeometryCodec {
    private RenderGeometryCodec() {}
    private static final int MAGIC = 0x4d4d5247, VERSION = 7;
    public static final int MAX_FILE = 128 * 1024 * 1024;
    private static final int MAX_SECTIONS = 65536, MAX_LAYERS = 32;
    public record Batch(String runtime, String dimension, long exportedMs, RenderSectionStore.Snapshot snapshot, boolean full, List<RenderSectionStore.Key> removed) {
        public Batch { removed = List.copyOf(removed); }
        /** The whole scene. */
        public Batch(String runtime, String dimension, long exportedMs, RenderSectionStore.Snapshot snapshot) { this(runtime, dimension, exportedMs, snapshot, true, List.of()); }
    }

    public static byte[] encode(Batch batch) throws IOException {
        if (batch.runtime() == null || batch.runtime().isBlank() || batch.dimension() == null || batch.dimension().isBlank())
            throw new IOException("missing scene identity");
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION);
        out.writeUTF(batch.runtime()); out.writeUTF(batch.dimension());
        out.writeLong(batch.exportedMs()); out.writeLong(batch.snapshot().generation()); out.writeLong(batch.snapshot().revision());
        out.writeBoolean(batch.full());
        if (batch.full() && !batch.removed().isEmpty()) throw new IOException("the whole scene has nothing removed");
        if (batch.removed().size() > MAX_SECTIONS) throw new IOException("too many sections");
        out.writeInt(batch.removed().size());
        for (var key : batch.removed()) { out.writeUTF(key.producer()); out.writeLong(key.section()); }
        var sections = new TreeMap<RenderSectionStore.Key,RenderSectionStore.Section>(Comparator.comparing(RenderSectionStore.Key::producer).thenComparingLong(RenderSectionStore.Key::section));
        sections.putAll(batch.snapshot().sections());
        if (sections.size() > MAX_SECTIONS) throw new IOException("too many sections");
        out.writeInt(sections.size());
        for (var entry : sections.entrySet()) {
            out.writeUTF(entry.getKey().producer()); out.writeLong(entry.getKey().section()); out.writeLong(entry.getValue().capturedMs());
            var layers = entry.getValue().layers();
            if (layers.size() > MAX_LAYERS) throw new IOException("too many layers");
            out.writeInt(layers.size());
            for (var layer : layers) {
                byte[] data = layer.vertices();
                byte[] indices=layer.indices();
                if ((long) bytes.size() + data.length + indices.length > MAX_FILE) throw new IOException("geometry batch too large");
                out.writeUTF(layer.renderType()); out.writeUTF(layer.material()); out.writeUTF(layer.vertexFormat()); out.writeUTF(layer.topology());
                out.writeInt(layer.vertexStride()); out.writeInt(layer.vertexCount());
                out.writeInt(data.length); out.write(data); out.writeUTF(layer.indexFormat()); out.writeInt(indices.length); out.write(indices); out.writeUTF(layer.meshId());
            }
        }
        out.flush();
        if (bytes.size() > MAX_FILE) throw new IOException("geometry batch too large");
        return bytes.toByteArray();
    }

    public static Batch decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE) throw new IOException("geometry batch too large");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("unsupported geometry format");
        String runtime = in.readUTF(), dimension = in.readUTF();
        if (runtime.isBlank() || dimension.isBlank()) throw new IOException("missing scene identity");
        long exported = in.readLong(), generation = in.readLong(), revision = in.readLong();
        if (generation < 0 || revision < 0) throw new IOException("invalid scene generation or revision");
        boolean full = in.readBoolean();
        int removedCount = in.readInt();
        if (removedCount < 0 || removedCount > MAX_SECTIONS || full && removedCount != 0) throw new IOException("invalid removed sections");
        List<RenderSectionStore.Key> removed = new ArrayList<>();
        for (int i = 0; i < removedCount; i++) {
            String producer = in.readUTF(); long section = in.readLong();
            if (producer.isBlank()) throw new IOException("missing producer");
            removed.add(new RenderSectionStore.Key(producer, section));
        }
        int count = in.readInt();
        if (count < 0 || count > MAX_SECTIONS) throw new IOException("invalid section count");
        Map<RenderSectionStore.Key, RenderSectionStore.Section> sections = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String producer=in.readUTF(); long key = in.readLong(), captured = in.readLong();
            if(producer.isBlank()) throw new IOException("missing producer");
            int layerCount = in.readInt();
            if (layerCount < 0 || layerCount > MAX_LAYERS) throw new IOException("invalid layer count");
            var layers = new ArrayList<RenderSectionStore.Layer>();
            for (int l = 0; l < layerCount; l++) {
                String name = in.readUTF(), material=in.readUTF(), format=in.readUTF(), topology=in.readUTF();
                int stride=in.readInt(), vertices = in.readInt(), length = in.readInt();
                if (name.isBlank() || material.isBlank() || format.isBlank() || topology.isBlank() || stride<1 || stride>256 || vertices < 0
                    || (long) vertices * stride != length || length > in.available()) throw new IOException("invalid vertex data");
                byte[] data=in.readNBytes(length); String indexFormat=in.readUTF(); int indexLength=in.readInt();
                if(indexFormat.isBlank() || indexLength<0 || indexLength>in.available() || indexFormat.equals("none") && indexLength!=0
                    || indexFormat.equals("u16le") && indexLength%2!=0 || indexFormat.equals("u32le") && indexLength%4!=0)
                    throw new IOException("invalid index data");
                byte[] indices=in.readNBytes(indexLength); String meshId=in.readUTF();
                layers.add(new RenderSectionStore.Layer(name, material, format, topology, stride, vertices, data,indexFormat,indices,meshId));
            }
            if (sections.put(new RenderSectionStore.Key(producer,key), new RenderSectionStore.Section(captured, layers)) != null) throw new IOException("duplicate section");
        }
        if (in.available() != 0) throw new IOException("trailing geometry bytes");
        return new Batch(runtime, dimension, exported, new RenderSectionStore.Snapshot(generation, revision, sections), full, removed);
    }
}
