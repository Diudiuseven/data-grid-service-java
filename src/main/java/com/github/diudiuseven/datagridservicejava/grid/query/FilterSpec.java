package com.github.diudiuseven.datagridservicejava.grid.query;

import java.util.List;

/**
 * One filter predicate on a column. {@code value} is used by all ops except
 * {@link FilterOp#IN}, which uses {@code values}.
 */
public record FilterSpec(String column, FilterOp op, Object value, List<Object> values) {

    public FilterSpec(String column, FilterOp op, Object value) {
        this(column, op, value, null);
    }
}
