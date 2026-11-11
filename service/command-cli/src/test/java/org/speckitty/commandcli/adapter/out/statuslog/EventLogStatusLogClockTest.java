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
  void aFreeLockIsTakenWhenTheDeadlineIsAheadOnTheInjectedClockOnly() throws Exception {
    Path root = Files.createTempDirectory("event-log-clock-free");
    Path gcd = root.resolve(".git");
    Files.createDirectories(root.resolve("kitty-specs/m"));
    AtomicLong fake = new AtomicLong(0);
    var result = assertTimeoutPreemptively(TIMEOUT,
        () -> new EventLogStatusLog(new StatusLogAppender(fake::get), gcd, root)
            .withLock(new MissionRef("id", "kitty-specs/m"), Duration.ofSeconds(3).toNanos(), session -> "done"));
    assertThat(((StatusLog.LockResult.Completed<String>) result).value()).isEqualTo("done");
  }
}
