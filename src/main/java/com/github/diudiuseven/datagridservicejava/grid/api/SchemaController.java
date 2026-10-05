package com.github.diudiuseven.datagridservicejava.grid.api;

import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.GridSchema;
import com.github.diudiuseven.datagridservicejava.grid.store.MvccGridStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST endpoints for inspecting and evolving the grid schema. */
@RestController
@RequestMapping("/api/grid/schema")
public class SchemaController {

    private final MvccGridStore store;

    public SchemaController(MvccGridStore store) {
        this.store = store;
    }

    public record SchemaRequest(List<ColumnDef> columns) {
    }

    @GetMapping
    public GridSchema current() {
        return store.latest().schema();
    }

    /** Evolve the schema; committed as a new snapshot so in-flight reads stay consistent. */
    @PutMapping
    public GridSchema evolve(@RequestBody SchemaRequest request) {
        GridSchema current = store.latest().schema();
        GridSchema evolved = current.withColumns(request.columns());
        return store.evolveSchema(evolved).schema();
    }
}
