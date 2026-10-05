package com.github.diudiuseven.datagridservicejava.grid.service;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.model.RowData;

/** Outcome of one row mutation inside a batch. */
public record RowResult(String rowId,
                        boolean success,
                        RowData row,
                        ErrorReason errorReason,
                        String errorMessage) {

    public static RowResult ok(String rowId, RowData row) {
        return new RowResult(rowId, true, row, null, null);
    }

    public static RowResult failed(String rowId, ErrorReason reason, String message) {
        return new RowResult(rowId, false, null, reason, message);
    }
}
