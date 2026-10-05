package com.github.diudiuseven.datagridservicejava.grid.store;

import com.github.diudiuseven.datagridservicejava.grid.model.GridRow;

import java.util.List;
import java.util.Optional;

/**
 * An immutable, fully-committed view of the table at one commit version.
 * Readers always read from a Snapshot, so they never observe partially
 * committed or field-level mixed state, and never block writers.
 */
public interface Snapshot {

    /** Commit version this snapshot represents. */
    long commitVersion();

    /** Look up one row by id. */
    Optional<GridRow> row(String id);

    /** All rows, unordered. Callers apply their own ordering. */
    List<GridRow> rows();
}
