package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/** The root checkout independently verified from a configured git common directory. */
public final class RootCheckout {
  private static final Pattern CORE = Pattern.compile("(?ms)^\\s*\\[core]\\s*(.*?)(?=^\\s*\\[|\\z)");
  private static final Pattern BARE = Pattern.compile("(?m)^\\s*bare\\s*=\\s*true\\s*(?:[#;].*)?$");

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
    } catch (IOException | RuntimeException exception) {
      return new RootCheckout(null);
    }
  }

  public Optional<Path> root() {
    return Optional.ofNullable(root);
  }

  private static boolean isBare(Path gcd) throws IOException {
    Path config = gcd.resolve("config");
    if (!Files.isRegularFile(config)) return true;
    String text = Files.readString(config);
    var core = CORE.matcher(text);
    return !core.find() || BARE.matcher(core.group(1)).find();
  }
}
