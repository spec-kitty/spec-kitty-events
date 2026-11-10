package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.domain.service.ServiceState;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.speckitty.commandcli.adapter.in.http.edge.EdgeRig;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The drain over HTTP (spec FR-009, FR-011; LAUNCHER.md "Stop"): a shutdown answers 202 and stops the process once; while the service
 * drains, the health reads draining and every write is refused 503 SERVICE_DRAINING without Retry-After, before its shape is judged, with
 * nothing written; a request in flight holds the stop; a refused stranger is not counted. The process exit is recorded by the rig.
 */
class DrainTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BASE = "/api/v1/missions/01J6XW9KQT7M0YB3N4R5CQZ2EX";

  private static void await(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(20);
    }
  }

  private static RawClient.Reply shutdown(EdgeRig rig) {
    return rig.request("POST /api/v1/shutdown HTTP/1.1", "", rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 1");
  }

  @Test
  void aShutdownAnswers202ThenStopsTheProcessOnceWithExitZero() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      RawClient.Reply reply = shutdown(rig);

      assertThat(reply.status()).isEqualTo(202);
      assertThat(reply.body()).isEmpty();
      await("the exit", () -> !rig.exits.isEmpty());
      Thread.sleep(200);
      assertThat(rig.exits).isEqualTo(List.of(0));
      assertThat(rig.context().isActive()).isFalse();
    }
  }

  @Test
  void aDrainingServiceReportsDrainingAndRefusesEveryWriteBeforeItsShape() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      rig.context().getBean(ServiceLifecycle.class).beginDrain();
      List<String> before = rig.tree();

      RawClient.Reply health = rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer());
      assertThat(JSON.readTree(health.body()).get("status").asString()).isEqualTo("draining");
      assertThat(ContractSchemas.violations("Health", health.body())).isEmpty();

      for (String path : List.of(BASE + "/work-package-transitions", BASE + "/work-package-transitions/batch", BASE + "/inner-state-annotations",
          BASE + "/status-log-repairs")) {
        for (String body : List.of("{}", "not json")) {
          RawClient.Reply write =
              rig.request("POST " + path + " HTTP/1.1", body, rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 1", "Content-Type: application/json");
          assertThat(write.status()).as(path + " " + body).isEqualTo(503);
          assertThat(write.head()).doesNotContainIgnoringCase("Retry-After");
          JsonNode refusal = JSON.readTree(write.body());
          assertThat(refusal.get("code").asString()).isEqualTo("SERVICE_DRAINING");
          assertThat(ContractSchemas.violations("WriteRefusal", write.body())).isEmpty();
        }
      }
      assertThat(rig.tree()).isEqualTo(before);
      assertThat(rig.exits).as("draining alone stops nothing").isEmpty();
    }
  }

  @Test
  void aRequestInFlightHoldsTheStopAndASecondShutdownStartsNothingNew() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      ServiceLifecycle lifecycle = rig.context().getBean(ServiceLifecycle.class);
      lifecycle.enter(); // a request the service holds

      assertThat(shutdown(rig).status()).isEqualTo(202);
      assertThat(shutdown(rig).status()).isEqualTo(202);
      Thread.sleep(300);
      assertThat(rig.exits).as("held by the request in flight").isEmpty();
      assertThat(JSON.readTree(rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer()).body()).get("status").asString()).isEqualTo("draining");

      lifecycle.exit();
      await("the exit", () -> !rig.exits.isEmpty());
      Thread.sleep(200);
      assertThat(rig.exits).isEqualTo(List.of(0));
    }
  }

  @Test
  void aRefusedStrangerIsNotCountedAsActivity() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      ServiceLifecycle lifecycle = rig.context().getBean(ServiceLifecycle.class);
      rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer());
      Thread.sleep(300);
      Duration idleBefore = lifecycle.idleFor();

      rig.request("GET /api/v1/health HTTP/1.1", "", "Host: evil.example:" + rig.port(), rig.bearer());
      rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), "Authorization: Bearer wrong");
      rig.request("POST /api/v1/shutdown HTTP/1.1", "", rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 9");

      assertThat(lifecycle.idleFor()).as("refusals do not reset the idle clock").isGreaterThanOrEqualTo(idleBefore);
      assertThat(lifecycle.inFlight()).isZero();
      assertThat(lifecycle.draining()).as("a refused shutdown does not drain").isFalse();

      rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer());
      assertThat(lifecycle.idleFor()).as("an accepted request does").isLessThan(idleBefore);
    }
  }

  // ---- D10: a request still waiting when the drain starts answers 503 SERVICE_DRAINING within 1 s; a holder finishes ----

  private static final Duration ONE_SECOND = Duration.ofSeconds(1);

  /** A started HTTP call and the moment it was sent. */
  private record Call(CompletableFuture<RawClient.Reply> reply) {
    static Call start(Supplier<RawClient.Reply> request) {
      return new Call(CompletableFuture.supplyAsync(request));
    }
  }

  private static Path missionLock(WriteRig rig) throws Exception {
    Path lock = rig.gitCommonDir.resolve("spec-kitty-locks/mission.status.lock");
    Files.createDirectories(lock.getParent());
    return lock;
  }

  /**
   * Observes, deterministically, that a request is blocked on the Mission lock: the store consults the abandon signal only after a
   * failed lock attempt, so its first call proves the request passed the controller's draining check and is waiting on the lock.
   */
  private static final class WaitingLog implements BeanPostProcessor {
    final CountDownLatch waiting = new CountDownLatch(1);

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
      return bean instanceof StatusLog log ? new Observed(log) : bean;
    }

    private final class Observed implements StatusLog {
      private final StatusLog delegate;

      Observed(StatusLog delegate) {
        this.delegate = delegate;
      }

      @Override
      public <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, SessionWork<T> work) {
        return withLock(mission, deadlineNanos, () -> false, work);
      }

      @Override
      public <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, BooleanSupplier abandon, SessionWork<T> work) {
        return delegate.withLock(mission, deadlineNanos, () -> {
          waiting.countDown();
          return abandon.getAsBoolean();
        }, work);
      }
    }
  }

  private static WriteRig waitingRig(WaitingLog log) {
    return WriteRig.start(null, context -> context.getBeanFactory().addBeanPostProcessor(log));
  }

  private static void awaitLockWait(WaitingLog log) throws InterruptedException {
    assertThat(log.waiting.await(10, TimeUnit.SECONDS)).as("the request is blocked on the Mission lock").isTrue();
  }

  private static RawClient.Reply shutdown(WriteRig rig) {
    return rig.request("POST /api/v1/shutdown HTTP/1.1", "", rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 1");
  }

  private static void assertDraining(RawClient.Reply reply) {
    assertThat(reply.status()).as(reply.text()).isEqualTo(503);
    assertThat(reply.head()).doesNotContainIgnoringCase("Retry-After");
    assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("SERVICE_DRAINING");
    assertThat(ContractSchemas.violations("WriteRefusal", reply.body())).isEmpty();
  }

  /** The drain began at {@code shutdownAt}: the stop must follow inside the 5 s drain budget, with nothing left in flight. */
  private static void awaitStopped(ServiceLifecycle lifecycle, long shutdownAt) throws InterruptedException {
    long deadline = shutdownAt + ServiceLifecycle.DRAIN_BUDGET.toNanos();
    while (lifecycle.state() != ServiceState.STOPPED) {
      if (System.nanoTime() > deadline) throw new AssertionError("the drain did not finish inside its budget: " + lifecycle.state());
      Thread.sleep(10);
    }
    assertThat(lifecycle.inFlight()).as("stopped because nothing was in flight, not because the budget ran out").isZero();
  }

  @Test
  void drainBudgetStaysFiveSecondsAsTheLauncherContractSaysD10() {
    assertThat(ServiceLifecycle.DRAIN_BUDGET).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void aWriteWaitingOnTheMissionLockAnswersDrainingWithinASecondAndWritesNothing() throws Exception {
    WaitingLog observed = new WaitingLog();
    try (WriteRig rig = waitingRig(observed)) {
      ServiceLifecycle lifecycle = rig.bean(ServiceLifecycle.class);
      byte[] before = Files.readAllBytes(rig.log());
      try (LedgerLock.Held ignored = LedgerLocks.forThisOs().acquire(missionLock(rig), Duration.ofSeconds(2))) {
        Call write = Call.start(() -> rig.post("unit", WriteRig.unit(WriteRig.claimRow("00000000000000000000000002"))));
        awaitLockWait(observed);

        long started = System.nanoTime();
        assertThat(shutdown(rig).status()).isEqualTo(202);
        RawClient.Reply reply = write.reply().get(5, TimeUnit.SECONDS);
        Duration answered = Duration.ofNanos(System.nanoTime() - started);

        assertDraining(reply);
        assertThat(answered).as("the waiter stopped waiting").isLessThan(ONE_SECOND);
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
        awaitStopped(lifecycle, started);
      }
    }
  }

  @Test
  void withoutAShutdownTheSameWaitingWriteAnswersLockHeldAfterTheBudget() throws Exception {
    try (WriteRig rig = WriteRig.start(Duration.ofMillis(800))) {
      byte[] before = Files.readAllBytes(rig.log());
      try (LedgerLock.Held ignored = LedgerLocks.forThisOs().acquire(missionLock(rig), Duration.ofSeconds(2))) {
        RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow("00000000000000000000000002")));

        assertThat(reply.status()).as(reply.text()).isEqualTo(409);
        assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("STATUS_LOCK_HELD");
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      }
    }
  }

  @Test
  void aRepairWaitingOnTheMissionLockAnswersDrainingWithinASecondAndWritesNothing() throws Exception {
    WaitingLog observed = new WaitingLog();
    try (WriteRig rig = waitingRig(observed)) {
      ServiceLifecycle lifecycle = rig.bean(ServiceLifecycle.class);
      byte[] before = Files.readAllBytes(rig.log());
      try (LedgerLock.Held ignored = LedgerLocks.forThisOs().acquire(missionLock(rig), Duration.ofSeconds(2))) {
        Call repair = Call.start(rig::repair);
        awaitLockWait(observed);

        long started = System.nanoTime();
        assertThat(shutdown(rig).status()).isEqualTo(202);
        RawClient.Reply reply = repair.reply().get(5, TimeUnit.SECONDS);
        Duration answered = Duration.ofNanos(System.nanoTime() - started);

        assertDraining(reply);
        assertThat(answered).isLessThan(ONE_SECOND);
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
        assertThat(Files.exists(rig.quarantine())).isFalse();
        awaitStopped(lifecycle, started);
      }
    }
  }

  /** Holds every Mission-lock session open from inside, once armed, until released: a write that is then a true holder. */
  private static final class HoldingLog implements BeanPostProcessor {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
      return bean instanceof StatusLog log ? new Holding(log) : bean;
    }

    private final class Holding implements StatusLog {
      private final StatusLog delegate;

      Holding(StatusLog delegate) {
        this.delegate = delegate;
      }

      @Override
      public <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, SessionWork<T> work) {
        return withLock(mission, deadlineNanos, () -> false, work);
      }

      @Override
      public <T> LockResult<T> withLock(MissionRef mission, long deadlineNanos, BooleanSupplier abandon, SessionWork<T> work) {
        return delegate.withLock(mission, deadlineNanos, abandon, session -> {
          entered.countDown();
          try {
            release.await(15, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return work.run(session);
        });
      }
    }
  }

  private static WriteRig holdingRig(HoldingLog hold) {
    return WriteRig.start(null, context -> context.getBeanFactory().addBeanPostProcessor(hold));
  }

  @Test
  void aWriteWaitingOnTheTurnAnswersDrainingWhileTheHolderFinishesWithItsAck() throws Exception {
    HoldingLog hold = new HoldingLog();
    try (WriteRig rig = holdingRig(hold)) {
      ServiceLifecycle lifecycle = rig.bean(ServiceLifecycle.class);
      Call holder = Call.start(() -> rig.post("unit", WriteRig.unit(WriteRig.claimRow("00000000000000000000000002"))));
      assertThat(hold.entered.await(10, TimeUnit.SECONDS)).as("the holder is inside the lock").isTrue();
      Call waiter = Call.start(() -> rig.post("annotation",
          WriteRig.annotation("WP02", WriteRig.annotationRow("00000000000000000000000003", "WP02", "waits"))));
      await("the second request to queue for the turn", () -> rig.turn().hasWaiters());

      long started = System.nanoTime();
      assertThat(shutdown(rig).status()).isEqualTo(202);
      RawClient.Reply refused = waiter.reply().get(5, TimeUnit.SECONDS);
      Duration answered = Duration.ofNanos(System.nanoTime() - started);

      assertDraining(refused);
      assertThat(answered).isLessThan(ONE_SECOND);
      assertThat(holder.reply().isDone()).as("the holder is never interrupted").isFalse();
      assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.priorPlannedLine());

      hold.release.countDown();
      RawClient.Reply acknowledged = holder.reply().get(5, TimeUnit.SECONDS);
      assertThat(acknowledged.status()).as(acknowledged.text()).isEqualTo(201);
      assertThat(Files.readString(rig.log()))
          .isEqualTo(WriteRig.priorPlannedLine() + WriteRig.expectedClaimLine("00000000000000000000000002"));
      awaitStopped(lifecycle, started);
    }
  }

  @Test
  void aWriteHoldingTheLockWhenTheShutdownArrivesFinishesAndItsBytesAreInTheLog() throws Exception {
    HoldingLog hold = new HoldingLog();
    try (WriteRig rig = holdingRig(hold)) {
      ServiceLifecycle lifecycle = rig.bean(ServiceLifecycle.class);
      Call holder = Call.start(() -> rig.post("unit", WriteRig.unit(WriteRig.claimRow("00000000000000000000000002"))));
      assertThat(hold.entered.await(10, TimeUnit.SECONDS)).isTrue();

      long started = System.nanoTime();
      assertThat(shutdown(rig).status()).isEqualTo(202);
      Thread.sleep(300);
      assertThat(holder.reply().isDone()).isFalse();
      assertThat(lifecycle.state()).as("the drain waits for the holder").isEqualTo(ServiceState.DRAINING);

      hold.release.countDown();
      RawClient.Reply reply = holder.reply().get(5, TimeUnit.SECONDS);

      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      assertThat(Files.readString(rig.log()))
          .isEqualTo(WriteRig.priorPlannedLine() + WriteRig.expectedClaimLine("00000000000000000000000002"));
      awaitStopped(lifecycle, started);
    }
  }
}
