package org.speckitty.commandcli.application.write;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * One in-process write turn per service instance.
 *
 * <p>A waiter waits in slices of at most {@link #SLICE_NANOS} and looks at its abandon signal between slices, so a request still
 * waiting when the service begins to drain can stop waiting (D10). Slicing means a waiter re-queues on every slice, so the strict
 * first-come-first-served order of a single long fair wait is lost among waiters, in normal operation too, not only while draining. That
 * is accepted: waiter order is not a contract.
 */
public final class WriteTurn {
  /** The longest a waiter blocks before it looks at its abandon signal again. */
  static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

  private final ReentrantLock lock = new ReentrantLock(true);

  /** How an acquisition ended. */
  public sealed interface Acquisition permits Acquired, TimedOut, Abandoned {}

  /** The turn is held; the caller closes it. */
  public record Acquired(Turn turn) implements Acquisition {}

  /** The budget ran out, or the wait was interrupted, while another request held the turn. */
  public record TimedOut() implements Acquisition {}

  /** The abandon signal rose while the caller was still waiting. Nothing is held. */
  public record Abandoned() implements Acquisition {}

  /** Waits up to {@code remainingNanos}; ignores abandonment. */
  public Optional<Turn> tryAcquire(long remainingNanos) {
    return acquire(remainingNanos, () -> false) instanceof Acquired acquired ? Optional.of(acquired.turn()) : Optional.empty();
  }

  /**
   * Waits up to {@code remainingNanos} for the turn. {@code abandon} is consulted only while the turn is not held: once the lock is taken
   * the result is {@link Acquired} even if the service began to drain meanwhile, because a holder finishes.
   */
  public Acquisition acquire(long remainingNanos, BooleanSupplier abandon) {
    long deadline = System.nanoTime() + Math.max(0, remainingNanos);
    try {
      while (true) {
        long left = deadline - System.nanoTime();
        if (lock.tryLock(Math.max(0, Math.min(SLICE_NANOS, left)), TimeUnit.NANOSECONDS)) return new Acquired(new Turn());
        if (abandon.getAsBoolean()) return new Abandoned();
        if (left <= SLICE_NANOS) return new TimedOut();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new TimedOut();
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

  /** Whether a request is queued for the turn right now: an observation for diagnostics and tests, never a decision input. */
  public boolean hasWaiters() {
    return lock.hasQueuedThreads();
  }
}
