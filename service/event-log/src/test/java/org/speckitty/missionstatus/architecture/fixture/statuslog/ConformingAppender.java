package org.speckitty.missionstatus.architecture.fixture.statuslog;

import java.nio.file.Path;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;

/** Conforms: status-log mechanics may use themselves, ledger locks and the JDK. */
public final class ConformingAppender {

  private ConformingAppender() {}

  public static Path lockDirectory(Path gcd, LedgerLock lock) {
    return gcd.resolve("spec-kitty-locks");
  }
}
