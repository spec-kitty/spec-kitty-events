package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.function.Supplier;
import org.speckitty.commandcli.adapter.in.http.edge.RefusalBody;
import org.speckitty.commandcli.application.diagnostics.FailureRecord;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.speckitty.commandcli.application.write.WriteOutcome;
import org.speckitty.commandcli.application.write.WriteRequest;
import org.speckitty.commandcli.application.write.WriteService;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** The unchanged write edge, followed by typed shape validation and the authoritative write service. */
@RestController
class WriteController {
  private static final System.Logger LOG = System.getLogger(WriteController.class.getName());
  /** Fixed, and never the message of the failure: no write answers a Problem without a contract code, or with a value of the request. */
  private static final String UNEXPECTED_DETAIL = "The write failed unexpectedly; nothing was acknowledged.";

  private final ServiceLifecycle lifecycle;
  private final WriteService writes;
  private final WriteBodies bodies;
  private final JsonMapper mapper;

  WriteController(ServiceLifecycle lifecycle, WriteService writes, WriteBodies bodies, JsonMapper mapper) {
    this.lifecycle = lifecycle;
    this.writes = writes;
    this.bodies = bodies;
    this.mapper = mapper;
  }

  @PostMapping(path = "/api/v1/missions/{missionId}/work-package-transitions")
  ResponseEntity<byte[]> recordWorkPackageTransition(@PathVariable("missionId") String missionId,
      @RequestBody(required = false) byte[] body) {
    return write("recordWorkPackageTransition", missionId, () -> bodies.parseUnit(missionId, body));
  }

  @PostMapping(path = "/api/v1/missions/{missionId}/work-package-transitions/batch")
  ResponseEntity<byte[]> recordWorkPackageTransitionBatch(@PathVariable("missionId") String missionId,
      @RequestBody(required = false) byte[] body) {
    return write("recordWorkPackageTransitionBatch", missionId, () -> bodies.parseBatch(missionId, body));
  }

  @PostMapping(path = "/api/v1/missions/{missionId}/inner-state-annotations")
  ResponseEntity<byte[]> recordInnerStateAnnotation(@PathVariable("missionId") String missionId,
      @RequestBody(required = false) byte[] body) {
    return write("recordInnerStateAnnotation", missionId, () -> bodies.parseAnnotation(missionId, body));
  }

  private ResponseEntity<byte[]> write(String operation, String missionId, Supplier<WriteBodies.Parsed> shape) {
    try {
      return checkedWrite(missionId, shape);
    } catch (RuntimeException unexpected) {
      // Errors deliberately remain uncaught and propagate to Spring's default handling.
      LOG.log(System.Logger.Level.WARNING,
          FailureRecord.describe(operation, RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED.name(), unexpected));
      return refuse(RefusalBody.of(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, UNEXPECTED_DETAIL));
    }
  }

  private ResponseEntity<byte[]> checkedWrite(String missionId, Supplier<WriteBodies.Parsed> shape) {
    if (lifecycle.draining()) return refuse(RefusalBody.of(RefusalCode.SERVICE_DRAINING));
    var invalidMission = bodies.missionId(missionId);
    if (invalidMission.isPresent()) return malformed(invalidMission.orElseThrow());
    WriteBodies.Parsed parsed = shape.get();
    if (parsed instanceof WriteBodies.Parsed.Failure failure) return malformed(failure.malformed());
    WriteRequest request = ((WriteBodies.Parsed.Success) parsed).request();
    WriteOutcome outcome = writes.write(request);
    if (outcome instanceof WriteOutcome.Acked ack) {
      return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).body(AckBody.bytes(ack, mapper));
    }
    WriteOutcome.Refused refused = (WriteOutcome.Refused) outcome;
    RefusalBody body = RefusalBody.of(refused.code(), refused.detail());
    if (refused.reason() != null) body = body.withType(refused.reason().urn());
    if (refused.unitIndex() != null) body = body.withInstance("#/units/" + refused.unitIndex());
    return refuse(body);
  }

  private ResponseEntity<byte[]> malformed(WriteBodies.Malformed malformed) {
    RefusalBody refusal = RefusalBody.of(RefusalCode.REQUEST_MALFORMED, malformed.detail());
    return refuse(malformed.instance() == null ? refusal : refusal.withInstance(malformed.instance()));
  }

  private ResponseEntity<byte[]> refuse(RefusalBody refusal) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(refusal.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (RefusalCode.SERVICE_BUSY.name().equals(refusal.code())) response.header(HttpHeaders.RETRY_AFTER, "1");
    return response.body(refusal.bytes(mapper));
  }
}
