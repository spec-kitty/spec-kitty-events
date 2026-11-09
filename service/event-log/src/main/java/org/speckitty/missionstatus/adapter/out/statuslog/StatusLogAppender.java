package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLockTimeoutException;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;

/**
 * Runs byte-oriented status-log work under the Mission lock core's Python writer uses.
 *
 * <p>The caller shares one deadline between its in-process turn and this lock. Calls are not reentrant: a callback must
 * not call {@code withMissionLock} for the same Mission. A session closes when its callback returns or throws.
 */
public final class StatusLogAppender {

  private static final Path LOG_NAME = Path.of("status.events.jsonl");

  private final LedgerLock lock;
  private final FileOps files;

  public StatusLogAppender() {
    this(LedgerLocks.forThisOs(), new JdkFileOps());
  }

  StatusLogAppender(LedgerLock lock, FileOps files) {
    this.lock = lock;
    this.files = files;
  }

  /** Work performed while the Mission status lock is held. */
  @FunctionalInterface
  public interface SessionWork<T> {
    T run(StatusLogSession session) throws IOException;
  }

  /**
   * Takes {@code <gcd>/spec-kitty-locks/<mission basename>.status.lock} with the time remaining to
   * {@code deadlineNanos}, then yields one session for {@code <missionDirectory>/status.events.jsonl}.
   */
  public <T> T withMissionLock(Path gcd, Path missionDirectory, long deadlineNanos, SessionWork<T> work) throws IOException {
    Path lockPath = MissionLockPath.of(gcd, missionDirectory.getFileName().toString());
    long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
    try (LedgerLock.Held held = acquire(lockPath, remaining)) {
      LockedStatusLogSession session = new LockedStatusLogSession(missionDirectory.resolve(LOG_NAME), files);
      try {
        return work.run(session);
      } finally {
        session.close();
      }
    }
  }

  private LedgerLock.Held acquire(Path lockPath, long remainingNanos) throws IOException {
    try {
      return lock.acquire(lockPath, Duration.ofNanos(remainingNanos));
    } catch (LedgerLockTimeoutException e) {
      throw new StatusLockHeldException(lockPath, e);
    }
  }
}
