package com.github.diudiuseven.datagridservicejava.grid.view;

import com.github.diudiuseven.datagridservicejava.grid.model.ColumnType;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;

import java.util.List;
import java.util.Map;

/**
 * A named, saved grid view: filters, sort order, page size and visible
 * columns. {@code columnTypes} captures the schema column types at save
 * time so later type changes can be detected and degraded gracefully.
 */
public record ViewDefinition(String name,
                             List<FilterSpec> filters,
                             List<SortKey> sorts,
                             int pageSize,
                             List<String> visibleColumns,
                             long schemaVersion,
                             Map<String, ColumnType> columnTypes) {

    public ViewDefinition {
        filters = filters == null ? List.of() : List.copyOf(filters);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
        visibleColumns = visibleColumns == null ? List.of() : List.copyOf(visibleColumns);
        columnTypes = columnTypes == null ? Map.of() : Map.copyOf(columnTypes);
    }
}
