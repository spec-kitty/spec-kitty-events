package org.speckitty.commandcli.application.write;

import java.util.List;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.commandcli.domain.service.RefusalReason;

/** The result of one complete write transaction; no session remains open when this is returned. */
public sealed interface WriteOutcome permits WriteOutcome.Acked, WriteOutcome.Refused {
  record Acked(List<UnitAck> units, boolean batch, int fsyncs) implements WriteOutcome {
    public Acked {
      units = List.copyOf(units);
    }
  }

  record UnitAck(List<String> eventIds, int appendedLines, boolean replayed) {
    public UnitAck {
      eventIds = List.copyOf(eventIds);
    }
  }

  record Refused(RefusalCode code, RefusalReason reason, String detail, Integer unitIndex) implements WriteOutcome {}
}
