package org.speckitty.commandcli.domain.authority;

import java.util.Locale;

/** Lifecycle operation associated with a write unit. */
public enum Operation {
  TRANSITION,
  START_IMPLEMENTATION,
  START_REVIEW;

  public static Operation fromWire(String value) {
    if (value == null) return TRANSITION;
    if (value.equals("annotation")) return null;
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}
