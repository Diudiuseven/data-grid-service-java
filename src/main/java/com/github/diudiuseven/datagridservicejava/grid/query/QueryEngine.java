package com.github.diudiuseven.datagridservicejava.grid.query;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnType;
import com.github.diudiuseven.datagridservicejava.grid.model.GridSchema;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic query evaluation: typed filtering and a total row ordering.
 * The row id is always appended as the final sort tiebreaker, so equal sort
 * keys never produce unstable order between pages or runs.
 */
public final class QueryEngine {

    private QueryEngine() {
    }

    /** Validate the spec against the schema and coerce filter values to column types. */
    public static List<FilterSpec> validateAndCoerce(QuerySpec spec, GridSchema schema) {
        List<FilterSpec> coerced = new ArrayList<>();
        for (FilterSpec f : spec.filters()) {
            ColumnDef col = schema.column(f.column()).orElseThrow(() ->
                    new GridException(ErrorReason.INVALID_QUERY, "unknown filter column: " + f.column()));
            coerced.add(new FilterSpec(f.column(), f.op(),
                    f.value() == null ? null : coerce(f.value(), col.type()),
                    f.values() == null ? null : f.values().stream().map(v -> coerce(v, col.type())).toList()));
        }
        for (SortKey s : spec.sorts()) {
            if (!schema.hasColumn(s.column())) {
                throw new GridException(ErrorReason.INVALID_QUERY, "unknown sort column: " + s.column());
            }
        }
        return coerced;
    }

    /** Coerce a client-supplied value into the column's canonical type. */
    public static Object coerce(Object value, ColumnType type) {
        try {
            return switch (type) {
                case STRING -> value instanceof String s ? s : String.valueOf(value);
                case LONG -> value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
                case DOUBLE -> value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
                case BOOLEAN -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
                case INSTANT -> value instanceof Instant i ? i : Instant.parse(String.valueOf(value));
            };
        } catch (RuntimeException e) {
            throw new GridException(ErrorReason.INVALID_QUERY,
                    "value '" + value + "' not convertible to " + type);
        }
    }

    /** Typed comparison; null sorts before any non-null value. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return ((Comparable) a).compareTo(b);
    }

    public static boolean matches(RowData row, List<FilterSpec> filters) {
        for (FilterSpec f : filters) {
            Object actual = row.fields().get(f.column());
            boolean ok = switch (f.op()) {
                case EQ -> compareValues(actual, f.value()) == 0;
                case NE -> compareValues(actual, f.value()) != 0;
                case LT -> actual != null && compareValues(actual, f.value()) < 0;
                case LTE -> actual != null && compareValues(actual, f.value()) <= 0;
                case GT -> actual != null && compareValues(actual, f.value()) > 0;
                case GTE -> actual != null && compareValues(actual, f.value()) >= 0;
                case CONTAINS -> actual instanceof String s && f.value() != null
                        && s.contains(String.valueOf(f.value()));
                case IN -> f.values() != null && f.values().stream()
                        .anyMatch(v -> compareValues(actual, v) == 0);
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Total ordering: sort keys in priority order, row id as final
     * tiebreaker. Deterministic regardless of paging order or concurrency.
     */
    public static Comparator<RowData> comparator(List<SortKey> sorts) {
        return (r1, r2) -> {
            for (SortKey s : sorts) {
                int c = compareValues(r1.fields().get(s.column()), r2.fields().get(s.column()));
                if (c != 0) {
                    return s.direction() == SortDirection.ASC ? c : -c;
                }
            }
            return r1.id().compareTo(r2.id());
        };
    }

    /**
     * Compare a row against a cursor position (sort values + row id).
     * Returns negative if the row sorts before the cursor position.
     */
    public static int compareToCursor(RowData row, List<SortKey> sorts, List<Object> lastSortValues,
                                      String lastRowId) {
        for (int i = 0; i < sorts.size(); i++) {
            SortKey s = sorts.get(i);
            int c = compareValues(row.fields().get(s.column()), lastSortValues.get(i));
            if (c != 0) {
                return s.direction() == SortDirection.ASC ? c : -c;
            }
        }
        return row.id().compareTo(lastRowId);
    }
}
