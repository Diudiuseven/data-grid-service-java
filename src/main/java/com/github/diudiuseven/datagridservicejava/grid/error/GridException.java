package com.github.diudiuseven.datagridservicejava.grid.error;

/** Domain exception carrying a machine-distinguishable {@link ErrorReason}. */
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
