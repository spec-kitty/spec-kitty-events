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
