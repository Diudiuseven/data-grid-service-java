package com.github.diudiuseven.datagridservicejava.grid.service;

public enum BatchMode {
    /** All mutations validated and applied as one commit; any failure rejects the whole batch. */
    ATOMIC,
    /** Each mutation applied independently; per-row results report success or the failure reason. */
    PER_ROW
}
