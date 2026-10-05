package com.github.diudiuseven.datagridservicejava.grid.store;

import java.util.Map;

/**
 * One row-level write intent. {@code expectedVersion} is the optimistic
 * concurrency token; use {@link #ANY_VERSION} to skip the check.
 */
public record Mutation(Kind kind, String rowId, long expectedVersion, Map<String, Object> fields) {

    public static final long ANY_VERSION = -1L;

    public enum Kind {
        CREATE,
        UPDATE,
        DELETE
    }

    public static Mutation create(String rowId, Map<String, Object> fields) {
        return new Mutation(Kind.CREATE, rowId, ANY_VERSION, fields);
    }

    public static Mutation update(String rowId, long expectedVersion, Map<String, Object> fields) {
        return new Mutation(Kind.UPDATE, rowId, expectedVersion, fields);
    }

    public static Mutation delete(String rowId, long expectedVersion) {
        return new Mutation(Kind.DELETE, rowId, expectedVersion, Map.of());
    }
}
