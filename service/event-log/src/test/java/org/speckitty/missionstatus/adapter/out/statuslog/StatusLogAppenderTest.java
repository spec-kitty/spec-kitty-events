package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;

class StatusLogAppenderTest {

  @TempDir Path repository;

  private Path gcd;
  private Path mission;
  private Path log;
  private StatusLogAppender appender;

  @BeforeEach
  void setUp() throws IOException {
    gcd = Files.createDirectories(repository.resolve(".git"));
    mission = Files.createDirectories(repository.resolve("kitty-specs/write-core-01M6TP6W"));
    log = mission.resolve("status.events.jsonl");
    appender = new StatusLogAppender();
  }

  @Test
  void holdsTheMissionLockForTheWholeCallbackAndReleasesItAfterSuccess() throws Exception {
    Path lockPath = MissionLockPath.of(gcd, mission.getFileName().toString());
    appender.withMissionLock(
        gcd,
        mission,
        deadlineAfterSeconds(5),
        session -> {
          try (LedgerLock.Held second = LedgerLocks.forThisOs().tryAcquire(lockPath)) {
            assertThat(second).as("a second holder is excluded while the callback runs").isNull();
          }
          return null;
        });

    try (LedgerLock.Held free = LedgerLocks.forThisOs().tryAcquire(lockPath)) {
      assertThat(free).as("the lock is released after the callback").isNotNull();
    }
  }

  @Test
  void releasesTheMissionLockWhenTheCallbackThrows() throws Exception {
    Path lockPath = MissionLockPath.of(gcd, mission.getFileName().toString());
    assertThatThrownBy(
            () ->
                appender.withMissionLock(
                    gcd,
                    mission,
                    deadlineAfterSeconds(5),
                    session -> {
                      throw new IOException("work failed");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessage("work failed");

    try (LedgerLock.Held free = LedgerLocks.forThisOs().tryAcquire(lockPath)) {
      assertThat(free).isNotNull();
    }
  }

  @Test
  void timesOutAgainstAnotherHolderWithoutRunningTheCallback() throws Exception {
    Path lockPath = MissionLockPath.of(gcd, mission.getFileName().toString());
    AtomicBoolean ran = new AtomicBoolean();
    try (LedgerLock.Held held = LedgerLocks.forThisOs().tryAcquire(lockPath)) {
      assertThat(held).isNotNull();
      long start = System.nanoTime();
      assertThatThrownBy(
              () ->
                  appender.withMissionLock(
                      gcd,
                      mission,
                      System.nanoTime() + 150_000_000L,
                      session -> {
                        ran.set(true);
                        return null;
                      }))
          .isInstanceOf(StatusLockHeldException.class)
          .hasCauseInstanceOf(org.speckitty.missionstatus.adapter.out.ledger.LedgerLockTimeoutException.class)
          .hasMessageContaining(lockPath.toString());
      assertThat(System.nanoTime() - start).as("the caller supplies the whole remaining budget").isGreaterThanOrEqualTo(100_000_000L);
    }
    assertThat(ran).isFalse();
  }

  @Test
  void readsMissingEmptyAndWholeLogsAndAcceptsCrLf() throws Exception {
    assertThat(readCurrent()).isEmpty();
    Files.write(log, new byte[0]);
    assertThat(readCurrent()).isEmpty();
    byte[] lines = "{\"first\": true}\r\n{\"second\": true}\n".getBytes(StandardCharsets.UTF_8);
    Files.write(log, lines);
    assertThat(readCurrent()).isEqualTo(lines);
  }

  @Test
  void refusesATornTailOnReadAndAppendWithoutRepairingIt() throws Exception {
    byte[] torn = "{\"complete\": true}\n{\"torn\":".getBytes(StandardCharsets.UTF_8);
    Files.write(log, torn);

    assertThatThrownBy(this::readCurrent).isInstanceOf(TornTailException.class).hasMessageContaining(log.toString());
    assertThatThrownBy(() -> append("{\"new\": true}\n")).isInstanceOf(TornTailException.class);
    assertThat(Files.readAllBytes(log)).isEqualTo(torn);
  }

  @Test
  void aMissingMissionDirectoryIsAnIoFailure() {
    Path missing = repository.resolve("kitty-specs/missing");
    assertThatIOException()
        .isThrownBy(() -> appender.withMissionLock(gcd, missing, deadlineAfterSeconds(5), StatusLogSession::currentBytes));
  }

  @Test
  void theSessionCannotBeUsedAfterTheCallbackReturns() throws Exception {
    AtomicReference<StatusLogSession> escaped = new AtomicReference<>();
    appender.withMissionLock(
        gcd,
        mission,
        deadlineAfterSeconds(5),
        session -> {
          escaped.set(session);
          session.appendAndSync("{\"one\": true}\n".getBytes(StandardCharsets.UTF_8));
          assertThatIllegalStateException().isThrownBy(() -> session.appendAndSync("ignored\n".getBytes(StandardCharsets.UTF_8)));
          return null;
        });

    assertThatIOException().isThrownBy(() -> escaped.get().currentBytes());
    assertThatIllegalStateException()
        .isThrownBy(() -> escaped.get().appendAndSync("ignored\n".getBytes(StandardCharsets.UTF_8)));
  }

  private byte[] readCurrent() throws IOException {
    return appender.withMissionLock(gcd, mission, deadlineAfterSeconds(5), StatusLogSession::currentBytes);
  }

  private void append(String row) throws IOException {
    appender.withMissionLock(
        gcd,
        mission,
        deadlineAfterSeconds(5),
        session -> {
          session.appendAndSync(row.getBytes(StandardCharsets.UTF_8));
          return null;
        });
  }

  private static long deadlineAfterSeconds(long seconds) {
    return System.nanoTime() + seconds * 1_000_000_000L;
  }
}
