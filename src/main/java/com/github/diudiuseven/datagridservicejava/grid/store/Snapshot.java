package com.github.diudiuseven.datagridservicejava.grid.store;

import com.github.diudiuseven.datagridservicejava.grid.model.GridSchema;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;

import java.util.NavigableMap;
import java.util.Optional;

/**
 * One immutable, fully committed state of the grid. Readers always operate
 * on a Snapshot, so a read can never observe a partially applied write.
 */
public final class Snapshot {

    private final long commitSeq;
    private final GridSchema schema;
    private final NavigableMap<String, RowData> rows;

    public Snapshot(long commitSeq, GridSchema schema, NavigableMap<String, RowData> rows) {
        this.commitSeq = commitSeq;
        this.schema = schema;
        this.rows = rows;
    }

    public long commitSeq() {
        return commitSeq;
    }

    public GridSchema schema() {
        return schema;
    }

    public NavigableMap<String, RowData> rows() {
        return rows;
    }

    public Optional<RowData> row(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    public int size() {
        return rows.size();
    }
}
