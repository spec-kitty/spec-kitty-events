package org.speckitty.commandcli.application.lifecycle;

import org.speckitty.commandcli.domain.service.RefusalCode;

/**
 * Where a well-formed write goes. Until the canonical writer (spec-kitty-events#160) and the lane authority (#161) land, every write is
 * refused fail-closed and nothing is written (core ADR 2026-10-22-1 D-5: no write is acknowledged that was not durably persisted). The
 * refusal reuses STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, the contract's code for "the unit is not acknowledged" (research D-6).
 */
public final class WriteGate {

  /** The fixed detail of the fail-closed refusal; it names no path and no request content. */
  public static final String NOT_BUILT = "The write core is not built yet; nothing was written.";

  /** A refusal of the gate: the code and its fixed detail. */
  public record Refusal(RefusalCode code, String detail) {}

  /** The outcome of every write in this release: refused, nothing written. */
  public Refusal persist() {
    return new Refusal(RefusalCode.STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED, NOT_BUILT);
  }
}
