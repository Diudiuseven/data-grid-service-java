package com.github.diudiuseven.datagridservicejava.grid.model;

/**
 * One sort key. The position of a SortSpec in the request list is its priority
 * (earlier = higher priority). Ties are always broken by row id ascending so the
 * total order is deterministic.
 */
public record SortSpec(String column, SortDirection direction) {
}
