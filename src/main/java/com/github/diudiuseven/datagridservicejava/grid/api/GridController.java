package com.github.diudiuseven.datagridservicejava.grid.api;

import com.github.diudiuseven.datagridservicejava.grid.model.BatchEditResult;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.model.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.model.RowEdit;
import com.github.diudiuseven.datagridservicejava.grid.model.SortSpec;
import com.github.diudiuseven.datagridservicejava.grid.model.ViewDefinition;
import com.github.diudiuseven.datagridservicejava.grid.model.ViewResolution;
import com.github.diudiuseven.datagridservicejava.grid.service.GridService;
import com.github.diudiuseven.datagridservicejava.grid.store.TableStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST API for the data grid service. */
@RestController
@RequestMapping("/grid")
public class GridController {

    /** Query request body. */
    public record QueryRequest(List<FilterSpec> filters, List<SortSpec> sorts,
                               Integer pageSize) {
    }

    /** Offset query request body. */
    public record OffsetQueryRequest(List<FilterSpec> filters, List<SortSpec> sorts,
                                     int page, Integer pageSize) {
    }

    /** Cursor page request body. */
    public record CursorRequest(String cursor) {
    }

    /** Batch edit request body. */
    public record EditRequest(List<RowEdit> edits, boolean atomic) {
    }

    /** Row insert request body. */
    public record InsertRequest(Map<String, Object> values) {
    }

    /** Insert response body. */
    public record InsertResponse(String rowId) {
    }

    /** Schema update request body. */
    public record SchemaRequest(List<ColumnDef> columns) {
    }

    private final GridService service;
    private final TableStore store;

    public GridController(GridService service, TableStore store) {
        this.service = service;
        this.store = store;
    }

    @PostMapping("/query")
    public PageResult firstPage(@RequestBody QueryRequest request) {
        return service.firstPage(request.filters(), request.sorts(), request.pageSize());
    }

    @PostMapping("/query/page")
    public PageResult nextPage(@RequestBody CursorRequest request) {
        return service.nextPage(request.cursor());
    }

    @PostMapping("/query/offset")
    public PageResult offsetPage(@RequestBody OffsetQueryRequest request) {
        return service.offsetPage(request.filters(), request.sorts(), request.page(),
                request.pageSize());
    }

    @PostMapping("/edit")
    public BatchEditResult edit(@RequestBody EditRequest request) {
        return service.edit(request.edits(), request.atomic());
    }

    @PostMapping("/rows")
    public InsertResponse insert(@RequestBody InsertRequest request) {
        return new InsertResponse(store.insert(request.values()));
    }

    @DeleteMapping("/rows/{rowId}")
    public void delete(@PathVariable String rowId) {
        store.delete(rowId);
    }

    @GetMapping("/schema")
    public List<ColumnDef> schema() {
        return store.schema();
    }

    @PutMapping("/schema")
    public void updateSchema(@RequestBody SchemaRequest request) {
        store.updateSchema(request.columns());
    }

    @PutMapping("/views/{name}")
    public void saveView(@PathVariable String name, @RequestBody ViewDefinition view) {
        service.saveView(new ViewDefinition(name, view.filters(), view.sorts(),
                view.pageSize(), view.visibleColumns()));
    }

    @GetMapping("/views/{name}")
    public ViewResolution loadView(@PathVariable String name) {
        return service.loadView(name);
    }
}
