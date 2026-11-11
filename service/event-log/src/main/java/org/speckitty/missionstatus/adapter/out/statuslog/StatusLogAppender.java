package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLockTimeoutException;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;

/**
 * Runs byte-oriented status-log work under the Mission lock core's Python writer uses.
 *
 * <p>The caller shares one deadline between its in-process turn and this lock. Calls are not reentrant: a callback must
 * not call {@code withMissionLock} for the same Mission. A session closes when its callback returns or throws.
 *
 * <p>Every deadline passed in is absolute in the time base of this appender's nano clock ({@code System::nanoTime} unless one is
 * injected), so the lock wait and the caller's budget are computed from one clock.
 */
public final class StatusLogAppender {

  private static final Path LOG_NAME = Path.of("status.events.jsonl");

  private final LedgerLock lock;
  private final FileOps files;
  private final LongSupplier nanoClock;

  public StatusLogAppender() {
    this(System::nanoTime);
  }

  /** As the default appender, but reads {@code nanoClock} for the time remaining to each deadline. */
  public StatusLogAppender(LongSupplier nanoClock) {
    this(LedgerLocks.forThisOs(), new JdkFileOps(), nanoClock);
  }

  StatusLogAppender(LedgerLock lock, FileOps files) {
    this(lock, files, System::nanoTime);
  }

  StatusLogAppender(LedgerLock lock, FileOps files, LongSupplier nanoClock) {
    this.lock = lock;
    this.files = files;
    this.nanoClock = nanoClock;
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
    return withMissionLock(gcd, missionDirectory, deadlineNanos, () -> false, work);
  }

  /**
   * As above, but the wait for the lock ends in {@link org.speckitty.missionstatus.adapter.out.ledger.LockAbandonedException} once
   * {@code abandon} is true. The signal is consulted only while waiting; a held lock is never given up, and {@code work} always runs to
   * its end.
   */
  public <T> T withMissionLock(Path gcd, Path missionDirectory, long deadlineNanos, BooleanSupplier abandon, SessionWork<T> work)
      throws IOException {
    Path lockPath = MissionLockPath.of(gcd, missionDirectory.getFileName().toString());
    long remaining = Math.max(0L, deadlineNanos - nanoClock.getAsLong());
    try (LedgerLock.Held held = acquire(lockPath, remaining, abandon)) {
      LockedStatusLogSession session = new LockedStatusLogSession(missionDirectory.resolve(LOG_NAME), files);
      try {
        return work.run(session);
      } finally {
        session.close();
      }
    }
  }

  private LedgerLock.Held acquire(Path lockPath, long remainingNanos, BooleanSupplier abandon) throws IOException {
    try {
      return lock.acquire(lockPath, Duration.ofNanos(remainingNanos), abandon);
    } catch (LedgerLockTimeoutException e) {
      throw new StatusLockHeldException(lockPath, e);
    }
  }
}
