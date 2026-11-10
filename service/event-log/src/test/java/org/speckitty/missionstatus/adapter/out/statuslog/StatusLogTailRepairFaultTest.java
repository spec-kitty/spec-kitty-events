package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import org.speckitty.missionstatus.adapter.out.statuslog.NthCallFaultyFileOps.Mode;
import org.speckitty.missionstatus.adapter.out.statuslog.TailRepair.Action;

/**
 * NFR-001: a fault injected at each mutating call of a repair, in turn, never loses a byte. The expectation per call number is written
 * out below, and the test first checks the table covers exactly the calls a clean run makes.
 */
class StatusLogTailRepairFaultTest {

  private static final byte[] ROWS = "{\"row\": 1}\n{\"row\": 2}\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] FRAGMENT = "{\"torn\": ".getBytes(StandardCharsets.UTF_8);
  private static final byte[] EARLIER = "earlier fragment\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LF = {'\n'};
  private static final TailClassifier NEVER = (prefix, fragment) -> false;
  private static final TailClassifier ALWAYS = (prefix, fragment) -> true;

  /** The log is byte-identical and the quarantine file is at its starting length. */
  private static final String UNCHANGED = "unchanged";

  /** The fragment is durable in quarantine and the log ends at its last LF (quarantine); the log has its LF (completion). */
  private static final String REPAIRED = "repaired";

  private static final List<String> QUARANTINE_CALLS =
      List.of("open", "open", "write", "force", "sync-directory", "truncate", "force", "sync-directory");

  /**
   * Quarantine, by call number (1-based): open log, open quarantine, write fragment, force quarantine, sync directory, truncate log,
   * force log, sync directory. The truncate (call 6) is the only call that removes bytes from the log. A fault that throws before it
   * has any effect leaves the log whole and the quarantine copy is withdrawn; a truncate that took effect and then threw is
   * recognised by the shrunken log and the quarantine copy is kept. Calls 7 and 8 come after the truncate.
   */
  private static final List<String> QUARANTINE_EXPECTED_BEFORE =
      List.of(UNCHANGED, UNCHANGED, UNCHANGED, UNCHANGED, UNCHANGED, UNCHANGED, REPAIRED, REPAIRED);

  private static final List<String> QUARANTINE_EXPECTED_AFTER =
      List.of(UNCHANGED, UNCHANGED, UNCHANGED, UNCHANGED, UNCHANGED, REPAIRED, REPAIRED, REPAIRED);

  private static final List<String> COMPLETION_CALLS = List.of("open", "write", "force");

  /** Completion: open log, write the LF, force. Every failure, with or without effect, truncates the LF away again. */
  private static final List<String> COMPLETION_EXPECTED = List.of(UNCHANGED, UNCHANGED, UNCHANGED);

  @TempDir Path repository;

  private Path gcd;
  private Path mission;
  private Path log;
  private Path torn;

  @BeforeEach
  void setUp() throws IOException {
    gcd = Files.createDirectories(repository.resolve(".git"));
    mission = Files.createDirectories(repository.resolve("kitty-specs/mission-01M70THX"));
    log = mission.resolve("status.events.jsonl");
    torn = mission.resolve("status.events.jsonl.torn");
  }

  @Test
  void aCleanQuarantineMakesExactlyTheCallsTheTableCovers() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));
    NthCallFaultyFileOps clean = NthCallFaultyFileOps.clean();

    assertThat(run(clean, NEVER).action()).isEqualTo(Action.QUARANTINED);

    assertThat(names(clean.calls())).isEqualTo(QUARANTINE_CALLS);
    assertThat(QUARANTINE_EXPECTED_BEFORE).hasSameSizeAs(QUARANTINE_CALLS);
    assertThat(QUARANTINE_EXPECTED_AFTER).hasSameSizeAs(QUARANTINE_CALLS);
  }

  @Test
  void aCleanCompletionMakesExactlyTheCallsTheTableCovers() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));
    NthCallFaultyFileOps clean = NthCallFaultyFileOps.clean();

    assertThat(run(clean, ALWAYS).action()).isEqualTo(Action.COMPLETED);

    assertThat(names(clean.calls())).isEqualTo(COMPLETION_CALLS);
    assertThat(COMPLETION_EXPECTED).hasSameSizeAs(COMPLETION_CALLS);
  }

  @Test
  void aFaultAtEachCallOfAQuarantineNeverLosesAByte() throws Exception {
    for (boolean quarantineExists : new boolean[] {false, true}) {
      for (Mode mode : new Mode[] {Mode.BEFORE, Mode.AFTER, Mode.SHORT_WRITE}) {
        for (int n = 1; n <= QUARANTINE_CALLS.size(); n++) {
          // A short write only exists at a write call; every other call of that mode fails without effect, as BEFORE does.
          List<String> table = mode == Mode.AFTER ? QUARANTINE_EXPECTED_AFTER : QUARANTINE_EXPECTED_BEFORE;
          String context = "call " + n + " (" + QUARANTINE_CALLS.get(n - 1) + "), " + mode + ", quarantine exists=" + quarantineExists;
          byte[] original = concat(ROWS, FRAGMENT);
          byte[] tornStart = quarantineExists ? EARLIER : new byte[0];
          reset(original, quarantineExists ? EARLIER : null);

          NthCallFaultyFileOps faulty = new NthCallFaultyFileOps(Set.of(n), mode);
          assertThatThrownBy(() -> run(faulty, NEVER))
              .as(context)
              .isInstanceOf(PersistenceFailedException.class)
              .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(repository.toString()));

          if (table.get(n - 1).equals(UNCHANGED)) {
            assertThat(Files.readAllBytes(log)).as("log " + context).isEqualTo(original);
            assertThat(tornBytes()).as("quarantine " + context).isEqualTo(tornStart);
            assertThat(run(NthCallFaultyFileOps.clean(), NEVER).action()).as("rerun " + context).isEqualTo(Action.QUARANTINED);
          } else {
            assertThat(Files.readAllBytes(log)).as("log " + context).isEqualTo(ROWS);
            assertThat(tornBytes()).as("quarantine " + context).isEqualTo(concat(tornStart, concat(FRAGMENT, LF)));
            assertThat(run(NthCallFaultyFileOps.clean(), NEVER).action()).as("rerun " + context).isEqualTo(Action.NONE);
          }
          assertThat(Files.readAllBytes(log)).as("final log " + context).isEqualTo(ROWS);
          assertThat(tornBytes()).as("final quarantine " + context).isEqualTo(concat(tornStart, concat(FRAGMENT, LF)));
        }
      }
    }
  }

  @Test
  void aFaultAtEachCallOfACompletionLeavesTheLogWholeOrCompleted() throws Exception {
    for (Mode mode : new Mode[] {Mode.BEFORE, Mode.AFTER, Mode.SHORT_WRITE}) {
      for (int n = 1; n <= COMPLETION_CALLS.size(); n++) {
        String context = "call " + n + " (" + COMPLETION_CALLS.get(n - 1) + "), " + mode;
        byte[] original = concat(ROWS, FRAGMENT);
        reset(original, null);

        NthCallFaultyFileOps faulty = new NthCallFaultyFileOps(Set.of(n), mode);
        assertThatThrownBy(() -> run(faulty, ALWAYS))
            .as(context)
            .isInstanceOf(PersistenceFailedException.class);

        assertThat(COMPLETION_EXPECTED.get(n - 1)).isEqualTo(UNCHANGED);
        assertThat(Files.readAllBytes(log)).as("log " + context).isEqualTo(original);
        assertThat(torn).as("no quarantine file " + context).doesNotExist();
        assertThat(run(NthCallFaultyFileOps.clean(), ALWAYS).action()).as("rerun " + context).isEqualTo(Action.COMPLETED);
        assertThat(Files.readAllBytes(log)).isEqualTo(concat(original, LF));
      }
    }
  }

  @Test
  void aShortQuarantineWriteIsRolledBackToTheStartingSize() throws Exception {
    Files.write(log, concat(ROWS, FRAGMENT));
    Files.write(torn, EARLIER);

    assertThatThrownBy(() -> run(new NthCallFaultyFileOps(Set.of(3), Mode.SHORT_WRITE), NEVER)).isInstanceOf(PersistenceFailedException.class);

    assertThat(Files.readAllBytes(torn)).isEqualTo(EARLIER);
    assertThat(Files.readAllBytes(log)).isEqualTo(concat(ROWS, FRAGMENT));
  }

  @Test
  void aFailedRollbackIsSuppressedAndNoByteOfTheLogIsLost() throws Exception {
    byte[] original = concat(ROWS, FRAGMENT);
    Files.write(log, original);

    // Two faults, beyond the single-fault guarantee: the short write at call 3, then the rollback truncate (call 4) at once.
    assertThatThrownBy(() -> run(new NthCallFaultyFileOps(Set.of(3, 4), Mode.SHORT_WRITE), NEVER))
        .isInstanceOf(PersistenceFailedException.class)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).isNotEmpty());

    assertThat(Files.readAllBytes(log)).isEqualTo(original);
  }

  @Test
  void aLogThatChangedAfterTheReadIsRefusedBeforeAnyMutation() throws Exception {
    byte[] original = concat(ROWS, FRAGMENT);
    byte[] foreign = "foreign".getBytes(StandardCharsets.UTF_8);
    Files.write(log, original);
    FileOps growsAfterRead =
        new DelegatingFileOps() {
          @Override
          public byte[] read(Path path) throws IOException {
            byte[] bytes = super.read(path);
            Files.write(path, foreign, StandardOpenOption.APPEND);
            return bytes;
          }
        };

    assertThatThrownBy(() -> repairWith(growsAfterRead, NEVER)).isInstanceOf(PersistenceFailedException.class);

    assertThat(Files.readAllBytes(log)).isEqualTo(concat(original, foreign));
    assertThat(torn).doesNotExist();
  }

  @Test
  void aLogThatChangedWhileTheClassifierRanIsRefusedBeforeTheTruncate() throws Exception {
    byte[] original = concat(ROWS, FRAGMENT);
    byte[] foreign = "foreign".getBytes(StandardCharsets.UTF_8);
    Files.write(log, original);
    Files.write(torn, EARLIER);
    TailClassifier growsTheLog =
        (prefix, fragment) -> {
          try {
            Files.write(log, foreign, StandardOpenOption.APPEND);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          return false;
        };

    assertThatThrownBy(() -> repairWith(new NthCallFaultyFileOps(Set.of(), Mode.BEFORE), growsTheLog))
        .isInstanceOf(PersistenceFailedException.class);

    assertThat(Files.readAllBytes(log)).as("the foreign bytes survive").isEqualTo(concat(original, foreign));
    assertThat(Files.readAllBytes(torn)).as("the quarantine copy is withdrawn").isEqualTo(EARLIER);
  }

  @Test
  void aLogThatChangedWhileTheClassifierRanIsRefusedBeforeTheLineFeed() throws Exception {
    byte[] original = concat(ROWS, FRAGMENT);
    byte[] foreign = "foreign".getBytes(StandardCharsets.UTF_8);
    Files.write(log, original);
    TailClassifier growsTheLogAndCompletes =
        (prefix, fragment) -> {
          try {
            Files.write(log, foreign, StandardOpenOption.APPEND);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          return true;
        };

    assertThatThrownBy(() -> repairWith(new NthCallFaultyFileOps(Set.of(), Mode.BEFORE), growsTheLogAndCompletes))
        .isInstanceOf(PersistenceFailedException.class);

    assertThat(Files.readAllBytes(log)).as("the foreign bytes survive and no LF is added").isEqualTo(concat(original, foreign));
    assertThat(torn).doesNotExist();
  }

  // ---- helpers ----

  private void reset(byte[] logBytes, byte[] quarantineBytes) throws IOException {
    Files.deleteIfExists(torn);
    Files.write(log, logBytes);
    if (quarantineBytes != null) {
      Files.write(torn, quarantineBytes);
    }
  }

  /** A missing and an empty quarantine file are equivalent here: an early failed quarantine may leave an empty one (no bytes). */
  private byte[] tornBytes() throws IOException {
    return Files.exists(torn) ? Files.readAllBytes(torn) : new byte[0];
  }

  private TailRepair run(FileOps files, TailClassifier classifier) throws IOException {
    return repairWith(files, classifier);
  }

  private TailRepair repairWith(FileOps files, TailClassifier classifier) throws IOException {
    return new StatusLogAppender(LedgerLocks.forThisOs(), files)
        .withMissionLock(gcd, mission, System.nanoTime() + 5_000_000_000L, session -> session.repairTail(classifier));
  }

  private static List<String> names(List<String> calls) {
    return calls.stream().map(call -> call.substring(call.indexOf(':') + 1)).toList();
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] joined = new byte[first.length + second.length];
    System.arraycopy(first, 0, joined, 0, first.length);
    System.arraycopy(second, 0, joined, first.length, second.length);
    return joined;
  }

  /** Real behaviour for every operation; a test overrides what it needs. */
  private static class DelegatingFileOps implements FileOps {
    private final FileOps real = new JdkFileOps();

    @Override
    public boolean exists(Path path) {
      return real.exists(path);
    }

    @Override
    public byte[] read(Path path) throws IOException {
      return real.read(path);
    }

    @Override
    public boolean regularOrAbsent(Path path) throws IOException {
      return real.regularOrAbsent(path);
    }

    @Override
    public AppendChannel openForAppend(Path path) throws IOException {
      return real.openForAppend(path);
    }

    @Override
    public void syncDirectory(Path directory) throws IOException {
      real.syncDirectory(directory);
    }
  }
}
