package org.speckitty.commandcli.domain.service;

/**
 * The life of one service instance. It only moves forward: READY accepts requests, DRAINING (after a shutdown request or an idle exit)
 * finishes what it holds and refuses new writes with SERVICE_DRAINING, STOPPED has removed its endpoint file and exits.
 * {@link #wireValue()} is the contract's HealthStatus for the two states a health answer can report.
 */
public enum ServiceState {
  READY("ready"),
  DRAINING("draining"),
  STOPPED("draining");

  private final String wireValue;

  ServiceState(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The HealthStatus value: {@code ready} or {@code draining} (a stopping service still reports draining). */
  public String wireValue() {
    return wireValue;
  }

  /** True when this state may move to {@code next}: forward only, and a repeated state is not a move. */
  public boolean canMoveTo(ServiceState next) {
    return next.ordinal() > ordinal();
  }
}
