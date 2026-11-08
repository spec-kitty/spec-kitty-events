package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** core.bare read the way git reads it: every [core] section, case-insensitive, the last value wins. */
class RootCheckoutTest {
  private static Map<String, Boolean> cases() {
    Map<String, Boolean> cases = new LinkedHashMap<>(); // config text -> a root checkout is accepted
    cases.put("[core]\n\tbare = false\n", true);
    cases.put("[core]\n\tbare = true\n", false);
    cases.put("[core]\n\trepositoryformatversion = 0\n", true);
    cases.put("[remote \"o\"]\n\turl = x\n", false);
    cases.put("", false);
    cases.put("[core]\n\tbare = false\n[remote \"o\"]\n\turl = x\n[core]\n\tbare = true\n", false);
    cases.put("[core]\n\tbare = true\n[remote \"o\"]\n\turl = x\n[core]\n\tbare = false\n", true);
    cases.put("[core]\n\tbare = true\n\tbare = false\n", true);
    cases.put("[core]\n\tbare = false\n\tbare\n", false);
    cases.put("[CORE]\n\tBARE = TRUE\n", false);
    cases.put("[Core]\n\tBare = No\n", true);
    cases.put("[core]\n\tbare = \"true\"\n", false);
    cases.put("[core]\n\tbare = \"false\"\n", true);
    cases.put("[core]\n\tbare = \"tr\"\"ue\"\n", false);
    cases.put("[core] bare = true\n", false);
    cases.put("[core] # comment\n\tbare = false\n", true);
    cases.put("[core]\n\tbare = true # comment\n", false);
    cases.put("[core]\n\tbare = false ; comment\n", true);
    cases.put("[core]\n\tbare = \"false # not a comment\"\n", false);
    cases.put("[core]\n\tbare\n", false);
    cases.put("[core]\n\tbare = \n", true);
    cases.put("[core]\n\tbare = \"\"\n", true);
    cases.put("[core \"sub\"]\n\tbare = true\n[core]\n\tbare = false\n", true);
    cases.put("[core.sub]\n\tbare = true\n[core]\n\tbare = false\n", true);
    cases.put("[core]\n\tbare = false\n[other]\n\tbare = true\n", true);
    cases.put("[core]\n\tbare = maybe\n", false);
    cases.put("[user]\n\tname = Jos\u00e9\n[core]\n\tbare = false\n", true); // written as Latin-1: not UTF-8
    cases.put("[core]\n\tbare = 0x0\n", true); // strtol base 0: hex zero is false
    cases.put("[core]\n\tbare = 0x1\n", false);
    cases.put("[core]\n\tbare = 010\n", false); // octal 8
    cases.put("[core]\n\tbare = false\n\tx = a\\\nbare = true\n", true); // the continuation is inside x's value
    cases.put("[core]\n\tbare = true\n\tx = a\\\nbare = false\n", false); // so it does not hide a bare = false
    cases.put("[core]\n\tbare = false\r\n\tx = 1\r\n", true);
    cases.put("\u00ef\u00bb\u00bf[core]\n\tbare = false\n", true);
    cases.put("[core]\n\trepositoryformatversion = 1\n[extensions]\n\tworktreeConfig = true\n", true);
    cases.put("[core]\n\tbare = false\n[extensions]\n\tworktreeConfig = true\n", true);
    cases.put("[core]\n\trepositoryformatversion = 1\n[extensions]\n\tfromTheFuture = true\n", false);
    cases.put("[core]\n\trepositoryformatversion = 0\n[extensions]\n\tfromTheFuture = true\n", true);
    cases.put("[core]\n\trepositoryformatversion = 2\n", false);
    cases.put("[core]\n\trepositoryformatversion = x\n", false);
    cases.put("[core]\n\tbare = maybe\n\tbare = false\n", false); // git dies on the first value it cannot read
    cases.put("[branch \"" + "a".repeat(100_000) + "\"]\n\tremote = o\n[core]\n\tbare = false\n", true);
    cases.put("[branch \"" + "a\\\"".repeat(40_000) + "\"]\n[core]\n\tbare = false\n", true);
    cases.put("[core]\n\tbare = false\n\tx = " + "y".repeat(100_000) + "\n", true);
    for (String spelling : new String[] {"true", "yes", "on", "1", "2", "-1", "1k", "TRUE", "Yes", "ON"}) {
      cases.put("[core]\n\tbare = " + spelling + "\n", false);
    }
    for (String spelling : new String[] {"false", "no", "off", "0", "00", "0k", "FALSE", "No", "OFF"}) {
      cases.put("[core]\n\tbare = " + spelling + "\n", true);
    }
    return cases;
  }

  @Test
  void bareFollowsGitsLastValueWinsRuleAcrossEveryCoreSection() throws Exception {
    for (Map.Entry<String, Boolean> expected : cases().entrySet()) {
      Path host = Files.createTempDirectory("root-checkout");
      try {
        Path gcd = host.resolve(".git");
        Files.createDirectories(gcd);
        Files.write(gcd.resolve("config"), expected.getKey().getBytes(StandardCharsets.ISO_8859_1));
        assertThat(RootCheckout.of(gcd).root().isPresent()).as(expected.getKey()).isEqualTo(expected.getValue());
        String git = gitBoolean(gcd.resolve("config"));
        if (git != null) {
          assertThat(expected.getValue()).as("git itself reads %s for %s", git, expected.getKey()).isEqualTo(git.equals("false"));
        }
      } finally {
        delete(host);
      }
    }
  }

  /** Config text git refuses ("bad config line", an unterminated quote, an unknown escape): never a verified root. */
  private static Map<String, String> refused() {
    Map<String, String> refused = new LinkedHashMap<>(); // config text -> what is wrong with it
    refused.put("[core]\n\tbare = true\n\tbare = fal\\se\n", "an unknown escape");
    refused.put("[core]\n\tbare = false\n\tx = \"unterminated\n", "an unterminated quote");
    refused.put("[core\n\tbare = false\n", "a malformed section header");
    refused.put("[core]\n\tbare = false\n[remote \"o\n\turl = x\n", "an unterminated subsection");
    refused.put("[core]\n\tbare = false\ncore.bare = true\n", "a dotted key outside a section header");
    refused.put("[core]\n\tbare = false\n\tgarbage line\n", "a line that is neither a section nor an entry");
    refused.put("[core]\n\tbare = false\n\t= value\n", "an entry with no name");
    refused.put("[core]\n\tbare = false\n\t9x = 1\n", "a key that does not start with a letter");
    refused.put("[ core ]\n\tbare = false\n", "a space inside the brackets");
    refused.put("[core ]\n\tbare = false\n", "a trailing space inside the brackets");
    refused.put("[core \"x\" ]\n\tbare = false\n", "text after the closing quote");
    refused.put("[]\n\tbare = false\n", "an empty section name");
    refused.put("[core]\n\tbare = false\n\tx # comment\n", "a comment after a key with no value");
    refused.put("[core]\n\tbare = \"false;\"\n", "a boolean git cannot read");
    refused.put("[core]\n\tbare = 08\n", "an invalid octal");
    refused.put("[core]\n\tbare = 4294967296\n", "an integer beyond an int");
    return refused;
  }

  @Test
  void aConfigGitRefusesIsNeverAVerifiedRootCheckout() throws Exception {
    for (Map.Entry<String, String> expected : refused().entrySet()) {
      Path host = Files.createTempDirectory("root-checkout");
      try {
        Path gcd = host.resolve(".git");
        Files.createDirectories(gcd);
        Files.write(gcd.resolve("config"), expected.getKey().getBytes(StandardCharsets.ISO_8859_1));
        assertThat(RootCheckout.of(gcd).root()).as("%s: %s", expected.getValue(), expected.getKey()).isEmpty();
        assertThat(gitReads(gcd.resolve("config"))).as("git itself refuses to read core.bare: %s", expected.getValue()).isFalse();
      } finally {
        delete(host);
      }
    }
  }

  @Test
  void aConfigOverTheSizeLimitIsNotRead() throws Exception {
    Path host = Files.createTempDirectory("root-checkout");
    try {
      Path gcd = host.resolve(".git");
      Files.createDirectories(gcd);
      byte[] filler = "# x\n".repeat(4 * 1024 * 1024 + 1).getBytes(StandardCharsets.ISO_8859_1);
      Files.write(gcd.resolve("config"), filler);
      assertThat(RootCheckout.of(gcd).root()).isEmpty();
    } finally {
      delete(host);
    }
  }

  private static boolean gitReads(Path config) throws IOException, InterruptedException {
    Process process = new ProcessBuilder("git", "config", "--file", config.toString(), "--type=bool", "--get", "core.bare").redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    // Reading core.bare as a boolean is what core's git does: a refused file, or a value that is no boolean, fails.
    return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
  }

  /** git's own answer, or null where git has none or refuses the value (the service then refuses too). */
  private static String gitBoolean(Path config) throws IOException, InterruptedException {
    Process process = new ProcessBuilder("git", "config", "--file", config.toString(), "--type=bool", "--get", "core.bare")
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
    return output;
  }

  private static void delete(Path path) throws IOException {
    try (Stream<Path> paths = Files.walk(path)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(item -> item.toFile().delete());
    }
  }
}
