package org.speckitty.commandcli.domain.authority;

import org.speckitty.commandcli.domain.service.RefusalReason;

/** FR-025: core messages that echo request values are replaced with fixed client details. */
public final class DetailPolicy {
  private DetailPolicy() {}

  public static String detail(RefusalReason reason, String message) {
    if (reason == null) return message;
    return switch (reason) {
      case UNKNOWN_LANE -> "The requested lane is unknown.";
      case FORCE_NOTE_REQUIRED -> "A forced review-lane exit requires a non-blank note.";
      case CLAIM_HELD_BY_OTHER_ACTOR -> "The work package is already claimed by another actor.";
      case START_REJECTED -> "The work package cannot start in its current lane.";
      default -> message;
    };
  }
}
