package org.speckitty.commandcli.config;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.speckitty.commandcli.adapter.out.filesystem.EndpointDocument;
import org.speckitty.commandcli.adapter.out.filesystem.EndpointFile;
import org.speckitty.commandcli.application.lifecycle.ServiceIdentity;
import org.speckitty.commandcli.domain.service.WriteToken;

/**
 * Publishes the endpoint file once the web server is bound (LAUNCHER.md "Ready": the launcher's first readiness check is that the file
 * exists). {@code started_at} is the publish time: core's launcher proves an instance by comparing it with the process creation time, so it
 * must come after the process started and inside the start budget. A failed publish fails the start: a service nobody can find must not
 * run.
 */
public final class EndpointPublisher {

  private final EndpointFile endpoint;
  private final ServiceIdentity identity;
  private final WriteToken token;
  private final Clock clock;

  public EndpointPublisher(EndpointFile endpoint, ServiceIdentity identity, WriteToken token, Clock clock) {
    this.endpoint = endpoint;
    this.identity = identity;
    this.token = token;
    this.clock = clock;
  }

  public void publish(int port) {
    EndpointDocument document =
        new EndpointDocument(
            port, token.reveal(), identity.instanceId().value(), ProcessHandle.current().pid(), identity.protocolVersions(), OffsetDateTime.now(clock).toString());
    try {
      endpoint.publish(document);
    } catch (IOException e) {
      // fail the application's start (refresh), which stops the server and ends main non-zero; never exit from inside the refresh
      throw new IllegalStateException("command-cli: the endpoint file cannot be published (" + e.getClass().getSimpleName() + ")");
    }
  }
}
