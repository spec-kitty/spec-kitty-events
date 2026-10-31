package org.speckitty.commandcli.application.lifecycle;

import java.time.Duration;

/**
 * The idle exit of LAUNCHER.md: a service that has had no request for its idle timeout (1800 s unless {@code --idle-timeout} says otherwise)
 * drains and stops, as after a shutdown request. {@link #tick()} is called periodically by the process adapter.
 */
public final class IdleWatch {

  private final ServiceLifecycle lifecycle;
  private final Duration timeout;

  public IdleWatch(ServiceLifecycle lifecycle, Duration timeout) {
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("the idle timeout must be positive");
    }
    this.lifecycle = lifecycle;
    this.timeout = timeout;
  }

  public Duration timeout() {
    return timeout;
  }

  /** Begins the drain when the service has been idle for the timeout; answers true when this tick began it. */
  public boolean tick() {
    if (lifecycle.draining() || lifecycle.idleFor().compareTo(timeout) < 0) {
      return false;
    }
    return lifecycle.beginDrain();
  }
}
