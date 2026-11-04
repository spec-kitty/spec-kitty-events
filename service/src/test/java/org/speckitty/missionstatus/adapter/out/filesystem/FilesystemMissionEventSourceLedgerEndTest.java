package org.speckitty.missionstatus.adapter.out.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.missionstatus.application.query.port.out.dto.LedgerEnd;
import org.speckitty.missionstatus.application.query.port.out.dto.LedgerRead;
import org.speckitty.missionstatus.application.query.port.out.dto.LedgerStamp;
import org.speckitty.missionstatus.application.query.port.out.dto.MissionRef;
import org.speckitty.missionstatus.domain.readmodel.cursor.StreamCursor;
import org.speckitty.missionstatus.domain.model.LedgerLine;
import org.speckitty.missionstatus.testsupport.Rows;

/**
 * The cheap end read a new watch starts from: {@code ledgerEnd} must report exactly the position the old whole-ledger baseline
 * reported, because the watch starts from it and the truncation check compares it with the digest of the line the next window ends on.
 * An absent ledger ends at zero, and an unterminated tail is not the end.
 */
class FilesystemMissionEventSourceLedgerEndTest {

  private static final String NAME = AdapterServiceHarness.ALPHA;
  private static final String ID = Rows.ALPHA_ID;
  private static final String CREATED_AT = "2026-09-01T09:00:00+00:00";
  /** The backward scan's read size in {@code FilesystemMissionEventSourceLedger}. */
  private static final int CHUNK = 64 * 1024;

  @TempDir Path sandbox;
  Path root;
  FilesystemMissionEventSource source;
  MissionRef ref = new MissionRef(NAME);

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectory(sandbox.resolve("project"));
    source = new FilesystemMissionEventSource(root, AllowlistAccess.passThrough());
    AdapterServiceHarness.healthyProject(root);
  }

  private LedgerRead wholeRead() {
    return source.readLedger(ref, source.stampLedger(ref));
  }

  @Test
  void theEndIsTheTerminatedLengthAndTheDigestOfTheLastTerminatedLine() {
    AdapterServiceHarness.mission(root, NAME, ID, CREATED_AT, AdapterServiceHarness.laneRow(1, "WP01", "planned", "claimed") + AdapterServiceHarness.laneRow(2, "WP01", "claimed", "in_progress"));

    LedgerEnd end = source.ledgerEnd(ref);
    LedgerRead whole = wholeRead();
    long terminated = whole.lines().stream().mapToLong(LedgerLine::byteLength).sum();
    LedgerStamp stamp = source.stampLedger(ref);

    assertThat(end.offset()).as("the terminated length, never the tail").isEqualTo(terminated);
    assertThat(end.invariant()).as("the digest the next window's preceding line must have").isEqualTo(whole.lines().getLast().rawLineSha256());
    assertThat(end.stamp()).as("the identity the shared poller compares on its next tick").isEqualTo(stamp);
    assertThat(source.readLedgerFrom(ref, end.offset()).precedingLineSha256()).isEqualTo(end.invariant());
  }

  @Test
  void anUnterminatedTailIsNotTheEnd() {
    String row = AdapterServiceHarness.laneRow(1, "WP01", "planned", "claimed");
    AdapterServiceHarness.mission(root, NAME, ID, CREATED_AT, row + "{\"half\":\"a row still being written\"");

    LedgerEnd end = source.ledgerEnd(ref);

    assertThat(end.offset()).as("a fragment is never consumed, so it is not part of the position").isEqualTo(row.getBytes(StandardCharsets.UTF_8).length);
    assertThat(end.invariant()).isEqualTo(wholeRead().lines().getFirst().rawLineSha256());
    assertThat(source.readLedgerFrom(ref, end.offset()).tail()).isEqualTo(wholeRead().tail());
  }

  @Test
  void anAbsentLedgerEndsAtZeroWithTheDigestOfNothing() {
    AdapterServiceHarness.mission(root, NAME, ID, CREATED_AT, null);

    LedgerEnd end = source.ledgerEnd(ref);

    assertThat(end.offset()).isZero();
    assertThat(end.invariant()).isEqualTo(StreamCursor.start().invariant());
    assertThat(end.stamp()).isEqualTo(source.stampLedger(ref));
    assertThat(source.readLedgerFrom(ref, 0).lines()).isEmpty();
  }

  /** The end read against a position worked out from the bytes here, sharing nothing with the backward scan or the decoder. */
  private void assertEndOf(String ledger) {
    AdapterServiceHarness.mission(root, NAME, ID, CREATED_AT, ledger);
    int terminated = ledger.lastIndexOf('\n') + 1;
    String digest = terminated == 0
        ? StreamCursor.start().invariant()
        : StreamCursor.digestOf(ledger.substring(ledger.lastIndexOf('\n', terminated - 2) + 1, terminated).getBytes(StandardCharsets.UTF_8));

    LedgerEnd end = source.ledgerEnd(ref);

    assertThat(end.offset()).isEqualTo(terminated);
    assertThat(end.invariant()).isEqualTo(digest);
    assertThat(source.readLedgerFrom(ref, end.offset()).precedingLineSha256()).isEqualTo(digest);
  }

  @Test
  void anEmptyLedgerEndsAtZero() {
    assertEndOf("");
  }

  @Test
  void aLedgerWithNoNewlineEndsAtZero() {
    assertEndOf("{\"half\":\"a row still being written\"");
  }

  @Test
  void aBlankLastLineIsTheLastTerminatedLine() {
    assertEndOf("first\n\n");
  }

  @Test
  void aLastLineLongerThanOneChunkIsHashedWhole() {
    assertEndOf("first\n" + "x".repeat(200_000) + "\n");
  }

  @Test
  void aLastLineOfExactlyOneChunkEndsAtTheChunkBoundary() {
    assertEndOf("first\n" + "x".repeat(CHUNK - 1) + "\n");
  }

  @Test
  void aTailThatFillsTheChunkAfterItsNewlineLeavesTheEndAtThatNewline() {
    assertEndOf("first\n" + "x".repeat(CHUNK - 1));
  }

  @Test
  void aCarriageReturnStaysInTheRawLine() {
    assertEndOf("first\r\nsecond\r\n");
    assertEndOf("first\rstill first\n");
  }
}
