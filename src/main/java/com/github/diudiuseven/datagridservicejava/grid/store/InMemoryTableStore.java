package com.github.diudiuseven.datagridservicejava.grid.store;

import com.github.diudiuseven.datagridservicejava.grid.config.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.BatchEditResult;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.GridRow;
import com.github.diudiuseven.datagridservicejava.grid.model.RowEdit;
import com.github.diudiuseven.datagridservicejava.grid.model.RowEditOutcome;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory MVCC store. Writers serialize on a lock and publish a new
 * immutable snapshot per commit; readers only touch the volatile head or a
 * pinned snapshot, so reads never block writes and never observe mixed state.
 */
@Component
public class InMemoryTableStore implements TableStore {

    /** Immutable committed state; one instance per commit version. */
    private record Committed(long version, Map<String, GridRow> rowMap) implements Snapshot {
        private Committed {
            rowMap = Map.copyOf(rowMap);
        }

        @Override
        public long commitVersion() {
            return version;
        }

        @Override
        public Optional<GridRow> row(String id) {
            return Optional.ofNullable(rowMap.get(id));
        }

        @Override
        public List<GridRow> rows() {
            return List.copyOf(rowMap.values());
        }
    }

    private final GridProperties properties;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong idSequence = new AtomicLong();

    private volatile List<ColumnDef> schema = List.of();
    private volatile Committed head = new Committed(0, Map.of());

    /** commit version -> snapshot, insertion ordered, bounded by retention. */
    private final Map<Long, Committed> retained = new LinkedHashMap<>();
    /** commit version -> active pin count; pinned snapshots are never evicted. */
    private final Map<Long, Integer> pins = new HashMap<>();

    public InMemoryTableStore(GridProperties properties) {
        this.properties = properties;
        retained.put(0L, head);
    }

    @Override
    public List<ColumnDef> schema() {
        return schema;
    }

    @Override
    public void updateSchema(List<ColumnDef> newSchema) {
        writeLock.lock();
        try {
            schema = List.copyOf(newSchema);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Snapshot latestSnapshot() {
        return head;
    }

    @Override
    public Snapshot pinSnapshot(long commitVersion) {
        writeLock.lock();
        try {
            Committed snapshot = retained.get(commitVersion);
            if (snapshot == null) {
                throw new GridException(ErrorReason.CURSOR_EXPIRED,
                        "snapshot at commit version " + commitVersion
                                + " is no longer retained");
            }
            pins.merge(commitVersion, 1, Integer::sum);
            return snapshot;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void releaseSnapshot(long commitVersion) {
        writeLock.lock();
        try {
            pins.computeIfPresent(commitVersion, (v, n) -> n <= 1 ? null : n - 1);
            evictIfNeeded();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public String insert(Map<String, Object> values) {
        writeLock.lock();
        try {
            String id = "r-" + idSequence.incrementAndGet();
            validateValues(values);
            Map<String, GridRow> next = new HashMap<>(head.rowMap());
            next.put(id, new GridRow(id, 1, Map.copyOf(values)));
            commit(next);
            return id;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void delete(String rowId) {
        writeLock.lock();
        try {
            if (!head.rowMap().containsKey(rowId)) {
                throw new GridException(ErrorReason.ROW_NOT_FOUND,
                        "row " + rowId + " does not exist");
            }
            Map<String, GridRow> next = new HashMap<>(head.rowMap());
            next.remove(rowId);
            commit(next);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public BatchEditResult applyBatch(List<RowEdit> edits, boolean atomic) {
        writeLock.lock();
        try {
            Map<String, GridRow> working = new HashMap<>(head.rowMap());
            List<RowEditOutcome> outcomes = new ArrayList<>(edits.size());
            boolean anyFailure = false;
            for (RowEdit edit : edits) {
                GridRow current = working.get(edit.rowId());
                if (current == null) {
                    outcomes.add(failure(edit, ErrorReason.ROW_NOT_FOUND,
                            "row " + edit.rowId() + " does not exist"));
                    anyFailure = true;
                    continue;
                }
                if (current.version() != edit.expectedVersion()) {
                    outcomes.add(failure(edit, ErrorReason.VERSION_CONFLICT,
                            "row " + edit.rowId() + " is at version " + current.version()
                                    + ", caller expected " + edit.expectedVersion()));
                    anyFailure = true;
                    continue;
                }
                try {
                    validateValues(edit.values());
                } catch (GridException e) {
                    outcomes.add(failure(edit, e.reason(), e.getMessage()));
                    anyFailure = true;
                    continue;
                }
                if (atomic) {
                    // Validate everything first; apply nothing until all pass.
                    outcomes.add(null);
                    continue;
                }
                GridRow updated = apply(current, edit);
                working.put(edit.rowId(), updated);
                outcomes.add(new RowEditOutcome(edit.rowId(), true,
                        updated.version(), null, null));
            }
            if (atomic && anyFailure) {
                // All-or-nothing: nothing committed, no partial writes.
                List<RowEditOutcome> full = new ArrayList<>(edits.size());
                for (int i = 0; i < edits.size(); i++) {
                    RowEditOutcome o = outcomes.get(i);
                    full.add(o != null ? o : new RowEditOutcome(edits.get(i).rowId(),
                            false, -1, ErrorReason.BATCH_NOT_COMMITTED.name(),
                            "batch not committed because another row failed"));
                }
                return new BatchEditResult(true, false, head.version(), full);
            }
            if (atomic) {
                List<RowEditOutcome> full = new ArrayList<>(edits.size());
                for (int i = 0; i < edits.size(); i++) {
                    GridRow updated = apply(working.get(edits.get(i).rowId()), edits.get(i));
                    working.put(edits.get(i).rowId(), updated);
                    full.add(new RowEditOutcome(edits.get(i).rowId(), true,
                            updated.version(), null, null));
                }
                outcomes = full;
            }
            commit(working);
            return new BatchEditResult(atomic, true, head.version(), outcomes);
        } finally {
            writeLock.unlock();
        }
    }

    private GridRow apply(GridRow current, RowEdit edit) {
        Map<String, Object> merged = new HashMap<>(current.values());
        merged.putAll(edit.values());
        return new GridRow(current.id(), current.version() + 1, Map.copyOf(merged));
    }

    private RowEditOutcome failure(RowEdit edit, ErrorReason reason, String message) {
        return new RowEditOutcome(edit.rowId(), false, -1, reason.name(), message);
    }

    /** Publish a new immutable snapshot; only called under the write lock. */
    private void commit(Map<String, GridRow> rows) {
        Committed next = new Committed(head.version() + 1, rows);
        head = next;
        retained.put(next.version(), next);
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        var it = retained.entrySet().iterator();
        while (retained.size() > properties.getRetainedSnapshots() && it.hasNext()) {
            Map.Entry<Long, Committed> oldest = it.next();
            if (pins.getOrDefault(oldest.getKey(), 0) > 0) {
                continue;
            }
            it.remove();
        }
    }

    private void validateValues(Map<String, Object> values) {
        Map<String, ColumnDef> columns = new HashMap<>();
        for (ColumnDef def : schema) {
            columns.put(def.name(), def);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ColumnDef def = columns.get(entry.getKey());
            if (def == null) {
                throw new GridException(ErrorReason.UNKNOWN_COLUMN,
                        "unknown column: " + entry.getKey());
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            boolean ok = switch (def.type()) {
                case STRING -> value instanceof String;
                case NUMBER -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
                case TIMESTAMP -> value instanceof Instant || value instanceof String;
            };
            if (!ok) {
                throw new GridException(ErrorReason.TYPE_MISMATCH,
                        "column " + def.name() + " expects " + def.type()
                                + " but got " + value.getClass().getSimpleName());
            }
        }
    }
}
