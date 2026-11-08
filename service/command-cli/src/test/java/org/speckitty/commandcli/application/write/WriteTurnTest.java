package org.speckitty.commandcli.application.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The fair in-process turn is exclusive, bounded and releasable. */
class WriteTurnTest {
  @Test
  void aHeldTurnTimesOutAndCanBeAcquiredAfterRelease() throws Exception {
    WriteTurn turn = new WriteTurn();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> {
      Optional<WriteTurn.Turn> acquired = turn.tryAcquire(Duration.ofSeconds(1).toNanos());
      assertThat(acquired).isPresent();
      held.countDown();
      try (var ignored = acquired.orElseThrow()) {
        try {
          release.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
    });
    holder.start();
    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
    long started = System.nanoTime();
    assertThat(turn.tryAcquire(Duration.ofMillis(30).toNanos())).isEmpty();
    assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(Duration.ofMillis(20).toNanos());
    release.countDown();
    holder.join(1000);
    try (var ignored = turn.tryAcquire(0).orElseThrow()) {
      assertThat(ignored).isNotNull();
    }
  }

  @Test
  void aWaitingInterruptionRestoresTheInterruptFlag() throws Exception {
    WriteTurn turn = new WriteTurn();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> {
      try (var ignored = turn.tryAcquire(0).orElseThrow()) {
        held.countDown();
        release.await(1, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
    holder.start();
    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
    Thread.currentThread().interrupt();
    assertThat(turn.tryAcquire(Duration.ofSeconds(1).toNanos())).isEmpty();
    assertThat(Thread.interrupted()).isTrue();
    release.countDown();
    holder.join(1000);
  }
}
