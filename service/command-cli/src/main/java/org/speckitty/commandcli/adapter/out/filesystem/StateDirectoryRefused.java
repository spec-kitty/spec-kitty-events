package org.speckitty.commandcli.adapter.out.filesystem;

/** The state directory cannot be made safe (a symbolic link, not a directory, or its access cannot be restricted). */
public final class StateDirectoryRefused extends Exception {

  private static final long serialVersionUID = 1L;

  StateDirectoryRefused(String reason) {
    super(reason);
  }
}
