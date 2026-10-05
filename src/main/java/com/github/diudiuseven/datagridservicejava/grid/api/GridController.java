package com.github.diudiuseven.datagridservicejava.grid.api;

import com.github.diudiuseven.datagridservicejava.grid.model.RowData;
import com.github.diudiuseven.datagridservicejava.grid.query.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.query.PageResult;
import com.github.diudiuseven.datagridservicejava.grid.query.QuerySpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.service.BatchMode;
import com.github.diudiuseven.datagridservicejava.grid.service.BatchResult;
import com.github.diudiuseven.datagridservicejava.grid.service.GridQueryService;
import com.github.diudiuseven.datagridservicejava.grid.service.GridWriteService;
import com.github.diudiuseven.datagridservicejava.grid.store.Mutation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST endpoints for querying and mutating grid rows. */
@RestController
@RequestMapping("/api/grid")
public class GridController {

    private final GridQueryService queryService;
    private final GridWriteService writeService;

    public GridController(GridQueryService queryService, GridWriteService writeService) {
        this.queryService = queryService;
        this.writeService = writeService;
    }

    public record QueryRequest(List<FilterSpec> filters,
                               List<SortKey> sorts,
                               Integer offset,
                               Integer limit,
                               String cursor) {
    }

    @PostMapping("/query")
    public PageResult query(@RequestBody QueryRequest request) {
        QuerySpec spec = new QuerySpec(request.filters(), request.sorts());
        int limit = request.limit() == null ? 0 : request.limit();
        if (request.cursor() != null && !request.cursor().isBlank()) {
            return queryService.queryByCursor(spec, request.cursor(), limit);
        }
        return queryService.queryPage(spec, request.offset() == null ? 0 : request.offset(), limit);
    }

    @PostMapping("/rows/{id}")
    public RowData create(@PathVariable String id, @RequestBody Map<String, Object> fields) {
        return writeService.create(id, fields);
    }

    @PutMapping("/rows/{id}")
    public RowData update(@PathVariable String id,
                          @RequestParam long version,
                          @RequestBody Map<String, Object> fields) {
        return writeService.update(id, version, fields);
    }

    @DeleteMapping("/rows/{id}")
    public void delete(@PathVariable String id, @RequestParam long version) {
        writeService.delete(id, version);
    }

    public record BatchRequest(BatchMode mode, List<Mutation> mutations) {
    }

    @PostMapping("/batch")
    public BatchResult batch(@RequestBody BatchRequest request) {
        BatchMode mode = request.mode() == null ? BatchMode.ATOMIC : request.mode();
        return writeService.applyBatch(request.mutations(), mode);
    }
}
