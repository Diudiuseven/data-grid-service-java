package com.github.diudiuseven.datagridservicejava.grid.api;

import com.github.diudiuseven.datagridservicejava.grid.error.ErrorReason;
import com.github.diudiuseven.datagridservicejava.grid.error.GridException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps GridException reasons to distinguishable HTTP responses. */
@RestControllerAdvice
public class GridExceptionHandler {

    /** Error response body: machine-readable reason + human-readable message. */
    public record ErrorBody(String reason, String message) {
    }

    @ExceptionHandler(GridException.class)
    public ResponseEntity<ErrorBody> handle(GridException e) {
        return ResponseEntity.status(statusFor(e.reason()))
                .body(new ErrorBody(e.reason().name(), e.getMessage()));
    }

    private HttpStatus statusFor(ErrorReason reason) {
        return switch (reason) {
            case ROW_NOT_FOUND, VIEW_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VERSION_CONFLICT, BATCH_NOT_COMMITTED -> HttpStatus.CONFLICT;
            case CURSOR_EXPIRED -> HttpStatus.GONE;
            case CURSOR_INVALID, CURSOR_QUERY_MISMATCH, UNKNOWN_COLUMN, TYPE_MISMATCH,
                 VALIDATION_FAILED, VIEW_ALREADY_EXISTS -> HttpStatus.BAD_REQUEST;
        };
    }
}
