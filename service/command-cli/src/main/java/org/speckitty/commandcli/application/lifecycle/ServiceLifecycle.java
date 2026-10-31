package org.speckitty.commandcli.application.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.speckitty.commandcli.application.lifecycle.port.out.ShutdownPort;
import org.speckitty.commandcli.domain.service.ServiceState;

/**
 * The life of one service instance (data model ServiceState; contracts/mission-write HealthStatus and LAUNCHER.md "Stop"). It counts the
 * requests in flight and the time of the last one, starts the drain once (on a shutdown request or an idle timeout) and, when the requests it
 * holds have finished or the drain budget has run out, stops the process through {@link ShutdownPort}.
 */
public final class ServiceLifecycle {

  /** How long a drain waits for the requests in flight before the process stops anyway. */
  public static final Duration DRAIN_BUDGET = Duration.ofSeconds(5);

  private final Clock clock;
  private final ShutdownPort shutdown;
  private final AtomicReference<ServiceState> state = new AtomicReference<>(ServiceState.READY);
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicReference<Instant> lastActivity;
  private final Object idle = new Object();

  public ServiceLifecycle(Clock clock, ShutdownPort shutdown) {
    this.clock = clock;
    this.shutdown = shutdown;
    this.lastActivity = new AtomicReference<>(clock.instant());
  }

  public ServiceState state() {
    return state.get();
  }

  public boolean draining() {
    return state.get() != ServiceState.READY;
  }

  /** A request starts. */
  public void enter() {
    inFlight.incrementAndGet();
    lastActivity.set(clock.instant());
  }

  /** A request has finished. */
  public void exit() {
    lastActivity.set(clock.instant());
    if (inFlight.decrementAndGet() == 0) {
      synchronized (idle) {
        idle.notifyAll();
      }
    }
  }

  public int inFlight() {
    return inFlight.get();
  }

  /** How long the service has been without a request, or zero while one is in flight. */
  public Duration idleFor() {
    if (inFlight.get() > 0) {
      return Duration.ZERO;
    }
    Duration idleFor = Duration.between(lastActivity.get(), clock.instant());
    return idleFor.isNegative() ? Duration.ZERO : idleFor;
  }

  /** Starts the drain; answers true only for the call that moved the service from READY, so the stop runs once. */
  public boolean beginDrain() {
    return state.compareAndSet(ServiceState.READY, ServiceState.DRAINING);
  }

  /**
   * Finishes a drain begun by {@link #beginDrain()}: waits up to {@code budget} for the requests in flight (not counting {@code own} requests
   * of the caller still on the stack), moves to STOPPED and stops the process. Answers false when the service was not draining or was
   * already stopped.
   */
  public boolean finishDrain(Duration budget, int own) throws InterruptedException {
    if (state.get() != ServiceState.DRAINING) {
      return false;
    }
    long deadline = System.nanoTime() + budget.toNanos();
    synchronized (idle) {
      while (inFlight.get() > own) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          break;
        }
        idle.wait(Math.max(1, left / 1_000_000));
      }
    }
    if (!state.compareAndSet(ServiceState.DRAINING, ServiceState.STOPPED)) {
      return false;
    }
    shutdown.stop();
    return true;
  }
}
