package com.github.diudiuseven.datagridservicejava.grid.error;

/** A failure with a machine-readable reason and a human-readable message. */
public class GridException extends RuntimeException {

    private final ErrorReason reason;

    public GridException(ErrorReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ErrorReason reason() {
        return reason;
    }
}
