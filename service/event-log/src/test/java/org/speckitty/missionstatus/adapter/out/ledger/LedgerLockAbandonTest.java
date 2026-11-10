package org.speckitty.missionstatus.adapter.out.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The abandon-aware bounded wait (D10): a waiter whose signal rises stops waiting with {@link LockAbandonedException}, while a lock it
 * can take is taken and handed to the caller whatever the signal says, so a held lock is never dropped.
 */
class LedgerLockAbandonTest {

  private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  @TempDir Path dir;

  static Stream<LedgerLock> locks() {
    return WINDOWS ? Stream.of(new ChannelLedgerLock()) : Stream.of(new FlockLedgerLock(), new ChannelLedgerLock());
  }

  @ParameterizedTest
  @MethodSource("locks")
  void aWaiterStopsWithinAPollOfTheSignalRising(LedgerLock lock) throws Exception {
    Path lockFile = dir.resolve("x.lock");
    try (LedgerLock.Held other = lock.tryAcquire(lockFile)) {
      assertThat(other).isNotNull();
      AtomicBoolean abandon = new AtomicBoolean();
      CompletableFuture<Object> waiter = CompletableFuture.supplyAsync(() -> {
        try {
          return lock.acquire(lockFile, Duration.ofSeconds(30), abandon::get);
        } catch (IOException exception) {
          return exception;
        }
      });
      Thread.sleep(200);
      assertThat(waiter.isDone()).as("still waiting for the other holder").isFalse();

      long raised = System.nanoTime();
      abandon.set(true);
      Object result = waiter.get(5, TimeUnit.SECONDS);

      assertThat(result).isInstanceOf(LockAbandonedException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - raised)).isLessThan(Duration.ofMillis(300));
    }
    try (LedgerLock.Held again = lock.tryAcquire(lockFile)) {
      assertThat(again).as("the abandoned wait left the lock free").isNotNull();
    }
  }

  @ParameterizedTest
  @MethodSource("locks")
  void anUncontendedLockIsTakenEvenWithTheSignalAlreadyUpAndTheCallerReleasesIt(LedgerLock lock) throws IOException {
    Path lockFile = dir.resolve("x.lock");

    try (LedgerLock.Held held = lock.acquire(lockFile, Duration.ofSeconds(1), () -> true)) {
      assertThat(held).as("a lock the caller can take is never abandoned").isNotNull();
      assertThat(lock.tryAcquire(lockFile)).as("and it is held").isNull();
    }

    try (LedgerLock.Held again = lock.tryAcquire(lockFile)) {
      assertThat(again).as("released by the caller").isNotNull();
    }
  }

  @ParameterizedTest
  @MethodSource("locks")
  void withoutTheSignalTheWaitStillTimesOut(LedgerLock lock) throws IOException {
    Path lockFile = dir.resolve("x.lock");
    try (LedgerLock.Held other = lock.tryAcquire(lockFile)) {
      assertThat(other).isNotNull();
      assertThatThrownBy(() -> lock.acquire(lockFile, Duration.ofMillis(250), () -> false)).isInstanceOf(LedgerLockTimeoutException.class);
    }
  }
}
