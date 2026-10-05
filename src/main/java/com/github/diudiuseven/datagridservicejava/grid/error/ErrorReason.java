package com.github.diudiuseven.datagridservicejava.grid.error;

/**
 * Machine-distinguishable failure reasons. Every rejected read or write
 * carries exactly one of these so callers can react programmatically.
 */
public enum ErrorReason {
    /** Row exists but its current version differs from the caller's expected version. */
    VERSION_CONFLICT,
    /** Referenced row id does not exist. */
    ROW_NOT_FOUND,
    /** Row with the same id already exists. */
    ROW_ALREADY_EXISTS,
    /** Cursor text could not be decoded at all. */
    CURSOR_MALFORMED,
    /** Cursor is well-formed but its snapshot has been garbage-collected. */
    CURSOR_EXPIRED,
    /** Cursor was issued for a different filter/sort query than the current one. */
    CURSOR_QUERY_MISMATCH,
    /** Unknown column, bad operator for the column type, or unparseable value. */
    INVALID_QUERY,
    /** Unknown column in a write payload or value not convertible to the column type. */
    INVALID_WRITE,
    /** Referenced named view does not exist. */
    VIEW_NOT_FOUND,
    /** A view with the same name already exists. */
    VIEW_ALREADY_EXISTS,
    /** Batch contained a mix of failures; nothing was applied (atomic mode). */
    BATCH_REJECTED
}
