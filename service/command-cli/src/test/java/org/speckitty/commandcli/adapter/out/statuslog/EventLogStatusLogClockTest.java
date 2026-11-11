package org.speckitty.commandcli.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import org.speckitty.missionstatus.adapter.out.statuslog.StatusLogAppender;

/** The adapter's Mission lock wait follows the appender's injected clock, so a fake clock decides when the deadline has passed. */
class EventLogStatusLogClockTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  @Test
  void aDeadlineThatIsPastOnTheInjectedClockGivesUpAtOnceDespiteHoursOnTheWallClock() throws Exception {
    Path root = Files.createTempDirectory("event-log-clock-port");
    Path gcd = root.resolve(".git");
    Files.createDirectories(root.resolve("kitty-specs/m"));
    Files.createDirectories(gcd.resolve("spec-kitty-locks"));
    long deadline = System.nanoTime() + Duration.ofHours(1).toNanos();
    AtomicLong fake = new AtomicLong(deadline + 1);
    try (var ignored = LedgerLocks.forThisOs().acquire(gcd.resolve("spec-kitty-locks/m.status.lock"), Duration.ofSeconds(2))) {
      EventLogStatusLog log = new EventLogStatusLog(new StatusLogAppender(fake::get), gcd, root);
      var result = assertTimeoutPreemptively(TIMEOUT,
          () -> log.withLock(new MissionRef("id", "kitty-specs/m"), deadline, session -> "not reached"));
      assertThat(result).isInstanceOf(StatusLog.LockResult.LockHeld.class);
    }
  }

  @Test
  void aHeldLockIsWaitedForWhileTheDeadlineIsAheadOnTheInjectedClockEvenIfItIsPastOnTheWallClock() throws Exception {
    Path root = Files.createTempDirectory("event-log-clock-free");
    Path gcd = root.resolve(".git");
    Files.createDirectories(root.resolve("kitty-specs/m"));
    Files.createDirectories(gcd.resolve("spec-kitty-locks"));
    // The fake clock sits an hour behind the wall clock: a deadline 3 s ahead on the fake clock is long past on System.nanoTime().
    AtomicLong fake = new AtomicLong(System.nanoTime() - Duration.ofHours(1).toNanos());
    long deadline = fake.get() + Duration.ofSeconds(3).toNanos();
    var held = LedgerLocks.forThisOs().acquire(gcd.resolve("spec-kitty-locks/m.status.lock"), Duration.ofSeconds(2));
    Thread releaser = new Thread(() -> {
      try {
        Thread.sleep(400);
        held.close();
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    });
    releaser.start();
    var result = assertTimeoutPreemptively(TIMEOUT,
        () -> new EventLogStatusLog(new StatusLogAppender(fake::get), gcd, root)
            .withLock(new MissionRef("id", "kitty-specs/m"), deadline, session -> "done"));
    releaser.join();
    assertThat(((StatusLog.LockResult.Completed<String>) result).value()).isEqualTo("done");
  }
}
