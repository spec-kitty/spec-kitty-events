package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R31: the root checkout is whatever git itself says it is. Every case builds a real repository, applies what a user (or git) can
 * do to it, and pins the outcome git implies; {@code gitAccepts} states that premise independently of the code under test.
 */
class RootCheckoutTest {
  private static final String CORE = "[core]\n\trepositoryformatversion = 1\n";

  /** A repository whose config is changed after {@code git init}: a name, the config text appended, and whether git accepts it. */
  private static Map<String, Object[]> configCases() {
    Map<String, Object[]> cases = new LinkedHashMap<>();
    cases.put("untouched", new Object[] {"", true});
    cases.put("core.bare = true", new Object[] {"[core]\n\tbare = true\n", false});
    cases.put("core.bare = false later wins", new Object[] {"[core]\n\tbare = true\n[core]\n\tbare = false\n", true});
    cases.put("core.bare = true later wins", new Object[] {"[core]\n\tbare = false\n[core]\n\tbare = true\n", false});
    cases.put("CORE BARE TRUE", new Object[] {"[CORE]\n\tBARE = TRUE\n", false});
    cases.put("core.bare = maybe", new Object[] {"[core]\n\tbare = maybe\n", false});
    cases.put("core.bare with no value", new Object[] {"[core]\n\tbare\n", false});
    cases.put("core.worktree empty", new Object[] {"[core]\n\tworktree\n", false});
    cases.put("core.worktree elsewhere", new Object[] {"[core]\n\tworktree = /\n", false});
    cases.put("filemode = flase", new Object[] {"[core]\n\tfilemode = flase\n", false});
    cases.put("logallrefupdates = sometimes", new Object[] {"[core]\n\tlogallrefupdates = sometimes\n", false});
    cases.put("core.compression = 99", new Object[] {"[core]\n\tcompression = 99\n", false});
    cases.put("unknown extension, version 0", new Object[] {"[extensions]\n\tfromTheFuture = true\n", true});
    cases.put("unknown extension, version 1", new Object[] {CORE + "[extensions]\n\tfromTheFuture = true\n", false});
    cases.put("version 2", new Object[] {"[core]\n\trepositoryformatversion = 2\n", false});
    cases.put("bad objectFormat", new Object[] {"[core]\n\trepositoryformatversion = 1\n[extensions]\n\tobjectFormat = md5\n", false});
    cases.put("worktreeConfig = maybe", new Object[] {"[extensions]\n\tworktreeConfig = maybe\n", false});
    cases.put("worktreeConfig = true, v1", new Object[] {"[core]\n\trepositoryformatversion = 1\n[extensions]\n\tworktreeConfig = true\n", true});
    cases.put("garbage line", new Object[] {"[core]\n\tgarbage line\n", false});
    cases.put("unterminated quote", new Object[] {"[core]\n\tx = \"unterminated\n", false});
    cases.put("malformed section", new Object[] {"[core\n", false});
    cases.put("continuation hides nothing", new Object[] {"[core]\n\tx = a\\\nbare = true\n", true});
    cases.put("include of a good file", new Object[] {"[include]\n\tpath = good.cfg\n", true});
    cases.put("include of a broken file", new Object[] {"[include]\n\tpath = broken.cfg\n", false});
    cases.put("include of a directory", new Object[] {"[include]\n\tpath = subdir\n", false});
    cases.put("include with no value", new Object[] {"[include]\n\tpath\n", false});
    cases.put("include cycle", new Object[] {"[include]\n\tpath = cycle.cfg\n", false});
    return cases;
  }

  @Test
  void aRepositoryIsVerifiedExactlyWhenGitAcceptsItAsANonBareCheckoutAtTheRoot() throws Exception {
    for (Map.Entry<String, Object[]> entry : configCases().entrySet()) {
      Path repository = repository("root-checkout");
      try {
        Path gcd = repository.resolve(".git");
        Files.writeString(gcd.resolve("good.cfg"), "[user]\n\tname = a\n");
        Files.writeString(gcd.resolve("broken.cfg"), "[core\n");
        Files.createDirectories(gcd.resolve("subdir"));
        Files.writeString(gcd.resolve("cycle.cfg"), "[include]\n\tpath = cycle.cfg\n");
        Files.writeString(gcd.resolve("config"), (String) entry.getValue()[0], StandardOpenOption.APPEND);
        boolean accepted = (Boolean) entry.getValue()[1];
        assertThat(gitAccepts(repository)).as("git's own verdict on %s", entry.getKey()).isEqualTo(accepted);
        assertThat(RootCheckout.of(gcd).root().isPresent()).as(entry.getKey()).isEqualTo(accepted);
        if (accepted) assertThat(RootCheckout.of(gcd).root()).contains(repository.toRealPath());
      } finally {
        delete(repository);
      }
    }
  }

  @Test
  void aBareRepositoryNamedDotGitIsNeverAVerifiedRoot() throws Exception {
    Path host = Files.createTempDirectory("bare-host");
    try {
      run(host, "git", "init", "--bare", "--quiet", host.resolve(".git").toString());
      assertThat(RootCheckout.of(host.resolve(".git")).root()).isEmpty();
    } finally {
      delete(host);
    }
  }

  @Test
  void aBareCloneWhoseWorktreeRanSparseCheckoutInitIsNeverAVerifiedRoot() throws Exception {
    Path host = Files.createTempDirectory("bare-clone");
    try {
      Path source = Files.createDirectories(host.resolve("src"));
      run(source, "git", "init", "--quiet");
      run(source, "git", "-c", "user.email=t@example.invalid", "-c", "user.name=T", "commit", "--allow-empty", "-m", "i", "--quiet");
      Path project = Files.createDirectories(host.resolve("proj"));
      run(project, "git", "clone", "--bare", "--quiet", source.toString(), project.resolve(".git").toString());
      run(project, "git", "worktree", "add", "--quiet", "../wt");
      assertThat(RootCheckout.of(project.resolve(".git")).root()).isEmpty();
      // git's own sparse-checkout moves core.bare = true into config.worktree, which only git reads.
      run(host.resolve("wt"), "git", "sparse-checkout", "init");
      assertThat(Files.readString(project.resolve(".git/config.worktree"))).contains("bare = true");
      assertThat(gitAccepts(project)).isFalse();
      assertThat(RootCheckout.of(project.resolve(".git")).root()).isEmpty();
    } finally {
      delete(host);
    }
  }

  @Test
  void aNonBareCheckoutWithConfigWorktreeBareTrueIsRefusedAndWithBareFalseIsAccepted() throws Exception {
    for (boolean bare : new boolean[] {true, false}) {
      Path repository = repository("config-worktree");
      try {
        Path gcd = repository.resolve(".git");
        Files.writeString(gcd.resolve("config"), "[extensions]\n\tworktreeConfig = true\n[core]\n\trepositoryformatversion = 1\n", StandardOpenOption.APPEND);
        Files.writeString(gcd.resolve("config.worktree"), "[core]\n\tbare = " + bare + "\n");
        assertThat(gitAccepts(repository)).isEqualTo(!bare);
        assertThat(RootCheckout.of(gcd).root().isPresent()).isEqualTo(!bare);
      } finally {
        delete(repository);
      }
    }
  }

  @Test
  void aLinkedWorktreeGitDirIsNeverTheRoot() throws Exception {
    Path repository = repository("linked-root");
    try {
      run(repository, "git", "-c", "user.email=t@example.invalid", "-c", "user.name=T", "commit", "--allow-empty", "-m", "i", "--quiet");
      run(repository, "git", "worktree", "add", "--quiet", "../linked-lane-" + repository.getFileName());
      assertThat(RootCheckout.of(repository.resolve(".git/worktrees").resolve(
          "linked-lane-" + repository.getFileName())).root()).isEmpty();
      assertThat(RootCheckout.of(repository.resolve(".git")).root()).contains(repository.toRealPath());
    } finally {
      delete(repository.resolveSibling("linked-lane-" + repository.getFileName()));
      delete(repository);
    }
  }

  @Test
  void aGcdThatIsNotNamedDotGitOrIsMissingIsNeverVerified() throws Exception {
    Path repository = repository("odd-names");
    try {
      Path renamed = repository.resolveSibling(repository.getFileName() + "-gcd");
      Files.move(repository.resolve(".git"), renamed);
      assertThat(RootCheckout.of(renamed).root()).isEmpty();
      assertThat(RootCheckout.of(repository.resolve("nowhere/.git")).root()).isEmpty();
      Files.move(renamed, repository.resolve(".git"));
    } finally {
      delete(repository);
    }
  }

  @Test
  void aSymlinkedPathVerifiesToTheRealRoot() throws Exception {
    Path repository = repository("symlink-target");
    Path link = repository.resolveSibling(repository.getFileName() + "-link");
    try {
      Files.createSymbolicLink(link, repository);
      assertThat(RootCheckout.of(link.resolve(".git")).root()).contains(repository.toRealPath());
    } finally {
      Files.deleteIfExists(link);
      delete(repository);
    }
  }

  @Test
  void aMissingFailingSilentOrHungGitFailsClosed() throws Exception {
    assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
    Path repository = repository("git-failures");
    Path scripts = Files.createTempDirectory("fake-git");
    try {
      Path gcd = repository.resolve(".git");
      assertThat(RootCheckout.of(gcd, "git", 5).root()).contains(repository.toRealPath());
      assertThat(RootCheckout.of(gcd, scripts.resolve("no-such-git").toString(), 5).root()).as("missing binary").isEmpty();
      String real = repository.toRealPath().toString();
      Map<String, String> fakes = new LinkedHashMap<>();
      fakes.put("non-zero exit", "exit 1");
      fakes.put("exit 0, no output", "exit 0");
      fakes.put("short output", "printf '.git\\nfalse\\n'");
      fakes.put("long output", "printf '.git\\nfalse\\n%s\\nextra\\n' '" + real + "'");
      fakes.put("no trailing newline", "printf '.git\\nfalse\\n%s' '" + real + "'");
      fakes.put("bare answer", "printf '.git\\ntrue\\n%s\\n' '" + real + "'");
      fakes.put("other top level", "printf '.git\\nfalse\\n%s\\n' '" + scripts + "'");
      fakes.put("other common dir", "printf '%s\\nfalse\\n%s\\n' '" + scripts + "' '" + real + "'");
      fakes.put("unresolvable path", "printf '.git\\nfalse\\n/no/such/path\\n'");
      fakes.put("huge output", "head -c 200000 /dev/zero | tr '\\0' 'a'");
      fakes.put("killed by a signal", "kill -9 $$");
      for (Map.Entry<String, String> fake : fakes.entrySet()) {
        Path script = script(scripts, fake.getKey().replace(' ', '-'), fake.getValue());
        assertThat(RootCheckout.of(gcd, script.toString(), 5).root()).as(fake.getKey()).isEmpty();
      }
      Path agreeing = script(scripts, "agreeing", "printf '.git\\nfalse\\n%s\\n' '" + real + "'");
      assertThat(RootCheckout.of(gcd, agreeing.toString(), 5).root()).as("a git that agrees").contains(repository.toRealPath());
      Path hung = script(scripts, "hung", "sleep 30");
      long started = System.nanoTime();
      assertThat(RootCheckout.of(gcd, hung.toString(), 1).root()).as("timeout").isEmpty();
      assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10);
    } finally {
      delete(scripts);
      delete(repository);
    }
  }

  @Test
  void anInterruptedStartupFailsClosedAndKeepsTheInterrupt() throws Exception {
    assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
    Path repository = repository("interrupted");
    Path scripts = Files.createTempDirectory("fake-git");
    try {
      Path hung = script(scripts, "hung", "sleep 30");
      Thread.currentThread().interrupt();
      assertThat(RootCheckout.of(repository.resolve(".git"), hung.toString(), 30).root()).isEmpty();
      assertThat(Thread.interrupted()).isTrue();
    } finally {
      Thread.interrupted();
      delete(scripts);
      delete(repository);
    }
  }

  @Test
  void everyGitVariableIsRemovedFromTheChildEnvironment() {
    Map<String, String> environment = new LinkedHashMap<>(Map.of(
        "GIT_DIR", "x", "GIT_WORK_TREE", "x", "git_config_count", "1", "GIT_CONFIG_KEY_0", "core.bare",
        "PATH", "/usr/bin", "HOME", "/home/x", "NOT_GIT_DIR", "kept", "GITHUB_TOKEN", "kept"));
    RootCheckout.clearGitEnvironment(environment);
    assertThat(environment).containsOnlyKeys("PATH", "HOME", "NOT_GIT_DIR", "GITHUB_TOKEN");
  }

  /** git's own verdict, stated without the code under test: exit 0, not bare, and the top level is the repository itself. */
  private static boolean gitAccepts(Path repository) throws IOException, InterruptedException {
    Process process = new ProcessBuilder("git", "rev-parse", "--is-bare-repository", "--show-toplevel")
        .directory(repository.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
    String[] lines = output.strip().split("\n");
    return lines.length == 2 && lines[0].equals("false") && Path.of(lines[1]).toRealPath().equals(repository.toRealPath());
  }

  private static Path repository(String name) throws Exception {
    Path repository = Files.createTempDirectory(name);
    run(repository, "git", "init", "--quiet");
    return repository;
  }

  private static Path script(Path directory, String name, String body) throws IOException {
    Path script = directory.resolve(name);
    Files.writeString(script, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return script;
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new AssertionError(String.join(" ", command) + ": " + output);
    }
  }

  private static void delete(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (Stream<Path> paths = Files.walk(path)) {
      paths.sorted(Comparator.reverseOrder()).forEach(item -> item.toFile().delete());
    }
  }
}
