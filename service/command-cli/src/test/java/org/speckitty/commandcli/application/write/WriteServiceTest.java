package org.speckitty.commandcli.application.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.domain.authority.Authority;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.UnreducibleLog;
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
    assertThat(ports.missions.locationCalls).isZero();
  }

  @Test
  void malformedRowStillPrecedesCoordinationSurface() {
    Ports ports = new Ports();
    Map<String, Object> row = row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor");
    row.put("n", Double.NaN);
    WriteOutcome outcome = ports.service().write(new WriteRequest(MISSION, WriteRequest.Kind.UNIT, List.of(unit(row)),
        context("coordination_ref_commit")));
    assertRefusal(outcome, RefusalCode.REQUEST_MALFORMED, null);
    assertThat(ports.missions.findCalls).isZero();
    assertThat(ports.missions.locationCalls).isZero();
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
  void locationMismatchPrecedesUnknownWorkPackageAndCountsOneLocationCheck() {
    Ports ports = new Ports();
    ports.missions.location = false;
    ports.missions.wpExists = false;
    assertRefusal(ports.service().write(validRequest()), RefusalCode.WRITE_LOCATION_UNVERIFIED, null);
    assertThat(ports.missions.locationCalls).isOne();
    assertThat(ports.missions.wpCalls).isZero();
  }

  @Test
  void unknownWorkPackagePrecedesDuplicateRequestIds() {
    Ports ports = new Ports();
    ports.missions.wpExists = false;
    Map<String, Object> repeated = row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor");
    WriteRequest request = new WriteRequest(MISSION, WriteRequest.Kind.BATCH, List.of(unit(repeated), unit(repeated)),
        context("checkout_append"));
    assertRefusal(ports.service().write(request), RefusalCode.WP_NOT_FOUND, 0);
    assertThat(ports.missions.locationCalls).isOne();
    assertThat(ports.log.calls).isZero();
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

  @Test
  void aRowTheServiceCannotReadBackIsRefusedBeforeTheAppendAndNeverAcknowledged() {
    for (Object actor : List.<Object>of(Map.of("tool", "x"), nested(300))) {
      Ports ports = new Ports();
      Map<String, Object> bad = row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor");
      bad.put(actor instanceof Map ? "actor" : "policy_metadata", actor);
      byte[] before = ports.log.log;
      WriteOutcome outcome = ports.service().write(request(List.of(unit(bad))));
      assertRefusal(outcome, RefusalCode.REQUEST_MALFORMED, null);
      assertThat(((WriteOutcome.Refused) outcome).detail()).doesNotContain("tool").doesNotContain("[[[");
      assertThat(ports.log.appendCalls).isZero();
      assertThat(ports.log.log).isEqualTo(before);
      assertThat(ports.service().write(validRequest())).isInstanceOf(WriteOutcome.Acked.class);
    }
  }

  @Test
  void anUnreadableUnitOfABatchIsNamedByItsRequestIndexEvenWhenTheAuthorityCannotJudgeTheNextUnit() {
    Ports ports = new Ports();
    Map<String, Object> bad = row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor");
    bad.put("actor", Map.of("tool", "x"));
    Map<String, Object> second = row("01J6XW9KQT7M0YB3N4R5CQZ2EZ", "WP02", "genesis", "planned", "actor");
    WriteOutcome outcome = ports.service().write(new WriteRequest(MISSION, WriteRequest.Kind.BATCH,
        List.of(unit(bad), unit(second)), context("checkout_append")));
    assertRefusal(outcome, RefusalCode.REQUEST_MALFORMED, 0);
    assertThat(ports.log.appendCalls).isZero();
    Map<String, Object> good = row("01J6XW9KQT7M0YB3N4R5CQZ2F0", "WP01", "planned", "claimed", "actor");
    Map<String, Object> unreadableSecond = row("01J6XW9KQT7M0YB3N4R5CQZ2F1", "WP02", "genesis", "planned", "actor");
    unreadableSecond.put("actor", Map.of("tool", "x"));
    WriteOutcome later = ports.service().write(new WriteRequest(MISSION, WriteRequest.Kind.BATCH,
        List.of(unit(good), unit(unreadableSecond)), context("checkout_append")));
    assertRefusal(later, RefusalCode.REQUEST_MALFORMED, 1);
    assertThat(ports.log.appendCalls).isZero();
  }

  @Test
  void aReadableRowStillAppendsExactlyOnceAndTheAppendedBytesAreTheEncodedRow() {
    Ports ports = new Ports();
    Map<String, Object> row = row("01J6XW9KQT7M0YB3N4R5CQZ2EY", "WP01", "planned", "claimed", "actor");
    byte[] before = ports.log.log;
    assertThat(ports.service().write(request(List.of(unit(row))))).isInstanceOf(WriteOutcome.Acked.class);
    assertThat(ports.log.appendCalls).isOne();
    assertThat(ports.log.log).isEqualTo(concat(before, StatusRowEncoder.encodeLine(row)));
  }

  @Test
  void anUnexpectedFailureAnywhereIsACodedRefusalAndTheTurnAndLockAreReleased() {
    Ports ports = new Ports();
    WriteService service = ports.service();
    ports.missions.failure = new IllegalStateException("boom: secret-value");
    WriteOutcome outcome = service.write(validRequest());
    assertRefusal(outcome, RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, null);
    assertThat(((WriteOutcome.Refused) outcome).detail()).doesNotContain("boom").doesNotContain("secret");
    assertThat(ports.log.appendCalls).isZero();
    assertThat(ports.log.locksHeld).isZero();
    ports.missions.failure = null;
    assertThat(service.write(validRequest())).isInstanceOf(WriteOutcome.Acked.class);
  }

  @Test
  void anAuthorityThatCannotReduceReadableUnitsBlamesTheLogNotTheClient() {
    Ports ports = new Ports();
    Authority authority = mock(Authority.class);
    when(authority.judge(anyList(), anyList(), anyBoolean(), any())).thenThrow(new UnreducibleLog("log"));
    WriteService service = new WriteService(ports.missions, ports.log, ports.codec, new WriteTurn(), authority,
        Duration.ofMillis(200), System::nanoTime);
    assertRefusal(service.write(validRequest()), RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, null);
    assertThat(ports.log.appendCalls).isZero();
    assertThat(ports.log.locksHeld).isZero();
  }

  @Test
  void aHeldTurnIsBusyAndTheLockReceivesTheOriginalAbsoluteDeadline() throws Exception {
    Ports ports = new Ports();
    WriteTurn turn = new WriteTurn();
    WriteService service = new WriteService(ports.missions, ports.log, ports.codec, turn, Authority.standard(),
        Duration.ofMillis(200), System::nanoTime);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> {
      try (var ignored = turn.tryAcquire(0).orElseThrow()) {
        held.countDown();
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError(exception);
      }
    });
    holder.start();
    assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
    long before = System.nanoTime();
    assertRefusal(service.write(validRequest()), RefusalCode.SERVICE_BUSY, null);
    long waited = System.nanoTime() - before;
    assertThat(ports.log.calls).isZero();
    // The wait is the budget, not less (a timed wait never returns early) and not the holder's turn: the holder is still inside its turn
    // here and is released only below, so a service that waited for it would hang until the test's timeout. An upper bound in wall time
    // cannot be both tight and free of flakes on every runner; the exact deadline arithmetic is pinned below with a frozen clock, and
    // aHeldTurnIsRefusedAtOnceWhenTheBudgetIsZero pins the no-wait case without any timing.
    assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(150).toNanos());
    release.countDown();
    holder.join(30_000);

    // The deadline is the clock at the start of the write plus the budget. A frozen clock makes that exact, whatever the runner's speed.
    long frozen = 5_000_000_000L;
    WriteService frozenClock = new WriteService(ports.missions, ports.log, ports.codec, new WriteTurn(), Authority.standard(),
        Duration.ofMillis(200), () -> frozen);
    assertThat(frozenClock.write(validRequest())).isInstanceOf(WriteOutcome.Acked.class);
    assertThat(ports.log.deadline).isEqualTo(frozen + Duration.ofMillis(200).toNanos());
  }

  @Test
  @org.junit.jupiter.api.Timeout(30)
  void aHeldTurnIsRefusedAtOnceWhenTheBudgetIsZero() throws Exception {
    Ports ports = new Ports();
    WriteTurn turn = new WriteTurn();
    WriteService service = new WriteService(ports.missions, ports.log, ports.codec, turn, Authority.standard(), Duration.ZERO, System::nanoTime);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> {
      try (var ignored = turn.tryAcquire(0).orElseThrow()) {
        held.countDown();
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError(exception);
      }
    });
    holder.start();
    assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
    // The holder keeps its turn until after the refusal has come back: no timing, only the order of events.
    assertRefusal(service.write(validRequest()), RefusalCode.SERVICE_BUSY, null);
    assertThat(ports.log.calls).isZero();
    release.countDown();
    holder.join(30_000);
    assertThat(service.write(validRequest())).isInstanceOf(WriteOutcome.Acked.class);
  }

  @Test
  void concurrentWritesAreSerializedAndTheSecondSeesTheFirstResult() throws Exception {
    Ports ports = new Ports();
    ports.log.entered = new CountDownLatch(1);
    ports.log.release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    // A budget far above any runner's scheduling delay: the second write must wait for the first's turn, never time out. The busy
    // path has its own test (aHeldTurnIsBusy...) with a short budget.
    WriteService service = ports.service(Duration.ofSeconds(30));
    try {
      Future<WriteOutcome> first = executor.submit(() -> service.write(validRequest()));
      assertThat(ports.log.entered.await(30, TimeUnit.SECONDS)).isTrue();
      Map<String, Object> secondRow = row("01J6XW9KQT7M0YB3N4R5CQZ2EZ", "WP01", "claimed", "in_progress", "actor");
      Future<WriteOutcome> second = executor.submit(() -> service.write(request(List.of(unit(secondRow)))));
      Thread.sleep(40);
      assertThat(second.isDone()).isFalse();
      ports.log.release.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isInstanceOf(WriteOutcome.Acked.class);
      assertThat(second.get(30, TimeUnit.SECONDS)).isInstanceOf(WriteOutcome.Acked.class);
      assertThat(ports.log.appendCalls).isEqualTo(2);
    } finally {
      ports.log.release.countDown();
      executor.shutdownNow();
    }
  }

  private static List<Object> nested(int depth) {
    List<Object> value = new ArrayList<>(List.of(java.math.BigInteger.ONE));
    for (int level = 1; level < depth; level++) value = new ArrayList<>(List.of(value));
    return value;
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
      return service(Duration.ofMillis(200));
    }

    WriteService service(Duration budget) {
      return new WriteService(missions, log, codec, new WriteTurn(), Authority.standard(), budget, System::nanoTime);
    }
  }

  private static final class FakeMissions implements MissionCatalog {
    FindResult found = new Found(REF);
    boolean location = true;
    boolean wpExists = true;
    RuntimeException failure;
    int findCalls;
    int locationCalls;
    int wpCalls;

    @Override public FindResult find(String missionId) { findCalls++; return found; }
    @Override public boolean locationMatches(MissionRef mission, String checkout, String directory, String destination) {
      locationCalls++;
      return location;
    }
    @Override public boolean wpExists(MissionRef mission, String wpId) { wpCalls++; return wpExists; }
    @Override public MissionFacts facts(MissionRef mission) {
      if (failure != null) throw failure;
      return new MissionFacts() {
        @Override public DependencyDeclaration dependencies(String wpId) { return new DependencyDeclaration.None(); }
        @Override public org.speckitty.commandcli.domain.authority.SubtaskRoster subtaskRoster(String wpId) {
          return new org.speckitty.commandcli.domain.authority.SubtaskRoster.Resolved(List.of());
        }
      };
    }
  }

  private static final class FakeLog implements StatusLog {
    byte[] log;
    int calls;
    int appendCalls;
    boolean held;
    boolean persistenceFails;
    long deadline;
    int locksHeld;
    CountDownLatch entered;
    CountDownLatch release;

    @Override
    public <T> LockResult<T> withLock(MissionRef mission, long deadline, SessionWork<T> work) {
      calls++;
      this.deadline = deadline;
      if (entered != null) entered.countDown();
      if (release != null) {
        try {
          if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("test lock was not released");
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AssertionError(exception);
        }
      }
      if (held) return new LockResult.LockHeld<>();
      if (persistenceFails) return new LockResult.PersistenceFailed<>();
      locksHeld++;
      try {
        T result = work.run(new Session() {
          @Override public byte[] currentBytes() { return log; }
          @Override public void appendAndSync(byte[] bytes) {
            appendCalls++;
            log = concat(log, bytes);
          }
        });
        return new LockResult.Completed<>(result);
      } finally {
        locksHeld--;
      }
    }
  }
}
