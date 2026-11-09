package org.speckitty.commandcli.application.write;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** One fair in-process write turn per service instance. */
public final class WriteTurn {
  private final ReentrantLock lock = new ReentrantLock(true);

  public Optional<Turn> tryAcquire(long remainingNanos) {
    try {
      return lock.tryLock(Math.max(0, remainingNanos), TimeUnit.NANOSECONDS) ? Optional.of(new Turn()) : Optional.empty();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /** Confined to the try-with-resources scope of the successful acquisition. */
  public final class Turn implements AutoCloseable {
    private boolean open = true;

    private Turn() {}

    @Override
    public void close() {
      if (open) {
        open = false;
        lock.unlock();
      }
    }
  }
}
