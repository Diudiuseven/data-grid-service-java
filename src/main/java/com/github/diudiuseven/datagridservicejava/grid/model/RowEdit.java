package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.Map;

/**
 * One optimistic-concurrency guarded row write.
 *
 * @param rowId           target row id
 * @param expectedVersion version the caller last read; write is rejected if it
 *                        does not match the committed version
 * @param values          column values to set (partial update)
 */
public record RowEdit(String rowId, long expectedVersion, Map<String, Object> values) {
}
