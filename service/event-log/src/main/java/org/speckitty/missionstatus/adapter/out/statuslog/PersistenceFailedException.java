package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.file.Path;

/** A status-log append was short or could not be synced. The appender attempted rollback before raising this failure. */
public final class PersistenceFailedException extends IOException {

  private static final long serialVersionUID = 1L;

  private final boolean repairFailure;

  PersistenceFailedException(Path log, Exception cause) {
    super("status log " + log + " append was not durable; rollback was attempted", cause);
    this.repairFailure = false;
  }

  private PersistenceFailedException(String message, Exception cause) {
    super(message, cause);
    this.repairFailure = true;
  }

  /**
   * A tail repair failed; the message names no host path. Byte conservation held (see {@link TailRepair}), but the repair may
   * already have taken effect (a failure after the truncate, or on close), so the caller re-runs it: a re-run answers NONE then.
   */
  static PersistenceFailedException repairFailed(Exception cause) {
    return new PersistenceFailedException(
        "status log tail repair failed; no byte of the log was lost, and the repair may have taken effect: re-run it", cause);
  }

  /** True for a {@link #repairFailed} failure, whose message is path-free. */
  boolean isRepairFailure() {
    return repairFailure;
  }
}
