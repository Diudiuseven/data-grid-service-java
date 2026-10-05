package com.github.diudiuseven.datagridservicejava.grid.model;

import java.util.List;

/**
 * Result of a batch edit.
 *
 * @param atomic        whether the batch ran in all-or-nothing mode
 * @param committed     whether any change was committed (for atomic batches this
 *                      is all-or-nothing)
 * @param commitVersion store commit version after the batch, or the version
 *                      before if nothing was committed
 * @param outcomes      per-row outcomes, in request order
 */
public record BatchEditResult(boolean atomic, boolean committed, long commitVersion,
                              List<RowEditOutcome> outcomes) {
}
