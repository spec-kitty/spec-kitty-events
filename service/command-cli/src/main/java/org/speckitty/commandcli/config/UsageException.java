package org.speckitty.commandcli.config;

/** The command line is not {@code serve --git-common-dir <abs> [--idle-timeout <seconds>]}: the process prints the message and exits 2. */
public final class UsageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  static final String USAGE = "usage: command-cli serve --git-common-dir <absolute path> [--idle-timeout <seconds>]";

  UsageException(String reason) {
    super(reason + "\n" + USAGE);
  }
}
