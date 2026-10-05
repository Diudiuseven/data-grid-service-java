package com.github.diudiuseven.datagridservicejava.grid;

import com.github.diudiuseven.datagridservicejava.grid.model.ColumnDef;
import com.github.diudiuseven.datagridservicejava.grid.model.ColumnType;
import com.github.diudiuseven.datagridservicejava.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end REST tests: endpoints and distinguishable error responses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GridApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TableStore store;

    @BeforeEach
    void setUp() {
        store.updateSchema(List.of(
                new ColumnDef("name", ColumnType.STRING),
                new ColumnDef("age", ColumnType.NUMBER)));
    }

    @Test
    void queryEditAndViewRoundTrip() {
        ResponseEntity<Map> insert = rest.postForEntity("/grid/rows",
                Map.of("values", Map.of("name", "alice", "age", 30)), Map.class);
        assertEquals(HttpStatus.OK, insert.getStatusCode());
        String rowId = (String) insert.getBody().get("rowId");
        assertNotNull(rowId);

        ResponseEntity<Map> page = rest.postForEntity("/grid/query",
                Map.of("sorts", List.of(Map.of("column", "age", "direction", "ASC")),
                        "pageSize", 10), Map.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());
        assertEquals(1, ((Number) page.getBody().get("totalRows")).intValue());

        ResponseEntity<Map> edit = rest.postForEntity("/grid/edit",
                Map.of("atomic", true,
                        "edits", List.of(Map.of("rowId", rowId, "expectedVersion", 1,
                                "values", Map.of("age", 31)))),
                Map.class);
        assertEquals(HttpStatus.OK, edit.getStatusCode());
        assertEquals(Boolean.TRUE, edit.getBody().get("committed"));

        rest.put("/grid/views/v1", Map.of("filters", List.of(), "sorts", List.of(),
                "pageSize", 20, "visibleColumns", List.of("name")));
        ResponseEntity<Map> view = rest.getForEntity("/grid/views/v1", Map.class);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(((List<?>) view.getBody().get("degradations")).isEmpty());
    }

    @Test
    void staleVersionEditReturnsConflictWithReason() {
        ResponseEntity<Map> insert = rest.postForEntity("/grid/rows",
                Map.of("values", Map.of("name", "bob", "age", 40)), Map.class);
        String rowId = (String) insert.getBody().get("rowId");

        rest.postForEntity("/grid/edit", Map.of("atomic", true,
                "edits", List.of(Map.of("rowId", rowId, "expectedVersion", 1,
                        "values", Map.of("age", 41)))), Map.class);

        // Atomic batch with a stale version: 200 with committed=false and
        // per-row VERSION_CONFLICT reason.
        ResponseEntity<Map> stale = rest.postForEntity("/grid/edit",
                Map.of("atomic", true,
                        "edits", List.of(Map.of("rowId", rowId, "expectedVersion", 1,
                                "values", Map.of("age", 42)))),
                Map.class);
        assertEquals(HttpStatus.OK, stale.getStatusCode());
        assertEquals(Boolean.FALSE, stale.getBody().get("committed"));
        List<?> outcomes = (List<?>) stale.getBody().get("outcomes");
        assertEquals("VERSION_CONFLICT", ((Map<?, ?>) outcomes.get(0)).get("reason"));
    }

    @Test
    void invalidCursorAndUnknownColumnReturnDistinguishableErrors() {
        ResponseEntity<Map> badCursor = rest.postForEntity("/grid/query/page",
                Map.of("cursor", "garbage"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badCursor.getStatusCode());
        assertEquals("CURSOR_INVALID", badCursor.getBody().get("reason"));

        ResponseEntity<Map> badColumn = rest.postForEntity("/grid/query",
                Map.of("sorts", List.of(Map.of("column", "nope", "direction", "ASC"))),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badColumn.getStatusCode());
        assertEquals("UNKNOWN_COLUMN", badColumn.getBody().get("reason"));

        ResponseEntity<Map> missingView = rest.getForEntity("/grid/views/absent",
                Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missingView.getStatusCode());
        assertEquals("VIEW_NOT_FOUND", missingView.getBody().get("reason"));

        ResponseEntity<Map> missingRow = rest.exchange("/grid/rows/ghost",
                HttpMethod.DELETE, HttpEntity.EMPTY, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, missingRow.getStatusCode());
        assertEquals("ROW_NOT_FOUND", missingRow.getBody().get("reason"));
    }
}
