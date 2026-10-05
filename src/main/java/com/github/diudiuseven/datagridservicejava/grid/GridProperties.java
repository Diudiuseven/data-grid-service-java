package com.github.diudiuseven.datagridservicejava.grid;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the grid service; see README for semantics. */
@ConfigurationProperties(prefix = "grid")
public class GridProperties {

    /** Maximum page size accepted from clients. */
    private int maxPageSize = 500;

    /** Default page size when the client does not specify one. */
    private int defaultPageSize = 50;

    /** Number of recent snapshots retained for cursor-based paging. */
    private int snapshotRetention = 64;

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

    public int getSnapshotRetention() {
        return snapshotRetention;
    }

    public void setSnapshotRetention(int snapshotRetention) {
        this.snapshotRetention = snapshotRetention;
    }
}
