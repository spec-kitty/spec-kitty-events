package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.concurrent.Executor;
import org.speckitty.commandcli.application.lifecycle.ServiceIdentity;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Service operations of contracts/mission-write: {@code GET /api/v1/handshake} (what this service is), {@code GET /api/v1/health}
 * (ready or draining) and {@code POST /api/v1/shutdown} (drain and exit; LAUNCHER.md "Stop"). The shutdown answers 202 at once and finishes
 * the drain off the request thread, after this request itself has left the in-flight count, so the answer reaches the launcher before the
 * process exits; a shutdown while already draining is answered 202 and starts nothing new.
 */
@RestController
class ServiceController {

  private final ServiceIdentity identity;
  private final ServiceLifecycle lifecycle;
  private final Executor drainExecutor;

  ServiceController(ServiceIdentity identity, ServiceLifecycle lifecycle, DrainExecutor drainExecutor) {
    this.identity = identity;
    this.lifecycle = lifecycle;
    this.drainExecutor = drainExecutor;
  }

  @GetMapping(path = "/api/v1/handshake", produces = MediaType.APPLICATION_JSON_VALUE)
  ServiceBodies.Handshake handshake() {
    return new ServiceBodies.Handshake(
        identity.protocolVersions(), identity.serviceVersion(), identity.instanceId().value(), identity.repositoryId().value());
  }

  @GetMapping(path = "/api/v1/health", produces = MediaType.APPLICATION_JSON_VALUE)
  ServiceBodies.Health health() {
    return new ServiceBodies.Health(lifecycle.state().wireValue(), identity.instanceId().value());
  }

  @PostMapping(path = "/api/v1/shutdown")
  ResponseEntity<Void> shutdown() {
    if (lifecycle.beginDrain()) {
      drainExecutor.execute(
          () -> {
            try {
              lifecycle.finishDrain(ServiceLifecycle.DRAIN_BUDGET, 0);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }
}
