package org.speckitty.commandcli.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.adapter.out.filesystem.RepositoryIdentity;
import tools.jackson.databind.JsonNode;

/**
 * The launcher contract end to end (contracts/mission-write/LAUNCHER.md; core {@code services/launcher.py}; spec SC-001, FR-002, FR-011 to
 * FR-014, NFR-002, NFR-003): the boot jar is started exactly as core's launcher starts it and driven through the three-part readiness
 * check, the single-instance rule, the shutdown, the idle exit and the signal path. The token never reaches the log.
 */
@Tag("process")
public class LauncherContractProcessTest {

  @TempDir Path gitCommonDir;

  /** LAUNCHER.md "Ready": the endpoint file passes core's reader, the handshake matches, the health reads ready with the same instance. */
  public static JsonNode assertReady(ServiceProcess service) throws Exception {
    Optional<JsonNode> published = service.awaitEndpoint();
    assertThat(published).as("endpoint.json within the 15 s start budget; log:\n" + service.log()).isPresent();
    JsonNode endpoint = published.get();
    assertThat(endpoint.get("schema_version").asInt()).isEqualTo(1);
    assertThat(endpoint.get("host").asString()).isEqualTo("127.0.0.1");
    assertThat(endpoint.get("token").asString()).matches("[A-Za-z0-9_-]{43,}");
    assertThat(endpoint.get("pid").asLong()).isEqualTo(service.process.pid());
    assertThat(endpoint.get("protocol_versions").get(0).asInt()).isEqualTo(1);
    OffsetDateTime startedAt = OffsetDateTime.parse(endpoint.get("started_at").asString());
    assertThat(startedAt.toInstant()).isAfterOrEqualTo(service.process.info().startInstant().orElseThrow().minusSeconds(1));

    JsonNode handshake = ServiceProcess.JSON.readTree(ServiceProcess.get(endpoint, "/api/v1/handshake").body());
    assertThat(handshake.get("instanceId").asString()).isEqualTo(endpoint.get("instance_id").asString());
    assertThat(handshake.get("repositoryId").asString()).isEqualTo(RepositoryIdentity.of(service.gitCommonDir).value());
    assertThat(handshake.get("protocolVersions").get(0).asInt()).isEqualTo(1);

    JsonNode health = ServiceProcess.JSON.readTree(ServiceProcess.get(endpoint, "/api/v1/health").body());
    assertThat(health.get("status").asString()).isEqualTo("ready");
    assertThat(health.get("instanceId").asString()).isEqualTo(endpoint.get("instance_id").asString());
    return endpoint;
  }

  @Test
  void theLauncherSequenceStartsAReadyServiceAndStopsItWithShutdown() throws Exception {
    long started = System.nanoTime();
    try (ServiceProcess service = ServiceProcess.launch(gitCommonDir)) {
      JsonNode endpoint = assertReady(service);
      Duration toReady = Duration.ofNanos(System.nanoTime() - started);
      assertThat(toReady).as("NFR-002: the jar is ready within 10 s").isLessThan(Duration.ofSeconds(10));

      RawClient.Reply reply = ServiceProcess.shutdown(endpoint);
      long stopping = System.nanoTime();

      assertThat(reply.status()).isEqualTo(202);
      assertThat(service.awaitExit(Duration.ofSeconds(10))).as("NFR-003: exit within 10 s").isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - stopping)).isLessThan(Duration.ofSeconds(10));
      assertThat(service.process.exitValue()).isZero();
      assertThat(service.endpointFile()).doesNotExist();
      assertThat(service.log()).doesNotContain(endpoint.get("token").asString());
    }
  }

  @Test
  void aStalledRequestBodyDoesNotHoldTheProcessPastTheStopBudget() throws Exception {
    try (ServiceProcess service = ServiceProcess.launch(gitCommonDir)) {
      JsonNode endpoint = assertReady(service);
      int port = endpoint.get("port").asInt();
      // a write that declares 100 bytes and sends 5, then stalls with the connection open
      try (java.net.Socket stalled = new java.net.Socket("127.0.0.1", port)) {
        String head =
            "POST /api/v1/missions/01J6XW9KQT7M0YB3N4R5CQZ2EX/inner-state-annotations HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nAuthorization: Bearer " + endpoint.get("token").asString()
                + "\r\nSpec-Kitty-Write-Protocol: 1\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"wp";
        stalled.getOutputStream().write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        stalled.getOutputStream().flush();
        Thread.sleep(300);

        long stopping = System.nanoTime();
        assertThat(ServiceProcess.shutdown(endpoint).status()).isEqualTo(202);
        assertThat(service.awaitExit(Duration.ofSeconds(10))).as("NFR-003 with a stalled body; log:\n" + service.log()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - stopping)).isLessThan(Duration.ofSeconds(10));
        assertThat(service.endpointFile()).doesNotExist();
      }
    }
  }

  @Test
  void aSecondInstanceForTheSameGitCommonDirExitsZeroWithoutPublishing() throws Exception {
    try (ServiceProcess first = ServiceProcess.launch(gitCommonDir)) {
      JsonNode endpoint = assertReady(first);
      FileTime published = Files.getLastModifiedTime(first.endpointFile());
      String content = Files.readString(first.endpointFile());

      try (ServiceProcess second = ServiceProcess.launch(gitCommonDir)) {
        assertThat(second.awaitExit(Duration.ofSeconds(15))).isTrue();
        assertThat(second.process.exitValue()).isZero();
      }

      assertThat(Files.readString(first.endpointFile())).isEqualTo(content);
      assertThat(Files.getLastModifiedTime(first.endpointFile())).isEqualTo(published);
      ServiceProcess.shutdown(endpoint);
      assertThat(first.awaitExit(Duration.ofSeconds(10))).isTrue();
    }
  }

  @Test
  void anIdleServiceExitsByItselfAndRemovesItsEndpointFile() throws Exception {
    try (ServiceProcess service = ServiceProcess.launch(gitCommonDir, "--idle-timeout", "2")) {
      assertReady(service);

      assertThat(service.awaitExit(Duration.ofSeconds(15))).isTrue();
      assertThat(service.process.exitValue()).isZero();
      assertThat(service.endpointFile()).doesNotExist();
    }
  }

  @Test
  void aTerminatedServiceRemovesItsEndpointFile() throws Exception {
    assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "SIGTERM is POSIX; Windows uses TerminateProcess, which runs no hook");
    try (ServiceProcess service = ServiceProcess.launch(gitCommonDir)) {
      assertReady(service);

      service.process.destroy(); // SIGTERM

      assertThat(service.awaitExit(Duration.ofSeconds(10))).isTrue();
      assertThat(service.endpointFile()).doesNotExist();
    }
  }

  @Test
  void aBadCommandLineExitsTwoAndPublishesNothing() throws Exception {
    List<String> command = new java.util.ArrayList<>(ServiceProcess.argvPrefix());
    command.addAll(List.of("serve", "--git-common-dir", "relative/path"));
    try (ServiceProcess service = ServiceProcess.start(gitCommonDir, command)) {
      assertThat(service.awaitExit(Duration.ofSeconds(15))).isTrue();
      assertThat(service.process.exitValue()).isEqualTo(2);
    }
    assertThat(ServiceProcess.stateDirectory(gitCommonDir).resolve("endpoint.json")).doesNotExist();
  }

  @Test
  void aSymbolicLinkInThePlaceOfTheStateDirectoryExitsNonZeroAndPublishesNothing() throws Exception {
    Path elsewhere = Files.createDirectory(gitCommonDir.resolve("elsewhere"));
    try {
      Files.createSymbolicLink(ServiceProcess.stateDirectory(gitCommonDir), elsewhere);
    } catch (UnsupportedOperationException | java.io.IOException noLinks) {
      assumeTrue(false, "symbolic links are not available here");
    }
    List<String> command = new java.util.ArrayList<>(ServiceProcess.argvPrefix());
    command.addAll(List.of("serve", "--git-common-dir", gitCommonDir.toString()));
    try (ServiceProcess service = ServiceProcess.start(gitCommonDir, command)) {
      assertThat(service.awaitExit(Duration.ofSeconds(15))).isTrue();
      assertThat(service.process.exitValue()).isEqualTo(1);
    }
    // the launcher opened its own service.log there before the start; the service itself never followed the link
    assertThat(elsewhere.resolve("endpoint.json")).doesNotExist();
    assertThat(elsewhere.resolve("service.lock")).doesNotExist();
  }

  @Test
  void theLogNeverCarriesTheTokenOrCallerControlledHeaders() throws Exception {
    try (ServiceProcess service = ServiceProcess.launch(gitCommonDir)) {
      JsonNode endpoint = assertReady(service);
      int port = endpoint.get("port").asInt();
      String sentinel = "SENTINEL-" + System.nanoTime();
      RawClient.request(port, "GET /api/v1/" + sentinel + " HTTP/1.1", "", "Host: " + sentinel + ":" + port, "User-Agent: " + sentinel, "Origin: http://" + sentinel);
      RawClient.request(
          port, "GET /api/v1/" + sentinel + " HTTP/1.1", "", "Host: 127.0.0.1:" + port, "Authorization: Bearer " + endpoint.get("token").asString(), "Cookie: a=" + sentinel);
      ServiceProcess.shutdown(endpoint);
      assertThat(service.awaitExit(Duration.ofSeconds(10))).isTrue();

      assertThat(service.log()).doesNotContain(endpoint.get("token").asString()).doesNotContain(sentinel);
    }
  }
}
