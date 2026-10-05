package com.github.diudiuseven.datagridservicejava.grid.view;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnType;
import com.github.diudiuseven.datagridservicejava.grid.model.GridSchema;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores named views and reconciles them against schema evolution. A view
 * saved under an older schema always remains openable: parts that reference
 * removed or re-typed columns are dropped and reported as degradations.
 */
@Service
public class ViewService {

    private final MvccGridStore store;
    private final Map<String, ViewDefinition> views = new ConcurrentHashMap<>();

    public ViewService(MvccGridStore store) {
        this.store = store;
    }

    /** Save a new view, capturing the current schema version and column types. */
    public ViewDefinition save(ViewDefinition view) {
        GridSchema schema = store.latest().schema();
        Map<String, ColumnType> types = new HashMap<>();
        for (ColumnDef c : schema.columns()) {
            types.put(c.name(), c.type());
        }
        ViewDefinition captured = new ViewDefinition(view.name(), view.filters(), view.sorts(),
                view.pageSize(), view.visibleColumns(), schema.version(), types);
        if (views.putIfAbsent(view.name(), captured) != null) {
            throw new GridException(ErrorReason.VIEW_ALREADY_EXISTS, "view exists: " + view.name());
        }
        return captured;
    }

    public ViewDefinition get(String name) {
        ViewDefinition view = views.get(name);
        if (view == null) {
            throw new GridException(ErrorReason.VIEW_NOT_FOUND, "view not found: " + name);
        }
        return view;
    }

    public List<ViewDefinition> list() {
        return List.copyOf(views.values());
    }

    public void delete(String name) {
        if (views.remove(name) == null) {
            throw new GridException(ErrorReason.VIEW_NOT_FOUND, "view not found: " + name);
        }
    }

    /**
     * Resolve a saved view against the current schema. Filters and sorts on
     * removed or re-typed columns are dropped with a degradation hint;
     * visible columns that disappeared are dropped the same way.
     */
    public ResolvedView resolve(String name) {
        ViewDefinition view = get(name);
        GridSchema schema = store.latest().schema();
        List<Degradation> degradations = new ArrayList<>();

        List<FilterSpec> filters = new ArrayList<>();
        for (FilterSpec f : view.filters()) {
            if (keep(view, schema, f.column(), "filter", degradations)) {
                filters.add(f);
            }
        }
        List<SortKey> sorts = new ArrayList<>();
        for (SortKey s : view.sorts()) {
            if (keep(view, schema, s.column(), "sort", degradations)) {
                sorts.add(s);
            }
        }
        List<String> visible = new ArrayList<>();
        for (String col : view.visibleColumns()) {
            if (!schema.hasColumn(col)) {
                degradations.add(new Degradation(Degradation.Kind.COLUMN_REMOVED,
                        "visible column '" + col + "' no longer exists; hidden"));
            } else {
                visible.add(col);
            }
        }
        ViewDefinition resolved = new ViewDefinition(view.name(), filters, sorts, view.pageSize(),
                visible, schema.version(), view.columnTypes());
        return new ResolvedView(resolved, degradations);
    }

    private boolean keep(ViewDefinition view, GridSchema schema, String column, String part,
                         List<Degradation> degradations) {
        ColumnDef current = schema.column(column).orElse(null);
        if (current == null) {
            degradations.add(new Degradation(Degradation.Kind.COLUMN_REMOVED,
                    part + " on column '" + column + "' dropped: column no longer exists"));
            return false;
        }
        ColumnType saved = view.columnTypes().get(column);
        if (saved != null && saved != current.type()) {
            degradations.add(new Degradation(Degradation.Kind.COLUMN_TYPE_CHANGED,
                    part + " on column '" + column + "' dropped: type changed from "
                            + saved + " to " + current.type()));
            return false;
        }
        return true;
    }
}
