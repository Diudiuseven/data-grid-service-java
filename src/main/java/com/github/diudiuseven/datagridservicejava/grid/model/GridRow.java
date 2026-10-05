package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.Map;

/**
 * One row of grid data.
 *
 * @param id      stable unique row id, also the final tie-breaker for sorting
 * @param version optimistic-concurrency version, incremented on every write
 * @param values  column name -> value
 */
public record GridRow(String id, long version, Map<String, Object> values) {
}
