package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.Map;

/**
 * One immutable row. {@code version} is the optimistic-concurrency token:
 * it is incremented on every committed write to this row.
 */
public record RowData(String id, long version, Map<String, Object> fields) {

    public RowData {
        fields = Map.copyOf(fields);
    }

    public RowData withVersion(long newVersion) {
        return new RowData(id, newVersion, fields);
    }
}
