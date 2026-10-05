package com.github.diudiuseven.datagridservicejava.grid.api;

import com.github.diudiuseven.datagridservicejava.grid.query.FilterSpec;
import com.github.diudiuseven.datagridservicejava.grid.query.SortKey;
import com.github.diudiuseven.datagridservicejava.grid.view.ResolvedView;
import com.github.diudiuseven.datagridservicejava.grid.view.ViewDefinition;
import com.github.diudiuseven.datagridservicejava.grid.view.ViewService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST endpoints for named saved views. */
@RestController
@RequestMapping("/api/grid/views")
public class ViewController {

    private final ViewService viewService;

    public ViewController(ViewService viewService) {
        this.viewService = viewService;
    }

    public record ViewRequest(List<FilterSpec> filters,
                              List<SortKey> sorts,
                              Integer pageSize,
                              List<String> visibleColumns) {
    }

    @PutMapping("/{name}")
    public ViewDefinition save(@PathVariable String name, @RequestBody ViewRequest request) {
        return viewService.save(new ViewDefinition(name, request.filters(), request.sorts(),
                request.pageSize() == null ? 0 : request.pageSize(), request.visibleColumns(),
                0, null));
    }

    @GetMapping("/{name}")
    public ViewDefinition get(@PathVariable String name) {
        return viewService.get(name);
    }

    @GetMapping
    public List<ViewDefinition> list() {
        return viewService.list();
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable String name) {
        viewService.delete(name);
    }

    /** Resolve a saved view against the current schema, with degradation hints. */
    @GetMapping("/{name}/resolve")
    public ResolvedView resolve(@PathVariable String name) {
        return viewService.resolve(name);
    }
}
