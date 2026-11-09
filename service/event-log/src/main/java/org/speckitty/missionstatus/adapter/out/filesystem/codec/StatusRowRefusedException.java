package org.speckitty.missionstatus.adapter.out.filesystem.codec;

/** A status row that the write protocol refuses, without echoing row content. */
public final class StatusRowRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Kind {
    NON_FINITE,
    SESSION_TIMES_NOT_SUBTRACTABLE,
  }

  private final Kind kind;

  StatusRowRefusedException(Kind kind) {
    super(kind == Kind.NON_FINITE ? "status rows cannot contain NaN or infinity" : "session times must both be naive or both be aware");
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
