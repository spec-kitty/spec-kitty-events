package org.speckitty.commandcli.config;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What happens once the web server is bound (LAUNCHER.md "Ready" and "Stop"): the endpoint file is published, the signal hook that removes it
 * on {@code SIGTERM} is registered and the idle watch starts. Closing the application undoes the hook and stops the watch.
 */
public final class ProcessHooks implements AutoCloseable {

  private final EndpointPublisher publisher;
  private final ProcessShutdown shutdown;
  private final IdleTicker ticker;
  private final AtomicBoolean started = new AtomicBoolean();
  private final Thread signalHook;

  public ProcessHooks(EndpointPublisher publisher, ProcessShutdown shutdown, IdleTicker ticker) {
    this.publisher = publisher;
    this.shutdown = shutdown;
    this.ticker = ticker;
    this.signalHook = Thread.ofPlatform().name("command-cli-signal").unstarted(shutdown::onSignal);
  }

  /** Publishes the endpoint for {@code port}, registers the signal hook and starts the idle watch; runs once. */
  public void bound(int port) {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    publisher.publish(port);
    Runtime.getRuntime().addShutdownHook(signalHook);
    ticker.start();
  }

  @Override
  public void close() {
    ticker.close();
    if (started.get()) {
      try {
        Runtime.getRuntime().removeShutdownHook(signalHook);
      } catch (IllegalStateException alreadyExiting) {
        // the JVM is shutting down: the hook runs (or ran) and removes the endpoint file
      }
    }
  }
}
