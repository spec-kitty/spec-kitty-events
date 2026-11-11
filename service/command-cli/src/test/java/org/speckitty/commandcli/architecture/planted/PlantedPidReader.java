package org.speckitty.commandcli.architecture.planted;

/** A planted non-violation: reads its own pid, which is not process creation (config/EndpointPublisher does the same). */
public final class PlantedPidReader {

  public long pid() {
    return ProcessHandle.current().pid();
  }
}
