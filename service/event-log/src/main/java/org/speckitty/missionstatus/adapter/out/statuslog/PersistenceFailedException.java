package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.file.Path;

/** A status-log append was short or could not be synced. The appender attempted rollback before raising this failure. */
public final class PersistenceFailedException extends IOException {

  private static final long serialVersionUID = 1L;

  PersistenceFailedException(Path log, Exception cause) {
    super("status log " + log + " append was not durable; rollback was attempted", cause);
  }
}
