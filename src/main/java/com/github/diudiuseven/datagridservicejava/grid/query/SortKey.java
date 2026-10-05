package com.github.diudiuseven.datagridservicejava.grid.query;

/** One sort criterion; the row id is always appended as the final tiebreaker. */
public record SortKey(String column, SortDirection direction) {
}
