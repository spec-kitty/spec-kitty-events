package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.Optional;
import org.speckitty.commandcli.adapter.in.http.edge.RefusalBody;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.speckitty.commandcli.application.lifecycle.WriteGate;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The WorkPackages operations of contracts/mission-write: one transition unit, a batch of units, one standalone annotation. After the edge
 * checks (guard, token, protocol, size) a write is refused 503 {@code SERVICE_DRAINING} while the service drains, then 400
 * {@code REQUEST_MALFORMED} when its body does not have the contract's shape, and otherwise goes to the {@link WriteGate}, which in this
 * release refuses every write fail-closed: nothing is ever acknowledged that was not durably persisted. The body is taken as bytes and
 * checked by hand ({@link WriteBodies}) rather than bound to generated models, so every shape fault is the contract's refusal.
 */
@RestController
class WriteController {

  private final ServiceLifecycle lifecycle;
  private final WriteGate gate;
  private final WriteBodies bodies;
  private final tools.jackson.databind.json.JsonMapper mapper;

  WriteController(ServiceLifecycle lifecycle, WriteGate gate, WriteBodies bodies, tools.jackson.databind.json.JsonMapper mapper) {
    this.lifecycle = lifecycle;
    this.gate = gate;
    this.bodies = bodies;
    this.mapper = mapper;
  }

  @PostMapping(path = "/api/v1/missions/{missionId}/work-package-transitions")
  ResponseEntity<byte[]> recordWorkPackageTransition(@PathVariable("missionId") String missionId, @RequestBody(required = false) byte[] body) {
    return write(missionId, () -> bodies.transitionUnit(missionId, body));
  }

  @PostMapping(path = "/api/v1/missions/{missionId}/work-package-transitions/batch")
  ResponseEntity<byte[]> recordWorkPackageTransitionBatch(@PathVariable("missionId") String missionId, @RequestBody(required = false) byte[] body) {
    return write(missionId, () -> bodies.transitionBatch(missionId, body));
  }

  @PostMapping(path = "/api/v1/missions/{missionId}/inner-state-annotations")
  ResponseEntity<byte[]> recordInnerStateAnnotation(@PathVariable("missionId") String missionId, @RequestBody(required = false) byte[] body) {
    return write(missionId, () -> bodies.annotation(missionId, body));
  }

  private ResponseEntity<byte[]> write(String missionId, java.util.function.Supplier<Optional<WriteBodies.Malformed>> shape) {
    if (lifecycle.draining()) {
      return refuse(RefusalBody.of(RefusalCode.SERVICE_DRAINING));
    }
    Optional<WriteBodies.Malformed> malformed = bodies.missionId(missionId).or(shape);
    if (malformed.isPresent()) {
      RefusalBody refusal = RefusalBody.of(RefusalCode.REQUEST_MALFORMED, malformed.get().detail());
      return refuse(malformed.get().instance() == null ? refusal : refusal.withInstance(malformed.get().instance()));
    }
    WriteGate.Refusal refused = gate.persist();
    return refuse(RefusalBody.of(refused.code(), refused.detail()));
  }

  private ResponseEntity<byte[]> refuse(RefusalBody refusal) {
    return ResponseEntity.status(refusal.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(refusal.bytes(mapper));
  }
}
