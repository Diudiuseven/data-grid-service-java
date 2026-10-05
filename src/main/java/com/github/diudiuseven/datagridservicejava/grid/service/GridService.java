package com.github.diudiuseven.datagridservicejava.grid.service;

import com.github.diudiuseven.datagridservicejava.grid.config.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.BatchEditResult;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.model.GridRow;
import com.github.diudiuseven.datagridservicejava.grid.model.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.model.RowEdit;
import com.github.diudiuseven.datagridservicejava.grid.model.SortSpec;
import com.github.diudiuseven.datagridservicejava.grid.model.ViewDefinition;
import com.github.diudiuseven.datagridservicejava.grid.model.ViewResolution;
import com.github.diudiuseven.datagridservicejava.grid.query.QueryEngine;
import com.github.diudiuseven.datagridservicejava.grid.store.Snapshot;
import com.github.diudiuseven.datagridservicejava.grid.store.TableStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application service. Cursor pages are always sliced from the single pinned
 * snapshot the cursor was created on, so concurrent inserts/deletes during
 * paging can never produce duplicate or missing rows within that cursor.
 */
@Service
public class GridService {

    /** Server-side cursor state; the client only sees the opaque token. */
    private record CursorState(String id, long commitVersion, List<FilterSpec> filters,
                               List<SortSpec> sorts, int pageSize, int position,
                               long lastAccessMillis) {
    }

    private final TableStore store;
    private final QueryEngine queryEngine;
    private final GridProperties properties;
    private final Map<String, CursorState> cursors = new ConcurrentHashMap<>();
    private final Map<String, ViewDefinition> views = new ConcurrentHashMap<>();

    public GridService(TableStore store, QueryEngine queryEngine,
                       GridProperties properties) {
        this.store = store;
        this.queryEngine = queryEngine;
        this.properties = properties;
    }

    /** First page of a query; pins the snapshot and returns a cursor. */
    public PageResult firstPage(List<FilterSpec> filters, List<SortSpec> sorts,
                                Integer pageSize) {
        evictExpiredCursors();
        int size = resolvePageSize(pageSize);
        List<FilterSpec> f = filters == null ? List.of() : List.copyOf(filters);
        List<SortSpec> s = sorts == null ? List.of() : List.copyOf(sorts);
        validateColumns(f, s);
        Snapshot snapshot = store.latestSnapshot();
        store.pinSnapshot(snapshot.commitVersion());
        String id = UUID.randomUUID().toString();
        CursorState state = new CursorState(id, snapshot.commitVersion(), f, s, size, 0,
                now());
        // slice() publishes the advanced cursor state (or invalidates it when
        // the cursor is already exhausted).
        return slice(state, snapshot);
    }

    /** Next page from a cursor previously returned by this service. */
    public PageResult nextPage(String cursor) {
        evictExpiredCursors();
        String id = parseCursor(cursor);
        CursorState state = cursors.get(id);
        if (state == null) {
            throw new GridException(ErrorReason.CURSOR_EXPIRED,
                    "cursor is unknown or has expired");
        }
        if (now() - state.lastAccessMillis() > properties.getCursorTtlMillis()) {
            invalidate(id);
            throw new GridException(ErrorReason.CURSOR_EXPIRED,
                    "cursor idle for too long");
        }
        Snapshot snapshot = store.pinSnapshot(state.commitVersion());
        try {
            CursorState advanced = new CursorState(state.id(), state.commitVersion(),
                    state.filters(), state.sorts(), state.pageSize(), state.position(),
                    now());
            return slice(advanced, snapshot);
        } finally {
            store.releaseSnapshot(state.commitVersion());
        }
    }

    /** Stateless offset page read from the latest committed snapshot. */
    public PageResult offsetPage(List<FilterSpec> filters, List<SortSpec> sorts,
                                 int page, Integer pageSize) {
        if (page < 0) {
            throw new GridException(ErrorReason.VALIDATION_FAILED,
                    "page must be >= 0");
        }
        int size = resolvePageSize(pageSize);
        List<FilterSpec> f = filters == null ? List.of() : filters;
        List<SortSpec> s = sorts == null ? List.of() : sorts;
        validateColumns(f, s);
        Snapshot snapshot = store.latestSnapshot();
        List<GridRow> sorted = queryEngine.sortedRows(snapshot, f, s);
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        return new PageResult(List.copyOf(sorted.subList(from, to)),
                snapshot.commitVersion(), null, sorted.size());
    }

    /** Batch edit; atomic = all-or-nothing, otherwise per-row outcomes. */
    public BatchEditResult edit(List<RowEdit> edits, boolean atomic) {
        if (edits == null || edits.isEmpty()) {
            throw new GridException(ErrorReason.VALIDATION_FAILED,
                    "batch must contain at least one edit");
        }
        return store.applyBatch(edits, atomic);
    }

    /** Save a named view. */
    public void saveView(ViewDefinition view) {
        if (view.name() == null || view.name().isBlank()) {
            throw new GridException(ErrorReason.VALIDATION_FAILED,
                    "view name must not be blank");
        }
        views.put(view.name(), view);
    }

    /**
     * Load a named view. Parts incompatible with the current schema (removed
     * or retyped columns) are dropped and reported as degradations; the view
     * itself still opens.
     */
    public ViewResolution loadView(String name) {
        ViewDefinition view = views.get(name);
        if (view == null) {
            throw new GridException(ErrorReason.VIEW_NOT_FOUND,
                    "no view named " + name);
        }
        Map<String, ColumnDef> schema = schemaByName();
        List<String> degradations = new ArrayList<>();

        List<FilterSpec> filters = new ArrayList<>();
        for (FilterSpec filter : safe(view.filters())) {
            ColumnDef def = schema.get(filter.column());
            if (def == null) {
                degradations.add("filter on column '" + filter.column()
                        + "' dropped: column no longer exists");
            } else if (!valueMatchesType(filter.value(), def)) {
                degradations.add("filter on column '" + filter.column()
                        + "' dropped: value no longer matches column type " + def.type());
            } else {
                filters.add(filter);
            }
        }

        List<SortSpec> sorts = new ArrayList<>();
        for (SortSpec sort : safe(view.sorts())) {
            if (schema.containsKey(sort.column())) {
                sorts.add(sort);
            } else {
                degradations.add("sort on column '" + sort.column()
                        + "' dropped: column no longer exists");
            }
        }

        List<String> visible = new ArrayList<>();
        for (String column : safe(view.visibleColumns())) {
            if (schema.containsKey(column)) {
                visible.add(column);
            } else {
                degradations.add("visible column '" + column
                        + "' dropped: column no longer exists");
            }
        }

        int pageSize = view.pageSize();
        if (pageSize > properties.getMaxPageSize()) {
            degradations.add("page size " + pageSize + " clamped to maximum "
                    + properties.getMaxPageSize());
            pageSize = properties.getMaxPageSize();
        }

        ViewDefinition resolved = new ViewDefinition(view.name(),
                List.copyOf(filters), List.copyOf(sorts), pageSize, List.copyOf(visible));
        return new ViewResolution(resolved, List.copyOf(degradations));
    }

    // ---- internals ----

    private PageResult slice(CursorState state, Snapshot snapshot) {
        List<GridRow> sorted = queryEngine.sortedRows(snapshot, state.filters(),
                state.sorts());
        int from = Math.min(state.position(), sorted.size());
        int to = Math.min(from + state.pageSize(), sorted.size());
        List<GridRow> rows = List.copyOf(sorted.subList(from, to));
        String nextCursor = null;
        if (to < sorted.size()) {
            nextCursor = formatCursor(state.id());
            cursors.put(state.id(), new CursorState(state.id(), state.commitVersion(),
                    state.filters(), state.sorts(), state.pageSize(), to, now()));
        } else {
            // Cursor exhausted: release the pinned snapshot.
            invalidate(state.id());
        }
        return new PageResult(rows, snapshot.commitVersion(), nextCursor, sorted.size());
    }

    private void invalidate(String cursorId) {
        CursorState removed = cursors.remove(cursorId);
        if (removed != null) {
            store.releaseSnapshot(removed.commitVersion());
        }
    }

    private void evictExpiredCursors() {
        long cutoff = now() - properties.getCursorTtlMillis();
        for (CursorState state : cursors.values()) {
            if (state.lastAccessMillis() < cutoff) {
                invalidate(state.id());
            }
        }
    }

    private int resolvePageSize(Integer pageSize) {
        int size = pageSize == null ? properties.getDefaultPageSize() : pageSize;
        if (size <= 0) {
            throw new GridException(ErrorReason.VALIDATION_FAILED,
                    "page size must be positive");
        }
        return Math.min(size, properties.getMaxPageSize());
    }

    private void validateColumns(List<FilterSpec> filters, List<SortSpec> sorts) {
        Map<String, ColumnDef> schema = schemaByName();
        for (FilterSpec f : filters) {
            if (!schema.containsKey(f.column())) {
                throw new GridException(ErrorReason.UNKNOWN_COLUMN,
                        "unknown filter column: " + f.column());
            }
        }
        for (SortSpec s : sorts) {
            if (!schema.containsKey(s.column())) {
                throw new GridException(ErrorReason.UNKNOWN_COLUMN,
                        "unknown sort column: " + s.column());
            }
        }
    }

    private Map<String, ColumnDef> schemaByName() {
        Map<String, ColumnDef> byName = new ConcurrentHashMap<>();
        for (ColumnDef def : store.schema()) {
            byName.put(def.name(), def);
        }
        return byName;
    }

    private boolean valueMatchesType(Object value, ColumnDef def) {
        if (value == null) {
            return true;
        }
        return switch (def.type()) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case TIMESTAMP -> value instanceof Instant || value instanceof String;
        };
    }

    private String formatCursor(String id) {
        return "v1:" + id;
    }

    private String parseCursor(String cursor) {
        if (cursor == null || !cursor.startsWith("v1:") || cursor.length() <= 3) {
            throw new GridException(ErrorReason.CURSOR_INVALID,
                    "malformed cursor token");
        }
        return cursor.substring(3);
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
