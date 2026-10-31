package org.speckitty.commandcli.config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.speckitty.commandcli.adapter.out.filesystem.EndpointFile;
import org.speckitty.commandcli.adapter.out.filesystem.ServiceLock;
import org.speckitty.commandcli.application.lifecycle.port.out.ShutdownPort;
import org.speckitty.commandcli.domain.service.InstanceId;

/**
 * The end of the process (LAUNCHER.md "Stop"): once the service has drained, the endpoint file is removed while it still names this
 * instance, the application is closed, the single-instance lock is released and the process exits 0. It runs once, whichever of the
 * shutdown request, the idle exit or a signal comes first.
 */
public final class ProcessShutdown implements ShutdownPort {

  private final EndpointFile endpoint;
  private final InstanceId instance;
  private final ServiceLock lock;
  private final Runnable closeApplication;
  private final IntConsumer exit;
  private final AtomicBoolean ran = new AtomicBoolean();

  public ProcessShutdown(EndpointFile endpoint, InstanceId instance, ServiceLock lock, Runnable closeApplication, IntConsumer exit) {
    this.endpoint = endpoint;
    this.instance = instance;
    this.lock = lock;
    this.closeApplication = closeApplication;
    this.exit = exit;
  }

  @Override
  public void stop() {
    if (!ran.compareAndSet(false, true)) {
      return;
    }
    endpoint.removeIfOwn(instance.value());
    try {
      closeApplication.run();
    } finally {
      releaseLock();
      exit.accept(0);
    }
  }

  /** The signal path (a JVM shutdown hook): only the endpoint file is removed; the JVM is already exiting. */
  public void onSignal() {
    if (ran.get()) {
      return;
    }
    endpoint.removeIfOwn(instance.value());
  }

  private void releaseLock() {
    try {
      lock.close();
    } catch (java.io.IOException | RuntimeException ignored) {
      // the process exits next; the OS releases the lock with the descriptor
    }
  }
}
