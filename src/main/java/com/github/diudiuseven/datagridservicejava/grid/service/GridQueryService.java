package com.github.diudiuseven.datagridservicejava.grid.service;

import com.github.diudiuseven.datagridservicejava.grid.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.cursor.CursorCodec;
import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.query.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.query.QueryEngine;
import com.github.diudiuseven.datagridservicejava.grid.query.QuerySpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import com.github.diudiuseven.datagridservicejava.grid.store.Snapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Read path. Every query runs against one pinned immutable {@link Snapshot},
 * so any interleaving of pages and concurrent writes returns a
 * self-consistent order and content; readers never block writers.
 */
@Service
public class GridQueryService {

    private final MvccGridStore store;
    private final CursorCodec cursorCodec;
    private final GridProperties properties;

    public GridQueryService(MvccGridStore store, CursorCodec cursorCodec, GridProperties properties) {
        this.store = store;
        this.cursorCodec = cursorCodec;
        this.properties = properties;
    }

    /** Offset-based page against the latest snapshot. */
    public PageResult queryPage(QuerySpec spec, int offset, int limit) {
        Snapshot snapshot = store.latest();
        List<RowData> sorted = sortedMatching(spec, snapshot);
        int from = Math.min(Math.max(0, offset), sorted.size());
        int to = Math.min(from + clampLimit(limit), sorted.size());
        List<RowData> page = sorted.subList(from, to);
        String nextCursor = to < sorted.size() && !page.isEmpty()
                ? cursorFor(snapshot, spec, page.get(page.size() - 1))
                : null;
        return new PageResult(page, nextCursor, snapshot.commitSeq(), sorted.size());
    }

    /**
     * Cursor-based page. A null cursor starts a new scan on the latest
     * snapshot; a non-null cursor resumes the snapshot it pinned, so rows
     * inserted or deleted by concurrent writers after the scan started can
     * neither duplicate nor skip rows within the scan.
     *
     * @throws GridException CURSOR_MALFORMED / CURSOR_QUERY_MISMATCH / CURSOR_EXPIRED
     */
    public PageResult queryByCursor(QuerySpec spec, String cursor, int limit) {
        Snapshot snapshot;
        CursorCodec.Cursor position = null;
        if (cursor == null || cursor.isBlank()) {
            snapshot = store.latest();
        } else {
            position = cursorCodec.decode(cursor);
            if (!position.queryFingerprint().equals(spec.fingerprint())) {
                throw new GridException(ErrorReason.CURSOR_QUERY_MISMATCH,
                        "cursor was issued for a different filter/sort query");
            }
            CursorCodec.Cursor pos = position;
            snapshot = store.snapshotAt(pos.commitSeq()).orElseThrow(() ->
                    new GridException(ErrorReason.CURSOR_EXPIRED,
                            "snapshot " + pos.commitSeq() + " is no longer retained"));
        }

        List<RowData> sorted = sortedMatching(spec, snapshot);
        List<RowData> page = new ArrayList<>();
        int max = clampLimit(limit);
        for (RowData row : sorted) {
            if (position != null && QueryEngine.compareToCursor(row, spec.sorts(),
                    position.lastSortValues(), position.lastRowId()) <= 0) {
                continue; // already delivered by an earlier page of this scan
            }
            page.add(row);
            if (page.size() >= max) {
                break;
            }
        }
        boolean exhausted = page.isEmpty()
                || sorted.size() - indexOf(sorted, page.get(page.size() - 1)) - 1 <= 0;
        String nextCursor = exhausted ? null : cursorFor(snapshot, spec, page.get(page.size() - 1));
        return new PageResult(page, nextCursor, snapshot.commitSeq(), sorted.size());
    }

    private int indexOf(List<RowData> sorted, RowData row) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).id().equals(row.id())) {
                return i;
            }
        }
        return sorted.size() - 1;
    }

    private List<RowData> sortedMatching(QuerySpec spec, Snapshot snapshot) {
        List<FilterSpec> filters = QueryEngine.validateAndCoerce(spec, snapshot.schema());
        List<RowData> matching = new ArrayList<>();
        for (RowData row : snapshot.rows().values()) {
            if (QueryEngine.matches(row, filters)) {
                matching.add(row);
            }
        }
        matching.sort(QueryEngine.comparator(spec.sorts()));
        return matching;
    }

    private String cursorFor(Snapshot snapshot, QuerySpec spec, RowData lastRow) {
        List<Object> sortValues = new ArrayList<>();
        for (SortKey s : spec.sorts()) {
            sortValues.add(lastRow.fields().get(s.column()));
        }
        return cursorCodec.encode(new CursorCodec.Cursor(
                snapshot.commitSeq(), spec.fingerprint(), sortValues, lastRow.id()));
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return properties.getDefaultPageSize();
        }
        return Math.min(limit, properties.getMaxPageSize());
    }
}
