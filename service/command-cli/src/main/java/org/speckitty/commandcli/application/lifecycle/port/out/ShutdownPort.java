package org.speckitty.commandcli.application.lifecycle.port.out;

/**
 * Ends the process once the service has drained: removes the endpoint file while it still names this instance, closes the application and
 * exits 0. Implemented by the process adapter; called once, off the request thread, after the shutdown answer is sent.
 */
@FunctionalInterface
public interface ShutdownPort {
  void stop();
}
