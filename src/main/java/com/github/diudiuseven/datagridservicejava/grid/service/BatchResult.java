package com.github.diudiuseven.datagridservicejava.grid.service;

import java.util.List;

/** Outcome of a batch write. In ATOMIC mode either all rows succeed or none is applied. */
public record BatchResult(boolean applied, List<RowResult> results) {

    public BatchResult {
        results = List.copyOf(results);
    }
}
