package org.speckitty.commandcli.adapter.in.http.operations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.speckitty.commandcli.adapter.in.http.edge.OwnPortSource;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.adapter.out.filesystem.RepositoryIdentity;
import org.speckitty.commandcli.adapter.out.filesystem.ServiceLock;
import org.speckitty.commandcli.adapter.out.filesystem.StateDirectory;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import org.speckitty.commandcli.config.CommandCliApplication;
import org.speckitty.commandcli.config.ServeOptions;
import org.speckitty.commandcli.config.ServeRuntime;
import org.speckitty.commandcli.domain.service.InstanceId;
import org.speckitty.commandcli.domain.service.WriteToken;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** A real temporary repository and the production HTTP application started against its git common dir. */
final class WriteRig implements AutoCloseable {
  static final String MISSION = "01J6XW9KQT7M0YB3N4R5CQZ2EX";
  static final String DIRECTORY = "kitty-specs/mission";
  static final String PRIOR_ID = "00000000000000000000000001";

  final Path repository;
  final Path gitCommonDir;
  final ServeRuntime runtime;
  final WriteToken token;
  Path missionDirectory;
  private String missionId = MISSION;
  private final ServiceLock serviceLock;
  private final ConfigurableApplicationContext context;

  private WriteRig() throws Exception {
    repository = Files.createTempDirectory("command-cli-write-repository");
    run(repository, "git", "init", "--quiet");
    gitCommonDir = repository.resolve(".git");
    missionDirectory = repository.resolve(DIRECTORY);
    Files.createDirectories(missionDirectory.resolve("tasks"));
    Files.writeString(missionDirectory.resolve("meta.json"), "{\"mission_id\":\"" + MISSION + "\"}\n");
    Files.writeString(missionDirectory.resolve("tasks/WP01.md"), "---\nwork_package_id: WP01\ndependencies: []\nsubtasks: []\n---\n");
    Files.writeString(missionDirectory.resolve("tasks/WP02-write.md"), "---\nwork_package_id: WP02\ndependencies: []\nsubtasks: []\n---\n");
    Files.writeString(missionDirectory.resolve("status.events.jsonl"), priorPlannedLine());
    StateDirectory directory = StateDirectory.prepare(gitCommonDir);
    serviceLock = ServiceLock.tryAcquire(directory).orElseThrow();
    SecureRandom random = new SecureRandom();
    token = WriteToken.mint(random);
    runtime = new ServeRuntime(
        new ServeOptions(gitCommonDir, Duration.ofMinutes(5)), directory, serviceLock,
        InstanceId.mint(System.currentTimeMillis(), random), token, RepositoryIdentity.of(gitCommonDir),
        "0.0.0-dev", ignored -> { });
    ServeRuntime.install(runtime);
    context = new SpringApplicationBuilder(CommandCliApplication.class).run();
  }

  static WriteRig start() {
    try {
      return new WriteRig();
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot start write test rig", exception);
    }
  }

  String host() {
    return "Host: 127.0.0.1:" + port();
  }

  String bearer() {
    return "Authorization: Bearer " + token.reveal();
  }

  RawClient.Reply request(String requestLine, String body, String... headers) {
    return RawClient.request(port(), requestLine, body, headers);
  }

  RawClient.Reply post(String operation, String body) {
    return postMission(missionId, operation, body);
  }

  RawClient.Reply postMission(String targetMission, String operation, String body) {
    String path = switch (operation) {
      case "unit" -> "/work-package-transitions";
      case "batch" -> "/work-package-transitions/batch";
      case "annotation" -> "/inner-state-annotations";
      default -> throw new IllegalArgumentException(operation);
    };
    return RawClient.request(port(), "POST /api/v1/missions/" + targetMission + path + " HTTP/1.1", body,
        host(), bearer(), "Spec-Kitty-Write-Protocol: 1", "Content-Type: application/json");
  }

  void removeMission() throws IOException {
    try (Stream<Path> paths = Files.walk(missionDirectory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }

  List<String> tree() {
    try (Stream<Path> files = Files.walk(gitCommonDir)) {
      return files.filter(Files::isRegularFile).sorted().map(path -> {
        try {
          return gitCommonDir.relativize(path) + " " + Files.size(path) + " " + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
          return gitCommonDir.relativize(path) + " ?";
        }
      }).toList();
    } catch (IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  Path log() {
    return missionDirectory.resolve("status.events.jsonl");
  }

  void replaceMission(String id, String directory, java.util.Map<String, String> tasks, byte[] initialLog) throws IOException {
    try (Stream<Path> paths = Files.walk(missionDirectory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
    missionId = id;
    missionDirectory = repository.resolve("kitty-specs").resolve(directory);
    Files.createDirectories(missionDirectory.resolve("tasks"));
    Files.writeString(missionDirectory.resolve("meta.json"), "{\"mission_id\":\"" + id + "\"}\n");
    for (var task : tasks.entrySet()) {
      Files.writeString(missionDirectory.resolve("tasks").resolve(task.getKey()), task.getValue());
    }
    if (initialLog == null) {
      Files.deleteIfExists(log());
    } else {
      Files.write(log(), initialLog);
    }
  }

  static String context() {
    return "{\"actor\":\"java-jenny\",\"checkout\":\".\",\"missionDirectory\":\"" + DIRECTORY
        + "\",\"writeSurface\":\"checkout_append\",\"destinationRef\":null}";
  }

  static String claimRow(String eventId) {
    return "{\"event_id\":\"" + eventId + "\",\"wp_id\":\"WP01\",\"from_lane\":\"planned\",\"to_lane\":\"claimed\","
        + "\"at\":\"2026-11-07T10:00:00+00:00\",\"actor\":\"java-jenny\",\"force\":false,"
        + "\"execution_mode\":\"direct_repo\",\"reason\":null,\"review_ref\":null,\"evidence\":null,\"policy_metadata\":null}";
  }

  static String unit(String row) {
    return unit("WP01", null, row);
  }

  static String unit(String wp, String operation, String row) {
    return "{\"wpId\":\"" + wp + "\"," + (operation == null ? "" : "\"operation\":\"" + operation + "\",")
        + "\"rows\":[" + row + "],\"context\":" + context() + "}";
  }

  static String batch(String... units) {
    return "{\"units\":[" + String.join(",", units) + "]}";
  }

  static String transitionRow(String id, String wp, String from, String to, String actor) {
    return "{\"event_id\":\"" + id + "\",\"wp_id\":\"" + wp + "\",\"from_lane\":\"" + from + "\",\"to_lane\":\"" + to
        + "\",\"at\":\"2026-11-07T10:00:00+00:00\",\"actor\":\"" + actor + "\",\"force\":false,"
        + "\"execution_mode\":\"direct_repo\",\"reason\":null,\"review_ref\":null,\"evidence\":null,\"policy_metadata\":null}";
  }

  static String encodedRow(String raw) {
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> row = (java.util.Map<String, Object>)
        org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowDecoder.parse(raw);
    return new String(org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowEncoder.encodeLine(row),
        java.nio.charset.StandardCharsets.US_ASCII);
  }

  static String annotationRow(String id, String wp, String note) {
    return "{\"event_id\":\"" + id + "\",\"kind\":\"annotation\",\"wp_id\":\"" + wp
        + "\",\"at\":\"2026-11-07T10:00:00+00:00\",\"actor\":\"java-jenny\",\"delta\":{\"note\":\"" + note + "\"}}";
  }

  static String annotation(String wp, String row) {
    return "{\"wpId\":\"" + wp + "\",\"row\":" + row + ",\"context\":" + context() + "}";
  }

  static String expectedClaimLine(String eventId) {
    return "{\"actor\": \"java-jenny\", \"at\": \"2026-11-07T10:00:00+00:00\", \"event_id\": \"" + eventId
        + "\", \"evidence\": null, \"execution_mode\": \"direct_repo\", \"force\": false, \"from_lane\": \"planned\", "
        + "\"policy_metadata\": null, \"reason\": null, \"review_ref\": null, \"to_lane\": \"claimed\", \"wp_id\": \"WP01\"}\n";
  }

  static String priorPlannedLine() {
    return "{\"actor\": \"java-jenny\", \"at\": \"2026-11-07T09:00:00+00:00\", \"event_id\": \"" + PRIOR_ID
        + "\", \"evidence\": null, \"execution_mode\": \"direct_repo\", \"force\": false, \"from_lane\": \"genesis\", "
        + "\"policy_metadata\": null, \"reason\": null, \"review_ref\": null, \"to_lane\": \"planned\", \"wp_id\": \"WP01\"}\n";
  }

  int inFlight() {
    return context.getBean(ServiceLifecycle.class).inFlight();
  }

  void beginDrain() {
    context.getBean(ServiceLifecycle.class).beginDrain();
  }

  private int port() {
    return context.getBean(OwnPortSource.class).port();
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new IOException(String.join(" ", command) + " failed: " + new String(process.getInputStream().readAllBytes()));
    }
  }

  @Override
  public void close() throws Exception {
    context.close();
    serviceLock.close();
    try (Stream<Path> paths = Files.walk(repository)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }
}
