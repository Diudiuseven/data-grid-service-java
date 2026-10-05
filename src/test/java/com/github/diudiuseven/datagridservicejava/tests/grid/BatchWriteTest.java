package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.service.BatchMode;
import com.github.diudiuseven.datagridservicejava.grid.service.BatchResult;
import com.github.diudiuseven.datagridservicejava.grid.service.GridWriteService;
import com.github.diudiuseven.datagridservicejava.grid.service.RowResult;
import com.github.diudiuseven.datagridservicejava.grid.store.Mutation;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atomic and per-row batch semantics. */
class BatchWriteTest {

    private final MvccGridStore store = GridTestSupport.newStore();
    private final GridWriteService writes = GridTestSupport.writeService(store);

    @Test
    void atomicBatchAppliesAllOrNothing() {
        writes.create("ok1", GridTestSupport.fields("a", 1, 1, true));
        RowData ok1 = store.latest().rows().get("ok1");

        List<Mutation> batch = List.of(
                Mutation.update("ok1", ok1.version(), Map.of("name", "updated")),
                Mutation.update("ghost", 1, Map.of("name", "x")) // ROW_NOT_FOUND
        );
        BatchResult result = writes.applyBatch(batch, BatchMode.ATOMIC);

        assertFalse(result.applied());
        // The failing row is identified with its reason.
        RowResult failed = result.results().get(1);
        assertFalse(failed.success());
        assertEquals(ErrorReason.ROW_NOT_FOUND, failed.errorReason());
        // Nothing was applied: the valid mutation left no partial write.
        assertEquals("a", store.latest().rows().get("ok1").fields().get("name"));
        assertEquals(ok1.version(), store.latest().rows().get("ok1").version());
    }

    @Test
    void atomicBatchSuccessCommitsEverything() {
        BatchResult result = writes.applyBatch(List.of(
                Mutation.create("a", GridTestSupport.fields("a", 1, 1, true)),
                Mutation.create("b", GridTestSupport.fields("b", 2, 2, false))),
                BatchMode.ATOMIC);

        assertTrue(result.applied());
        assertEquals(2, result.results().size());
        assertTrue(result.results().stream().allMatch(RowResult::success));
        assertEquals(2, store.latest().size());
    }

    @Test
    void perRowBatchReportsEachRowAndAppliesValidOnes() {
        writes.create("keep", GridTestSupport.fields("k", 1, 1, true));
        long keepVersion = store.latest().rows().get("keep").version();

        BatchResult result = writes.applyBatch(List.of(
                Mutation.update("keep", keepVersion, Map.of("name", "k2")),       // ok
                Mutation.update("keep", keepVersion, Map.of("name", "stale")),   // stale version
                Mutation.create("new", GridTestSupport.fields("n", 2, 2, true)), // ok
                Mutation.delete("ghost", 1)),                                    // not found
                BatchMode.PER_ROW);

        List<RowResult> rows = result.results();
        assertEquals(4, rows.size());
        assertTrue(rows.get(0).success());
        assertFalse(rows.get(1).success());
        assertEquals(ErrorReason.VERSION_CONFLICT, rows.get(1).errorReason());
        assertTrue(rows.get(2).success());
        assertFalse(rows.get(3).success());
        assertEquals(ErrorReason.ROW_NOT_FOUND, rows.get(3).errorReason());

        // Valid rows applied; failed rows left no trace.
        assertEquals("k2", store.latest().rows().get("keep").fields().get("name"));
        assertTrue(store.latest().rows().containsKey("new"));
        assertNull(store.latest().rows().get("ghost"));
    }

    @Test
    void versionConflictInsideAtomicBatchRejectsEverything() {
        writes.create("x", GridTestSupport.fields("x", 1, 1, true));
        writes.create("y", GridTestSupport.fields("y", 2, 2, true));
        long xVersion = store.latest().rows().get("x").version();
        long yVersion = store.latest().rows().get("y").version();

        // Someone else modifies y first.
        writes.update("y", yVersion, Map.of("name", "y-other"));

        BatchResult result = writes.applyBatch(List.of(
                Mutation.update("x", xVersion, Map.of("name", "x-mine")),
                Mutation.update("y", yVersion, Map.of("name", "y-mine"))),
                BatchMode.ATOMIC);

        assertFalse(result.applied());
        assertEquals(ErrorReason.VERSION_CONFLICT, result.results().get(1).errorReason());
        assertEquals("x", store.latest().rows().get("x").fields().get("name"));
        assertEquals("y-other", store.latest().rows().get("y").fields().get("name"));
    }
}
