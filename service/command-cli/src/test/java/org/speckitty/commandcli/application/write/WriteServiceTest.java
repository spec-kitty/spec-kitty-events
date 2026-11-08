package org.speckitty.commandcli.application.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.domain.authority.Authority;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowEncoder;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogRowCodec;

/** FR-026 step tests with recording in-memory ports. */
class WriteServiceTest {
  private static final String MISSION = "01J6XW9KQT7M0YB3N4R5CQZ2EX";
  private static final String ROOT = "kitty-specs/m";
  private static final MissionRef REF = new MissionRef(MISSION, ROOT);

  @Test
  void malformedRowStopsBeforeMissionLookup() {
    Ports ports = new Ports();
    Map<String, Object> row = row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor");
    row.put("n", Double.NaN);
    WriteOutcome outcome = ports.service().write(request(List.of(unit(row))));
    assertRefusal(outcome, RefusalCode.REQUEST_MALFORMED, null);
    assertThat(ports.missions.findCalls).isZero();
  }

  @Test
  void coordinationSurfacePrecedesAnUnknownMission() {
    Ports ports = new Ports();
    ports.missions.found = new MissionCatalog.NotFound();
    WriteRequest request = request(List.of(unit(row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor"))));
    request = new WriteRequest(MISSION, WriteRequest.Kind.UNIT, request.units(),
        new WriteRequest.Context("actor", ".", ROOT, "coordination_ref_commit", "refs/heads/m"));
    WriteOutcome outcome = ports.service().write(request);
    assertRefusal(outcome, RefusalCode.WRITE_LOCATION_UNVERIFIED, null);
    assertThat(((WriteOutcome.Refused) outcome).detail()).contains("migration step 4");
    assertThat(ports.missions.findCalls).isZero();
  }

  @Test
  void missingMissionAndUnverifiableRootStopBeforeLaterPorts() {
    Ports missing = new Ports();
    missing.missions.found = new MissionCatalog.NotFound();
    assertRefusal(missing.service().write(validRequest()), RefusalCode.MISSION_NOT_FOUND, null);
    assertThat(missing.log.calls).isZero();
    Ports root = new Ports();
    root.missions.found = new MissionCatalog.RootUnverifiable();
    assertRefusal(root.service().write(validRequest()), RefusalCode.WRITE_LOCATION_UNVERIFIED, null);
    assertThat(root.log.calls).isZero();
  }

  @Test
  void locationAndWorkPackageRefusalsPrecedeTheTurn() {
    Ports location = new Ports();
    location.missions.location = false;
    assertRefusal(location.service().write(validRequest()), RefusalCode.WRITE_LOCATION_UNVERIFIED, null);
    assertThat(location.missions.wpCalls).isZero();
    Ports wp = new Ports();
    wp.missions.wpExists = false;
    assertRefusal(wp.service().write(validRequest()), RefusalCode.WP_NOT_FOUND, null);
    assertThat(wp.log.calls).isZero();
  }

  @Test
  void duplicateRequestIdPrecedesTheTurnAndNamesTheBatchUnit() {
    Ports ports = new Ports();
    Map<String, Object> first = row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor");
    WriteOutcome outcome = ports.service().write(new WriteRequest(MISSION, WriteRequest.Kind.BATCH,
        List.of(unit(first), unit(first)), context("checkout_append")));
    assertRefusal(outcome, RefusalCode.BOOKKEEPING_DOUBLE_EVENT_ID, 1);
    assertThat(ports.log.calls).isZero();
  }

  @Test
  void tornOrUnreducibleLogsAndLockTimeoutsAreNotWritten() {
    Ports unreducible = new Ports();
    unreducible.log.log = "{invalid\n".getBytes(StandardCharsets.UTF_8);
    assertRefusal(unreducible.service().write(validRequest()), RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, null);
    assertThat(unreducible.log.appendCalls).isZero();
    Ports held = new Ports();
    held.log.held = true;
    assertRefusal(held.service().write(validRequest()), RefusalCode.STATUS_LOCK_HELD, null);
    assertThat(held.log.appendCalls).isZero();
  }

  @Test
  void aLegalSingleAppendIsAcknowledgedOnce() {
    Ports ports = new Ports();
    WriteOutcome.Acked ack = (WriteOutcome.Acked) ports.service().write(validRequest());
    assertThat(ack.units().getFirst().appendedLines()).isEqualTo(1);
    assertThat(ack.units().getFirst().replayed()).isFalse();
    assertThat(ack.fsyncs()).isEqualTo(1);
    assertThat(ports.log.appendCalls).isOne();
  }

  @Test
  void aBatchUsesOneAppendAndAnAllReplayedBatchUsesNone() {
    Ports ports = new Ports();
    List<WriteRequest.UnitRequest> units = List.of(
        unit(row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor")),
        unit(row("01J6XW9KQT7M0YB3N4R5CQZ2EZ", "WP01", "claimed", "in_progress", "actor")));
    WriteOutcome.Acked ack = (WriteOutcome.Acked) ports.service().write(new WriteRequest(MISSION,
        WriteRequest.Kind.BATCH, units, context("checkout_append")));
    assertThat(ack.fsyncs()).isEqualTo(1);
    assertThat(ports.log.appendCalls).isOne();
    Ports replay = new Ports();
    replay.log.log = concat(replay.log.log, encode(units.get(0).rows().getFirst()), encode(units.get(1).rows().getFirst()));
    WriteOutcome.Acked replayed = (WriteOutcome.Acked) replay.service().write(new WriteRequest(MISSION,
        WriteRequest.Kind.BATCH, units, context("checkout_append")));
    assertThat(replayed.fsyncs()).isZero();
    assertThat(replay.log.appendCalls).isZero();
  }

  @Test
  void authorityAndPersistenceFailuresNeverAcknowledge() {
    Ports illegal = new Ports();
    WriteOutcome outcome = illegal.service().write(request(List.of(unit(
        row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "done", "actor")))));
    assertRefusal(outcome, RefusalCode.TRANSITION_REJECTED, null);
    assertThat(illegal.log.appendCalls).isZero();
    Ports failed = new Ports();
    failed.log.persistenceFails = true;
    assertRefusal(failed.service().write(validRequest()), RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, null);
  }

  private static WriteRequest validRequest() {
    return request(List.of(unit(row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor"))));
  }

  private static WriteRequest request(List<WriteRequest.UnitRequest> units) {
    return new WriteRequest(MISSION, WriteRequest.Kind.UNIT, units, context("checkout_append"));
  }

  private static WriteRequest.Context context(String surface) {
    return new WriteRequest.Context("actor", ".", ROOT, surface,
        surface.equals("coordination_ref_commit") ? "refs/heads/m" : null);
  }

  private static WriteRequest.UnitRequest unit(Map<String, Object> row) {
    return new WriteRequest.UnitRequest((String) row.get("wp_id"), WriteRequest.Operation.TRANSITION,
        List.of(new WriteRequest.Row((String) row.get("event_id"), row)));
  }

  static Map<String, Object> row(String id, String wp, String from, String to, String actor) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("event_id", id);
    row.put("wp_id", wp);
    row.put("from_lane", from);
    row.put("to_lane", to);
    row.put("at", "2026-01-01T00:00:00+00:00");
    row.put("actor", actor);
    row.put("force", false);
    row.put("execution_mode", "worktree");
    row.put("reason", null);
    row.put("review_ref", null);
    row.put("evidence", null);
    row.put("policy_metadata", null);
    return row;
  }

  private static void assertRefusal(WriteOutcome outcome, RefusalCode code, Integer index) {
    assertThat(outcome).isInstanceOf(WriteOutcome.Refused.class);
    WriteOutcome.Refused refused = (WriteOutcome.Refused) outcome;
    assertThat(refused.code()).isEqualTo(code);
    assertThat(refused.unitIndex()).isEqualTo(index);
  }

  private static byte[] encode(WriteRequest.Row row) {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>) row.value();
    return StatusRowEncoder.encodeLine(value);
  }

  private static byte[] concat(byte[]... arrays) {
    int size = Arrays.stream(arrays).mapToInt(array -> array.length).sum();
    byte[] out = new byte[size];
    int offset = 0;
    for (byte[] array : arrays) {
      System.arraycopy(array, 0, out, offset, array.length);
      offset += array.length;
    }
    return out;
  }

  private static final class Ports {
    final FakeMissions missions = new FakeMissions();
    final FakeLog log = new FakeLog();
    final RowCodec codec = new EventLogRowCodec();

    Ports() {
      log.log = encode(new WriteRequest.Row("00000000000000000000000001",
          row("00000000000000000000000001", "WP01", "genesis", "planned", "actor")));
    }

    WriteService service() {
      return new WriteService(missions, log, codec, new WriteTurn(), Authority.standard(), Duration.ofMillis(200), System::nanoTime);
    }
  }

  private static final class FakeMissions implements MissionCatalog {
    FindResult found = new Found(REF);
    boolean location = true;
    boolean wpExists = true;
    int findCalls;
    int wpCalls;

    @Override public FindResult find(String missionId) { findCalls++; return found; }
    @Override public boolean locationMatches(MissionRef mission, String checkout, String directory, String destination) { return location; }
    @Override public boolean wpExists(MissionRef mission, String wpId) { wpCalls++; return wpExists; }
    @Override public MissionFacts facts(MissionRef mission) {
      return new MissionFacts() {
        @Override public DependencyDeclaration dependencies(String wpId) { return new DependencyDeclaration.None(); }
        @Override public List<String> subtaskRoster(String wpId) { return List.of(); }
      };
    }
  }

  private static final class FakeLog implements StatusLog {
    byte[] log;
    int calls;
    int appendCalls;
    boolean held;
    boolean persistenceFails;

    @Override
    public <T> LockResult<T> withLock(MissionRef mission, long deadline, SessionWork<T> work) {
      calls++;
      if (held) return new LockResult.LockHeld<>();
      if (persistenceFails) return new LockResult.PersistenceFailed<>();
      T result = work.run(new Session() {
        @Override public byte[] currentBytes() { return log; }
        @Override public void appendAndSync(byte[] bytes) {
          appendCalls++;
          log = concat(log, bytes);
        }
      });
      return new LockResult.Completed<>(result);
    }
  }
}
