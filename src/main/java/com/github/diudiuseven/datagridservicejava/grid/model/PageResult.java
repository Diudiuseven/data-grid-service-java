package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.List;

/**
 * One page of query results.
 *
 * @param rows          page rows in deterministic order
 * @param commitVersion snapshot commit version this page was read from
 * @param nextCursor    opaque cursor for the next page, null when no more rows
 * @param totalRows     total rows matching the filter in the snapshot
 */
public record PageResult(List<GridRow> rows, long commitVersion, String nextCursor,
                         long totalRows) {
}
