package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The root checkout independently verified from a configured git common directory, by asking git the way core resolves its root
 * (R31): {@code git rev-parse --git-common-dir --is-bare-repository --show-toplevel} once at startup. Git, not a port of its
 * config reader, decides what a repository is, so a config, include, extension or worktree layout git refuses is never verified.
 */
public final class RootCheckout {
  /**
   * A local {@code rev-parse} takes milliseconds. 5 seconds leaves two orders of magnitude for a cold start or a slow disk, and a
   * hung git (an unresponsive mount) delays startup by at most that, well inside the launcher's 15 s start budget.
   */
  static final long GIT_TIMEOUT_SECONDS = 5;

  /** Three short lines; anything near this bound is not an answer from git rev-parse. */
  private static final int MAX_OUTPUT_BYTES = 64 * 1024;

  private final Path root;

  private RootCheckout(Path root) {
    this.root = root;
  }

  public static RootCheckout of(Path gcd) {
    return of(gcd, "git", GIT_TIMEOUT_SECONDS);
  }

  /** The executable and the bound are parameters so a missing, failing, silent or hung git is testable. */
  static RootCheckout of(Path gcd, String git, long timeoutSeconds) {
    try {
      Path realGcd = gcd.toRealPath();
      if (!realGcd.getFileName().toString().equals(".git")) return new RootCheckout(null);
      Path candidate = realGcd.getParent();
      if (!candidate.resolve(".git").toRealPath().equals(realGcd)) return new RootCheckout(null);
      return gitAgrees(candidate, realGcd, git, timeoutSeconds) ? new RootCheckout(candidate) : new RootCheckout(null);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new RootCheckout(null);
    } catch (IOException | RuntimeException | Error exception) {
      // A missing git, an unreadable path or anything unexpected is "not verified": the Mission is then not written, never written wrongly.
      return new RootCheckout(null);
    }
  }

  public Optional<Path> root() {
    return Optional.ofNullable(root);
  }

  /** Verified only when git exits 0, says the repository is not bare, and names this root's own checkout and common dir. */
  private static boolean gitAgrees(Path candidate, Path realGcd, String git, long timeoutSeconds) throws IOException, InterruptedException {
    List<String> lines = revParse(candidate, git, timeoutSeconds);
    if (lines.size() != 3 || !lines.get(1).equals("false")) return false;
    Path commonDir = candidate.resolve(lines.get(0)).toRealPath();
    Path topLevel = Path.of(lines.get(2)).toRealPath();
    return commonDir.equals(realGcd) && topLevel.equals(candidate);
  }

  /** The three answer lines in request order, or an empty list for any failure, a timeout included. */
  private static List<String> revParse(Path directory, String git, long timeoutSeconds) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(git, "rev-parse", "--git-common-dir", "--is-bare-repository", "--show-toplevel")
        .directory(directory.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD);
    // Git is asked about this directory alone: an inherited GIT_DIR, GIT_WORK_TREE, GIT_CONFIG_* and the like would answer for another.
    clearGitEnvironment(builder.environment());
    Process process = builder.start();
    try {
      process.getOutputStream().close();
      // The answer is far below the pipe's capacity, so git never blocks on writing it and the timeout bounds the whole call.
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || process.exitValue() != 0) return List.of();
      byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
      if (output.length > MAX_OUTPUT_BYTES) return List.of();
      String text = new String(output, StandardCharsets.UTF_8);
      return text.endsWith("\n") ? List.of(text.substring(0, text.length() - 1).split("\n", -1)) : List.of();
    } finally {
      process.destroyForcibly();
    }
  }

  /** Removes every {@code GIT_*} variable (any case, for platforms that fold it). */
  static void clearGitEnvironment(Map<String, String> environment) {
    environment.keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
  }
}
