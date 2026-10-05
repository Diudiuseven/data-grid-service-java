package com.github.diudiuseven.datagridservicejava.grid;

import com.github.diudiuseven.datagridservicejava.grid.config.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.BatchEditResult;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnType;
import com.github.diudiuseven.datagridservicejava.grid.model.FilterOp;
import com.github.diudiuseven.datagridservicejava.grid.model.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.model.GridRow;
import com.github.diudiuseven.datagridservicejava.grid.model.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.model.RowEdit;
import com.github.diudiuseven.datagridservicejava.grid.model.SortDirection;
import com.github.diudiuseven.datagridservicejava.grid.model.SortSpec;
import com.github.diudiuseven.datagridservicejava.grid.model.ViewDefinition;
import com.github.diudiuseven.datagridservicejava.grid.model.ViewResolution;
import com.github.diudiuseven.datagridservicejava.grid.query.QueryEngine;
import com.github.diudiuseven.datagridservicejava.grid.service.GridService;
import com.github.diudiuseven.datagridservicejava.grid.store.InMemoryTableStore;
import com.github.diudiuseven.datagridservicejava.grid.store.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridServiceTest {

    private GridProperties properties;
    private InMemoryTableStore store;
    private GridService service;
    private QueryEngine queryEngine;

    @BeforeEach
    void setUp() {
        properties = new GridProperties();
        store = new InMemoryTableStore(properties);
        queryEngine = new QueryEngine();
        service = new GridService(store, queryEngine, properties);
        store.updateSchema(List.of(
                new ColumnDef("name", ColumnType.STRING),
                new ColumnDef("age", ColumnType.NUMBER),
                new ColumnDef("city", ColumnType.STRING)));
    }

    private List<String> seed(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(store.insert(Map.of(
                    "name", "user-" + i,
                    "age", 20 + (i % 50),
                    "city", "city-" + (i % 7))));
        }
        return ids;
    }

    private List<String> collectAllPages(PageResult first) {
        List<String> ids = new ArrayList<>();
        PageResult page = first;
        while (page != null) {
            page.rows().forEach(r -> ids.add(r.id()));
            page = page.nextCursor() == null ? null : service.nextPage(page.nextCursor());
        }
        return ids;
    }

    // ---- 1. deterministic paging under concurrency ----

    @Test
    void concurrentPagingYieldsIdenticalOrderAndContent() throws Exception {
        seed(120);
        List<SortSpec> sorts = List.of(
                new SortSpec("city", SortDirection.ASC),
                new SortSpec("age", SortDirection.DESC));

        List<String> expected = collectAllPages(service.firstPage(null, sorts, 10));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return collectAllPages(service.firstPage(null, sorts, 10));
            }));
        }
        start.countDown();
        for (Future<List<String>> f : futures) {
            assertEquals(expected, f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        // Deterministic tie-break: no duplicate ids, full coverage.
        assertEquals(120, new HashSet<>(expected).size());
    }

    @Test
    void multiColumnSortHasPriorityAndStableTieBreak() {
        seed(60);
        List<SortSpec> sorts = List.of(new SortSpec("age", SortDirection.ASC));
        List<String> ids = collectAllPages(service.firstPage(null, sorts, 7));
        Snapshot snapshot = store.latestSnapshot();
        long prevAge = Long.MIN_VALUE;
        String prevId = "";
        for (String id : ids) {
            GridRow row = snapshot.row(id).orElseThrow();
            long age = ((Number) row.values().get("age")).longValue();
            assertTrue(age > prevAge || (age == prevAge && id.compareTo(prevId) > 0),
                    "ties must be broken by row id ascending");
            prevAge = age;
            prevId = id;
        }
    }

    // ---- 2. cursor paging coexisting with writes ----

    @Test
    void cursorPagingUnaffectedByConcurrentInsertsAndDeletes() {
        List<String> seeded = seed(50);
        PageResult first = service.firstPage(null,
                List.of(new SortSpec("name", SortDirection.ASC)), 10);
        long cursorCommit = first.commitVersion();

        // Concurrent writes after the cursor was created.
        store.insert(Map.of("name", "aaa-new", "age", 1, "city", "city-0"));
        store.delete(seeded.get(0));
        store.delete(seeded.get(10));

        List<String> paged = collectAllPages(first);

        // Expected: exactly the rows of the pinned snapshot, in sorted order.
        Snapshot pinned = store.pinSnapshot(cursorCommit);
        List<String> expected;
        try {
            expected = queryEngine.sortedRows(pinned, null,
                    List.of(new SortSpec("name", SortDirection.ASC)))
                    .stream().map(GridRow::id).toList();
        } finally {
            store.releaseSnapshot(cursorCommit);
        }
        assertEquals(expected, paged, "no duplicate or missing rows vs the snapshot");
        assertEquals(50, paged.size());
        assertTrue(paged.contains(seeded.get(0)), "deleted row still visible in cursor snapshot");
    }

    @Test
    void expiredOrInvalidCursorFailsWithDistinguishableReason() throws Exception {
        seed(30);
        properties.setCursorTtlMillis(1);
        PageResult first = service.firstPage(null, null, 10);
        assertNotNull(first.nextCursor());
        Thread.sleep(10);
        GridException expired = assertThrows(GridException.class,
                () -> service.nextPage(first.nextCursor()));
        assertEquals(ErrorReason.CURSOR_EXPIRED, expired.reason());

        GridException invalid = assertThrows(GridException.class,
                () -> service.nextPage("not-a-cursor"));
        assertEquals(ErrorReason.CURSOR_INVALID, invalid.reason());
    }

    // ---- 3. optimistic concurrency ----

    @Test
    void staleVersionWriteIsRejectedWithVersionConflict() {
        String id = store.insert(Map.of("name", "a", "age", 1, "city", "c"));
        BatchEditResult ok = service.edit(List.of(new RowEdit(id, 1, Map.of("age", 2))), true);
        assertTrue(ok.committed());

        BatchEditResult stale = service.edit(
                List.of(new RowEdit(id, 1, Map.of("age", 3))), false);
        assertFalse(stale.outcomes().get(0).success());
        assertEquals(ErrorReason.VERSION_CONFLICT.name(), stale.outcomes().get(0).reason());
        // Not silently overwritten: value from the first edit is intact.
        GridRow row = store.latestSnapshot().row(id).orElseThrow();
        assertEquals(2, ((Number) row.values().get("age")).intValue());
        assertEquals(2, row.version());
    }

    @Test
    void concurrentEditsOfSameRowExactlyOneWins() throws Exception {
        String id = store.insert(Map.of("name", "a", "age", 1, "city", "c"));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<BatchEditResult> results = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            int value = t;
            pool.submit(() -> {
                start.await();
                results.add(service.edit(
                        List.of(new RowEdit(id, 1, Map.of("age", value))), true));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long wins = results.stream().filter(BatchEditResult::committed).count();
        assertEquals(1, wins, "exactly one concurrent edit may commit");
        assertEquals(2, store.latestSnapshot().row(id).orElseThrow().version());
    }

    // ---- 4. batch semantics ----

    @Test
    void atomicBatchWithOneFailureCommitsNothing() {
        List<String> ids = seed(3);
        List<RowEdit> edits = List.of(
                new RowEdit(ids.get(0), 1, Map.of("age", 100)),
                new RowEdit(ids.get(1), 999, Map.of("age", 100)), // stale version
                new RowEdit(ids.get(2), 1, Map.of("age", 100)));
        BatchEditResult result = service.edit(edits, true);

        assertFalse(result.committed());
        assertEquals(ErrorReason.VERSION_CONFLICT.name(), result.outcomes().get(1).reason());
        assertEquals(ErrorReason.BATCH_NOT_COMMITTED.name(), result.outcomes().get(0).reason());
        Snapshot snapshot = store.latestSnapshot();
        for (String id : ids) {
            GridRow row = snapshot.row(id).orElseThrow();
            assertEquals(1, row.version(), "no partial writes allowed");
        }
    }

    @Test
    void perRowBatchAppliesGoodRowsAndReportsBadOnes() {
        List<String> ids = seed(3);
        List<RowEdit> edits = List.of(
                new RowEdit(ids.get(0), 1, Map.of("age", 100)),
                new RowEdit(ids.get(1), 999, Map.of("age", 100)),
                new RowEdit("missing-row", 1, Map.of("age", 100)));
        BatchEditResult result = service.edit(edits, false);

        assertTrue(result.committed());
        assertTrue(result.outcomes().get(0).success());
        assertEquals(2, result.outcomes().get(0).newVersion());
        assertEquals(ErrorReason.VERSION_CONFLICT.name(), result.outcomes().get(1).reason());
        assertEquals(ErrorReason.ROW_NOT_FOUND.name(), result.outcomes().get(2).reason());
        Snapshot snapshot = store.latestSnapshot();
        assertEquals(100, ((Number) snapshot.row(ids.get(0)).orElseThrow()
                .values().get("age")).intValue());
        assertEquals(1, snapshot.row(ids.get(1)).orElseThrow().version());
    }

    // ---- 5. saved view compatibility ----

    @Test
    void viewSurvivesSchemaChangesWithDegradationNotes() {
        service.saveView(new ViewDefinition("my-view",
                List.of(new FilterSpec("age", FilterOp.GT, 30),
                        new FilterSpec("name", FilterOp.CONTAINS, "user")),
                List.of(new SortSpec("city", SortDirection.ASC)),
                25,
                List.of("name", "age", "city")));

        // Schema evolves: city removed, age retyped to STRING.
        store.updateSchema(List.of(
                new ColumnDef("name", ColumnType.STRING),
                new ColumnDef("age", ColumnType.STRING)));

        ViewResolution resolution = service.loadView("my-view");
        assertEquals(3, resolution.degradations().size());
        assertTrue(resolution.degradations().stream().anyMatch(d -> d.contains("city")));
        assertTrue(resolution.degradations().stream().anyMatch(d -> d.contains("age")));
        // Still-openable remainder: name filter and name visible column survive.
        assertEquals(1, resolution.view().filters().size());
        assertEquals("name", resolution.view().filters().get(0).column());
        assertEquals(List.of("name", "age"), resolution.view().visibleColumns());
        assertTrue(resolution.view().sorts().isEmpty());
    }

    @Test
    void missingViewFailsWithDistinguishableReason() {
        GridException e = assertThrows(GridException.class, () -> service.loadView("nope"));
        assertEquals(ErrorReason.VIEW_NOT_FOUND, e.reason());
    }

    // ---- 6. concurrent read/write consistency ----

    @Test
    void readersNeverObservePartiallyCommittedBatch() throws Exception {
        // Both rows start identical so any observed divergence is a torn read.
        String a = store.insert(Map.of("name", "a", "age", 0, "city", "c"));
        String b = store.insert(Map.of("name", "b", "age", 0, "city", "c"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (long v = 1; v <= 200; v++) {
                    // Atomic two-row commit: both rows always advance together.
                    BatchEditResult r = service.edit(List.of(
                            new RowEdit(a, v, Map.of("age", v)),
                            new RowEdit(b, v, Map.of("age", v))), true);
                    assertTrue(r.committed());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Future<?> reader = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    Snapshot snapshot = store.latestSnapshot();
                    GridRow ra = snapshot.row(a).orElseThrow();
                    GridRow rb = snapshot.row(b).orElseThrow();
                    assertEquals(ra.version(), rb.version(),
                            "snapshot must never mix versions across rows");
                    assertEquals(ra.values().get("age"), rb.values().get("age"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        reader.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(201, store.latestSnapshot().row(a).orElseThrow().version());
    }

    @Test
    void offsetAndCursorPagingOfSameQueryAgreeOnContent() {
        seed(45);
        List<FilterSpec> filters = List.of(new FilterSpec("age", FilterOp.GE, 25));
        List<SortSpec> sorts = List.of(new SortSpec("name", SortDirection.DESC));

        Set<String> viaCursor = new HashSet<>(
                collectAllPages(service.firstPage(filters, sorts, 8)));
        Set<String> viaOffset = new HashSet<>();
        for (int page = 0; page < 10; page++) {
            PageResult p = service.offsetPage(filters, sorts, page, 8);
            p.rows().forEach(r -> viaOffset.add(r.id()));
            if (p.rows().size() < 8) {
                break;
            }
        }
        assertEquals(viaCursor, viaOffset);
        assertNull(service.offsetPage(filters, sorts, 0, 8).nextCursor(),
                "offset paging is stateless and returns no cursor");
    }
}
