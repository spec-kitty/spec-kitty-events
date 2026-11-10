package org.speckitty.commandcli.application.repair;

import org.speckitty.commandcli.application.write.port.StatusLog.RepairResult.Action;
import org.speckitty.commandcli.domain.service.RefusalCode;

/** The result of one status-log repair: what it did, or the coded refusal. No session remains open when this is returned. */
public sealed interface RepairOutcome permits RepairOutcome.Repaired, RepairOutcome.Refused {
  /** The repair ran. {@code readableAfterRepair} says whether the log it left reads and folds as the next write would. */
  record Repaired(Action action, long movedBytes, boolean readableAfterRepair) implements RepairOutcome {}

  /** The repair did not run, or failed with the log unchanged or byte-conserved; {@code detail} is fixed text, never a path. */
  record Refused(RefusalCode code, String detail) implements RepairOutcome {}
}
