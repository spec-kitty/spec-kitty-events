package org.speckitty.commandcli.adapter.out.mission;

import java.util.Locale;
import java.util.Set;

/**
 * A strict, linear port of git's own config parser (config.c {@code git_parse_source}), used to read {@code core.bare}.
 *
 * <p>Core asks git. Git refuses a config it cannot parse, and reads some spellings differently from a lenient reader, so
 * anything git rejects is rejected here ({@link Unverifiable}) and the caller treats the checkout as not verifiable (R17,
 * WP08 review 8). The scan is one pass over the bytes: no recursion and no backtracking regex.
 */
final class GitConfig {
  /** The config is a syntax git refuses, so what git would answer cannot be known. */
  static final class Unverifiable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unverifiable() {
      super("a git config line git does not accept", null, false, false);
    }
  }

  /** The extensions a version-1 repository may carry (an unknown one makes git refuse it). */
  private static final Set<String> KNOWN_EXTENSIONS = Set.of(
      "noop", "preciousobjects", "partialclone", "worktreeconfig", "objectformat", "compatobjectformat", "refstorage",
      "relativeworktrees", "submodulepathconfig");

  private final byte[] input;
  private int position;
  private boolean ended;
  private String section = "";
  private boolean sawCore;
  private boolean bare;
  private long formatVersion;
  private boolean unknownExtension;

  private GitConfig(byte[] input) {
    this.input = input;
  }

  /** What git reads: whether a [core] section exists and the last core.bare value (false when none is set). */
  record Result(boolean sawCore, boolean bare) {}

  static Result parse(byte[] input) {
    GitConfig scan = new GitConfig(input);
    scan.skipBom();
    scan.run();
    // Git refuses a repository whose format it does not know (setup.c verify_repository_format).
    if (scan.formatVersion > 1 || (scan.formatVersion == 1 && scan.unknownExtension)) throw new Unverifiable();
    return new Result(scan.sawCore, scan.bare);
  }

  /** The entries git's repository setup acts on: core.bare, core.repositoryformatversion and the extensions. */
  private void record(String key, String value) {
    if (section.equals("core") && key.equals("bare")) {
      bare = truth(value);
    } else if (section.equals("core") && key.equals("repositoryformatversion")) {
      formatVersion = integer(value);
    } else if (section.equals("extensions") && !KNOWN_EXTENSIONS.contains(key)) {
      unknownExtension = true;
    }
  }

  /**
   * git_config_bool: true for a bare key, the true spellings and a non-zero integer, false for "", the false spellings and
   * zero. Git evaluates every core.bare entry as it reads it and dies on one it cannot parse, even when a later entry
   * overrides it, so such a value is {@link Unverifiable}.
   */
  private static boolean truth(String value) {
    if (value == null) return true;
    String text = cString(value);
    if (text.isEmpty()) return false;
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.equals("true") || lower.equals("yes") || lower.equals("on")) return true;
    if (lower.equals("false") || lower.equals("no") || lower.equals("off")) return false;
    return integer(value) != 0;
  }

  /** A config value is a C string: it ends at the first NUL. */
  private static String cString(String value) {
    int cut = value.indexOf('\0');
    return cut < 0 ? value : value.substring(0, cut);
  }

  private void skipBom() {
    if (input.length >= 3 && (input[0] & 0xff) == 0xef && (input[1] & 0xff) == 0xbb && (input[2] & 0xff) == 0xbf) position = 3;
  }

  /** git's get_next_char: CRLF is LF, and the end of input is one newline with the end flag set. */
  private int next() {
    if (position >= input.length) {
      ended = true;
      return '\n';
    }
    int character = input[position++] & 0xff;
    if (character == '\r' && position < input.length && input[position] == '\n') {
      position++;
      return '\n';
    }
    return character;
  }

  private void run() {
    boolean comment = false;
    while (true) {
      int character = next();
      if (character == '\n') {
        if (ended) return;
        comment = false;
      } else if (comment || isSpace(character)) {
        continue;
      } else if (character == '#' || character == ';') {
        comment = true;
      } else if (character == '[') {
        section = baseVariable();
        if (section.equals("core")) sawCore = true;
      } else if (isAlpha(character)) {
        entry(character);
      } else {
        throw new Unverifiable();
      }
    }
  }

  /** git's get_base_var: {@code [name]}, {@code [name.sub]} or {@code [name "sub"]}; an empty name is refused. */
  private String baseVariable() {
    StringBuilder name = new StringBuilder();
    while (true) {
      int character = next();
      if (ended) throw new Unverifiable();
      if (character == ']') {
        if (name.length() == 0) throw new Unverifiable();
        return name.toString();
      }
      if (isSpace(character)) return extendedVariable(name, character);
      if (!isKeyChar(character) && character != '.') throw new Unverifiable();
      name.append(Character.toLowerCase((char) character));
    }
  }

  private String extendedVariable(StringBuilder name, int first) {
    int character = first;
    do {
      if (character == '\n') throw new Unverifiable();
      character = next();
    } while (isSpace(character));
    if (character != '"') throw new Unverifiable();
    name.append('.');
    while (true) {
      character = next();
      if (character == '\n') throw new Unverifiable();
      if (character == '"') break;
      if (character == '\\') {
        character = next();
        if (character == '\n') throw new Unverifiable();
      }
      name.append((char) character);
    }
    if (next() != ']') throw new Unverifiable();
    return name.toString();
  }

  /** git's get_value: the rest of the key name, then nothing (a bare key) or {@code =} and a value. */
  private void entry(int firstCharacter) {
    StringBuilder key = new StringBuilder().append(Character.toLowerCase((char) firstCharacter));
    int character;
    while (true) {
      character = next();
      if (ended || !isKeyChar(character)) break;
      key.append(Character.toLowerCase((char) character));
    }
    while (character == ' ' || character == '\t') character = next();
    String value = null;
    if (character != '\n') {
      if (character != '=') throw new Unverifiable();
      value = value();
    }
    record(key.toString(), value);
  }

  /** git's parse_value: quotes, escapes, comments and line continuation. */
  private String value() {
    StringBuilder value = new StringBuilder();
    boolean quote = false;
    boolean comment = false;
    int space = 0;
    while (true) {
      int character = next();
      if (character == '\n') {
        if (quote) throw new Unverifiable();
        return value.toString();
      }
      if (comment) continue;
      if (isSpace(character) && !quote) {
        if (value.length() > 0) space++;
        continue;
      }
      if (!quote && (character == ';' || character == '#')) {
        comment = true;
        continue;
      }
      for (; space > 0; space--) value.append(' ');
      if (character == '\\') {
        character = escaped(next());
        if (character >= 0) value.append((char) character);
      } else if (character == '"') {
        quote = !quote;
      } else {
        value.append((char) character);
      }
    }
  }

  /** The character a backslash escape stands for, or -1 for a line continuation; an unknown escape is refused. */
  private static int escaped(int character) {
    switch (character) {
      case '\n':
        return -1;
      case 't':
        return '\t';
      case 'b':
        return '\b';
      case 'n':
        return '\n';
      case '\\':
      case '"':
        return character;
      default:
        throw new Unverifiable();
    }
  }

  private static boolean isSpace(int character) {
    return character == ' ' || character == '\t' || character == '\n' || character == '\r';
  }

  private static boolean isAlpha(int character) {
    return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
  }

  private static boolean isKeyChar(int character) {
    return isAlpha(character) || (character >= '0' && character <= '9') || character == '-';
  }

  /**
   * git_config_int: C's strtoimax with base 0 (decimal, 0x hex, 0 octal), an optional k, m or g suffix and the range of an
   * int. A missing, invalid or out-of-range integer makes git die, so it is Unverifiable.
   */
  private static long integer(String raw) {
    if (raw == null) throw new Unverifiable();
    String text = cString(raw);
    int index = skipSpaces(text);
    boolean negative = index < text.length() && text.charAt(index) == '-';
    if (index < text.length() && (negative || text.charAt(index) == '+')) index++;
    int radix = 10;
    if (hasHexPrefix(text, index)) {
      radix = 16;
      index += 2;
    } else if (text.startsWith("0", index)) {
      radix = 8;
    }
    int end = index;
    while (end < text.length() && digit(text.charAt(end), radix) >= 0) end++;
    long factor = unitFactor(text.substring(end));
    long magnitude = magnitude(text, index, end, radix);
    if (end == index || factor == 0 || magnitude * factor > Integer.MAX_VALUE) throw new Unverifiable();
    return negative ? -magnitude * factor : magnitude * factor;
  }

  /** The digits of text[from, to) in the radix; past 2^40 the value is out of an int's range, so it stops growing. */
  private static long magnitude(String text, int from, int to, int radix) {
    long magnitude = 0;
    for (int index = from; index < to && magnitude < (1L << 40); index++) {
      magnitude = magnitude * radix + digit(text.charAt(index), radix);
    }
    return magnitude;
  }

  private static int skipSpaces(String text) {
    int index = 0;
    while (index < text.length() && isCSpace(text.charAt(index))) index++;
    return index;
  }

  private static boolean hasHexPrefix(String text, int index) {
    return text.startsWith("0", index) && index + 2 < text.length() && (text.charAt(index + 1) | 0x20) == 'x'
        && digit(text.charAt(index + 2), 16) >= 0;
  }

  private static int digit(char character, int radix) {
    return character < 128 ? Character.digit(character, radix) : -1;
  }

  private static long unitFactor(String suffix) {
    switch (suffix.toLowerCase(Locale.ROOT)) {
      case "":
        return 1;
      case "k":
        return 1024;
      case "m":
        return 1024L * 1024;
      case "g":
        return 1024L * 1024 * 1024;
      default:
        return 0;
    }
  }

  private static boolean isCSpace(char character) {
    return character == ' ' || (character >= '\t' && character <= '\r');
  }
}
