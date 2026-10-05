package com.github.diudiuseven.datagridservicejava.grid.service;

import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.store.Mutation;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import com.github.diudiuseven.datagridservicejava.grid.store.Snapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Write path: single-row optimistic writes and atomic / per-row batches.
 * Every commit is one MVCC snapshot, so readers never observe a partially
 * applied batch.
 */
@Service
public class GridWriteService {

    private final MvccGridStore store;

    public GridWriteService(MvccGridStore store) {
        this.store = store;
    }

    public RowData create(String rowId, Map<String, Object> fields) {
        Snapshot committed = store.commit(List.of(Mutation.create(rowId, fields)));
        return committed.rows().get(rowId);
    }

    /** @throws GridException VERSION_CONFLICT when expectedVersion is stale */
    public RowData update(String rowId, long expectedVersion, Map<String, Object> fields) {
        Snapshot committed = store.commit(List.of(Mutation.update(rowId, expectedVersion, fields)));
        return committed.rows().get(rowId);
    }

    /** @throws GridException VERSION_CONFLICT when expectedVersion is stale */
    public void delete(String rowId, long expectedVersion) {
        store.commit(List.of(Mutation.delete(rowId, expectedVersion)));
    }

    /**
     * Apply a batch. In ATOMIC mode the whole batch is one commit: any
     * failure rejects everything and the result carries per-row diagnostics.
     * In PER_ROW mode each mutation is its own atomic commit and the result
     * reports success or the failure reason per row.
     */
    public BatchResult applyBatch(List<Mutation> mutations, BatchMode mode) {
        return switch (mode) {
            case ATOMIC -> applyAtomic(mutations);
            case PER_ROW -> applyPerRow(mutations);
        };
    }

    private BatchResult applyAtomic(List<Mutation> mutations) {
        try {
            Snapshot committed = store.commit(mutations);
            List<RowResult> results = new ArrayList<>();
            for (Mutation m : mutations) {
                RowData row = committed.rows().get(m.rowId());
                results.add(RowResult.ok(m.rowId(), row));
            }
            return new BatchResult(true, results);
        } catch (GridException commitFailure) {
            // Nothing was applied. Re-validate each row to explain why.
            List<GridException> errors = store.dryRun(mutations);
            List<RowResult> results = new ArrayList<>();
            for (int i = 0; i < mutations.size(); i++) {
                GridException error = errors.get(i);
                results.add(error == null
                        ? RowResult.ok(mutations.get(i).rowId(), null)
                        : RowResult.failed(mutations.get(i).rowId(), error.reason(), error.getMessage()));
            }
            return new BatchResult(false, results);
        }
    }

    private BatchResult applyPerRow(List<Mutation> mutations) {
        List<RowResult> results = new ArrayList<>();
        boolean anyApplied = false;
        for (Mutation m : mutations) {
            try {
                Snapshot committed = store.commit(List.of(m));
                results.add(RowResult.ok(m.rowId(), committed.rows().get(m.rowId())));
                anyApplied = true;
            } catch (GridException e) {
                results.add(RowResult.failed(m.rowId(), e.reason(), e.getMessage()));
            }
        }
        return new BatchResult(anyApplied, results);
    }
}
