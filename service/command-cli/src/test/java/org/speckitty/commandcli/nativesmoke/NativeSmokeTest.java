package org.speckitty.commandcli.nativesmoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.adapter.in.http.operations.ContractSchemas;
import org.speckitty.commandcli.process.LauncherContractProcessTest;
import org.speckitty.commandcli.process.ServiceProcess;
import tools.jackson.databind.JsonNode;

/** Launcher, response latency and a real FFM-locked Mission write against the built native binary. */
@Tag("native-binary")
class NativeSmokeTest {
  private static final String MISSION = "01J6XW9KQT7M0YB3N4R5CQZ2EX";
  private static final String EVENT = "01J6XW9KQT7M0YB3N4R5CQZ2EY";
  private static final String PRIOR = "{\"actor\": \"a\", \"at\": \"2026-11-07T09:00:00+00:00\", "
      + "\"event_id\": \"00000000000000000000000001\", \"evidence\": null, \"execution_mode\": \"direct_repo\", "
      + "\"force\": false, \"from_lane\": \"genesis\", \"policy_metadata\": null, \"reason\": null, "
      + "\"review_ref\": null, \"to_lane\": \"planned\", \"wp_id\": \"WP01\"}\n";
  private static final String CLAIM = "{\"actor\": \"a\", \"at\": \"2026-11-07T10:00:00+00:00\", "
      + "\"event_id\": \"" + EVENT + "\", \"evidence\": null, \"execution_mode\": \"direct_repo\", "
      + "\"force\": false, \"from_lane\": \"planned\", \"policy_metadata\": null, \"reason\": null, "
      + "\"review_ref\": null, \"to_lane\": \"claimed\", \"wp_id\": \"WP01\"}\n";

  @TempDir Path repository;

  @Test
  void theNativeBinaryHonoursTheLauncherContractAndPerformsALockedWrite() throws Exception {
    assertThat(System.getProperty("nativeBinary")).as("run through nativeSmoke -PnativeBinary=<path>").isNotBlank();
    Process git = new ProcessBuilder("git", "init", "--quiet").directory(repository.toFile()).start();
    assertThat(git.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(git.exitValue()).isZero();
    Path mission = repository.resolve("kitty-specs/m");
    Files.createDirectories(mission.resolve("tasks"));
    Files.writeString(mission.resolve("meta.json"), "{\"mission_id\":\"" + MISSION + "\"}\n");
    Files.writeString(mission.resolve("tasks/WP01.md"), "---\nwork_package_id: WP01\ndependencies: []\nsubtasks: []\n---\n");
    Path eventLog = mission.resolve("status.events.jsonl");
    Files.writeString(eventLog, PRIOR);

    long started = System.nanoTime();
    try (ServiceProcess service = ServiceProcess.launch(repository.resolve(".git"))) {
      JsonNode endpoint = LauncherContractProcessTest.assertReady(service);
      Duration toReady = Duration.ofNanos(System.nanoTime() - started);
      assertThat(toReady).as("the native binary is ready within 2 s").isLessThan(Duration.ofSeconds(2));
      assertHandshakeAndHealthLatency(endpoint);

      int port = endpoint.get("port").asInt();
      String row = "{\"event_id\":\"" + EVENT + "\",\"wp_id\":\"WP01\",\"from_lane\":\"planned\","
          + "\"to_lane\":\"claimed\",\"at\":\"2026-11-07T10:00:00+00:00\",\"actor\":\"a\",\"force\":false,"
          + "\"execution_mode\":\"direct_repo\",\"reason\":null,\"review_ref\":null,\"evidence\":null,"
          + "\"policy_metadata\":null}";
      String body = "{\"wpId\":\"WP01\",\"rows\":[" + row + "],\"context\":{\"actor\":\"a\",\"checkout\":\".\","
          + "\"missionDirectory\":\"kitty-specs/m\",\"writeSurface\":\"checkout_append\",\"destinationRef\":null}}";
      RawClient.Reply write = RawClient.request(port,
          "POST /api/v1/missions/" + MISSION + "/work-package-transitions HTTP/1.1", body,
          "Host: 127.0.0.1:" + port, "Authorization: Bearer " + endpoint.get("token").asString(),
          "Spec-Kitty-Write-Protocol: 1", "Content-Type: application/json");
      assertThat(write.status()).as(write.text()).isEqualTo(201);
      assertThat(ContractSchemas.violations("DurabilityAck", write.body())).isEmpty();
      assertThat(Files.readString(eventLog)).isEqualTo(PRIOR + CLAIM);
      assertThat(repository.resolve(".git/spec-kitty-locks/m.status.lock")).exists();

      assertThat(ServiceProcess.shutdown(endpoint).status()).isEqualTo(202);
      assertThat(service.awaitExit(Duration.ofSeconds(10))).isTrue();
      assertThat(service.process.exitValue()).isZero();
      assertThat(service.endpointFile()).doesNotExist();
      assertThat(service.log()).doesNotContain(endpoint.get("token").asString());
    }
  }

  private static void assertHandshakeAndHealthLatency(JsonNode endpoint) {
    List<Long> nanos = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      for (String path : List.of("/api/v1/handshake", "/api/v1/health")) {
        long started = System.nanoTime();
        RawClient.Reply reply = ServiceProcess.get(endpoint, path);
        nanos.add(System.nanoTime() - started);
        assertThat(reply.status()).isEqualTo(200);
      }
    }
    Collections.sort(nanos);
    Duration p95 = Duration.ofNanos(nanos.get((int) Math.ceil(nanos.size() * 0.95) - 1));
    assertThat(p95).as("handshake and health p95").isLessThanOrEqualTo(Duration.ofMillis(50));
  }
}
