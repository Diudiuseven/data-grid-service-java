package com.github.diudiuseven.datagridservicejava.grid.query;

import java.util.List;

/**
 * Filter + sort definition of a grid query. The fingerprint identifies the
 * query so cursors cannot be replayed against a different query.
 */
public record QuerySpec(List<FilterSpec> filters, List<SortKey> sorts) {

    public QuerySpec {
        filters = filters == null ? List.of() : List.copyOf(filters);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
    }

    public static QuerySpec empty() {
        return new QuerySpec(List.of(), List.of());
    }

    /**
     * Stable fingerprint of filters + sorts. Cursors embed it so a cursor
     * issued for one query cannot be silently replayed against another.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder("f:");
        for (FilterSpec f : filters) {
            sb.append(f.column()).append('|').append(f.op()).append('|')
                    .append(String.valueOf(f.value())).append('|')
                    .append(f.values() == null ? "" : f.values()).append(';');
        }
        sb.append("s:");
        for (SortKey s : sorts) {
            sb.append(s.column()).append('|').append(s.direction()).append(';');
        }
        return sb.toString();
    }
}
