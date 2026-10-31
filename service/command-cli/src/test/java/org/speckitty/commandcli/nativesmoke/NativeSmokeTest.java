package org.speckitty.commandcli.nativesmoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.process.LauncherContractProcessTest;
import org.speckitty.commandcli.process.ServiceProcess;
import tools.jackson.databind.JsonNode;

/**
 * The launcher contract against the built native binary ({@code -PnativeBinary}, run by {@code nativeSmoke} on every native leg): start it as
 * core's launcher does, pass the three-part readiness check within NFR-001's 2 s, answer 100 handshake and health requests at a p95 within
 * NFR-004's 50 ms, refuse a write fail-closed, and exit 0 after {@code /shutdown} within NFR-003's 10 s with the endpoint file removed.
 */
@Tag("native-binary")
class NativeSmokeTest {

  @TempDir Path gitCommonDir;

  @Test
  void theNativeBinaryHonoursTheLauncherContract() throws Exception {
    assertThat(System.getProperty("nativeBinary")).as("run through nativeSmoke -PnativeBinary=<path>").isNotBlank();
    long started = System.nanoTime();
    try (ServiceProcess service = ServiceProcess.launch(gitCommonDir)) {
      JsonNode endpoint = LauncherContractProcessTest.assertReady(service);
      Duration toReady = Duration.ofNanos(System.nanoTime() - started);
      assertThat(toReady).as("NFR-001: the native binary is ready within 2 s").isLessThan(Duration.ofSeconds(2));

      List<Long> nanos = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        for (String path : List.of("/api/v1/handshake", "/api/v1/health")) {
          long t = System.nanoTime();
          RawClient.Reply reply = ServiceProcess.get(endpoint, path);
          nanos.add(System.nanoTime() - t);
          assertThat(reply.status()).isEqualTo(200);
        }
      }
      Collections.sort(nanos);
      Duration p95 = Duration.ofNanos(nanos.get((int) Math.ceil(nanos.size() * 0.95) - 1));
      assertThat(p95).as("NFR-004: handshake and health p95").isLessThanOrEqualTo(Duration.ofMillis(50));

      int port = endpoint.get("port").asInt();
      RawClient.Reply write =
          RawClient.request(
              port,
              "POST /api/v1/missions/01J6XW9KQT7M0YB3N4R5CQZ2EX/inner-state-annotations HTTP/1.1",
              "{\"wpId\":\"WP01\",\"row\":{\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EY\",\"wp_id\":\"WP01\"},\"context\":{\"actor\":\"a\",\"checkout\":\".\","
                  + "\"missionDirectory\":\"kitty-specs/m\",\"writeSurface\":\"checkout_append\",\"destinationRef\":null}}",
              "Host: 127.0.0.1:" + port,
              "Authorization: Bearer " + endpoint.get("token").asString(),
              "Spec-Kitty-Write-Protocol: 1",
              "Content-Type: application/json");
      assertThat(write.status()).isEqualTo(500);
      assertThat(ServiceProcess.JSON.readTree(write.body()).get("code").asString()).isEqualTo("STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");

      assertThat(ServiceProcess.shutdown(endpoint).status()).isEqualTo(202);
      assertThat(service.awaitExit(Duration.ofSeconds(10))).as("NFR-003").isTrue();
      assertThat(service.process.exitValue()).isZero();
      assertThat(service.endpointFile()).doesNotExist();
      assertThat(service.log()).doesNotContain(endpoint.get("token").asString());
    }
  }
}
