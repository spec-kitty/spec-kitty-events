package org.speckitty.commandcli.adapter.in.http.operations;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.speckitty.commandcli.adapter.in.http.edge.RefusalBody;
import org.speckitty.commandcli.application.diagnostics.FailureRecord;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.speckitty.commandcli.application.repair.RepairOutcome;
import org.speckitty.commandcli.application.repair.StatusLogRepairService;
import org.speckitty.commandcli.application.write.port.StatusLog.RepairResult.Action;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The explicit status-log repair edge (contracts/mission-write StatusLogRepairRequest and StatusLogRepairAck): after the unchanged edge
 * filters, a draining check, the typed shape check and the repair service. The answer is an ordered map, never a bean, so it needs no
 * native hints.
 */
@RestController
class StatusLogRepairController {
  private static final System.Logger LOG = System.getLogger(StatusLogRepairController.class.getName());
  /** The fixed name of the quarantine file beside the log; the answer never carries a host path. */
  private static final String QUARANTINE_FILE = "status.events.jsonl.torn";
  /** Fixed, and never the message of the failure. */
  private static final String UNEXPECTED_DETAIL = "The repair failed unexpectedly; no byte of the log was lost, so re-run it.";

  private final ServiceLifecycle lifecycle;
  private final StatusLogRepairService repairs;
  private final WriteBodies bodies;
  private final JsonMapper mapper;

  StatusLogRepairController(ServiceLifecycle lifecycle, StatusLogRepairService repairs, WriteBodies bodies, JsonMapper mapper) {
    this.lifecycle = lifecycle;
    this.repairs = repairs;
    this.bodies = bodies;
    this.mapper = mapper;
  }

  @PostMapping(path = "/api/v1/missions/{missionId}/status-log-repairs")
  ResponseEntity<byte[]> repairStatusLog(@PathVariable("missionId") String missionId, @RequestBody(required = false) byte[] body) {
    try {
      return checkedRepair(missionId, body);
    } catch (RuntimeException unexpected) {
      LOG.log(System.Logger.Level.WARNING,
          FailureRecord.describe("repairStatusLog", RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED.name(), unexpected));
      return refuse(RefusalBody.of(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, UNEXPECTED_DETAIL));
    }
  }

  private ResponseEntity<byte[]> checkedRepair(String missionId, byte[] body) {
    if (lifecycle.draining()) return refuse(RefusalBody.of(RefusalCode.SERVICE_DRAINING));
    var invalidMission = bodies.missionId(missionId);
    if (invalidMission.isPresent()) return malformed(invalidMission.orElseThrow());
    WriteBodies.ParsedRepair parsed = bodies.parseRepair(body);
    if (parsed instanceof WriteBodies.ParsedRepair.Failure failure) return malformed(failure.malformed());
    RepairOutcome outcome = repairs.repair(missionId, ((WriteBodies.ParsedRepair.Success) parsed).context());
    if (outcome instanceof RepairOutcome.Refused refused) return refuse(RefusalBody.of(refused.code(), refused.detail()));
    return acknowledge((RepairOutcome.Repaired) outcome);
  }

  private ResponseEntity<byte[]> acknowledge(RepairOutcome.Repaired repaired) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes(repaired));
  }

  private byte[] bytes(RepairOutcome.Repaired repaired) {
    return (mapper.writeValueAsString(members(repaired)) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  static Map<String, Object> members(RepairOutcome.Repaired repaired) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repaired", repaired.action() != Action.NONE);
    body.put("action", repaired.action().name().toLowerCase(Locale.ROOT));
    body.put("movedBytes", repaired.movedBytes());
    body.put("quarantinedFile", repaired.action() == Action.QUARANTINED ? QUARANTINE_FILE : null);
    body.put("readableAfterRepair", repaired.readableAfterRepair());
    return body;
  }

  private ResponseEntity<byte[]> malformed(WriteBodies.Malformed malformed) {
    return refuse(RefusalBody.of(RefusalCode.REQUEST_MALFORMED, malformed.detail()));
  }

  private ResponseEntity<byte[]> refuse(RefusalBody refusal) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(refusal.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (RefusalCode.SERVICE_BUSY.name().equals(refusal.code())) response.header(HttpHeaders.RETRY_AFTER, "1");
    return response.body(refusal.bytes(mapper));
  }
}
