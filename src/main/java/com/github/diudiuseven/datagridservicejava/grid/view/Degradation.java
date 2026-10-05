package com.github.diudiuseven.datagridservicejava.grid.view;

/** One part of a saved view that can no longer be applied as-is. */
public record Degradation(Kind kind, String detail) {

    public enum Kind {
        /** Column referenced by the view no longer exists; the affected part was dropped. */
        COLUMN_REMOVED,
        /** Column type changed; the affected filter/sort was dropped to avoid wrong semantics. */
        COLUMN_TYPE_CHANGED
    }
}
