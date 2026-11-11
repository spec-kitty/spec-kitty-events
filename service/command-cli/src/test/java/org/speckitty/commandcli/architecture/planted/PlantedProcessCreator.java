package org.speckitty.commandcli.architecture.planted;

/** A planted violation of the process-creation rule. It is never executed; the rule only reads its bytecode. */
public final class PlantedProcessCreator {

  public Object create() {
    return new ProcessBuilder("true");
  }
}
