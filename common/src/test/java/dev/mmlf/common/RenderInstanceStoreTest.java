package dev.mmlf.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderInstanceStoreTest {
    @Test void revisionsAndSceneChangesRejectStaleUpdates() {
        var store=new RenderInstanceStore(); long scene=store.generation();
        float[] transform=new float[16]; transform[0]=1;
        assertTrue(store.submit(scene,new RenderInstanceStore.Instance("gear", "mesh", "material", 1, transform)));
        transform[0]=9;
        assertEquals(1,store.snapshot().instances().get("gear").transform()[0]);
        assertTrue(store.remove(scene,"gear",2));
        assertFalse(store.submit(scene,new RenderInstanceStore.Instance("gear", "mesh", "material", 1, transform)));
        assertTrue(store.snapshot().instances().isEmpty());
        long next=store.resetScene();
        assertFalse(store.submit(scene,new RenderInstanceStore.Instance("gear", "mesh", "material", 3, transform)));
        assertTrue(store.submit(next,new RenderInstanceStore.Instance("gear", "mesh", "material", 1, transform)));
    }

    @Test void coordinatedSceneGenerationMustIncrease() {
        var store=new RenderInstanceStore();
        assertEquals(5,store.resetScene(5));
        assertThrows(IllegalArgumentException.class,()->store.resetScene(5));
        assertEquals(6,store.resetScene());
    }

    @Test void instanceSnapshotsSurviveTransportAndRejectTruncation() throws Exception {
        var store=new RenderInstanceStore();
        assertTrue(store.submit(0,new RenderInstanceStore.Instance("a", "mesh", "material", 4, new float[16])));
        byte[] data=RenderInstanceCodec.encode(new RenderInstanceCodec.Batch("fabric","minecraft:overworld",store.snapshot()));
        var batch=RenderInstanceCodec.decode(data);
        assertEquals("mesh",batch.snapshot().instances().get("a").mesh());
        assertEquals(4,batch.snapshot().instances().get("a").revision());
        assertThrows(java.io.IOException.class,()->RenderInstanceCodec.decode(java.util.Arrays.copyOf(data,data.length-1)));
    }

    @Test void unchangedInstanceAdvancesSourceRevisionWithoutRepublishing() {
        var store=new RenderInstanceStore();
        var transform=new float[16];
        assertTrue(store.submit(0,new RenderInstanceStore.Instance("a","mesh","material",1,transform)));
        long version=store.snapshot().version();
        assertTrue(store.submit(0,new RenderInstanceStore.Instance("a","mesh","material",2,transform)));
        assertEquals(version,store.snapshot().version());
        assertFalse(store.remove(0,"a",2));
        transform[12]=1;
        assertTrue(store.submit(0,new RenderInstanceStore.Instance("a","mesh","material",3,transform)));
        assertEquals(version+1,store.snapshot().version());
    }
}
