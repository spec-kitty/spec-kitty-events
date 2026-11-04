package org.speckitty.missionstatus.application.query.port.out.dto;

import java.util.Objects;

/**
 * A position at the end of a ledger, without its history: {@code offset} is the byte length of the terminated lines, {@code invariant}
 * the digest of the last of them (the digest of nothing when the ledger has no terminated line), and {@code stamp} the identity the
 * shared poller compares on its next tick. A watch created at the end of a ledger starts from it.
 */
public record LedgerEnd(long offset, String invariant, LedgerStamp stamp) {

  public LedgerEnd {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    Objects.requireNonNull(invariant, "invariant");
    Objects.requireNonNull(stamp, "stamp");
  }
}
