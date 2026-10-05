package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.github.diudiuseven.datagridservicejava.grid.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.query.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.query.QuerySpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortDirection;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.service.BatchMode;
import com.github.diudiuseven.datagridservicejava.grid.service.GridQueryService;
import com.github.diudiuseven.datagridservicejava.grid.service.GridWriteService;
import com.github.diudiuseven.datagridservicejava.grid.store.Mutation;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads always observe a fully committed snapshot, never a mixed state. */
class ConcurrentReadWriteTest {

    private final GridProperties properties = new GridProperties();
    private final MvccGridStore store = GridTestSupport.newStore(properties);
    private final GridWriteService writes = GridTestSupport.writeService(store);
    private final GridQueryService queries = GridTestSupport.queryService(store, properties);

    @Test
    void readersNeverObservePartiallyAppliedBatches() throws Exception {
        writes.create("p", GridTestSupport.fields("p", 0, 0, true));
        writes.create("q", GridTestSupport.fields("q", 0, 0, true));

        int writerIterations = 200;
        int readerThreads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> readerFailures = new CopyOnWriteArrayList<>();

        // Writer: repeatedly bumps both rows together in one atomic commit.
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int i = 1; i <= writerIterations; i++) {
                    long vp = store.latest().rows().get("p").version();
                    long vq = store.latest().rows().get("q").version();
                    writes.applyBatch(List.of(
                            Mutation.update("p", vp, Map.of("age", (long) i)),
                            Mutation.update("q", vq, Map.of("age", (long) i))),
                            BatchMode.ATOMIC);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Readers: continuously read both rows; they must always agree.
        QuerySpec spec = new QuerySpec(List.of(),
                List.of(new SortKey("name", SortDirection.ASC)));
        List<Future<?>> readers = new java.util.ArrayList<>();
        for (int r = 0; r < readerThreads; r++) {
            readers.add(pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        PageResult page = queries.queryPage(spec, 0, 10);
                        Map<String, Object> p = find(page.rows(), "p").fields();
                        Map<String, Object> q = find(page.rows(), "q").fields();
                        if (!p.get("age").equals(q.get("age"))) {
                            readerFailures.add(new AssertionError(
                                    "mixed state observed: p=" + p.get("age") + " q=" + q.get("age")));
                            return;
                        }
                        if (writer.isDone()) {
                            return;
                        }
                    }
                } catch (Throwable t) {
                    readerFailures.add(t);
                }
            }));
        }

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> reader : readers) {
            reader.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertTrue(readerFailures.isEmpty(), () -> "reader failures: " + readerFailures);
        // All writer commits landed.
        assertEquals((long) writerIterations, store.latest().rows().get("p").fields().get("age"));
    }

    @Test
    void slowReaderOnPinnedSnapshotDoesNotBlockWriters() throws Exception {
        for (int i = 0; i < 10; i++) {
            writes.create("r" + i, GridTestSupport.fields("n" + i, i, i, true));
        }
        QuerySpec spec = new QuerySpec(List.of(),
                List.of(new SortKey("age", SortDirection.ASC)));
        // Pin a snapshot by starting a cursor scan.
        PageResult first = queries.queryByCursor(spec, null, 3);
        long pinnedSeq = first.snapshotCommitSeq();

        // Writers keep committing while the "slow" reader holds its cursor.
        for (int i = 10; i < 30; i++) {
            writes.create("r" + i, GridTestSupport.fields("n" + i, i, i, true));
        }
        // The reader still finishes its scan against the original snapshot.
        PageResult second = queries.queryByCursor(spec, first.nextCursor(), 100);
        assertEquals(pinnedSeq, second.snapshotCommitSeq());
        assertEquals(10, first.totalMatching());
        assertEquals(7, second.rows().size());
    }

    private static RowData find(List<RowData> rows, String id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }
}
