package org.speckitty.commandcli.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import org.speckitty.missionstatus.adapter.out.statuslog.StatusLogAppender;

/** Exception translation and real locked writes at the outbound port boundary. */
class EventLogStatusLogTest {
  @Test
  void lockedAppendAndTornTailAreTranslated() throws Exception {
    Path root = Files.createTempDirectory("event-log-status-port");
    Path gcd = root.resolve(".git");
    Path mission = root.resolve("kitty-specs/m");
    Files.createDirectories(mission);
    Files.createDirectories(gcd);
    EventLogStatusLog log = new EventLogStatusLog(new StatusLogAppender(), gcd, root);
    MissionRef ref = new MissionRef("id", "kitty-specs/m");
    var appended = log.withLock(ref, System.nanoTime() + Duration.ofSeconds(2).toNanos(), session -> {
      session.appendAndSync("row\n".getBytes(StandardCharsets.UTF_8));
      return "done";
    });
    assertThat(((StatusLog.LockResult.Completed<String>) appended).value()).isEqualTo("done");
    assertThat(Files.readString(mission.resolve("status.events.jsonl"))).isEqualTo("row\n");
    Files.writeString(mission.resolve("status.events.jsonl"), "torn");
    assertThat(log.withLock(ref, System.nanoTime() + Duration.ofSeconds(2).toNanos(), session -> session.currentBytes()))
        .isInstanceOf(StatusLog.LockResult.PersistenceFailed.class);
    assertThat(Files.readString(mission.resolve("status.events.jsonl"))).isEqualTo("torn");
  }

  @Test
  void contendedMissionLockUsesTheRemainingDeadline() throws Exception {
    Path root = Files.createTempDirectory("event-log-lock-port");
    Path gcd = root.resolve(".git");
    Path mission = root.resolve("kitty-specs/m");
    Files.createDirectories(mission);
    Files.createDirectories(gcd.resolve("spec-kitty-locks"));
    Path lock = gcd.resolve("spec-kitty-locks/m.status.lock");
    try (var ignored = LedgerLocks.forThisOs().acquire(lock, Duration.ofSeconds(2))) {
      EventLogStatusLog log = new EventLogStatusLog(new StatusLogAppender(), gcd, root);
      var result = log.withLock(new MissionRef("id", "kitty-specs/m"),
          System.nanoTime() + Duration.ofMillis(150).toNanos(), session -> "not reached");
      assertThat(result).isInstanceOf(StatusLog.LockResult.LockHeld.class);
    }
  }
}
