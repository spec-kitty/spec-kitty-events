package org.speckitty.commandcli.application.repair;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.speckitty.commandcli.application.diagnostics.FailureRecord;
import org.speckitty.commandcli.application.write.WriteLocation;
import org.speckitty.commandcli.application.write.WriteRequest;
import org.speckitty.commandcli.application.write.WriteTurn;
import org.speckitty.commandcli.application.write.port.MissionCatalog;
import org.speckitty.commandcli.application.write.port.MissionRef;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.application.write.port.StatusLog;
import org.speckitty.commandcli.domain.authority.EventIds;
import org.speckitty.commandcli.domain.authority.LogLine;
import org.speckitty.commandcli.domain.authority.WriteFold;
import org.speckitty.commandcli.domain.service.RefusalCode;

/**
 * The explicit, operator-invoked repair of a Mission's torn status log (#222). It is never part of a write. It verifies the location
 * as a write does, takes the same in-process turn and the same Mission lock under the same budget, lets the log store repair the tail,
 * and reports what was done and whether the log it left reads and folds as the next write would.
 *
 * <p>The tail is completed only when the fragment is exactly one status row that folds after the log's prefix, with an {@code event_id}
 * no earlier row has; any other fragment is quarantined, so a repair never leaves the log LF-terminated but unfoldable. Everything the
 * repair does after the store has acted (the readability check) is reported, never refused: a repair already done is not a 500.
 */
public final class StatusLogRepairService {
  private static final System.Logger LOG = System.getLogger(StatusLogRepairService.class.getName());
  private static final String LOCK_HELD_DETAIL = "The Mission status lock is still held.";
  private static final String BUSY_DETAIL = "Another write is still in progress.";
  private static final String PERSISTENCE_DETAIL = "The status event log could not be repaired; no byte of it was lost, and the repair may have taken effect, so re-run it.";
  private static final String UNEXPECTED_DETAIL = "The repair failed unexpectedly; no byte of the log was lost, so re-run it.";

  private final MissionCatalog missions;
  private final StatusLog statusLog;
  private final RowCodec codec;
  private final WriteTurn turn;
  private final Duration budget;
  private final LongSupplier nanoClock;
  private final BooleanSupplier draining;

  /** A service that never abandons a wait: the signal is constant false. */
  public StatusLogRepairService(MissionCatalog missions, StatusLog statusLog, RowCodec codec, WriteTurn turn, Duration budget,
      LongSupplier nanoClock) {
    this(missions, statusLog, codec, turn, budget, nanoClock, () -> false);
  }

  /** {@code draining} is the service's drain signal (D10): a repair still waiting for the turn or the Mission lock stops waiting. */
  public StatusLogRepairService(MissionCatalog missions, StatusLog statusLog, RowCodec codec, WriteTurn turn, Duration budget,
      LongSupplier nanoClock, BooleanSupplier draining) {
    this.missions = Objects.requireNonNull(missions, "missions");
    this.statusLog = Objects.requireNonNull(statusLog, "statusLog");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.turn = Objects.requireNonNull(turn, "turn");
    this.budget = Objects.requireNonNull(budget, "budget");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.draining = Objects.requireNonNull(draining, "draining");
  }
  /** The shared write budget this service waits under (the same bean as the write path's). */
  public Duration budget() {
    return budget;
  }


  /** Never answers with anything but a coded outcome; the turn and the Mission lock are released before an unexpected failure is mapped. */
  public RepairOutcome repair(String missionId, WriteRequest.Context context) {
    try {
      return checkedRepair(missionId, context);
    } catch (RuntimeException unexpected) {
      return unexpectedFailure(unexpected);
    }
  }

  /** The one seam of the last-resort catch-all: it logs the content-free {@link FailureRecord} once, then answers the coded 500. */
  private RepairOutcome unexpectedFailure(RuntimeException unexpected) {
    LOG.log(System.Logger.Level.WARNING,
        FailureRecord.describe("repairStatusLog", RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED.name(), unexpected));
    return new RepairOutcome.Refused(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, UNEXPECTED_DETAIL);
  }

  private RepairOutcome checkedRepair(String missionId, WriteRequest.Context context) {
    WriteLocation.Result location = WriteLocation.verify(missions, missionId, context);
    if (location instanceof WriteLocation.Refused denied) return new RepairOutcome.Refused(denied.code(), denied.detail());
    return lockedRepair(((WriteLocation.Verified) location).mission());
  }

  private RepairOutcome lockedRepair(MissionRef mission) {
    long deadline = nanoClock.getAsLong() + budget.toNanos();
    WriteTurn.Acquisition acquisition = turn.acquire(deadline - nanoClock.getAsLong(), draining);
    if (acquisition instanceof WriteTurn.Abandoned) return new RepairOutcome.Refused(RefusalCode.SERVICE_DRAINING, null);
    if (!(acquisition instanceof WriteTurn.Acquired acquired)) return new RepairOutcome.Refused(RefusalCode.SERVICE_BUSY, BUSY_DETAIL);
    try (WriteTurn.Turn ignored = acquired.turn()) {
      StatusLog.LockResult<RepairOutcome> result = statusLog.withLock(mission, deadline, draining, this::insideLock);
      return switch (result) {
        case StatusLog.LockResult.Completed<RepairOutcome> completed -> completed.value();
        case StatusLog.LockResult.LockHeld<RepairOutcome> held ->
            new RepairOutcome.Refused(RefusalCode.STATUS_LOCK_HELD, LOCK_HELD_DETAIL);
        case StatusLog.LockResult.Abandoned<RepairOutcome> abandoned ->
            new RepairOutcome.Refused(RefusalCode.SERVICE_DRAINING, null);
        case StatusLog.LockResult.PersistenceFailed<RepairOutcome> failed ->
            new RepairOutcome.Refused(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, PERSISTENCE_DETAIL);
      };
    }
  }

  private RepairOutcome insideLock(StatusLog.Session session) {
    StatusLog.RepairResult result = session.repairTail(this::isCompleteRow);
    return new RepairOutcome.Repaired(result.action(), result.movedBytes(), readable(result));
  }

  /** Whether the log the repair left reads and folds as the next write would; any failure of the check is {@code false}, never an error. */
  private boolean readable(StatusLog.RepairResult result) {
    if (result.action() == StatusLog.RepairResult.Action.COMPLETED) return true;
    try {
      WriteFold.fold(codec.decode(result.bytesAfter()).values());
      return true;
    } catch (RuntimeException unreadable) {
      return false;
    }
  }

  /**
   * True iff {@code fragment} plus a LF decodes to exactly one status row, the {@code prefix} decodes, no row of the prefix has the
   * row's {@code event_id}, and the prefix followed by the row folds. False on any doubt, including any exception.
   */
  boolean isCompleteRow(byte[] prefix, byte[] fragment) {
    try {
      LogLine.Value row = singleRow(fragment);
      if (row == null) return false;
      List<LogLine> lines = new ArrayList<>(codec.decode(prefix).values());
      if (repeatsAnEventId(lines, row)) return false;
      lines.add(row);
      WriteFold.fold(lines);
      return true;
    } catch (RuntimeException unfit) {
      return false;
    }
  }

  /** The one row the fragment reads as under the log reader's codec, or null: the strictness of the write path's read-back. */
  private LogLine.Value singleRow(byte[] fragment) {
    byte[] line = new byte[fragment.length + 1];
    System.arraycopy(fragment, 0, line, 0, fragment.length);
    line[fragment.length] = '\n';
    List<RowCodec.Line> lines = codec.decode(line).lines().stream().filter(entry -> !(entry.value() instanceof LogLine.Blank)).toList();
    if (lines.size() != 1 || !(lines.getFirst().value() instanceof LogLine.Value value)) return null;
    return value;
  }

  /**
   * A row whose {@code event_id} equals an earlier row's under the fold's own identity (Python equality, {@link EventIds#sameId})
   * would be folded away or conflict, so it is not a row to complete. A row without an id is not a repeat.
   */
  private static boolean repeatsAnEventId(List<LogLine> earlier, LogLine.Value row) {
    Object id = row.row().get("event_id");
    if (id == null) return false;
    for (LogLine line : earlier) {
      if (line instanceof LogLine.Value value && EventIds.sameId(value.row().get("event_id"), id)) return true;
    }
    return false;
  }
}
