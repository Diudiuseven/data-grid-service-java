package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.List;

/**
 * A named, saved grid view: filters, sorts, page size and visible columns.
 */
public record ViewDefinition(String name,
                             List<FilterSpec> filters,
                             List<SortSpec> sorts,
                             int pageSize,
                             List<String> visibleColumns) {
}
