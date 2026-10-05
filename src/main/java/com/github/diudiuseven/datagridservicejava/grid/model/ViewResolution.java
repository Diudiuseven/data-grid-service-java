package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.List;

/**
 * A saved view resolved against the current schema. Parts of the view that can
 * no longer be applied (removed columns, changed types) are dropped and
 * reported as degradations instead of failing the whole view.
 *
 * @param view         the usable remainder of the saved view
 * @param degradations human-readable explanations of dropped parts
 */
public record ViewResolution(ViewDefinition view, List<String> degradations) {
}
