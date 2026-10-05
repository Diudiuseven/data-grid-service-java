package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnType;
import com.github.diudiuseven.datagridservicejava.grid.model.GridSchema;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterOp;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortDirection;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import com.github.diudiuseven.datagridservicejava.grid.view.Degradation;
import com.github.diudiuseven.datagridservicejava.grid.view.ResolvedView;
import com.github.diudiuseven.datagridservicejava.grid.view.ViewDefinition;
import com.github.diudiuseven.datagridservicejava.grid.view.ViewService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saved views survive schema evolution with explicit degradations. */
class ViewCompatibilityTest {

    private final MvccGridStore store = GridTestSupport.newStore();
    private final ViewService views = new ViewService(store);

    private ViewDefinition sampleView() {
        return new ViewDefinition("my-view",
                List.of(new FilterSpec("age", FilterOp.GTE, 18)),
                List.of(new SortKey("score", SortDirection.DESC)),
                25,
                List.of("name", "age", "score"),
                0, null);
    }

    @Test
    void viewResolvesCleanlyWhenSchemaIsUnchanged() {
        views.save(sampleView());
        ResolvedView resolved = views.resolve("my-view");
        assertTrue(resolved.degradations().isEmpty());
        assertEquals(1, resolved.view().filters().size());
        assertEquals(1, resolved.view().sorts().size());
        assertEquals(3, resolved.view().visibleColumns().size());
    }

    @Test
    void removedColumnDropsDependentPartsWithHints() {
        views.save(sampleView());
        // Schema evolution: drop the "age" column entirely.
        store.evolveSchema(new GridSchema(2, List.of(
                new ColumnDef("name", ColumnType.STRING),
                new ColumnDef("score", ColumnType.DOUBLE),
                new ColumnDef("active", ColumnType.BOOLEAN))));

        ResolvedView resolved = views.resolve("my-view");
        // View still opens; the age filter and age visible-column are dropped.
        assertTrue(resolved.view().filters().isEmpty());
        assertEquals(List.of("name", "score"), resolved.view().visibleColumns());
        assertEquals(1, resolved.view().sorts().size());
        assertTrue(resolved.degradations().stream()
                .anyMatch(d -> d.kind() == Degradation.Kind.COLUMN_REMOVED
                        && d.detail().contains("age")));
    }

    @Test
    void typeChangeDropsFilterAndSortWithHints() {
        views.save(sampleView());
        // Schema evolution: age LONG -> STRING.
        store.evolveSchema(new GridSchema(2, List.of(
                new ColumnDef("name", ColumnType.STRING),
                new ColumnDef("age", ColumnType.STRING),
                new ColumnDef("score", ColumnType.DOUBLE),
                new ColumnDef("active", ColumnType.BOOLEAN))));

        ResolvedView resolved = views.resolve("my-view");
        assertTrue(resolved.view().filters().isEmpty());
        assertTrue(resolved.degradations().stream()
                .anyMatch(d -> d.kind() == Degradation.Kind.COLUMN_TYPE_CHANGED
                        && d.detail().contains("age")));
        // Unaffected parts survive.
        assertEquals(1, resolved.view().sorts().size());
        assertEquals(25, resolved.view().pageSize());
    }

    @Test
    void unknownAndDuplicateViewsAreDistinguishable() {
        GridException missing = assertThrows(GridException.class, () -> views.get("nope"));
        assertEquals(ErrorReason.VIEW_NOT_FOUND, missing.reason());

        views.save(sampleView());
        GridException dup = assertThrows(GridException.class, () -> views.save(sampleView()));
        assertEquals(ErrorReason.VIEW_ALREADY_EXISTS, dup.reason());
    }
}
