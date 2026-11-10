package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import org.speckitty.missionstatus.adapter.out.statuslog.TailRepair.Action;

/** Drives the real {@link JdkFileOps} in a temp git-common-dir layout, through {@link StatusLogAppender#withMissionLock}. */
class StatusLogTailRepairTest {

  private static final byte[] ROWS = "{\"row\": 1}\n{\"row\": 2}\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] FRAGMENT = "{\"torn\": ".getBytes(StandardCharsets.UTF_8);
  private static final TailClassifier NEVER = (prefix, fragment) -> false;
  private static final TailClassifier ALWAYS = (prefix, fragment) -> true;

  @TempDir Path repository;
  @TempDir Path outside;

  private Path gcd;
  private Path mission;
  private Path log;
  private Path torn;
  private final StatusLogAppender appender = new StatusLogAppender();

  @BeforeEach
  void setUp() throws IOException {
    gcd = Files.createDirectories(repository.resolve(".git"));
    mission = Files.createDirectories(repository.resolve("kitty-specs/mission-01M70THX"));
    log = mission.resolve("status.events.jsonl");
    torn = mission.resolve("status.events.jsonl.torn");
  }

  // ---- NONE, each paired with a positive control on the same fixture ----

  @Test
  void aMissingLogIsLeftAloneAndNothingIsCreated() throws Exception {
    TailRepair result = repair(NEVER);

    assertNone(result, new byte[0]);
    assertThat(log).doesNotExist();
    assertThat(torn).doesNotExist();

    // Positive control: the same fixture, once torn, is repaired.
    Files.write(log, FRAGMENT);
    assertThat(repair(NEVER).action()).isEqualTo(Action.QUARANTINED);
  }

  @Test
  void anEmptyLogIsLeftAlone() throws Exception {
    Files.write(log, new byte[0]);

    assertNone(repair(NEVER), new byte[0]);
    assertThat(log).exists().isEmptyFile();
    assertThat(torn).doesNotExist();

    Files.write(log, FRAGMENT);
    assertThat(repair(NEVER).action()).isEqualTo(Action.QUARANTINED);
  }

  @Test
  void anLfTerminatedLogIsLeftByteIdentical() throws Exception {
    Files.write(log, ROWS);

    assertNone(repair(NEVER), ROWS);
    assertThat(Files.readAllBytes(log)).isEqualTo(ROWS);
    assertThat(torn).doesNotExist();

    Files.write(log, concat(ROWS, FRAGMENT));
    assertThat(repair(NEVER).action()).isEqualTo(Action.QUARANTINED);
  }

  @Test
  void theClassifierIsNotAskedAboutAHealthyLog() throws Exception {
    Files.write(log, ROWS);
    AtomicInteger asked = new AtomicInteger();

    repair((prefix, fragment) -> asked.incrementAndGet() > 0);

    assertThat(asked).hasValue(0);
  }

  // ---- QUARANTINED ----

  @Test
  void aMidJsonFragmentIsMovedToTheQuarantineFile() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));

    TailRepair result = repair(NEVER);

    assertThat(result.action()).isEqualTo(Action.QUARANTINED);
    assertThat(result.movedBytes()).isEqualTo(FRAGMENT.length);
    assertThat(result.bytesAfter()).isEqualTo(ROWS);
    assertThat(Files.readAllBytes(log)).isEqualTo(ROWS);
    assertThat(Files.readAllBytes(torn)).isEqualTo(concat(FRAGMENT, new byte[] {'\n'}));
  }

  @Test
  void aSecondQuarantineAppendsAndNeverRewritesEarlierContent() throws Exception {
    byte[] earlier = "earlier fragment\n".getBytes(StandardCharsets.UTF_8);
    Files.write(torn, earlier);
    Files.write(log, concat(ROWS, FRAGMENT));

    repair(NEVER);

    assertThat(Files.readAllBytes(torn)).isEqualTo(concat(earlier, concat(FRAGMENT, new byte[] {'\n'})));
    assertThat(Files.readAllBytes(log)).isEqualTo(ROWS);
  }

  @Test
  void aWholeFileFragmentTruncatesTheLogToZeroBytes() throws Exception {
    Files.write(log, FRAGMENT);

    TailRepair result = repair(NEVER);

    assertThat(result.action()).isEqualTo(Action.QUARANTINED);
    assertThat(result.movedBytes()).isEqualTo(FRAGMENT.length);
    assertThat(result.bytesAfter()).isEmpty();
    assertThat(log).exists().isEmptyFile();
    assertThat(Files.readAllBytes(torn)).isEqualTo(concat(FRAGMENT, new byte[] {'\n'}));
  }

  @Test
  void aClassifierThatThrowsIsTreatedAsFalse() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));

    TailRepair result =
        repair(
            (prefix, fragment) -> {
              throw new IllegalStateException("boom");
            });

    assertThat(result.action()).isEqualTo(Action.QUARANTINED);
    assertThat(Files.readAllBytes(log)).isEqualTo(ROWS);
  }

  // ---- COMPLETED ----

  @Test
  void aCompleteRowGetsExactlyOneLfAndNoQuarantineFile() throws Exception {
    byte[] row = "{\"row\": 3}".getBytes(StandardCharsets.UTF_8);
    Files.write(log, concat(ROWS, row));

    TailRepair result = repair(ALWAYS);

    byte[] expected = concat(concat(ROWS, row), new byte[] {'\n'});
    assertThat(result.action()).isEqualTo(Action.COMPLETED);
    assertThat(result.movedBytes()).isZero();
    assertThat(result.bytesAfter()).isEqualTo(expected);
    assertThat(Files.readAllBytes(log)).isEqualTo(expected);
    assertThat(torn).doesNotExist();
  }

  @Test
  void aWholeFileCompleteRowGetsOneLf() throws Exception {
    Files.write(log, FRAGMENT);

    TailRepair result = repair(ALWAYS);

    assertThat(result.action()).isEqualTo(Action.COMPLETED);
    assertThat(Files.readAllBytes(log)).isEqualTo(concat(FRAGMENT, new byte[] {'\n'}));
    assertThat(torn).doesNotExist();
  }

  // ---- idempotence and the classifier's view ----

  @Test
  void aRerunAfterEachActionAnswersNone() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));
    assertThat(repair(NEVER).action()).isEqualTo(Action.QUARANTINED);
    byte[] quarantinedLog = Files.readAllBytes(log);
    byte[] quarantinedTorn = Files.readAllBytes(torn);
    assertNone(repair(NEVER), quarantinedLog);
    assertThat(Files.readAllBytes(torn)).isEqualTo(quarantinedTorn);

    Files.write(log, concat(ROWS, FRAGMENT));
    assertThat(repair(ALWAYS).action()).isEqualTo(Action.COMPLETED);
    byte[] completedLog = Files.readAllBytes(log);
    assertNone(repair(ALWAYS), completedLog);
    assertThat(Files.readAllBytes(log)).isEqualTo(completedLog);
  }

  @Test
  void theClassifierReceivesTheExactPrefixAndFragment() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));
    List<byte[]> seen = new ArrayList<>();

    repair(
        (prefix, fragment) -> {
          seen.add(prefix);
          seen.add(fragment);
          return false;
        });

    assertThat(seen).hasSize(2);
    assertThat(seen.get(0)).isEqualTo(ROWS);
    assertThat(seen.get(1)).isEqualTo(FRAGMENT);
  }

  @Test
  void withoutAnyLfThePrefixIsEmpty() throws Exception {
    Files.write(log, FRAGMENT);
    List<byte[]> seen = new ArrayList<>();

    repair(
        (prefix, fragment) -> {
          seen.add(prefix);
          seen.add(fragment);
          return false;
        });

    assertThat(seen.get(0)).isEmpty();
    assertThat(seen.get(1)).isEqualTo(FRAGMENT);
  }

  @Test
  void aClassifierMutatingItsArgumentsChangesNothingOnDisk() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));

    TailRepair result =
        repair(
            (prefix, fragment) -> {
              java.util.Arrays.fill(prefix, (byte) 'X');
              java.util.Arrays.fill(fragment, (byte) 'X');
              return false;
            });

    assertThat(result.bytesAfter()).isEqualTo(ROWS);
    assertThat(Files.readAllBytes(log)).isEqualTo(ROWS);
    assertThat(Files.readAllBytes(torn)).isEqualTo(concat(FRAGMENT, new byte[] {'\n'}));
  }

  @Test
  void theResultOwnsItsBytes() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));
    TailRepair result = repair(NEVER);

    result.bytesAfter()[0] = 'X';

    assertThat(result.bytesAfter()).isEqualTo(ROWS);
    assertThat(result.toString()).doesNotContain("row");
  }

  // ---- the lock ----

  @Test
  void anotherHolderOfTheMissionLockMakesTheRepairFailWithNothingChanged() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);
    Path lockPath = MissionLockPath.of(gcd, mission.getFileName().toString());
    AtomicInteger asked = new AtomicInteger();

    try (LedgerLock.Held held = LedgerLocks.forThisOs().tryAcquire(lockPath)) {
      assertThat(held).isNotNull();
      assertThatThrownBy(
              () ->
                  appender.withMissionLock(
                      gcd,
                      mission,
                      System.nanoTime() + 150_000_000L,
                      session -> {
                        asked.incrementAndGet();
                        return session.repairTail(NEVER);
                      }))
          .isInstanceOf(StatusLockHeldException.class);
    }

    assertThat(asked).hasValue(0);
    assertThat(Files.readAllBytes(log)).isEqualTo(before);
    assertThat(torn).doesNotExist();
    // Positive control: with the lock free, the same fixture is repaired.
    assertThat(repair(NEVER).action()).isEqualTo(Action.QUARANTINED);
  }

  // ---- FR-009: the write path never repairs ----

  @Test
  void appendOnATornLogStillThrowsTornTailAndChangesNothingWhileRepairSucceeds() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);

    assertThatThrownBy(() -> append("{\"new\": true}\n")).isInstanceOf(TornTailException.class);
    assertThat(Files.readAllBytes(log)).isEqualTo(before);
    assertThat(torn).doesNotExist();

    assertThat(repair(NEVER).action()).isEqualTo(Action.QUARANTINED);
    append("{\"new\": true}\n");
    assertThat(Files.readAllBytes(log)).isEqualTo(concat(ROWS, "{\"new\": true}\n".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void currentBytesStillRefusesATornTail() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);

    assertThatThrownBy(() -> appender.withMissionLock(gcd, mission, deadline(), StatusLogSession::currentBytes))
        .isInstanceOf(TornTailException.class);
    assertThat(Files.readAllBytes(log)).isEqualTo(before);
  }

  // ---- one mutation per session ----

  @Test
  void aSessionPermitsOneMutationOnly() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));
    byte[] line = "x\n".getBytes(StandardCharsets.UTF_8);

    appender.withMissionLock(
        gcd,
        mission,
        deadline(),
        session -> {
          session.repairTail(NEVER);
          assertThatIllegalStateException().isThrownBy(() -> session.appendAndSync(line));
          assertThatIllegalStateException().isThrownBy(() -> session.repairTail(NEVER));
          return null;
        });
    appender.withMissionLock(
        gcd,
        mission,
        deadline(),
        session -> {
          session.appendAndSync(line);
          assertThatIllegalStateException().isThrownBy(() -> session.repairTail(NEVER));
          return null;
        });
    assertThat(Files.readAllBytes(log)).isEqualTo(concat(ROWS, line));
  }

  @Test
  void aNullClassifierIsRefusedBeforeAnythingHappens() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);

    appender.withMissionLock(
        gcd,
        mission,
        deadline(),
        session -> {
          assertThatNullPointerException().isThrownBy(() -> session.repairTail(null));
          return null;
        });
    assertThat(Files.readAllBytes(log)).isEqualTo(before);
  }

  // ---- T010: the quarantine type guard ----

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aDanglingSymlinkAtTheQuarantinePathIsRefusedAndItsTargetIsNotCreated() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);
    Path target = outside.resolve("elsewhere.torn");
    Files.createSymbolicLink(torn, target);

    assertRefusedWithNothingChanged(before);

    assertThat(target).doesNotExist();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aLiveSymlinkAtTheQuarantinePathIsRefusedAndItsTargetIsUnchanged() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);
    Path target = outside.resolve("elsewhere.torn");
    byte[] targetBytes = "do not touch\n".getBytes(StandardCharsets.UTF_8);
    Files.write(target, targetBytes);
    Files.createSymbolicLink(torn, target);

    assertRefusedWithNothingChanged(before);

    assertThat(Files.readAllBytes(target)).isEqualTo(targetBytes);
  }

  @Test
  void aDirectoryAtTheQuarantinePathIsRefused() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);
    Files.createDirectory(torn);

    assertRefusedWithNothingChanged(before);

    assertThat(torn).isDirectory();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aFifoAtTheQuarantinePathIsRefusedPromptlyWithoutBlocking() throws Exception {
    byte[] before = concat(ROWS, FRAGMENT);
    Files.write(log, before);
    Process mkfifo = new ProcessBuilder("mkfifo", torn.toString()).redirectErrorStream(true).start();
    assertThat(mkfifo.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(mkfifo.exitValue()).isZero();

    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertRefusedWithNothingChanged(before));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aSymlinkedLogIsRefusedAndItsTargetIsUnchanged() throws Exception {
    Path target = outside.resolve("status.events.jsonl");
    byte[] targetBytes = concat(ROWS, FRAGMENT);
    Files.write(target, targetBytes);
    Files.createSymbolicLink(log, target);

    assertThatThrownBy(() -> repair(NEVER))
        .isInstanceOf(PersistenceFailedException.class)
        .satisfies(
            thrown ->
                assertThat(thrown.getMessage()).doesNotContain(repository.toString()).doesNotContain(outside.toString()));

    assertThat(Files.readAllBytes(target)).isEqualTo(targetBytes);
    assertThat(torn).doesNotExist();
  }

  private void assertRefusedWithNothingChanged(byte[] logBefore) throws IOException {
    assertThatThrownBy(() -> repair(NEVER))
        .isInstanceOf(PersistenceFailedException.class)
        .satisfies(
            thrown -> {
              assertThat(thrown.getMessage()).doesNotContain(repository.toString()).doesNotContain(outside.toString());
              assertThat(thrown.getMessage()).doesNotContain("kitty-specs");
            });
    assertThat(Files.readAllBytes(log)).isEqualTo(logBefore);
  }

  // ---- helpers ----

  private TailRepair repair(TailClassifier classifier) throws IOException {
    return appender.withMissionLock(gcd, mission, deadline(), session -> session.repairTail(classifier));
  }

  private void append(String row) throws IOException {
    appender.withMissionLock(
        gcd,
        mission,
        deadline(),
        session -> {
          session.appendAndSync(row.getBytes(StandardCharsets.UTF_8));
          return null;
        });
  }

  private static long deadline() {
    return System.nanoTime() + 5_000_000_000L;
  }

  private static void assertNone(TailRepair result, byte[] bytesAfter) {
    assertThat(result.action()).isEqualTo(Action.NONE);
    assertThat(result.movedBytes()).isZero();
    assertThat(result.bytesAfter()).isEqualTo(bytesAfter);
  }

  static byte[] concat(byte[] first, byte[] second) {
    byte[] joined = new byte[first.length + second.length];
    System.arraycopy(first, 0, joined, 0, first.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }
}
