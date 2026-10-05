package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.github.diudiuseven.datagridservicejava.grid.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.query.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.query.QuerySpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortDirection;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.service.GridQueryService;
import com.github.diudiuseven.datagridservicejava.grid.service.GridWriteService;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cursor paging coexisting with continuous writes. */
class CursorPagingTest {

    private final GridProperties properties = new GridProperties();
    private final MvccGridStore store = GridTestSupport.newStore(properties);
    private final GridWriteService writes = GridTestSupport.writeService(store);
    private final GridQueryService queries = GridTestSupport.queryService(store, properties);

    private static final QuerySpec BY_AGE = new QuerySpec(List.of(),
            List.of(new SortKey("age", SortDirection.ASC)));

    @Test
    void insertsAndDeletesDuringScanCauseNoDuplicatesOrMissingRows() {
        for (int i = 0; i < 30; i++) {
            writes.create(String.format("r%02d", i), GridTestSupport.fields("n" + i, i, i, true));
        }
        Set<String> baseline = new LinkedHashSet<>();
        for (int i = 0; i < 30; i++) {
            baseline.add(String.format("r%02d", i));
        }

        Set<String> seen = new LinkedHashSet<>();
        String cursor = null;
        int page = 0;
        do {
            PageResult result = queries.queryByCursor(BY_AGE, cursor, 7);
            for (RowData row : result.rows()) {
                assertTrue(seen.add(row.id()), "duplicate row across pages: " + row.id());
            }
            cursor = result.nextCursor();
            // Concurrent writer activity between pages:
            if (page == 0) {
                writes.create("new-1", GridTestSupport.fields("x", 100, 100, true)); // insert
                writes.delete("r05", 1);                                             // delete
            }
            if (page == 1) {
                writes.update("r10", 1, GridTestSupport.fields("n10b", 10, 10, true)); // update
                writes.create("new-2", GridTestSupport.fields("y", 0, 0, false));
            }
            page++;
        } while (cursor != null);

        // The scan is anchored to the snapshot of its first page: rows present
        // then are all delivered exactly once; rows deleted afterwards still
        // appear (they existed in that snapshot), rows added afterwards do not.
        assertEquals(baseline, seen);
    }

    @Test
    void malformedCursorIsDistinguishable() {
        GridException ex = assertThrows(GridException.class,
                () -> queries.queryByCursor(BY_AGE, "not-a-cursor", 10));
        assertEquals(ErrorReason.CURSOR_MALFORMED, ex.reason());
    }

    @Test
    void cursorFromDifferentQueryIsRejected() {
        writes.create("a", GridTestSupport.fields("a", 1, 1, true));
        writes.create("b", GridTestSupport.fields("b", 2, 2, true));
        String cursor = queries.queryByCursor(BY_AGE, null, 1).nextCursor();

        QuerySpec otherQuery = new QuerySpec(List.of(),
                List.of(new SortKey("age", SortDirection.DESC)));
        GridException ex = assertThrows(GridException.class,
                () -> queries.queryByCursor(otherQuery, cursor, 1));
        assertEquals(ErrorReason.CURSOR_QUERY_MISMATCH, ex.reason());
    }

    @Test
    void cursorExpiresWhenSnapshotIsEvicted() {
        GridProperties tight = new GridProperties();
        tight.setSnapshotRetention(2);
        MvccGridStore smallStore = GridTestSupport.newStore(tight);
        GridWriteService smallWrites = GridTestSupport.writeService(smallStore);
        GridQueryService smallQueries = GridTestSupport.queryService(smallStore, tight);

        for (int i = 0; i < 5; i++) {
            smallWrites.create("r" + i, GridTestSupport.fields("n" + i, i, i, true));
        }
        String cursor = smallQueries.queryByCursor(BY_AGE, null, 2).nextCursor();
        // Push the pinned snapshot out of the retention window with new commits.
        for (int i = 5; i < 15; i++) {
            smallWrites.create("r" + i, GridTestSupport.fields("n" + i, i, i, true));
        }
        GridException ex = assertThrows(GridException.class,
                () -> smallQueries.queryByCursor(BY_AGE, cursor, 2));
        assertEquals(ErrorReason.CURSOR_EXPIRED, ex.reason());
    }

    @Test
    void fullScanCoversExactlyAllRowsWithPageSizeOne() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            String id = String.format("r%02d", i);
            writes.create(id, GridTestSupport.fields("n" + i, i, i, true));
            expected.add(id);
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            PageResult result = queries.queryByCursor(BY_AGE, cursor, 1);
            result.rows().forEach(r -> seen.add(r.id()));
            cursor = result.nextCursor();
        } while (cursor != null);
        assertEquals(expected, seen);
    }
}
