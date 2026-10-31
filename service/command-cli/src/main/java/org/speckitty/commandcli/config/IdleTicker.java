package org.speckitty.commandcli.config;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.speckitty.commandcli.application.lifecycle.IdleWatch;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;

/**
 * Ticks the {@link IdleWatch} once a second on a daemon thread; when it begins the drain, the drain is finished on that thread. Closed with
 * the application.
 */
public final class IdleTicker implements AutoCloseable {

  static final Duration TICK = Duration.ofSeconds(1);

  private final IdleWatch watch;
  private final ServiceLifecycle lifecycle;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("command-cli-idle").daemon(true).factory());

  public IdleTicker(IdleWatch watch, ServiceLifecycle lifecycle) {
    this.watch = watch;
    this.lifecycle = lifecycle;
  }

  /** Starts ticking; called once the endpoint file is published. */
  public void start() {
    scheduler.scheduleWithFixedDelay(
        () -> {
          if (watch.tick()) {
            try {
              lifecycle.finishDrain(ServiceLifecycle.DRAIN_BUDGET, 0);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        },
        TICK.toMillis(),
        TICK.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
