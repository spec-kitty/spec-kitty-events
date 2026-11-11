package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;

/** The Mission lock's wait is derived from the injected clock, never from the wall clock. */
class StatusLogAppenderClockTest {

  private static final class RecordingLock implements LedgerLock {
    final List<Duration> timeouts = new ArrayList<>();

    @Override
    public Held tryAcquire(Path lockFile) {
      return () -> { };
    }

    @Override
    public Held acquire(Path lockFile, Duration timeout, BooleanSupplier abandon) {
      timeouts.add(timeout);
      return () -> { };
    }
  }

  private static Duration waitFor(AtomicLong clock, long deadline) throws IOException {
    Path dir = Files.createTempDirectory("appender-clock");
    Path mission = Files.createDirectories(dir.resolve("kitty-specs/m"));
    RecordingLock lock = new RecordingLock();
    new StatusLogAppender(lock, new JdkFileOps(), clock::get).withMissionLock(dir.resolve(".git"), mission, deadline, session -> null);
    assertThat(lock.timeouts).hasSize(1);
    return lock.timeouts.get(0);
  }

  @Test
  void waitIsTheDeadlineMinusTheInjectedClock() throws IOException {
    AtomicLong clock = new AtomicLong(42_000_000_000L);
    long deadline = clock.get() + Duration.ofSeconds(3).toNanos();
    clock.addAndGet(Duration.ofSeconds(1).toNanos());
    assertThat(waitFor(clock, deadline)).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void waitIsExactlyTheBudgetWhenTheFakeClockHasNotMoved() throws IOException {
    AtomicLong clock = new AtomicLong(-7_000_000_000L);
    assertThat(waitFor(clock, clock.get() + Duration.ofSeconds(3).toNanos())).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void aDeadlineInTheFakePastWaitsZero() throws IOException {
    AtomicLong clock = new AtomicLong(1_000_000_000L);
    assertThat(waitFor(clock, clock.get() - 1)).isEqualTo(Duration.ZERO);
  }
}
