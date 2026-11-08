package org.speckitty.commandcli.domain.authority;

import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.commandcli.domain.service.RefusalReason;

/** A domain refusal, retaining core's message separately from its safe client detail. */
public record Refusal(RefusalCode code, RefusalReason reason, String message, String detail, Integer unitIndex) {
  public static Refusal of(RefusalReason reason, String message) {
    return new Refusal(reason.code(), reason, message, DetailPolicy.detail(reason, message), null);
  }
}
