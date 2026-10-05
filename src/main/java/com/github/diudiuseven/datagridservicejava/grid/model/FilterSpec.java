package com.github.diudiuseven.datagridservicejava.grid.model;

/** One filter predicate; multiple filters are ANDed. */
public record FilterSpec(String column, FilterOp op, Object value) {
}
