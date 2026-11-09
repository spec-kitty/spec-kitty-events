package org.speckitty.commandcli.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.JsonNode;

@Tag("process")
class WriteProcessTest {
  private static final String MISSION = "01J6XW9KQT7M0YB3N4R5CQZ2EX";

  @TempDir Path repository;

  @Test
  void bootJarWritesALegalUnitWithoutRestrictedMethodWarnings() throws Exception {
    gitInit();
    Path mission = repository.resolve("kitty-specs/mission");
    Files.createDirectories(mission.resolve("tasks"));
    Files.writeString(mission.resolve("meta.json"), "{\"mission_id\":\"" + MISSION + "\"}\n");
    Files.writeString(mission.resolve("tasks/WP01.md"), "---\nwork_package_id: WP01\ndependencies: []\nsubtasks: []\n---\n");
    Files.writeString(mission.resolve("status.events.jsonl"),
        "{\"actor\": \"java-jenny\", \"at\": \"2026-11-07T09:00:00+00:00\", \"event_id\": \"00000000000000000000000001\", "
            + "\"evidence\": null, \"execution_mode\": \"direct_repo\", \"force\": false, \"from_lane\": \"genesis\", "
            + "\"policy_metadata\": null, \"reason\": null, \"review_ref\": null, \"to_lane\": \"planned\", \"wp_id\": \"WP01\"}\n");
    try (ServiceProcess service = ServiceProcess.launch(repository.resolve(".git"))) {
      JsonNode endpoint = LauncherContractProcessTest.assertReady(service);
      String row = "{\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EY\",\"wp_id\":\"WP01\",\"from_lane\":\"planned\","
          + "\"to_lane\":\"claimed\",\"at\":\"2026-11-07T10:00:00+00:00\",\"actor\":\"java-jenny\",\"force\":false,"
          + "\"execution_mode\":\"direct_repo\",\"reason\":null,\"review_ref\":null,\"evidence\":null,\"policy_metadata\":null}";
      String context = "{\"actor\":\"java-jenny\",\"checkout\":\".\",\"missionDirectory\":\"kitty-specs/mission\","
          + "\"writeSurface\":\"checkout_append\",\"destinationRef\":null}";
      String body = "{\"wpId\":\"WP01\",\"rows\":[" + row + "],\"context\":" + context + "}";
      int port = endpoint.get("port").asInt();
      RawClient.Reply reply = RawClient.request(port,
          "POST /api/v1/missions/" + MISSION + "/work-package-transitions HTTP/1.1", body,
          "Host: 127.0.0.1:" + port, "Authorization: Bearer " + endpoint.get("token").asString(),
          "Spec-Kitty-Write-Protocol: 1", "Content-Type: application/json");
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      assertThat(service.log()).doesNotContainIgnoringCase("restricted method");
      assertThat(ServiceProcess.shutdown(endpoint).status()).isEqualTo(202);
      assertThat(service.awaitExit(Duration.ofSeconds(10))).isTrue();
    }
  }

  private void gitInit() throws Exception {
    Process process = new ProcessBuilder("git", "init", "--quiet").directory(repository.toFile()).start();
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) throw new AssertionError("git init failed");
  }
}
