package org.speckitty.commandcli.application.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /** Holds the turn on a thread until released; returns the release handle. */
  private static CountDownLatch holdOnAThread(WriteTurn turn) throws InterruptedException {
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread.ofPlatform().daemon().start(() -> {
      try (var ignored = turn.tryAcquire(0).orElseThrow()) {
        held.countDown();
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
    return release;
  }

  @Test
  void aHeldTurnWithoutAnAbandonSignalIsTimedOut() throws Exception {
    WriteTurn turn = new WriteTurn();
    CountDownLatch release = holdOnAThread(turn);
    try {
      long started = System.nanoTime();
      WriteTurn.Acquisition result = turn.acquire(Duration.ofMillis(120).toNanos(), () -> false);

      assertThat(result).isInstanceOf(WriteTurn.TimedOut.class);
      assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(Duration.ofMillis(100).toNanos());
    } finally {
      release.countDown();
    }
  }

  @Test
  void aWaiterWhoseAbandonSignalRisesStopsWaitingWithinASlice() throws Exception {
    WriteTurn turn = new WriteTurn();
    CountDownLatch release = holdOnAThread(turn);
    try {
      AtomicBoolean abandon = new AtomicBoolean();
      java.util.concurrent.CompletableFuture<WriteTurn.Acquisition> waiter = java.util.concurrent.CompletableFuture.supplyAsync(
          () -> turn.acquire(Duration.ofSeconds(30).toNanos(), abandon::get));
      Thread.sleep(150);
      assertThat(waiter.isDone()).as("still waiting").isFalse();

      long raised = System.nanoTime();
      abandon.set(true);
      WriteTurn.Acquisition result = waiter.get(5, TimeUnit.SECONDS);

      assertThat(result).isInstanceOf(WriteTurn.Abandoned.class);
      // One 50 ms slice is the design bound; the assertion allows scheduler noise on loaded CI runners, within D10's 1 s answer.
      assertThat(System.nanoTime() - raised).isLessThan(Duration.ofMillis(500).toNanos());
    } finally {
      release.countDown();
    }
  }

  @Test
  void aFreeTurnIsTakenEvenWhenTheAbandonSignalIsAlreadyUpAndAHolderKeepsItUntilClosed() throws Exception {
    WriteTurn turn = new WriteTurn();

    WriteTurn.Acquisition result = turn.acquire(Duration.ofSeconds(1).toNanos(), () -> true);

    assertThat(result).isInstanceOf(WriteTurn.Acquired.class);
    try (var held = ((WriteTurn.Acquired) result).turn()) {
      assertThat(held).isNotNull();
      java.util.concurrent.CompletableFuture<WriteTurn.Acquisition> other = java.util.concurrent.CompletableFuture.supplyAsync(
          () -> turn.acquire(Duration.ofMillis(60).toNanos(), () -> false));
      assertThat(other.get(5, TimeUnit.SECONDS)).as("the holder was not dropped").isInstanceOf(WriteTurn.TimedOut.class);
    }
    assertThat(turn.acquire(0, () -> false)).isInstanceOf(WriteTurn.Acquired.class);
  }
}
