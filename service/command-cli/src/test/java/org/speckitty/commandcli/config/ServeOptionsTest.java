package org.speckitty.commandcli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The command line of LAUNCHER.md: {@code serve --git-common-dir <abs> [--idle-timeout <seconds>]} (FR-001). */
@Tag("native")
class ServeOptionsTest {

  @TempDir Path gcd;

  @Test
  void parsesTheLauncherCommandLineWithTheDefaultIdleTimeout() {
    ServeOptions options = ServeOptions.parse(new String[] {"serve", "--git-common-dir", gcd.toString()});

    assertThat(options.gitCommonDir()).isEqualTo(gcd.toAbsolutePath());
    assertThat(options.idleTimeout()).isEqualTo(Duration.ofSeconds(1800));
  }

  @Test
  void parsesAnExplicitIdleTimeoutAndTheEqualsForm() {
    ServeOptions options = ServeOptions.parse(new String[] {"serve", "--git-common-dir=" + gcd, "--idle-timeout", "2"});

    assertThat(options.gitCommonDir()).isEqualTo(gcd.toAbsolutePath());
    assertThat(options.idleTimeout()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void acceptsTheIdleTimeoutBeforeTheGitCommonDir() {
    ServeOptions options = ServeOptions.parse(new String[] {"serve", "--idle-timeout=60", "--git-common-dir", gcd.toString()});

    assertThat(options.idleTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  static Stream<Arguments> refused() {
    return Stream.of(
        Arguments.of("no arguments", new String[] {}),
        Arguments.of("no subcommand", new String[] {"--git-common-dir", "/tmp"}),
        Arguments.of("unknown subcommand", new String[] {"run", "--git-common-dir", "/tmp"}),
        Arguments.of("no git common dir", new String[] {"serve"}),
        Arguments.of("flag without value", new String[] {"serve", "--git-common-dir"}),
        Arguments.of("empty value", new String[] {"serve", "--git-common-dir="}),
        Arguments.of("relative path", new String[] {"serve", "--git-common-dir", "relative/.git"}),
        Arguments.of("drive-letter path on POSIX", new String[] {"serve", "--git-common-dir", "C:relative"}),
        Arguments.of("unknown flag", new String[] {"serve", "--git-common-dir", "/tmp", "--port", "1"}),
        Arguments.of("positional extra", new String[] {"serve", "--git-common-dir", "/tmp", "extra"}),
        Arguments.of("repeated flag", new String[] {"serve", "--git-common-dir", "/tmp", "--git-common-dir", "/tmp"}),
        Arguments.of("zero idle timeout", new String[] {"serve", "--git-common-dir", "/tmp", "--idle-timeout", "0"}),
        Arguments.of("negative idle timeout", new String[] {"serve", "--git-common-dir", "/tmp", "--idle-timeout", "-5"}),
        Arguments.of("signed idle timeout", new String[] {"serve", "--git-common-dir", "/tmp", "--idle-timeout", "+5"}),
        Arguments.of("fractional idle timeout", new String[] {"serve", "--git-common-dir", "/tmp", "--idle-timeout", "1.5"}),
        Arguments.of("overflowing idle timeout", new String[] {"serve", "--git-common-dir", "/tmp", "--idle-timeout", "99999999999999999999"}),
        Arguments.of("repeated idle timeout", new String[] {"serve", "--git-common-dir", "/tmp", "--idle-timeout", "1", "--idle-timeout", "2"}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refused")
  void refusesAnythingElseWithAUsageError(String name, String[] argv) {
    assertThatThrownBy(() -> ServeOptions.parse(argv)).isInstanceOf(UsageException.class).hasMessageContaining("usage:");
  }

  @Test
  void refusesAGitCommonDirThatIsNotADirectory() throws Exception {
    Path file = Files.createFile(gcd.resolve("not-a-dir"));

    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"serve", "--git-common-dir", file.toString()}))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("not a directory");
  }

  @Test
  void refusesAGitCommonDirThatDoesNotExist() {
    Path missing = gcd.resolve("missing");

    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"serve", "--git-common-dir", missing.toString()}))
        .isInstanceOf(UsageException.class)
        .hasMessageContaining("not a directory");
  }

  @Test
  void theUsageMessageNamesNoPathItWasGiven() {
    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"serve", "--git-common-dir", "/secret/place/x", "--bogus"}))
        .isInstanceOf(UsageException.class)
        .message()
        .doesNotContain("/secret/place");
  }
}
