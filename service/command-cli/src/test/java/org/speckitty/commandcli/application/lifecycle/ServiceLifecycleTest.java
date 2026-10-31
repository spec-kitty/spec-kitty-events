package org.speckitty.commandcli.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.commandcli.domain.service.RepositoryId;
import org.speckitty.commandcli.domain.service.ServiceState;

/** The life of an instance: drain once, wait for the requests in flight, stop once; the idle exit; the fail-closed gate. */
@Tag("native")
class ServiceLifecycleTest {

  /** A clock the test moves by hand. */
  static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-10-31T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final ManualClock clock = new ManualClock();
  private final AtomicInteger stops = new AtomicInteger();
  private final ServiceLifecycle lifecycle = new ServiceLifecycle(clock, stops::incrementAndGet);

  @Test
  void aServiceStartsReadyAndDrainsOnce() throws Exception {
    assertThat(lifecycle.state()).isEqualTo(ServiceState.READY);
    assertThat(lifecycle.draining()).isFalse();

    assertThat(lifecycle.beginDrain()).isTrue();
    assertThat(lifecycle.beginDrain()).isFalse();
    assertThat(lifecycle.draining()).isTrue();

    assertThat(lifecycle.finishDrain(Duration.ofSeconds(1), 0)).isTrue();
    assertThat(lifecycle.state()).isEqualTo(ServiceState.STOPPED);
    assertThat(lifecycle.finishDrain(Duration.ofSeconds(1), 0)).isFalse();
    assertThat(stops).hasValue(1);
  }

  @Test
  void finishingADrainThatNeverBeganStopsNothing() throws Exception {
    assertThat(lifecycle.finishDrain(Duration.ofMillis(10), 0)).isFalse();
    assertThat(stops).hasValue(0);
    assertThat(lifecycle.state()).isEqualTo(ServiceState.READY);
  }

  @Test
  void theDrainWaitsForTheRequestsInFlight() throws Exception {
    lifecycle.enter();
    lifecycle.beginDrain();
    CountDownLatch finished = new CountDownLatch(1);
    Thread drain =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    lifecycle.finishDrain(Duration.ofSeconds(10), 0);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  finished.countDown();
                });

    assertThat(finished.await(200, TimeUnit.MILLISECONDS)).as("still waiting for the request").isFalse();
    assertThat(stops).hasValue(0);
    lifecycle.exit();
    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    drain.join();
    assertThat(stops).hasValue(1);
  }

  @Test
  void theDrainStopsAfterItsBudgetEvenWithARequestStillInFlight() throws Exception {
    lifecycle.enter();
    lifecycle.beginDrain();

    long started = System.nanoTime();
    assertThat(lifecycle.finishDrain(Duration.ofMillis(100), 0)).isTrue();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(90));
    assertThat(stops).hasValue(1);
  }

  @Test
  void idlenessIsMeasuredFromTheLastRequestAndIsZeroWhileOneIsInFlight() {
    clock.advance(Duration.ofSeconds(5));
    assertThat(lifecycle.idleFor()).isEqualTo(Duration.ofSeconds(5));

    lifecycle.enter();
    clock.advance(Duration.ofSeconds(100));
    assertThat(lifecycle.idleFor()).isZero();
    assertThat(lifecycle.inFlight()).isEqualTo(1);

    lifecycle.exit();
    clock.advance(Duration.ofSeconds(3));
    assertThat(lifecycle.idleFor()).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void theIdleWatchBeginsTheDrainAtItsTimeoutOnce() {
    IdleWatch watch = new IdleWatch(lifecycle, Duration.ofSeconds(1800));
    assertThat(watch.timeout()).isEqualTo(Duration.ofSeconds(1800));

    clock.advance(Duration.ofSeconds(1799));
    assertThat(watch.tick()).isFalse();
    clock.advance(Duration.ofSeconds(1));
    assertThat(watch.tick()).isTrue();
    assertThat(lifecycle.draining()).isTrue();
    clock.advance(Duration.ofSeconds(1800));
    assertThat(watch.tick()).isFalse();
  }

  @Test
  void theIdleWatchDoesNotFireWhileARequestIsInFlight() {
    IdleWatch watch = new IdleWatch(lifecycle, Duration.ofSeconds(1));
    lifecycle.enter();
    clock.advance(Duration.ofSeconds(10));
    assertThat(watch.tick()).isFalse();
  }

  @Test
  void theIdleTimeoutMustBePositive() {
    assertThatThrownBy(() -> new IdleWatch(lifecycle, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdleWatch(lifecycle, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theGateRefusesEveryWriteFailClosed() {
    WriteGate.Refusal refusal = new WriteGate().persist();

    assertThat(refusal.code()).isEqualTo(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED);
    assertThat(refusal.detail()).isEqualTo(WriteGate.NOT_BUILT);
  }

  @Test
  void theIdentityChecksTheVersionAndListsTheProtocol() {
    InstanceId instance = InstanceId.mint(1L, new SecureRandom());
    RepositoryId repository = new RepositoryId("6b4ca2db35cfaf43d6963005a8445402c4ea6f70b038086ad0d6decda64670c0");

    ServiceIdentity identity = new ServiceIdentity(instance, repository, "0.1.0-rc.1");

    assertThat(identity.protocolVersions()).containsExactly(1);
    assertThatThrownBy(() -> new ServiceIdentity(instance, repository, "-bad")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ServiceIdentity(instance, repository, "x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
  }
}
