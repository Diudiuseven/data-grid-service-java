package com.github.diudiuseven.datagridservicejava.tests.grid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end REST coverage: schema, rows, query, batch, views, error mapping. */
@SpringBootTest
@AutoConfigureMockMvc
class GridApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void setUpSchema() throws Exception {
        mvc.perform(put("/api/grid/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("columns", List.of(
                                Map.of("name", "name", "type", "STRING"),
                                Map.of("name", "age", "type", "LONG"))))))
                .andExpect(status().isOk());
    }

    @Test
    void createQueryUpdateDeleteRoundTrip() throws Exception {
        mvc.perform(post("/api/grid/rows/r1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "alice", "age", 30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        MvcResult query = mvc.perform(post("/api/grid/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "sorts", List.of(Map.of("column", "age", "direction", "DESC")),
                                "limit", 10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].id").value("r1"))
                .andReturn();
        JsonNode body = mapper.readTree(query.getResponse().getContentAsString());
        assertTrue(body.has("snapshotCommitSeq"));

        mvc.perform(put("/api/grid/rows/r1?version=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("age", 31))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // Stale version -> 409 with machine-readable reason.
        mvc.perform(put("/api/grid/rows/r1?version=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("age", 32))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("VERSION_CONFLICT"));

        mvc.perform(delete("/api/grid/rows/r1?version=2")).andExpect(status().isOk());
        mvc.perform(delete("/api/grid/rows/r1?version=3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("ROW_NOT_FOUND"));
    }

    @Test
    void batchEndpointReturnsPerRowResults() throws Exception {
        mvc.perform(post("/api/grid/rows/a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "a", "age", 1))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/grid/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "mode", "PER_ROW",
                                "mutations", List.of(
                                        Map.of("kind", "UPDATE", "rowId", "a",
                                                "expectedVersion", 1, "fields", Map.of("age", 2)),
                                        Map.of("kind", "UPDATE", "rowId", "ghost",
                                                "expectedVersion", 1, "fields", Map.of("age", 3)))))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("results").get(0).get("success").asBoolean());
        assertEquals("ROW_NOT_FOUND", body.get("results").get(1).get("errorReason").asText());
    }

    @Test
    void cursorFlowOverRest() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/grid/rows/c" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("name", "n" + i, "age", 100 + i))))
                    .andExpect(status().isOk());
        }
        // Filter isolates this test's rows from rows created by other tests.
        Map<String, Object> filter = Map.of("column", "age", "op", "GTE", "value", 100);
        MvcResult first = mvc.perform(post("/api/grid/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "filters", List.of(filter),
                                "sorts", List.of(Map.of("column", "age", "direction", "ASC")),
                                "limit", 2))))
                .andExpect(status().isOk())
                .andReturn();
        String cursor = mapper.readTree(first.getResponse().getContentAsString())
                .get("nextCursor").asText();
        assertNotNull(cursor);

        mvc.perform(post("/api/grid/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "filters", List.of(filter),
                                "sorts", List.of(Map.of("column", "age", "direction", "ASC")),
                                "limit", 2,
                                "cursor", cursor))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].id").value("c2"));

        // Replaying the cursor against a different query is rejected distinctly.
        mvc.perform(post("/api/grid/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "filters", List.of(filter),
                                "sorts", List.of(Map.of("column", "age", "direction", "DESC")),
                                "limit", 2,
                                "cursor", cursor))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("CURSOR_QUERY_MISMATCH"));
    }

    @Test
    void viewLifecycleOverRest() throws Exception {
        mvc.perform(put("/api/grid/views/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "filters", List.of(Map.of("column", "age", "op", "GTE", "value", 18)),
                                "sorts", List.of(Map.of("column", "age", "direction", "DESC")),
                                "pageSize", 20,
                                "visibleColumns", List.of("name", "age")))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/grid/views/v1/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degradations").isArray())
                .andExpect(jsonPath("$.view.pageSize").value(20));

        mvc.perform(get("/api/grid/views/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("VIEW_NOT_FOUND"));
    }
}
