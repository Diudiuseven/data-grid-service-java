package com.github.diudiuseven.datagridservicejava.grid.store;

import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.BatchEditResult;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.RowEdit;

import java.util.List;
import java.util.Map;

/**
 * MVCC-style table store. Every committed batch produces a new immutable
 * snapshot; readers pin snapshots so concurrent writes never affect an
 * in-flight read and never block behind writers.
 */
public interface TableStore {

    /** Current committed schema, in column order. */
    List<ColumnDef> schema();

    /** Replace the schema (add/remove/retype columns). */
    void updateSchema(List<ColumnDef> newSchema);

    /** Latest committed snapshot. Never blocks on writers. */
    Snapshot latestSnapshot();

    /**
     * Pin the snapshot at the given commit version so it cannot be evicted
     * while a cursor is paging through it. Must be balanced by
     * {@link #releaseSnapshot(long)}.
     *
     * @throws GridException CURSOR_EXPIRED if the version is no longer retained
     */
    Snapshot pinSnapshot(long commitVersion);

    /** Release a pin previously acquired via {@link #pinSnapshot(long)}. */
    void releaseSnapshot(long commitVersion);

    /** Insert a new row; returns the assigned row id. */
    String insert(Map<String, Object> values);

    /** Delete a row by id. */
    void delete(String rowId);

    /**
     * Apply a batch of optimistic-concurrency guarded edits.
     *
     * @param edits  edits in caller order
     * @param atomic true: all-or-nothing; false: apply each row independently
     *               and report per-row outcomes
     */
    BatchEditResult applyBatch(List<RowEdit> edits, boolean atomic);
}
