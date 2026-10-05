package com.github.diudiuseven.datagridservicejava.grid.model;

/**
 * Per-row result of a batch edit executed in per-row (non-atomic) mode.
 *
 * @param rowId      the row this outcome belongs to
 * @param success    whether this row's edit was applied
 * @param newVersion committed version after the edit, or -1 when not applied
 * @param reason     machine-readable failure reason, null on success
 * @param message    human-readable explanation, null on success
 */
public record RowEditOutcome(String rowId, boolean success, long newVersion,
                             String reason, String message) {
}
