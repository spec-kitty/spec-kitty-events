package org.speckitty.commandcli.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.EdgeRig;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The process side of the lifecycle inside one JVM (spec FR-003, FR-011 to FR-013; LAUNCHER.md): the endpoint file is published once the
 * server is bound and names this instance, a shutdown removes it before the exit, an idle service drains and exits by itself, and a newer
 * instance's file is left in place. The drain over HTTP is DrainTest's (WP04). The process exit is recorded by the rig.
 */
class LifecycleTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Path endpoint(EdgeRig rig) {
    return rig.gitCommonDir.resolve("spec-kitty-write-service").resolve("endpoint.json");
  }

  private static void await(String what, java.util.function.BooleanSupplier condition, Duration budget) throws InterruptedException {
    long deadline = System.nanoTime() + budget.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(20);
    }
  }

  @Test
  void theEndpointFileNamesTheBoundPortAndThisInstance() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      JsonNode file = JSON.readTree(Files.readAllBytes(endpoint(rig)));

      assertThat(file.get("port").asInt()).isEqualTo(rig.port());
      assertThat(file.get("instance_id").asString()).isEqualTo(rig.runtime.instance().value());
      assertThat(file.get("token").asString()).isEqualTo(rig.token.reveal());
      assertThat(file.get("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
    }
  }

  @Test
  void aShutdownRemovesTheEndpointFileBeforeTheExit() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      assertThat(endpoint(rig)).exists();

      RawClient.Reply reply = rig.request("POST /api/v1/shutdown HTTP/1.1", "", rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 1");

      assertThat(reply.status()).isEqualTo(202);
      await("the exit", () -> !rig.exits.isEmpty(), Duration.ofSeconds(10));
      assertThat(rig.exits).isEqualTo(List.of(0));
      assertThat(endpoint(rig)).doesNotExist();
    }
  }

  @Test
  void anIdleServiceDrainsAndExitsByItself() throws Exception {
    try (EdgeRig rig = EdgeRig.start(Duration.ofSeconds(1))) {
      await("the idle exit", () -> !rig.exits.isEmpty(), Duration.ofSeconds(10));

      assertThat(rig.exits).isEqualTo(List.of(0));
      assertThat(endpoint(rig)).doesNotExist();
    }
  }

  @Test
  void aNewerInstancesEndpointFileIsLeftInPlace() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      String newer = Files.readString(endpoint(rig)).replace(rig.runtime.instance().value(), "01M694HJ0000000000000000AA");
      Files.writeString(endpoint(rig), newer);

      rig.request("POST /api/v1/shutdown HTTP/1.1", "", rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 1");
      await("the exit", () -> !rig.exits.isEmpty(), Duration.ofSeconds(10));

      assertThat(Files.readString(endpoint(rig))).isEqualTo(newer);
    }
  }
}
