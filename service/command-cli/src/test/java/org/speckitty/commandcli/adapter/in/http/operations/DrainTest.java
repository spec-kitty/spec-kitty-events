package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.EdgeRig;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The drain over HTTP (spec FR-009, FR-011; LAUNCHER.md "Stop"): a shutdown answers 202 and stops the process once; while the service
 * drains, the health reads draining and every write is refused 503 SERVICE_DRAINING without Retry-After, before its shape is judged, with
 * nothing written; a request in flight holds the stop; a refused stranger is not counted. The process exit is recorded by the rig.
 */
class DrainTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BASE = "/api/v1/missions/01J6XW9KQT7M0YB3N4R5CQZ2EX";

  private static void await(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(20);
    }
  }

  private static RawClient.Reply shutdown(EdgeRig rig) {
    return rig.request("POST /api/v1/shutdown HTTP/1.1", "", rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 1");
  }

  @Test
  void aShutdownAnswers202ThenStopsTheProcessOnceWithExitZero() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      RawClient.Reply reply = shutdown(rig);

      assertThat(reply.status()).isEqualTo(202);
      assertThat(reply.body()).isEmpty();
      await("the exit", () -> !rig.exits.isEmpty());
      Thread.sleep(200);
      assertThat(rig.exits).isEqualTo(List.of(0));
      assertThat(rig.context().isActive()).isFalse();
    }
  }

  @Test
  void aDrainingServiceReportsDrainingAndRefusesEveryWriteBeforeItsShape() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      rig.context().getBean(ServiceLifecycle.class).beginDrain();
      List<String> before = rig.tree();

      RawClient.Reply health = rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer());
      assertThat(JSON.readTree(health.body()).get("status").asString()).isEqualTo("draining");
      assertThat(ContractSchemas.violations("Health", health.body())).isEmpty();

      for (String path : List.of(BASE + "/work-package-transitions", BASE + "/work-package-transitions/batch", BASE + "/inner-state-annotations")) {
        for (String body : List.of("{}", "not json")) {
          RawClient.Reply write =
              rig.request("POST " + path + " HTTP/1.1", body, rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 1", "Content-Type: application/json");
          assertThat(write.status()).as(path + " " + body).isEqualTo(503);
          assertThat(write.head()).doesNotContainIgnoringCase("Retry-After");
          JsonNode refusal = JSON.readTree(write.body());
          assertThat(refusal.get("code").asString()).isEqualTo("SERVICE_DRAINING");
          assertThat(ContractSchemas.violations("WriteRefusal", write.body())).isEmpty();
        }
      }
      assertThat(rig.tree()).isEqualTo(before);
      assertThat(rig.exits).as("draining alone stops nothing").isEmpty();
    }
  }

  @Test
  void aRequestInFlightHoldsTheStopAndASecondShutdownStartsNothingNew() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      ServiceLifecycle lifecycle = rig.context().getBean(ServiceLifecycle.class);
      lifecycle.enter(); // a request the service holds

      assertThat(shutdown(rig).status()).isEqualTo(202);
      assertThat(shutdown(rig).status()).isEqualTo(202);
      Thread.sleep(300);
      assertThat(rig.exits).as("held by the request in flight").isEmpty();
      assertThat(JSON.readTree(rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer()).body()).get("status").asString()).isEqualTo("draining");

      lifecycle.exit();
      await("the exit", () -> !rig.exits.isEmpty());
      Thread.sleep(200);
      assertThat(rig.exits).isEqualTo(List.of(0));
    }
  }

  @Test
  void aRefusedStrangerIsNotCountedAsActivity() throws Exception {
    try (EdgeRig rig = EdgeRig.start()) {
      ServiceLifecycle lifecycle = rig.context().getBean(ServiceLifecycle.class);
      rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer());
      Thread.sleep(300);
      Duration idleBefore = lifecycle.idleFor();

      rig.request("GET /api/v1/health HTTP/1.1", "", "Host: evil.example:" + rig.port(), rig.bearer());
      rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), "Authorization: Bearer wrong");
      rig.request("POST /api/v1/shutdown HTTP/1.1", "", rig.host(), rig.bearer(), "Spec-Kitty-Write-Protocol: 9");

      assertThat(lifecycle.idleFor()).as("refusals do not reset the idle clock").isGreaterThanOrEqualTo(idleBefore);
      assertThat(lifecycle.inFlight()).isZero();
      assertThat(lifecycle.draining()).as("a refused shutdown does not drain").isFalse();

      rig.request("GET /api/v1/health HTTP/1.1", "", rig.host(), rig.bearer());
      assertThat(lifecycle.idleFor()).as("an accepted request does").isLessThan(idleBefore);
    }
  }
}
