package org.speckitty.commandcli.architecture.planted;

/** A planted violation of the process-creation rule through Runtime.exec. It is never executed; the rule only reads its bytecode. */
public final class PlantedRuntimeExecCaller {

  public Object create() throws java.io.IOException {
    return Runtime.getRuntime().exec("true");
  }
}
