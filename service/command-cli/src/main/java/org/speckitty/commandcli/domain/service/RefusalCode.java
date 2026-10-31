package org.speckitty.commandcli.domain.service;

/**
 * The refusal codes this service answers in PR1, each with the status the contract fixes for it (contracts/mission-write
 * WriteRefusalCode and the status bindings of WriteRefusal). The names are the contract's values verbatim.
 */
public enum RefusalCode {
  REQUEST_REFUSED(403, "Request refused"),
  WRITE_TOKEN_INVALID(401, "Write token invalid"),
  PROTOCOL_VERSION_UNSUPPORTED(400, "Protocol version unsupported"),
  REQUEST_TOO_LARGE(413, "Request too large"),
  REQUEST_MALFORMED(400, "Request malformed"),
  SERVICE_DRAINING(503, "Service draining"),
  STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED(500, "Status event persistence verification failed");

  private final int status;
  private final String title;

  RefusalCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }
}
