package com.github.diudiuseven.datagridservicejava.grid.cursor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Encodes/decodes opaque page cursors. A cursor pins a snapshot commit
 * sequence, the query fingerprint it was issued for, and the sort-key
 * position of the last returned row. Sort values carry type tags so they
 * decode back to exactly comparable values.
 */
@Component
public class CursorCodec {

    public record Cursor(long commitSeq, String queryFingerprint, List<Object> lastSortValues,
                         String lastRowId) {
    }

    private final ObjectMapper mapper;

    public CursorCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Cursor cursor) {
        ObjectNode node = mapper.createObjectNode();
        node.put("s", cursor.commitSeq());
        node.put("f", cursor.queryFingerprint());
        node.put("r", cursor.lastRowId());
        ArrayNode values = node.putArray("v");
        for (Object v : cursor.lastSortValues()) {
            ObjectNode tv = values.addObject();
            if (v == null) {
                tv.put("t", "NULL");
            } else if (v instanceof String s) {
                tv.put("t", "STRING").put("v", s);
            } else if (v instanceof Long l) {
                tv.put("t", "LONG").put("v", l.toString());
            } else if (v instanceof Integer i) {
                tv.put("t", "LONG").put("v", i.toString());
            } else if (v instanceof Double d) {
                tv.put("t", "DOUBLE").put("v", d.toString());
            } else if (v instanceof Boolean b) {
                tv.put("t", "BOOLEAN").put("v", b.toString());
            } else if (v instanceof Instant i) {
                tv.put("t", "INSTANT").put("v", i.toString());
            } else {
                tv.put("t", "STRING").put("v", String.valueOf(v));
            }
        }
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new IllegalStateException("cursor encode failed", e);
        }
    }

    /** @throws GridException with CURSOR_MALFORMED when undecodable */
    public Cursor decode(String text) {
        try {
            JsonNode node = mapper.readTree(Base64.getUrlDecoder().decode(text));
            long seq = node.get("s").asLong();
            String fingerprint = node.get("f").asText();
            String lastRowId = node.get("r").asText();
            List<Object> values = new ArrayList<>();
            for (JsonNode tv : node.get("v")) {
                String t = tv.get("t").asText();
                values.add(switch (t) {
                    case "NULL" -> null;
                    case "STRING" -> tv.get("v").asText();
                    case "LONG" -> Long.parseLong(tv.get("v").asText());
                    case "DOUBLE" -> Double.parseDouble(tv.get("v").asText());
                    case "BOOLEAN" -> Boolean.parseBoolean(tv.get("v").asText());
                    case "INSTANT" -> Instant.parse(tv.get("v").asText());
                    default -> throw new IllegalArgumentException("unknown tag " + t);
                });
            }
            return new Cursor(seq, fingerprint, values, lastRowId);
        } catch (Exception e) {
            throw new GridException(ErrorReason.CURSOR_MALFORMED, "cursor is not decodable");
        }
    }
}
