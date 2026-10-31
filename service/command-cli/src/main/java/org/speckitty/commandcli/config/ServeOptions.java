package org.speckitty.commandcli.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The command line core's launcher runs (contracts/mission-write/LAUNCHER.md, core {@code services/policies.py} WRITE_SERVICE):
 * {@code serve --git-common-dir <absolute path> [--idle-timeout <seconds>]}. Flags take their value as the next argument or after
 * {@code =}; each flag may appear once; nothing else is accepted. A refusal never echoes a value it was given (the value may be a path).
 *
 * @param gitCommonDir the absolute git common dir the service is for (one service per git common dir)
 * @param idleTimeout how long the service stays up without a request before it drains and exits
 */
public record ServeOptions(Path gitCommonDir, Duration idleTimeout) {

  /** The default idle timeout (operator ruling of 2026-10-31 on #156). */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(1800);

  private static final String SUBCOMMAND = "serve";
  private static final String GIT_COMMON_DIR = "--git-common-dir";
  private static final String IDLE_TIMEOUT = "--idle-timeout";

  /** Parses the launcher's command line or throws {@link UsageException}. */
  public static ServeOptions parse(String[] argv) {
    if (argv.length == 0 || !SUBCOMMAND.equals(argv[0])) {
      throw new UsageException("the first argument must be the subcommand serve");
    }
    String gitCommonDir = null;
    String idleTimeout = null;
    for (int i = 1; i < argv.length; i++) {
      String argument = argv[i];
      String flag;
      String value;
      int equals = argument.indexOf('=');
      if (argument.startsWith("--") && equals > 0) {
        flag = argument.substring(0, equals);
        value = argument.substring(equals + 1);
      } else {
        flag = argument;
        if (!flag.equals(GIT_COMMON_DIR) && !flag.equals(IDLE_TIMEOUT)) {
          throw new UsageException("unknown argument");
        }
        if (i + 1 >= argv.length) {
          throw new UsageException(flag + " needs a value");
        }
        value = argv[++i];
      }
      switch (flag) {
        case GIT_COMMON_DIR -> {
          if (gitCommonDir != null) {
            throw new UsageException(GIT_COMMON_DIR + " is given more than once");
          }
          gitCommonDir = value;
        }
        case IDLE_TIMEOUT -> {
          if (idleTimeout != null) {
            throw new UsageException(IDLE_TIMEOUT + " is given more than once");
          }
          idleTimeout = value;
        }
        default -> throw new UsageException("unknown argument");
      }
    }
    if (gitCommonDir == null) {
      throw new UsageException(GIT_COMMON_DIR + " is required");
    }
    return new ServeOptions(gitCommonDir(gitCommonDir), idleTimeout(idleTimeout));
  }

  private static Path gitCommonDir(String value) {
    if (value.isEmpty()) {
      throw new UsageException(GIT_COMMON_DIR + " needs a value");
    }
    Path path;
    try {
      path = Path.of(value);
    } catch (InvalidPathException e) {
      throw new UsageException(GIT_COMMON_DIR + " is not a valid path");
    }
    if (!path.isAbsolute()) {
      throw new UsageException(GIT_COMMON_DIR + " must be an absolute path");
    }
    if (!Files.isDirectory(path)) {
      throw new UsageException(GIT_COMMON_DIR + " is not a directory");
    }
    return path.toAbsolutePath();
  }

  private static Duration idleTimeout(String value) {
    if (value == null) {
      return DEFAULT_IDLE_TIMEOUT;
    }
    if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
      throw new UsageException(IDLE_TIMEOUT + " must be a positive whole number of seconds");
    }
    long seconds;
    try {
      seconds = Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new UsageException(IDLE_TIMEOUT + " must be a positive whole number of seconds");
    }
    if (seconds <= 0) {
      throw new UsageException(IDLE_TIMEOUT + " must be a positive whole number of seconds");
    }
    return Duration.ofSeconds(seconds);
  }
}
