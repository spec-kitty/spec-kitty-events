package org.speckitty.commandcli.adapter.out.mission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The root checkout independently verified from a configured git common directory. */
public final class RootCheckout {
  // [name], [name "subsection"] or [name.subsection], optionally followed by an entry on the same line.
  private static final Pattern SECTION = Pattern.compile("^\\[\\s*([A-Za-z0-9.-]+)(\\s+\"(?:[^\"\\\\]|\\\\.)*\")?\\s*](.*)$");
  private static final Pattern ENTRY = Pattern.compile("^([A-Za-z][-A-Za-z0-9]*)\\s*(?:[#;].*|=(.*))?$");
  private static final Pattern INTEGER = Pattern.compile("^[+-]?0*([0-9]*)[kmg]?$");

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

  /** Git's own reading of core.bare: every [core] section, case-insensitive, the last value wins. */
  private static boolean isBare(Path gcd) throws IOException {
    Path config = gcd.resolve("config");
    if (!Files.isRegularFile(config)) return true;
    boolean sawCore = false;
    boolean inCore = false;
    boolean bare = false;
    for (String line : Files.readAllLines(config)) {
      String text = line.strip();
      Matcher section = SECTION.matcher(text);
      if (section.matches()) {
        inCore = section.group(1).equalsIgnoreCase("core") && section.group(2) == null;
        sawCore |= inCore;
        text = section.group(3).strip();
      }
      Matcher entry = ENTRY.matcher(text);
      if (inCore && entry.matches() && entry.group(1).equalsIgnoreCase("bare")) bare = gitBoolean(entry.group(2));
    }
    return !sawCore || bare;
  }

  /** An unrecognised spelling counts as true: the Mission is then not written, never written wrongly. */
  private static boolean gitBoolean(String raw) {
    if (raw == null) return true;
    String value = unquote(raw).toLowerCase(Locale.ROOT);
    if (value.equals("false") || value.equals("no") || value.equals("off") || value.isEmpty()) return false;
    Matcher integer = INTEGER.matcher(value);
    return !integer.matches() || !integer.group(1).isEmpty();
  }

  private static String unquote(String raw) {
    StringBuilder value = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < raw.length(); index++) {
      char character = raw.charAt(index);
      if (character == '\\' && quoted && index + 1 < raw.length()) value.append(raw.charAt(++index));
      else if (character == '"') quoted = !quoted;
      else if (!quoted && (character == '#' || character == ';')) break;
      else value.append(character);
    }
    return value.toString().strip();
  }
}
