package com.github.diudiuseven.datagridservicejava.grid.query;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.model.GridRow;
import com.github.diudiuseven.datagridservicejava.grid.model.SortDirection;
import com.github.diudiuseven.datagridservicejava.grid.model.SortSpec;
import com.github.diudiuseven.datagridservicejava.grid.store.Snapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic filter + sort over an immutable snapshot. Multi-column sort
 * priority is the list order; ties are broken by row id ascending, so the
 * resulting total order is identical for any paging order or concurrency.
 */
@Component
public class QueryEngine {

    /** Filter and fully sort the snapshot into a deterministic total order. */
    public List<GridRow> sortedRows(Snapshot snapshot, List<FilterSpec> filters,
                                    List<SortSpec> sorts) {
        List<GridRow> rows = new ArrayList<>();
        for (GridRow row : snapshot.rows()) {
            if (matchesAll(row, filters)) {
                rows.add(row);
            }
        }
        rows.sort(comparator(sorts));
        return rows;
    }

    private boolean matchesAll(GridRow row, List<FilterSpec> filters) {
        if (filters == null) {
            return true;
        }
        for (FilterSpec filter : filters) {
            if (!matches(row, filter)) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(GridRow row, FilterSpec filter) {
        Object actual = row.values().get(filter.column());
        Object expected = filter.value();
        return switch (filter.op()) {
            case EQ -> compareValues(actual, expected) == 0;
            case NE -> compareValues(actual, expected) != 0;
            case GT -> compareValues(actual, expected) > 0;
            case GE -> compareValues(actual, expected) >= 0;
            case LT -> compareValues(actual, expected) < 0;
            case LE -> compareValues(actual, expected) <= 0;
            case CONTAINS -> actual instanceof String s
                    && expected != null
                    && s.contains(String.valueOf(expected));
        };
    }

    private Comparator<GridRow> comparator(List<SortSpec> sorts) {
        Comparator<GridRow> comparator = null;
        if (sorts != null) {
            for (SortSpec sort : sorts) {
                Comparator<GridRow> key = (a, b) ->
                        compareValues(a.values().get(sort.column()), b.values().get(sort.column()));
                if (sort.direction() == SortDirection.DESC) {
                    key = key.reversed();
                }
                comparator = comparator == null ? key : comparator.thenComparing(key);
            }
        }
        // Stable, deterministic tie-break: row id ascending.
        Comparator<GridRow> tieBreak = Comparator.comparing(GridRow::id);
        return comparator == null ? tieBreak : comparator.thenComparing(tieBreak);
    }

    /**
     * Typed comparison: numbers numerically, strings lexicographically,
     * booleans false < true. Nulls sort before non-nulls (the DESC reversal
     * keeps them consistently placed). Incompatible types are a client error,
     * never a silent arbitrary order.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba.compareTo(bb);
        }
        if (a.getClass().isInstance(b) && a instanceof Comparable comparable) {
            return comparable.compareTo(b);
        }
        throw new GridException(ErrorReason.TYPE_MISMATCH,
                "cannot compare values of types " + a.getClass().getSimpleName()
                        + " and " + b.getClass().getSimpleName());
    }
}
