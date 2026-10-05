package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.github.diudiuseven.datagridservicejava.grid.GridProperties;
import com.github.diudiuseven.datagridservicejava.grid.cursor.CursorCodec;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnType;
import com.github.diudiuseven.datagridservicejava.grid.model.GridSchema;
import com.github.diudiuseven.datagridservicejava.grid.service.GridQueryService;
import com.github.diudiuseven.datagridservicejava.grid.service.GridWriteService;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Shared fixture: a store with a fixed schema and helper services. */
final class GridTestSupport {

    private GridTestSupport() {
    }

    static GridSchema schema() {
        return new GridSchema(1, List.of(
                new ColumnDef("name", ColumnType.STRING),
                new ColumnDef("age", ColumnType.LONG),
                new ColumnDef("score", ColumnType.DOUBLE),
                new ColumnDef("active", ColumnType.BOOLEAN)));
    }

    static MvccGridStore newStore() {
        return newStore(new GridProperties());
    }

    static MvccGridStore newStore(GridProperties properties) {
        MvccGridStore store = new MvccGridStore(properties);
        store.evolveSchema(schema());
        return store;
    }

    static GridQueryService queryService(MvccGridStore store, GridProperties properties) {
        return new GridQueryService(store, new CursorCodec(new ObjectMapper()), properties);
    }

    static GridWriteService writeService(MvccGridStore store) {
        return new GridWriteService(store);
    }

    static Map<String, Object> fields(String name, long age, double score, boolean active) {
        return Map.of("name", name, "age", age, "score", score, "active", active);
    }
}
