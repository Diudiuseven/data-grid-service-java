package com.github.diudiuseven.datagridservicejava.grid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the data grid service. */
@ConfigurationProperties(prefix = "grid")
public class GridProperties {

    /** Maximum page size accepted from clients. */
    private int maxPageSize = 200;

    /** Default page size when the client does not specify one. */
    private int defaultPageSize = 50;

    /** How many recent commit versions to retain for cursor paging. */
    private int retainedSnapshots = 64;

    /** Milliseconds a paging cursor stays valid since its last use. */
    private long cursorTtlMillis = 300_000;

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getRetainedSnapshots() {
        return retainedSnapshots;
    }

    public void setRetainedSnapshots(int retainedSnapshots) {
        this.retainedSnapshots = retainedSnapshots;
    }

    public long getCursorTtlMillis() {
        return cursorTtlMillis;
    }

    public void setCursorTtlMillis(long cursorTtlMillis) {
        this.cursorTtlMillis = cursorTtlMillis;
    }
}
