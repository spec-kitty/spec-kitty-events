package org.speckitty.commandcli.application.repair;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogRowCodec;
import org.speckitty.commandcli.application.write.WriteRequest;
import org.speckitty.commandcli.application.write.WriteTurn;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.SubtaskRoster;
import org.speckitty.commandcli.domain.service.RefusalCode;

/** The repair service against fakes: the {@code isCompleteRow} decision table, the order of its steps and every way it can refuse. */
class StatusLogRepairServiceTest {

  private static final MissionRef REF = new MissionRef("01J6XW9KQT7M0YB3N4R5CQZ2EX", "kitty-specs/mission");
  private static final WriteRequest.Context CONTEXT = new WriteRequest.Context("java-jenny", ".", "kitty-specs/mission", "checkout_append", null);
  private static final String PRIOR = row("00000000000000000000000001", "genesis", "planned") + "\n";
  private static final String NEXT = row("00000000000000000000000002", "planned", "claimed");

  private static String row(String id, String from, String to) {
    return "{\"actor\": \"java-jenny\", \"at\": \"2026-11-07T09:00:00+00:00\", \"event_id\": \"" + id + "\", \"evidence\": null, "
        + "\"execution_mode\": \"direct_repo\", \"force\": false, \"from_lane\": \"" + from + "\", \"policy_metadata\": null, "
        + "\"reason\": null, \"review_ref\": null, \"to_lane\": \"" + to + "\", \"wp_id\": \"WP01\"}";
  }

  private static String numericId(String row, String id) {
    return row.replace("\"event_id\": \"x\"", "\"event_id\": " + id);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static List<Arguments> decisions() {
    String unknownKind = "{\"event_id\": \"00000000000000000000000009\", \"kind\": \"unknown\"}\n";
    return List.of(
        Arguments.of("a whole foldable row", PRIOR, NEXT, true),
        Arguments.of("a whole genesis row on an empty log", "", row("00000000000000000000000001", "genesis", "planned"), true),
        Arguments.of("a row with an event_id an earlier row has", PRIOR, row("00000000000000000000000001", "planned", "claimed"), false),
        Arguments.of("a row whose numeric event_id equals an earlier one under Python equality (1 == 1.0)",
            numericId(row("x", "genesis", "planned"), "1") + "\n", numericId(row("x", "planned", "claimed"), "1.0"), false),
        Arguments.of("a row whose numeric event_id differs from the earlier one", numericId(row("x", "genesis", "planned"), "1") + "\n",
            numericId(row("x", "planned", "claimed"), "2"), true),
        Arguments.of("a row the fold rejects in context", PRIOR, "{\"event_id\": \"00000000000000000000000003\", \"kind\": \"unknown\"}", false),
        Arguments.of("a whole row after an unreducible prefix", PRIOR + unknownKind, NEXT, false),
        Arguments.of("a prefix that does not decode", PRIOR + "{bad\n", NEXT, false),
        Arguments.of("a mid-JSON fragment", PRIOR, "{\"event_id\": \"0000000000000", false),
        Arguments.of("an empty object", PRIOR, "{}", false),
        Arguments.of("an array", PRIOR, "[" + NEXT + "]", false),
        Arguments.of("a scalar", PRIOR, "42", false),
        Arguments.of("two concatenated objects", PRIOR, NEXT + NEXT.replace("0002", "0003"), false),
        Arguments.of("trailing bytes after one object", PRIOR, NEXT + " x", false),
        Arguments.of("a blank fragment", PRIOR, "   ", false),
        Arguments.of("a fragment with a raw line separator", PRIOR, NEXT.replace("java-jenny", "java jenny"), false));
  }

  @ParameterizedTest(name = "{0} is complete: {3}")
  @MethodSource("decisions")
  void aFragmentIsCompleteOnlyWhenItIsOneRowThatFoldsAfterThePrefix(String name, String prefix, String fragment, boolean complete) {
    assertThat(new Rig().service().isCompleteRow(bytes(prefix), bytes(fragment))).isEqualTo(complete);
  }

  @Test
  void aFragmentOfInvalidUtf8IsNotAComplete() {
    byte[] fragment = bytes(NEXT.replace("java-jenny", "java-jennyé"));
    fragment[fragment.length - 3] = (byte) 0xff;

    assertThat(new Rig().service().isCompleteRow(bytes(PRIOR), fragment)).isFalse();
  }

  @Test
  void aCodecThatThrowsMakesTheFragmentIncompleteNotAnError() {
    Rig rig = new Rig();
    rig.codec = new ThrowingCodec();

    assertThat(rig.service().isCompleteRow(bytes(PRIOR), bytes(NEXT))).isFalse();
  }

  @Test
  void aCompletedRepairIsReadableByConstructionWithoutLookingAtTheBytes() {
    Rig rig = new Rig();
    rig.result = new StatusLog.RepairResult(StatusLog.RepairResult.Action.COMPLETED, 0, bytes("{bad\n"));

    assertThat(rig.service().repair(REF.missionId(), CONTEXT))
        .isEqualTo(new RepairOutcome.Repaired(StatusLog.RepairResult.Action.COMPLETED, 0, true));
  }

  @Test
  void aQuarantineOrNoneIsReadableWhenTheBytesItLeftDecodeAndFold() {
    for (StatusLog.RepairResult.Action action : List.of(StatusLog.RepairResult.Action.NONE, StatusLog.RepairResult.Action.QUARANTINED)) {
      Rig rig = new Rig();
      rig.result = new StatusLog.RepairResult(action, action == StatusLog.RepairResult.Action.NONE ? 0 : 12, bytes(PRIOR));

      RepairOutcome outcome = rig.service().repair(REF.missionId(), CONTEXT);

      assertThat(outcome).isEqualTo(new RepairOutcome.Repaired(action, action == StatusLog.RepairResult.Action.NONE ? 0 : 12, true));
    }
  }

  @Test
  void anUnreducibleOrUndecodableLogAfterTheRepairIsReportedNotRefused() {
    for (String left : List.of(PRIOR + "{\"event_id\": \"00000000000000000000000009\", \"kind\": \"unknown\"}\n", "{bad\n")) {
      Rig rig = new Rig();
      rig.result = new StatusLog.RepairResult(StatusLog.RepairResult.Action.QUARANTINED, 3, bytes(left));

      assertThat(rig.service().repair(REF.missionId(), CONTEXT))
          .isEqualTo(new RepairOutcome.Repaired(StatusLog.RepairResult.Action.QUARANTINED, 3, false));
    }
    Rig throwing = new Rig();
    throwing.codec = new ThrowingCodec();
    throwing.result = new StatusLog.RepairResult(StatusLog.RepairResult.Action.NONE, 0, bytes(PRIOR));
    assertThat(throwing.service().repair(REF.missionId(), CONTEXT))
        .as("a codec failure in the readability check is a report, never a 500")
        .isEqualTo(new RepairOutcome.Repaired(StatusLog.RepairResult.Action.NONE, 0, false));
  }

  @Test
  void theRepairTakesTheSharedTurnThenTheLockUnderOneDeadlineAndHandsTheStoreTheClassifier() {
    Rig rig = new Rig();
    rig.clock.set(1_000);
    rig.result = new StatusLog.RepairResult(StatusLog.RepairResult.Action.NONE, 0, bytes(PRIOR));

    rig.service().repair(REF.missionId(), CONTEXT);

    assertThat(rig.log.deadline).isEqualTo(1_000 + Duration.ofSeconds(10).toNanos());
    assertThat(rig.log.checked).isTrue();
    assertThat(rig.log.turnHeldInside).as("the turn is held while the Mission lock is taken").isTrue();
    assertThat(takenByAnotherThread(rig.turn)).as("and released afterwards").isFalse();
  }

  @Test
  void aTurnThatStaysTakenIsServiceBusyAndTheLogIsNotAsked() throws Exception {
    Rig rig = new Rig();
    CountDownLatch taken = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = Thread.ofPlatform().start(() -> {
      try (var ignored = rig.turn.tryAcquire(0).orElseThrow()) {
        taken.countDown();
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    assertThat(taken.await(10, TimeUnit.SECONDS)).isTrue();
    try {
      RepairOutcome outcome = rig.service(Duration.ZERO).repair(REF.missionId(), CONTEXT);

      assertThat(outcome).isInstanceOf(RepairOutcome.Refused.class);
      assertThat(((RepairOutcome.Refused) outcome).code()).isEqualTo(RefusalCode.SERVICE_BUSY);
      assertThat(rig.log.calls).isZero();
    } finally {
      release.countDown();
      holder.join();
    }
  }

  @Test
  void aWaitOnTheTurnEndedByTheDrainIsServiceDrainingWithoutAskingTheLog() throws Exception {
    Rig rig = new Rig();
    AtomicBoolean draining = new AtomicBoolean();
    CountDownLatch taken = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = Thread.ofPlatform().start(() -> {
      try (var ignored = rig.turn.tryAcquire(0).orElseThrow()) {
        taken.countDown();
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    assertThat(taken.await(10, TimeUnit.SECONDS)).isTrue();
    try {
      var waiting = java.util.concurrent.CompletableFuture.supplyAsync(
          () -> rig.service(Duration.ofSeconds(30), draining::get).repair(REF.missionId(), CONTEXT));
      Thread.sleep(150);
      assertThat(waiting.isDone()).isFalse();

      draining.set(true);

      RepairOutcome outcome = waiting.get(5, TimeUnit.SECONDS);
      assertThat(outcome).isInstanceOf(RepairOutcome.Refused.class);
      assertThat(((RepairOutcome.Refused) outcome).code()).isEqualTo(RefusalCode.SERVICE_DRAINING);
      assertThat(rig.log.calls).isZero();
    } finally {
      release.countDown();
      holder.join();
    }
  }

  @Test
  void aLockWaitAbandonedByTheStoreIsServiceDrainingAndTheStoreWasGivenTheDrainSignal() {
    Rig rig = new Rig();
    rig.log.outcome = new StatusLog.LockResult.Abandoned<>();
    BooleanSupplier draining = () -> true;

    RepairOutcome outcome = rig.service(Duration.ofSeconds(1), draining).repair(REF.missionId(), CONTEXT);

    assertThat(((RepairOutcome.Refused) outcome).code()).isEqualTo(RefusalCode.SERVICE_DRAINING);
    assertThat(rig.log.abandon).isSameAs(draining);
    assertThat(takenByAnotherThread(rig.turn)).as("the turn was released").isFalse();
  }

  /** The turn is re-entrant, so whether another thread could take it is the only honest probe of "taken". */
  private static boolean takenByAnotherThread(WriteTurn turn) {
    AtomicBoolean taken = new AtomicBoolean();
    Thread probe = Thread.ofPlatform().start(() -> {
      var acquired = turn.tryAcquire(0);
      taken.set(acquired.isEmpty());
      acquired.ifPresent(WriteTurn.Turn::close);
    });
    try {
      probe.join();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return taken.get();
  }

  @Test
  void aLockHeldIsStatusLockHeldAndAFailedStoreIsAPersistenceRefusalWithAFixedDetail() {
    Rig held = new Rig();
    held.log.outcome = new StatusLog.LockResult.LockHeld<>();
    Rig failed = new Rig();
    failed.log.outcome = new StatusLog.LockResult.PersistenceFailed<>();

    RepairOutcome.Refused heldRefusal = (RepairOutcome.Refused) held.service().repair(REF.missionId(), CONTEXT);
    RepairOutcome.Refused failedRefusal = (RepairOutcome.Refused) failed.service().repair(REF.missionId(), CONTEXT);

    assertThat(heldRefusal.code()).isEqualTo(RefusalCode.STATUS_LOCK_HELD);
    assertThat(failedRefusal.code()).isEqualTo(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED);
    assertThat(failedRefusal.detail()).doesNotContain("/").doesNotContain("\\");
    assertThat(takenByAnotherThread(held.turn)).as("the turn is released on every path").isFalse();
    assertThat(takenByAnotherThread(failed.turn)).isFalse();
  }

  @Test
  void anUnexpectedFailureIsACodedPersistenceRefusalThatEchoesNothing() {
    Rig rig = new Rig();
    rig.log.failure = new IllegalStateException("secret-row-value /home/someone/repo");

    RepairOutcome.Refused refusal = (RepairOutcome.Refused) rig.service().repair(REF.missionId(), CONTEXT);

    assertThat(refusal.code()).isEqualTo(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED);
    assertThat(refusal.detail()).doesNotContain("secret").doesNotContain("/home");
    assertThat(takenByAnotherThread(rig.turn)).as("the turn is released").isFalse();
  }

  @Test
  void aStoreWithoutARepairIsACodedRefusalNotAnEscapedException() {
    StatusLog withoutRepair = new StatusLog() {
      @Override
      public <T> LockResult<T> withLock(MissionRef mission, long deadline, SessionWork<T> work) {
        return new LockResult.Completed<>(work.run(new Session() {
          @Override public byte[] currentBytes() { return new byte[0]; }
          @Override public void appendAndSync(byte[] bytes) { }
        }));
      }
    };
    Rig rig = new Rig();

    RepairOutcome outcome = new StatusLogRepairService(rig.missions, withoutRepair, rig.codec, rig.turn, Duration.ofSeconds(1), rig.clock::get)
        .repair(REF.missionId(), CONTEXT);

    assertThat(outcome).isInstanceOf(RepairOutcome.Refused.class);
    assertThat(((RepairOutcome.Refused) outcome).code()).isEqualTo(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED);
  }

  @Test
  void aLocationTheWritePathCannotVerifyIsRefusedBeforeTheTurnAndTheLock() {
    Rig surface = new Rig();
    Rig unknown = new Rig();
    unknown.missions.found = new MissionCatalog.NotFound();
    Rig root = new Rig();
    root.missions.found = new MissionCatalog.RootUnverifiable();
    Rig mismatch = new Rig();
    mismatch.missions.location = false;
    WriteRequest.Context coordination = new WriteRequest.Context("java-jenny", ".", "kitty-specs/mission", "coordination_ref_commit", "kitty/m");

    assertThat(surface.service().repair(REF.missionId(), coordination)).isEqualTo(
        new RepairOutcome.Refused(RefusalCode.WRITE_LOCATION_UNVERIFIED, "coordination_ref_commit is unavailable until migration step 4."));
    assertThat(((RepairOutcome.Refused) unknown.service().repair(REF.missionId(), CONTEXT)).code()).isEqualTo(RefusalCode.MISSION_NOT_FOUND);
    assertThat(((RepairOutcome.Refused) root.service().repair(REF.missionId(), CONTEXT)).code()).isEqualTo(RefusalCode.WRITE_LOCATION_UNVERIFIED);
    assertThat(((RepairOutcome.Refused) mismatch.service().repair(REF.missionId(), CONTEXT)).code()).isEqualTo(RefusalCode.WRITE_LOCATION_UNVERIFIED);
    assertThat(surface.log.calls + unknown.log.calls + root.log.calls + mismatch.log.calls).isZero();
  }

  /** A service over fakes: the store runs the classifier on {@link #fragment} and answers {@link #result}. */
  private static final class Rig {
    final FakeMissions missions = new FakeMissions();
    final FakeLog log = new FakeLog();
    final WriteTurn turn = new WriteTurn();
    final AtomicLong clock = new AtomicLong();
    RowCodec codec = new EventLogRowCodec();
    StatusLog.RepairResult result = new StatusLog.RepairResult(StatusLog.RepairResult.Action.NONE, 0, new byte[0]);

    Rig() {
      log.rig = this;
    }

    StatusLogRepairService service() {
      return service(Duration.ofSeconds(10));
    }

    StatusLogRepairService service(Duration budget) {
      return new StatusLogRepairService(missions, log, codec, turn, budget, clock::get);
    }

    StatusLogRepairService service(Duration budget, BooleanSupplier draining) {
      return new StatusLogRepairService(missions, log, codec, turn, budget, clock::get, draining);
    }
  }

  private static final class FakeMissions implements MissionCatalog {
    FindResult found = new Found(REF);
    boolean location = true;

    @Override public FindResult find(String missionId) { return found; }
    @Override public boolean locationMatches(MissionRef mission, String checkout, String directory, String destination) { return location; }
    @Override public boolean wpExists(MissionRef mission, String wpId) { return true; }
    @Override public MissionFacts facts(MissionRef mission) {
      return new MissionFacts() {
        @Override public DependencyDeclaration dependencies(String wpId) { return new DependencyDeclaration.None(); }
        @Override public SubtaskRoster subtaskRoster(String wpId) { return new SubtaskRoster.Resolved(List.of()); }
      };
    }
  }

  private static final class FakeLog implements StatusLog {
    Rig rig;
    int calls;
    long deadline;
    boolean checked;
    boolean turnHeldInside;
    LockResult<?> outcome;
    RuntimeException failure;
    BooleanSupplier abandon;

    @Override
    public <T> LockResult<T> withLock(MissionRef mission, long deadline, BooleanSupplier abandon, SessionWork<T> work) {
      this.abandon = abandon;
      return withLock(mission, deadline, work);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> LockResult<T> withLock(MissionRef mission, long deadline, SessionWork<T> work) {
      calls++;
      this.deadline = deadline;
      turnHeldInside = takenByAnotherThread(rig.turn);
      if (failure != null) throw failure;
      if (outcome != null) return (LockResult<T>) outcome;
      return new LockResult.Completed<>(work.run(new Session() {
        @Override public byte[] currentBytes() { return new byte[0]; }
        @Override public void appendAndSync(byte[] bytes) { throw new AssertionError("a repair never appends"); }
        @Override public RepairResult repairTail(TailCheck check) {
          checked = check != null;
          return rig.result;
        }
      }));
    }
  }

  private static final class ThrowingCodec implements RowCodec {
    @Override public String refusal(java.util.Map<String, Object> row) { throw new IllegalStateException("codec"); }
    @Override public byte[] encodeLine(java.util.Map<String, Object> row) { throw new IllegalStateException("codec"); }
    @Override public DecodedLog decode(byte[] log) { throw new IllegalStateException("codec"); }
  }
}
