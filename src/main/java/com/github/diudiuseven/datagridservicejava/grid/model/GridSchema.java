package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable column layout of the grid. Every change produces a new instance
 * with an incremented {@link #version()}; snapshots reference the schema
 * version they were committed under.
 */
public final class GridSchema {

    private final long version;
    private final Map<String, ColumnDef> columns;

    public GridSchema(long version, List<ColumnDef> columns) {
        this.version = version;
        Map<String, ColumnDef> map = new LinkedHashMap<>();
        for (ColumnDef c : columns) {
            map.put(c.name(), c);
        }
        this.columns = Collections.unmodifiableMap(map);
    }

    @JsonProperty("version")
    public long version() {
        return version;
    }

    @JsonProperty("columns")
    public List<ColumnDef> columns() {
        return new ArrayList<>(columns.values());
    }

    public Optional<ColumnDef> column(String name) {
        return Optional.ofNullable(columns.get(name));
    }

    public boolean hasColumn(String name) {
        return columns.containsKey(name);
    }

    public GridSchema withColumns(List<ColumnDef> newColumns) {
        return new GridSchema(version + 1, newColumns);
    }
}
