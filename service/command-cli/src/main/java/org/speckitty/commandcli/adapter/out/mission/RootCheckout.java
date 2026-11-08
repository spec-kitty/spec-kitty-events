package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** The root checkout independently verified from a configured git common directory. */
public final class RootCheckout {
  /** A git config is a few lines; a larger one is not read (and so not verified). */
  private static final long MAX_CONFIG_BYTES = 16L * 1024 * 1024;

  private final Path root;

  private RootCheckout(Path root) {
    this.root = root;
  }

  public static RootCheckout of(Path gcd) {
    try {
      Path realGcd = gcd.toRealPath();
      if (!realGcd.getFileName().toString().equals(".git")) return new RootCheckout(null);
      Path candidate = realGcd.getParent();
      if (!candidate.resolve(".git").toRealPath().equals(realGcd) || isBare(realGcd)) return new RootCheckout(null);
      return new RootCheckout(candidate);
    } catch (IOException | RuntimeException | Error exception) {
      // Anything unreadable, unparsable or unexpected is "not verified": the Mission is then not written, never written wrongly.
      return new RootCheckout(null);
    }
  }

  public Optional<Path> root() {
    return Optional.ofNullable(root);
  }

  /**
   * Git's own reading of core.bare (every [core] section, case-insensitive, the last value wins). A config git would refuse
   * ({@link GitConfig.Unverifiable}) throws: core asks git, so a config git rejects cannot be verified.
   */
  private static boolean isBare(Path gcd) throws IOException {
    Path config = gcd.resolve("config");
    if (!Files.isRegularFile(config) || Files.size(config) > MAX_CONFIG_BYTES) return true;
    GitConfig.Result result = GitConfig.parse(Files.readAllBytes(config));
    return !result.sawCore() || result.bare();
  }
}
