package com.github.diudiuseven.datagridservicejava.grid.api;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps domain failures to HTTP responses carrying the machine-readable reason. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GridException.class)
    public ResponseEntity<Map<String, Object>> handle(GridException ex) {
        HttpStatus status = switch (ex.reason()) {
            case VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case ROW_NOT_FOUND, VIEW_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ROW_ALREADY_EXISTS, VIEW_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case CURSOR_MALFORMED, CURSOR_QUERY_MISMATCH, INVALID_QUERY, INVALID_WRITE -> HttpStatus.BAD_REQUEST;
            case CURSOR_EXPIRED -> HttpStatus.GONE;
            case BATCH_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of(
                "reason", ex.reason().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
