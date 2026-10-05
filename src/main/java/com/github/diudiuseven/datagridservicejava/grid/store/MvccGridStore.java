package com.github.diudiuseven.datagridservicejava.grid.store;

import com.github.diudiuseven.datagridservicejava.grid.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.GridSchema;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.query.QueryEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MVCC store: every commit publishes a new immutable {@link Snapshot}.
 *
 * <p>Consistency semantics:
 * <ul>
 *   <li>Readers pin a snapshot by commit sequence and iterate it without
 *       any locking, so slow queries never block writers and never observe
 *       partially applied writes.</li>
 *   <li>Writers serialize on this monitor; each commit is validated as a
 *       whole against the latest snapshot, so a failed batch leaves no
 *       partial state.</li>
 *   <li>Row versions are bumped per committed write and checked on update
 *       and delete, giving optimistic concurrency control.</li>
 * </ul>
 */
@Component
public class MvccGridStore {

    private final AtomicReference<Snapshot> latest;
    private final ConcurrentHashMap<Long, Snapshot> retained = new ConcurrentHashMap<>();
    private final int retention;

    public MvccGridStore(GridProperties properties) {
        this.retention = Math.max(1, properties.getSnapshotRetention());
        GridSchema schema = new GridSchema(1, List.of());
        Snapshot initial = new Snapshot(0, schema, new TreeMap<>());
        this.latest = new AtomicReference<>(initial);
        this.retained.put(initial.commitSeq(), initial);
    }

    /** Latest committed snapshot; never null. */
    public Snapshot latest() {
        return latest.get();
    }

    /** Pin and return the snapshot for a commit sequence, if still retained. */
    public Optional<Snapshot> snapshotAt(long commitSeq) {
        return Optional.ofNullable(retained.get(commitSeq));
    }

    /**
     * Validate all mutations against the latest snapshot and apply them as
     * one atomic commit. Any validation failure aborts the whole commit and
     * nothing is applied.
     *
     * @return the newly published snapshot
     */
    public synchronized Snapshot commit(List<Mutation> mutations) {
        Snapshot base = latest.get();
        GridSchema schema = base.schema();
        TreeMap<String, RowData> next = new TreeMap<>(base.rows());
        for (Mutation m : mutations) {
            apply(m, next, schema);
        }
        return publish(new Snapshot(base.commitSeq() + 1, schema, next));
    }

    /**
     * Validate mutations against the latest snapshot without publishing
     * anything. Returns one entry per mutation: null when the mutation
     * would apply cleanly, otherwise the GridException it would raise.
     * Used to produce per-row diagnostics for rejected atomic batches.
     */
    public synchronized List<GridException> dryRun(List<Mutation> mutations) {
        Snapshot base = latest.get();
        TreeMap<String, RowData> scratch = new TreeMap<>(base.rows());
        List<GridException> errors = new ArrayList<>();
        for (Mutation m : mutations) {
            try {
                apply(m, scratch, base.schema());
                errors.add(null);
            } catch (GridException e) {
                errors.add(e);
            }
        }
        return errors;
    }

    /**
     * Replace the schema, committed as a new snapshot so readers on old
     * snapshots keep a consistent view. Existing values are coerced to the
     * new column types on a best-effort basis; unconvertible values are
     * dropped.
     */
    public synchronized Snapshot evolveSchema(GridSchema newSchema) {
        Snapshot base = latest.get();
        TreeMap<String, RowData> next = new TreeMap<>();
        for (RowData row : base.rows().values()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.fields().entrySet()) {
                Optional<ColumnDef> col = newSchema.column(e.getKey());
                if (col.isEmpty()) {
                    continue; // column removed: value dropped in new snapshots
                }
                try {
                    fields.put(e.getKey(), QueryEngine.coerce(e.getValue(), col.get().type()));
                } catch (GridException ex) {
                    // unconvertible after type change: drop the value
                }
            }
            next.put(row.id(), new RowData(row.id(), row.version(), fields));
        }
        return publish(new Snapshot(base.commitSeq() + 1, newSchema, next));
    }

    private Snapshot publish(Snapshot snapshot) {
        latest.set(snapshot);
        retained.put(snapshot.commitSeq(), snapshot);
        // Evict oldest snapshots beyond the retention window; the latest is always kept.
        List<Long> seqs = new ArrayList<>(retained.keySet());
        seqs.sort(Long::compareTo);
        for (int i = 0; i < seqs.size() - retention; i++) {
            retained.remove(seqs.get(i));
        }
        return snapshot;
    }

    private void apply(Mutation m, TreeMap<String, RowData> rows, GridSchema schema) {
        RowData existing = rows.get(m.rowId());
        switch (m.kind()) {
            case CREATE -> {
                if (existing != null) {
                    throw new GridException(ErrorReason.ROW_ALREADY_EXISTS,
                            "row already exists: " + m.rowId());
                }
                Map<String, Object> fields = validateFields(m.fields(), schema);
                rows.put(m.rowId(), new RowData(m.rowId(), 1L, fields));
            }
            case UPDATE -> {
                checkExistsAndVersion(m, existing);
                Map<String, Object> merged = new LinkedHashMap<>(existing.fields());
                merged.putAll(validateFields(m.fields(), schema));
                rows.put(m.rowId(), new RowData(m.rowId(), existing.version() + 1, merged));
            }
            case DELETE -> {
                checkExistsAndVersion(m, existing);
                rows.remove(m.rowId());
            }
        }
    }

    private void checkExistsAndVersion(Mutation m, RowData existing) {
        if (existing == null) {
            throw new GridException(ErrorReason.ROW_NOT_FOUND, "row not found: " + m.rowId());
        }
        if (m.expectedVersion() != Mutation.ANY_VERSION && m.expectedVersion() != existing.version()) {
            throw new GridException(ErrorReason.VERSION_CONFLICT,
                    "row " + m.rowId() + " expected version " + m.expectedVersion()
                            + " but current version is " + existing.version());
        }
    }

    private Map<String, Object> validateFields(Map<String, Object> fields, GridSchema schema) {
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            ColumnDef col = schema.column(e.getKey()).orElseThrow(() ->
                    new GridException(ErrorReason.INVALID_WRITE, "unknown column: " + e.getKey()));
            try {
                validated.put(e.getKey(), e.getValue() == null ? null
                        : QueryEngine.coerce(e.getValue(), col.type()));
            } catch (GridException ex) {
                throw new GridException(ErrorReason.INVALID_WRITE,
                        "column " + e.getKey() + ": " + ex.getMessage());
            }
        }
        return validated;
    }
}
