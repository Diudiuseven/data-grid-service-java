package com.github.diudiuseven.datagridservicejava.grid.view;

import java.util.List;

/** A saved view reconciled against the current schema, plus degradation hints. */
public record ResolvedView(ViewDefinition view, List<Degradation> degradations) {

    public ResolvedView {
        degradations = List.copyOf(degradations);
    }
}
