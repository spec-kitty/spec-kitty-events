package org.speckitty.commandcli.adapter.in.http.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.speckitty.commandcli.application.lifecycle.ServiceLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The edge decides on the path the dispatcher routes on (pre-PR squad, security finding 1): a request target that is not in canonical form
 * (a path parameter, a percent-encoding, an empty or dot segment) would be routed by Spring to an operation after the protocol and size
 * checks had matched the raw text and let it pass. Such a target is refused 400 REQUEST_MALFORMED right after the token, and every request
 * body is capped, whatever its path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalPathTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BASE = "/api/v1/missions/01J6XW9KQT7M0YB3N4R5CQZ2EX";

  private EdgeRig server;

  @BeforeAll
  void start() {
    server = EdgeRig.start();
  }

  @AfterAll
  void stop() {
    server.close();
  }

  static Stream<String> nonCanonical() {
    return Stream.of(
        BASE + "/work-package-transitions;x=1",
        BASE + "/work-package-transition%73",
        BASE + ";x/work-package-transitions",
        BASE + "//work-package-transitions",
        "/api/v1/shutdow%6E",
        "/api/v1/shutdown;x",
        "/api/v1//shutdown",
        "/api/v1/./shutdown",
        "/api/v1/missions/../shutdown",
        "/api/v1/health;x",
        "/api/v1/health%41");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonCanonical")
  void aNonCanonicalTargetIsRefusedBeforeAnyOperation(String target) {
    RawClient.Reply reply =
        server.request("POST " + target + " HTTP/1.1", "{}", server.host(), server.bearer(), "Content-Type: application/json");

    assertThat(reply.status()).as(reply.text()).isIn(400);
    JsonNode problem = JSON.readTree(reply.body());
    assertThat(problem.get("code").asString()).isEqualTo("REQUEST_MALFORMED");
    assertThat(reply.body()).doesNotContain(target);
    assertThat(server.context().getBean(ServiceLifecycle.class).draining()).as("no shutdown slipped through").isFalse();
  }

  @Test
  void anEncodedSlashIsTheContainersOwn400AndReachesNothing() {
    RawClient.Reply reply = server.request("POST /api/v1/shutdown%2F HTTP/1.1", "", server.host(), server.bearer(), "Spec-Kitty-Write-Protocol: 1");
    assertThat(reply.status()).isEqualTo(400);
    assertThat(server.context().getBean(ServiceLifecycle.class).draining()).isFalse();
  }

  @Test
  void aNonCanonicalTargetWithoutTheTokenIsStill401() {
    RawClient.Reply reply = server.request("POST /api/v1/shutdow%6E HTTP/1.1", "", server.host());
    assertThat(reply.status()).isEqualTo(401);
  }

  @Test
  void anOversizedBodyIsRefusedOnAnyPath() {
    String body = "x".repeat(BodySizeFilter.MAX_BYTES + 1);
    for (String target : new String[] {"/api/v1/shutdown", "/api/v1/nowhere", "/api/v1/missions/X/other"}) {
      RawClient.Reply reply = server.request("POST " + target + " HTTP/1.1", body, server.host(), server.bearer(), "Spec-Kitty-Write-Protocol: 1");
      assertThat(reply.status()).as(target).isEqualTo(413);
    }
  }

  @Test
  void theCanonicalOperationsStillPass() {
    RawClient.Reply health = server.request("GET /api/v1/health HTTP/1.1", "", server.host(), server.bearer());
    RawClient.Reply query = server.request("GET /api/v1/health?x=1 HTTP/1.1", "", server.host(), server.bearer());
    assertThat(health.status()).isEqualTo(200);
    assertThat(query.status()).as("a query string is not part of the path").isEqualTo(200);
  }
}
