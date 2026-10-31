package org.speckitty.commandcli.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write service as core's launcher runs it (core {@code services/launcher.py}; LAUNCHER.md): {@code <argv prefix> serve
 * --git-common-dir <abs> [--idle-timeout <s>]}, stdin closed, stdout and stderr appended to {@code <gcd>/spec-kitty-write-service/service.log}
 * in a 0700 directory the launcher created, and the JVM option variables removed from the environment. The argv prefix is the boot jar on
 * the JVM ({@code commandCli.jar}) or the native binary ({@code nativeBinary}).
 */
public final class ServiceProcess implements AutoCloseable {

  public static final JsonMapper JSON = JsonMapper.builder().build();
  public static final Duration START_BUDGET = Duration.ofSeconds(15);

  public final Path gitCommonDir;
  public final Process process;

  private ServiceProcess(Path gitCommonDir, Process process) {
    this.gitCommonDir = gitCommonDir;
    this.process = process;
  }

  public static List<String> argvPrefix() {
    String binary = System.getProperty("nativeBinary");
    if (binary != null && !binary.isBlank()) {
      return List.of(Path.of(binary).toAbsolutePath().toString());
    }
    String jar = System.getProperty("commandCli.jar");
    if (jar == null) {
      throw new IllegalStateException("commandCli.jar is not set: run the process tests through the processTest task");
    }
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return List.of(java, "-jar", jar);
  }

  public static Path stateDirectory(Path gitCommonDir) {
    return gitCommonDir.resolve("spec-kitty-write-service");
  }

  /** Starts the service the way the launcher does: it creates the state directory 0700 and opens the log for appending first. */
  public static ServiceProcess launch(Path gitCommonDir, String... extraArgs) throws IOException {
    Path state = stateDirectory(gitCommonDir);
    if (!Files.isDirectory(state)) {
      Files.createDirectory(state);
      if (state.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      }
    }
    List<String> command = new ArrayList<>(argvPrefix());
    command.add("serve");
    command.add("--git-common-dir");
    command.add(gitCommonDir.toAbsolutePath().toString());
    command.addAll(List.of(extraArgs));
    return start(gitCommonDir, command);
  }

  public static ServiceProcess start(Path gitCommonDir, List<String> command) throws IOException {
    return start(gitCommonDir, command, java.util.Map.of(), null);
  }

  /** As {@link #start(Path, List)}, with extra environment variables and a working directory (null: inherited). */
  public static ServiceProcess start(Path gitCommonDir, List<String> command, java.util.Map<String, String> environment, Path workingDirectory) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().putAll(environment);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
      builder.environment().remove(variable);
    }
    builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
    Path log = stateDirectory(gitCommonDir).resolve("service.log");
    if (Files.isDirectory(stateDirectory(gitCommonDir))) {
      builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
      builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
    } else {
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    }
    return new ServiceProcess(gitCommonDir, builder.start());
  }

  private static java.io.File nullDevice() {
    return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
  }

  public Path endpointFile() {
    return stateDirectory(gitCommonDir).resolve("endpoint.json");
  }

  public String log() throws IOException {
    Path log = stateDirectory(gitCommonDir).resolve("service.log");
    return Files.exists(log) ? Files.readString(log) : "";
  }

  /** Polls for the endpoint file every 50 ms within the launcher's start budget; empty when it never appears. */
  public Optional<JsonNode> awaitEndpoint() throws InterruptedException {
    long deadline = System.nanoTime() + START_BUDGET.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(endpointFile())) {
        try {
          return Optional.of(JSON.readTree(Files.readAllBytes(endpointFile())));
        } catch (IOException | RuntimeException partial) {
          // a rename is atomic, so this is a race with a removal; try again
        }
      }
      if (!process.isAlive()) {
        return Optional.empty();
      }
      Thread.sleep(50);
    }
    return Optional.empty();
  }

  public static RawClient.Reply get(JsonNode endpoint, String path) {
    int port = endpoint.get("port").asInt();
    return RawClient.request(port, "GET " + path + " HTTP/1.1", "", "Host: 127.0.0.1:" + port, "Authorization: Bearer " + endpoint.get("token").asString());
  }

  public static RawClient.Reply shutdown(JsonNode endpoint) {
    int port = endpoint.get("port").asInt();
    return RawClient.request(
        port, "POST /api/v1/shutdown HTTP/1.1", "", "Host: 127.0.0.1:" + port, "Authorization: Bearer " + endpoint.get("token").asString(), "Spec-Kitty-Write-Protocol: 1");
  }

  public boolean awaitExit(Duration budget) throws InterruptedException {
    return process.waitFor(budget.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() throws InterruptedException {
    if (process.isAlive()) {
      process.destroyForcibly();
      process.waitFor();
    }
  }
}
