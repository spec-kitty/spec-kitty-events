package org.speckitty.missionstatus.adapter.out.ledger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The caller gave up waiting for a lock another holder has (its abandon signal rose, for example because the service began to drain).
 * Nothing is held and nothing was done under the lock.
 */
public final class LockAbandonedException extends IOException {

  private static final long serialVersionUID = 1L;

  LockAbandonedException(Path lockFile) {
    super("the wait for lock " + lockFile + " was abandoned while another holder has it");
  }
}
