package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.file.Path;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLockTimeoutException;

/** The shared Mission status lock remained held until the caller's deadline. */
public final class StatusLockHeldException extends IOException {

  private static final long serialVersionUID = 1L;

  StatusLockHeldException(Path lock, LedgerLockTimeoutException cause) {
    super("status lock " + lock + " remained held until the deadline", cause);
  }
}
