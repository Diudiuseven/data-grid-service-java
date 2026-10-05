package com.github.diudiuseven.datagridservicejava.grid.error;

/** Machine-readable, distinguishable failure reasons. */
public enum ErrorReason {
    ROW_NOT_FOUND,
    VERSION_CONFLICT,
    CURSOR_INVALID,
    CURSOR_EXPIRED,
    CURSOR_QUERY_MISMATCH,
    UNKNOWN_COLUMN,
    TYPE_MISMATCH,
    VALIDATION_FAILED,
    VIEW_NOT_FOUND,
    VIEW_ALREADY_EXISTS,
    BATCH_NOT_COMMITTED
}
