package org.speckitty.missionstatus.adapter.out.ledger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * A cross-process lock on a dedicated lock file, the Java side of the CLI's {@code kernel.locks.machine_file_lock}. Both sides lock the
 * same file, so a Java holder and a Python holder exclude each other (the #6193 lock-interop spike,
 * docs/architecture/services/lock-interop-spike.md). The lock file only exists to be locked; nothing is read from or written to it.
 */
public interface LedgerLock {

  /** Poll interval of the bounded wait, the same as {@code kernel.locks}' retry sleep. */
  Duration RETRY_INTERVAL = Duration.ofMillis(100);

  /** A held lock; closing it releases the lock. Closing it twice is harmless. */
  interface Held extends AutoCloseable {
    @Override
    void close() throws IOException;
  }

  /**
   * One non-blocking attempt. Creates the lock file (owner read and write only) and its missing parent directories.
   *
   * @return the held lock, or {@code null} when another holder has it
   * @throws IOException the lock file could not be opened, for example because it is a symbolic link
   */
  Held tryAcquire(Path lockFile) throws IOException;

  /**
   * Retries {@link #tryAcquire} every {@link #RETRY_INTERVAL} until it succeeds or {@code timeout} elapses.
   *
   * @throws LedgerLockTimeoutException the lock was still held when {@code timeout} elapsed
   */
  default Held acquire(Path lockFile, Duration timeout) throws IOException {
    return acquire(lockFile, timeout, () -> false);
  }

  /**
   * As {@link #acquire(Path, Duration)}, but gives up with {@link LockAbandonedException} once {@code abandon} is true. The signal is
   * consulted only after a <em>failed</em> attempt, never once the lock is held: a lock this method has taken is returned, so it is never
   * dropped (which would leak the descriptor and keep the other holder blocked).
   *
   * <p>Implementors override this three-argument form: the two-argument {@code acquire} delegates here, so a lock that overrides only
   * the two-argument form is bypassed by callers that pass an abandon signal ({@code StatusLogAppender}).
   *
   * @throws LockAbandonedException {@code abandon} was true while another holder had the lock
   * @throws LedgerLockTimeoutException the lock was still held when {@code timeout} elapsed
   */
  default Held acquire(Path lockFile, Duration timeout, BooleanSupplier abandon) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      Held held = tryAcquire(lockFile);
      if (held != null) {
        return held;
      }
      if (abandon.getAsBoolean()) {
        throw new LockAbandonedException(lockFile);
      }
      if (System.nanoTime() - deadline >= 0) {
        throw new LedgerLockTimeoutException(lockFile, timeout);
      }
      try {
        Thread.sleep(RETRY_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new java.io.InterruptedIOException("interrupted while waiting for " + lockFile);
      }
    }
  }
}
