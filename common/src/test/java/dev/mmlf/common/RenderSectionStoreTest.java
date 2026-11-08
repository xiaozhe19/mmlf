package dev.mmlf.common;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderSectionStoreTest {
    @Test void snapshotsAreStableAndOwnTheirVertexBytes() {
        var store = new RenderSectionStore();
        byte[] bytes = {1, 2};
        var layer = new RenderSectionStore.Layer("solid", 4, bytes);
        store.submit(1, 42, List.of(layer));
        var first = store.snapshot();
        bytes[0] = 9;
        first.sections().get(1L).layers().get(0).vertices()[1] = 9;
        store.submit(1, 43, List.of());
        assertArrayEquals(new byte[]{1, 2}, first.sections().get(1L).layers().get(0).vertices());
        assertTrue(store.snapshot().sections().get(1L).layers().isEmpty());
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
}
