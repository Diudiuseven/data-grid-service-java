package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.github.diudiuseven.datagridservicejava.grid.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterOp;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.query.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.query.QuerySpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortDirection;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.service.GridQueryService;
import com.github.diudiuseven.datagridservicejava.grid.service.GridWriteService;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sorting, filtering and offset paging determinism. */
class GridQueryServiceTest {

    private final MvccGridStore store = GridTestSupport.newStore();
    private final GridWriteService writes = GridTestSupport.writeService(store);
    private final GridQueryService queries = GridTestSupport.queryService(store, new GridProperties());

    @Test
    void multiColumnSortWithStableTiebreaker() {
        // Same age for several rows: row id must decide the order deterministically.
        writes.create("r3", GridTestSupport.fields("c", 30, 3.0, true));
        writes.create("r1", GridTestSupport.fields("a", 30, 1.0, true));
        writes.create("r2", GridTestSupport.fields("b", 30, 2.0, false));
        writes.create("r4", GridTestSupport.fields("d", 20, 4.0, true));

        QuerySpec spec = new QuerySpec(List.of(),
                List.of(new SortKey("age", SortDirection.DESC)));
        PageResult page = queries.queryPage(spec, 0, 10);

        assertEquals(List.of("r1", "r2", "r3", "r4"),
                page.rows().stream().map(RowData::id).toList());
    }

    @Test
    void filteringIsTypedAndDeterministicAcrossRepeatedQueries() {
        for (int i = 0; i < 20; i++) {
            writes.create("row-" + i, GridTestSupport.fields("n" + i, i, i * 1.5, i % 2 == 0));
        }
        QuerySpec spec = new QuerySpec(
                List.of(new FilterSpec("age", FilterOp.GTE, 5),
                        new FilterSpec("active", FilterOp.EQ, true)),
                List.of(new SortKey("score", SortDirection.ASC)));

        PageResult first = queries.queryPage(spec, 0, 100);
        // Repeat the same query many times: content and order must be identical.
        for (int i = 0; i < 10; i++) {
            PageResult again = queries.queryPage(spec, 0, 100);
            assertEquals(first.rows(), again.rows());
        }
        // ages 5..19, even only -> 6,8,...,18 -> 7 rows
        assertEquals(7, first.totalMatching());
    }

    @Test
    void offsetPagesAreDisjointAndCoverAllRows() {
        for (int i = 0; i < 25; i++) {
            writes.create(String.format("r%02d", i), GridTestSupport.fields("n" + i, i, i, true));
        }
        QuerySpec spec = new QuerySpec(List.of(), List.of(new SortKey("age", SortDirection.ASC)));
        List<String> seen = new java.util.ArrayList<>();
        for (int offset = 0; offset < 25; offset += 10) {
            queries.queryPage(spec, offset, 10).rows().forEach(r -> seen.add(r.id()));
        }
        assertEquals(25, seen.size());
        assertEquals(25, seen.stream().distinct().count());
    }
}
