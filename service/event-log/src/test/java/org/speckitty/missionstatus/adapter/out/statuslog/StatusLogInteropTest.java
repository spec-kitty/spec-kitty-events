package org.speckitty.missionstatus.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLock;
import org.speckitty.missionstatus.adapter.out.ledger.LedgerLocks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cross-process evidence that this appender and core's real status writer share one lock and never interleave rows. */
@EnabledIfEnvironmentVariable(named = "SPEC_KITTY_LOCK_INTEROP_PYTHON", matches = ".+")
class StatusLogInteropTest {

  private static final int PYTHON_WRITERS = 3;
  private static final int JAVA_WRITER = 9;
  private static final int ITERATIONS = 100;
  private static final Duration WAIT = Duration.ofSeconds(60);
  private static final Path PEER = Path.of("src", "test", "resources", "statuslog", "status_writer_peer.py");
  private static final Path OUT = Path.of("build", "status-log-interop");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path repo;

  @Test
  @Timeout(value = 15, unit = TimeUnit.MINUTES)
  void javaAndCoreStatusWritersAreMutuallyExclusive() throws Exception {
    run(repo, "git", "init", "-q");
    Path gcd = repo.resolve(".git").toRealPath();
    Path mission = Files.createDirectories(repo.resolve("kitty-specs/write-core-01M6TP6W"));
    Path lockPath = MissionLockPath.of(gcd, mission.getFileName().toString());
    JsonNode peerPath = peer("lock-path", "--gcd", gcd.toString(), "--mission-dir", mission.toString());
    assertThat(Path.of(peerPath.get("lock_path").asString())).as("core and Java derive the same status lock").isEqualTo(lockPath);
    assertThat(peerHoldsJavaTimesOut(gcd, mission, lockPath)).isTrue();

    Path sync = Files.createTempDirectory("status-log-interop");
    Path start = sync.resolve("start");
    List<Process> writers = new ArrayList<>();
    for (int writer = 1; writer <= PYTHON_WRITERS; writer++) {
      Path ready = sync.resolve("ready-" + writer);
      writers.add(
          startPeer(
              "append",
              "--gcd", gcd.toString(),
              "--mission-dir", mission.toString(),
              "--writer", String.valueOf(writer),
              "--count", String.valueOf(ITERATIONS),
              "--ready", ready.toString(),
              "--start", start.toString()));
    }
    for (int writer = 1; writer <= PYTHON_WRITERS; writer++) {
      awaitFile(sync.resolve("ready-" + writer));
    }
    Files.createFile(start);

    int javaContended = appendJavaRows(gcd, mission, lockPath);
    for (Process writer : writers) {
      JsonNode result = json(finish(writer));
      assertThat(result.get("appended").asInt()).isEqualTo(ITERATIONS);
    }

    Path log = mission.resolve("status.events.jsonl");
    List<String> lines = Files.readString(log, StandardCharsets.UTF_8).lines().toList();
    Set<String> expected = expectedIds();
    Set<String> actual = new HashSet<>();
    for (String line : lines) {
      actual.add(JSON.readTree(line).get("event_id").asString());
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("os", osKey());
    result.put("python_writers", PYTHON_WRITERS);
    result.put("iterations_per_writer", ITERATIONS);
    result.put("rows", lines.size());
    result.put("java_contended", javaContended);
    result.put("intact", lines.size() == expected.size() && actual.equals(expected));
    Files.createDirectories(OUT);
    Files.writeString(OUT.resolve(osKey() + ".json"), JSON.writeValueAsString(result) + "\n");
    System.out.println("status-log interop result: " + JSON.writeValueAsString(result));

    assertThat(lines).hasSameSizeAs(expected);
    assertThat(actual).isEqualTo(expected);
    assertThat(javaContended).as("Java observed Python holding the lock (non-vacuous contention)").isGreaterThan(0);
  }

  private boolean peerHoldsJavaTimesOut(Path gcd, Path mission, Path lockPath) throws Exception {
    Path sync = Files.createTempDirectory("status-lock-held");
    Path ready = sync.resolve("ready");
    Path release = sync.resolve("release");
    Process holder =
        startPeer(
            "hold",
            "--gcd", gcd.toString(),
            "--mission-dir", mission.toString(),
            "--ready", ready.toString(),
            "--release", release.toString());
    awaitFile(ready);
    try {
      assertThatThrownBy(
              () ->
                  new StatusLogAppender()
                      .withMissionLock(
                          gcd,
                          mission,
                          System.nanoTime() + Duration.ofMillis(500).toNanos(),
                          session -> {
                            session.appendAndSync("{\"event_id\": \"forbidden\"}\n".getBytes(StandardCharsets.UTF_8));
                            return null;
                          }))
          .isInstanceOf(StatusLockHeldException.class)
          .hasMessageContaining(lockPath.toString());
      return !Files.exists(mission.resolve("status.events.jsonl"));
    } finally {
      Files.createFile(release);
      finish(holder);
    }
  }

  private static int appendJavaRows(Path gcd, Path mission, Path lockPath) throws Exception {
    StatusLogAppender appender = new StatusLogAppender();
    LedgerLock probe = LedgerLocks.forThisOs();
    int contended = 0;
    for (int index = 0; index < ITERATIONS; index++) {
      try (LedgerLock.Held held = probe.tryAcquire(lockPath)) {
        if (held == null) {
          contended++;
        }
      }
      String row = row(JAVA_WRITER, index) + "\n";
      appender.withMissionLock(
          gcd,
          mission,
          System.nanoTime() + WAIT.toNanos(),
          session -> {
            session.appendAndSync(row.getBytes(StandardCharsets.UTF_8));
            return null;
          });
      jitter();
    }
    return contended;
  }

  private static Set<String> expectedIds() {
    Set<String> ids = new HashSet<>();
    for (int writer = 1; writer <= PYTHON_WRITERS; writer++) {
      for (int index = 0; index < ITERATIONS; index++) {
        ids.add(eventId(writer, index));
      }
    }
    for (int index = 0; index < ITERATIONS; index++) {
      ids.add(eventId(JAVA_WRITER, index));
    }
    return ids;
  }

  private static String row(int writer, int index) {
    return "{\"actor\": \"status-interop-%d\", \"event_id\": \"%s\", \"index\": %d, \"writer\": %d}"
        .formatted(writer, eventId(writer, index), index, writer);
  }

  private static String eventId(int writer, int index) {
    String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    long value = writer * 1_000_000L + index;
    StringBuilder digits = new StringBuilder();
    while (value > 0) {
      digits.append(alphabet.charAt((int) (value % 32)));
      value /= 32;
    }
    return "0".repeat(26 - digits.length()) + digits.reverse();
  }

  private static JsonNode peer(String... arguments) throws Exception {
    return json(finish(startPeer(arguments)));
  }

  private static Process startPeer(String... arguments) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(System.getenv("SPEC_KITTY_LOCK_INTEROP_PYTHON"));
    command.add(PEER.toAbsolutePath().toString());
    command.addAll(List.of(arguments));
    return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
  }

  private static String finish(Process process) throws Exception {
    if (!process.waitFor(300, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("status writer peer timed out");
    }
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IOException("status writer peer exited " + process.exitValue() + ": " + stdout);
    }
    return stdout;
  }

  private static JsonNode json(String value) {
    return JSON.readTree(value);
  }

  private static void awaitFile(Path path) throws Exception {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!Files.exists(path)) {
      if (System.nanoTime() >= deadline) {
        throw new IOException(path + " did not appear within " + WAIT);
      }
      Thread.sleep(10);
    }
  }

  private static void jitter() throws InterruptedException {
    Thread.sleep(ThreadLocalRandom.current().nextInt(0, 6));
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
    assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
  }

  private static String osKey() {
    String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    if (name.contains("win")) {
      return "windows";
    }
    return name.contains("mac") ? "macos" : "linux";
  }
}
