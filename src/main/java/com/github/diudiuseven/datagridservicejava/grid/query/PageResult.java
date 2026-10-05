package com.github.diudiuseven.datagridservicejava.grid.query;

import com.github.diudiuseven.datagridservicejava.grid.model.RowData;

import java.util.List;

/**
 * One page of rows. {@code nextCursor} is null when no further rows exist
 * within the pinned snapshot.
 */
public record PageResult(List<RowData> rows,
                         String nextCursor,
                         long snapshotCommitSeq,
                         long totalMatching) {

    public PageResult {
        rows = List.copyOf(rows);
    }
}
