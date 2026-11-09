package org.speckitty.commandcli.domain.authority;

/** The log cannot authoritatively establish the current work-package state. */
public final class UnreducibleLog extends RuntimeException {
  public UnreducibleLog(String message) {
    super(message);
  }
}
