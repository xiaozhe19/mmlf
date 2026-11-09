package dev.mmlf.common;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderGeometryCodecTest {
    @Test void preservesSceneAndSectionData() throws Exception {
        var store = new RenderSectionStore();
        store.submit(8, 23, List.of(new RenderSectionStore.Layer("solid", 4, new byte[128])));
        var decoded = RenderGeometryCodec.decode(RenderGeometryCodec.encode(
            new RenderGeometryCodec.Batch("fabric", "minecraft:overworld", 44, store.snapshot())));
        assertEquals("fabric", decoded.runtime());
        assertEquals("minecraft:overworld", decoded.dimension());
        assertEquals(44, decoded.exportedMs());
        assertEquals(0, decoded.snapshot().generation());
        assertEquals(1, decoded.snapshot().revision());
        assertEquals(128, decoded.snapshot().sections().get(new RenderSectionStore.Key("minecraft:sections",8)).layers().getFirst().vertices().length);
    }

    @Test void rejectsTruncationUnknownFormatsAndMalformedVertices() throws Exception {
        var store = new RenderSectionStore();
        store.submit(8, 23, List.of(new RenderSectionStore.Layer("solid", 4, new byte[128])));
        byte[] encoded = RenderGeometryCodec.encode(new RenderGeometryCodec.Batch("fabric", "world", 0, store.snapshot()));
        assertThrows(IOException.class, () -> RenderGeometryCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        encoded[7] = 7;
        assertThrows(IOException.class, () -> RenderGeometryCodec.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> new RenderSectionStore.Layer("solid", 4, new byte[4]));
    }

    @Test void transportsCustomMeshWithoutAssumingItsLayout() throws Exception {
        var store=new RenderSectionStore();
        store.submit(2, 0, List.of(new RenderSectionStore.Layer("custom-pass", "example:material", "example:packed-v1", "triangles", 12, 3, new byte[36],"u16le",new byte[]{0,0,1,0,2,0},"example:mesh")));
        var result=RenderGeometryCodec.decode(RenderGeometryCodec.encode(new RenderGeometryCodec.Batch("neo", "world", 0, store.snapshot())));
        var layer=result.snapshot().sections().get(new RenderSectionStore.Key("minecraft:sections",2)).layers().getFirst();
        assertEquals("example:packed-v1", layer.vertexFormat());
        assertEquals("example:material", layer.material());
        assertEquals("triangles", layer.topology());
        assertEquals(12, layer.vertexStride());
        assertEquals("u16le",layer.indexFormat());
        assertEquals(6,layer.indices().length);
        assertEquals("example:mesh",layer.meshId());
        var caps=new RenderMeshCapabilities(Set.of(new RenderMeshCapabilities.Layout("minecraft:block-v1", "quads", 32)), Set.of("solid"), Set.of("minecraft:block-atlas"));
        assertFalse(caps.accepts(layer));
    }
}
