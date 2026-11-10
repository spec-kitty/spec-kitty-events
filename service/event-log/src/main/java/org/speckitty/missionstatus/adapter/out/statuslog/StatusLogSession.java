package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;

/**
 * A single locked status-log transaction. It accepts encoded bytes, never rows, and mutates at most once: one append, or one tail
 * repair, never both.
 */
public interface StatusLogSession {

  /** Returns the whole current log, or an empty array when the file is missing or empty. A torn tail is refused unchanged. */
  byte[] currentBytes() throws IOException;

  /** Appends complete LF-terminated lines in one write and syncs them before returning. */
  void appendAndSync(byte[] bytes) throws IOException;

  /**
   * The only operation that may change a torn log, and never part of a write. A missing, empty or LF-terminated log is left untouched
   * ({@code NONE}, no file created). Otherwise {@code classifier} decides: a complete row gets exactly one LF ({@code COMPLETED}); any
   * other fragment is moved to {@code status.events.jsonl.torn} and the log truncated to its last LF ({@code QUARANTINED}). Under any
   * single fault no byte is lost (see {@link TailRepair}). Failures raise {@link PersistenceFailedException}, whose message names no
   * host path.
   *
   * @throws IllegalStateException the session already appended or repaired, or is closed
   */
  TailRepair repairTail(TailClassifier classifier) throws IOException;
}
