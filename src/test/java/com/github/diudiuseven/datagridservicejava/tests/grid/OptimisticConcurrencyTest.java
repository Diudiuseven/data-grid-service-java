package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.service.GridWriteService;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optimistic concurrency control on row versions. */
class OptimisticConcurrencyTest {

    private final MvccGridStore store = GridTestSupport.newStore();
    private final GridWriteService writes = GridTestSupport.writeService(store);

    @Test
    void staleVersionUpdateIsRejectedWithDistinguishableReason() {
        RowData created = writes.create("r1", GridTestSupport.fields("a", 1, 1, true));
        writes.update("r1", created.version(), Map.of("name", "b"));

        GridException ex = assertThrows(GridException.class,
                () -> writes.update("r1", created.version(), Map.of("name", "c")));
        assertEquals(ErrorReason.VERSION_CONFLICT, ex.reason());
        // The losing write must not have been applied.
        assertEquals("b", store.latest().rows().get("r1").fields().get("name"));
    }

    @Test
    void staleVersionDeleteIsRejected() {
        RowData created = writes.create("r1", GridTestSupport.fields("a", 1, 1, true));
        writes.update("r1", created.version(), Map.of("name", "b"));

        GridException ex = assertThrows(GridException.class,
                () -> writes.delete("r1", created.version()));
        assertEquals(ErrorReason.VERSION_CONFLICT, ex.reason());
        assertTrue(store.latest().rows().containsKey("r1"));
    }

    @Test
    void missingRowIsDistinguishableFromVersionConflict() {
        GridException ex = assertThrows(GridException.class,
                () -> writes.update("nope", 1, Map.of("name", "x")));
        assertEquals(ErrorReason.ROW_NOT_FOUND, ex.reason());
    }

    @Test
    void concurrentEditsNeverSilentlyOverwrite() throws Exception {
        RowData created = writes.create("r1", GridTestSupport.fields("a", 1, 1, true));
        long baseVersion = created.version();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    writes.update("r1", baseVersion, Map.of("name", "winner-" + n));
                    return true;
                } catch (GridException e) {
                    assertEquals(ErrorReason.VERSION_CONFLICT, e.reason());
                    return false;
                }
            }));
        }
        start.countDown();
        int successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                successes++;
            }
        }
        pool.shutdown();
        // Exactly one writer wins; every other writer is told, none silently overwritten.
        assertEquals(1, successes);
        assertEquals(baseVersion + 1, store.latest().rows().get("r1").version());
    }
}
