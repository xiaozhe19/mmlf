package dev.mmlf.common;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderSectionStoreTest {
    @Test void snapshotsAreStableAndOwnTheirVertexBytes() {
        var store = new RenderSectionStore();
        byte[] bytes = new byte[128]; bytes[0]=1; bytes[1]=2;
        var layer = new RenderSectionStore.Layer("solid", 4, bytes);
        store.submit(1, 42, List.of(layer));
        var first = store.snapshot();
        bytes[0] = 9;
        var key=new RenderSectionStore.Key("minecraft:sections",1);
        first.sections().get(key).layers().get(0).vertices()[1] = 9;
        store.submit(1, 43, List.of());
        assertEquals(1, first.sections().get(key).layers().get(0).vertices()[0]);
        assertEquals(2, first.sections().get(key).layers().get(0).vertices()[1]);
        assertTrue(store.snapshot().sections().get(key).layers().isEmpty());
        assertTrue(store.snapshot().revision() > first.revision());
        store.remove(1);
        assertTrue(store.snapshot().sections().isEmpty());
    }

    @Test void concurrentSectionCompilersDoNotLoseUpdates() throws Exception {
        var store = new RenderSectionStore();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 400; i++) {
                int section = i;
                tasks.add(pool.submit(() -> store.submit(section, 0, List.of())));
            }
            for (var task : tasks) task.get();
        }
        var snapshot = store.snapshot();
        assertEquals(400, snapshot.sections().size());
        assertEquals(400, snapshot.revision());
        store.clear();
        assertEquals(401, store.snapshot().revision());
    }

    @Test void replacedSceneRejectsLateCompilerResults() {
        var store = new RenderSectionStore();
        long oldScene = store.generation();
        store.submit(oldScene, 1, 0, List.of());
        long currentScene = store.resetScene();
        assertFalse(store.submit(oldScene, 2, 0, List.of()));
        assertTrue(store.submit(currentScene, 3, 0, List.of()));
        assertFalse(store.remove(oldScene, 3));
        assertEquals(java.util.Set.of(new RenderSectionStore.Key("minecraft:sections",3)), store.snapshot().sections().keySet());
    }

    @Test void identicalRebuildDoesNotProduceAnotherTransfer() {
        var store = new RenderSectionStore();
        byte[] original=new byte[128]; original[0]=1;
        store.submit(1, 1, List.of(new RenderSectionStore.Layer("solid", 4, original)));
        long revision = store.snapshot().revision();
        store.submit(1, 2, List.of(new RenderSectionStore.Layer("solid", 4, original.clone())));
        assertEquals(revision, store.snapshot().revision());
        assertNull(store.snapshotIfChanged(revision));
        byte[] changed=original.clone(); changed[1]=3;
        store.submit(1, 3, List.of(new RenderSectionStore.Layer("solid", 4, changed)));
        assertEquals(revision + 1, store.snapshot().revision());
        assertNotNull(store.snapshotIfChanged(revision));
    }

    @Test void independentProducersDoNotOverwriteTheSameSection() {
        var store=new RenderSectionStore();
        assertTrue(store.submit(0,"renderer:a",1,0,List.of()));
        assertTrue(store.submit(0,"renderer:b",1,0,List.of()));
        assertEquals(2,store.snapshot().sections().size());
        assertTrue(store.remove(0,"renderer:a",1));
        assertEquals(java.util.Set.of(new RenderSectionStore.Key("renderer:b",1)),store.snapshot().sections().keySet());
    }

    @Test void newerSectionResultsAndDeletesBeatLateWork() {
        var store=new RenderSectionStore();
        assertTrue(store.submitVersioned(0,"renderer",4,10,0,List.of()));
        assertTrue(store.removeVersioned(0,"renderer",4,12));
        assertFalse(store.submitVersioned(0,"renderer",4,11,0,List.of()));
        assertTrue(store.snapshot().sections().isEmpty());
        assertTrue(store.submitVersioned(0,"renderer",4,13,0,List.of()));
        assertEquals(1,store.snapshot().sections().size());
    }
}
