package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.speckitty.commandcli.adapter.in.http.edge.EdgeRig;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.adapter.out.filesystem.RepositoryIdentity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The six operations of contracts/mission-write on a running service (spec FR-008 to FR-011, FR-015, SC-003): the handshake and the
 * health answer what the service is, every well-formed write is refused fail-closed with nothing written, a malformed write is
 * REQUEST_MALFORMED, and every body validates against the contract's schemas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MISSION = "01J6XW9KQT7M0YB3N4R5CQZ2EX";

  private EdgeRig server;

  @BeforeAll
  void start() {
    server = EdgeRig.start();
  }

  @AfterAll
  void stop() {
    server.close();
  }

  private RawClient.Reply get(String path) {
    return server.request("GET " + path + " HTTP/1.1", "", server.host(), server.bearer());
  }

  private RawClient.Reply post(String path, String body) {
    return server.request("POST " + path + " HTTP/1.1", body, server.host(), server.bearer(), "Spec-Kitty-Write-Protocol: 1", "Content-Type: application/json");
  }

  @Test
  void theHandshakeNamesTheProtocolTheInstanceAndTheRepository() throws Exception {
    RawClient.Reply reply = get("/api/v1/handshake");

    assertThat(reply.status()).isEqualTo(200);
    assertThat(ContractSchemas.violations("Handshake", reply.body())).isEmpty();
    JsonNode handshake = JSON.readTree(reply.body());
    assertThat(handshake.get("protocolVersions").get(0).asInt()).isEqualTo(1);
    assertThat(handshake.get("protocolVersions").size()).isEqualTo(1);
    assertThat(handshake.get("instanceId").asString()).isEqualTo(server.runtime.instance().value());
    assertThat(handshake.get("repositoryId").asString()).isEqualTo(RepositoryIdentity.of(server.gitCommonDir).value());
    assertThat(handshake.get("serviceVersion").asString()).isEqualTo("0.0.0-dev");
    assertThat(reply.text()).doesNotContain(server.token.reveal());
  }

  @Test
  void theHealthIsReadyWithTheSameInstance() {
    RawClient.Reply reply = get("/api/v1/health");

    assertThat(reply.status()).isEqualTo(200);
    assertThat(ContractSchemas.violations("Health", reply.body())).isEmpty();
    JsonNode health = JSON.readTree(reply.body());
    assertThat(health.get("status").asString()).isEqualTo("ready");
    assertThat(health.get("instanceId").asString()).isEqualTo(server.runtime.instance().value());
  }

  /** Every request example of the contract, at the path its operation serves. */
  static Stream<Arguments> validWrites() {
    Path examples = ContractSchemas.root().resolve("examples");
    String base = "/api/v1/missions/" + MISSION;
    return Stream.of(
        Arguments.of("TransitionUnit.claim", base + "/work-package-transitions", examples.resolve("TransitionUnit.claim.yaml")),
        Arguments.of("TransitionBatch.two-units", base + "/work-package-transitions/batch", examples.resolve("TransitionBatch.two-units.yaml")),
        Arguments.of("AnnotationRequest.note", base + "/inner-state-annotations", examples.resolve("AnnotationRequest.note.yaml")));
  }

  /** The example's value, with each row's mission_id (when it names one) set to the Mission of the path. */
  static String body(Path example) {
    JsonNode value = ContractSchemas.yaml(example);
    JsonNode inner = value.has("value") ? value.get("value") : value;
    String text = JSON.writeValueAsString(inner);
    return text.replaceAll("\"mission_id\":\"[0-9A-HJKMNP-TV-Z]{26}\"", "\"mission_id\":\"" + MISSION + "\"");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("validWrites")
  void everyWellFormedWriteIsRefusedFailClosedAndWritesNothing(String name, String path, Path example) {
    String body = body(example);
    List<String> before = server.tree();

    RawClient.Reply reply = post(path, body);

    assertThat(reply.status()).as(reply.text()).isEqualTo(500);
    assertThat(ContractSchemas.violations("WriteRefusal", reply.body())).isEmpty();
    JsonNode refusal = JSON.readTree(reply.body());
    assertThat(refusal.get("code").asString()).isEqualTo("STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");
    assertThat(refusal.get("detail").asString()).isEqualTo("The write core is not built yet; nothing was written.");
    assertThat(server.tree()).as("nothing written").isEqualTo(before);
    assertThat(reply.text()).doesNotContain(server.token.reveal()).doesNotContain(server.gitCommonDir.toString());
  }

  static Stream<Arguments> malformedWrites() {
    String base = "/api/v1/missions/" + MISSION;
    String context = "{\"actor\":\"claude\",\"checkout\":\".\",\"missionDirectory\":\"kitty-specs/m\",\"writeSurface\":\"checkout_append\",\"destinationRef\":null}";
    String row = "{\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EY\",\"wp_id\":\"WP01\"}";
    String unit = "{\"wpId\":\"WP01\",\"rows\":[" + row + "],\"context\":" + context + "}";
    List<Arguments> cases = new ArrayList<>();
    String transitions = base + "/work-package-transitions";
    cases.add(Arguments.of("empty body", transitions, "", null));
    cases.add(Arguments.of("not JSON", transitions, "{", null));
    cases.add(Arguments.of("an array", transitions, "[]", null));
    cases.add(Arguments.of("trailing content", transitions, unit + " {}", null));
    cases.add(Arguments.of("duplicate member", transitions, "{\"wpId\":\"WP01\",\"wpId\":\"WP02\",\"rows\":[" + row + "],\"context\":" + context + "}", null));
    cases.add(Arguments.of("unknown member", transitions, unit.replace("{\"wpId\"", "{\"extra\":1,\"wpId\""), null));
    cases.add(Arguments.of("no rows", transitions, unit.replace("[" + row + "]", "[]"), null));
    cases.add(Arguments.of("bad wpId", transitions, unit.replace("\"wpId\":\"WP01\"", "\"wpId\":\"wp1\""), null));
    cases.add(Arguments.of("row of another WP", transitions, unit.replace("\"wp_id\":\"WP01\"", "\"wp_id\":\"WP02\""), null));
    cases.add(Arguments.of("row of another Mission", transitions, unit.replace("\"wp_id\":\"WP01\"", "\"wp_id\":\"WP01\",\"mission_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EZ\""), null));
    cases.add(Arguments.of("row without event_id", transitions, unit.replace("\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EY\",", ""), null));
    cases.add(Arguments.of("absolute checkout", transitions, unit.replace("\"checkout\":\".\"", "\"checkout\":\"/home/me\""), null));
    cases.add(Arguments.of("dot-dot directory", transitions, unit.replace("kitty-specs/m", "../outside"), null));
    cases.add(Arguments.of("backslash directory", transitions, unit.replace("kitty-specs/m", "kitty-specs\\\\m"), null));
    cases.add(Arguments.of("drive letter", transitions, unit.replace("kitty-specs/m", "C:/x"), null));
    cases.add(Arguments.of("unknown surface", transitions, unit.replace("checkout_append", "primary_checkout_append"), null));
    cases.add(Arguments.of("numeric destinationRef", transitions, unit.replace("\"destinationRef\":null", "\"destinationRef\":7"), null));
    cases.add(Arguments.of("empty actor", transitions, unit.replace("\"actor\":\"claude\"", "\"actor\":\"\""), null));
    cases.add(Arguments.of("missing context member", transitions, unit.replace(",\"destinationRef\":null", ""), null));
    cases.add(Arguments.of("ill-formed missionId", "/api/v1/missions/not-a-ulid/work-package-transitions", unit, null));
    String batch = base + "/work-package-transitions/batch";
    cases.add(Arguments.of("empty batch", batch, "{\"units\":[]}", null));
    cases.add(Arguments.of("65 units", batch, "{\"units\":[" + String.join(",", java.util.Collections.nCopies(65, unit)) + "]}", null));
    cases.add(Arguments.of("bad second unit", batch, "{\"units\":[" + unit + "," + unit.replace("\"wpId\":\"WP01\"", "\"wpId\":\"x\"") + "]}", "#/units/1"));
    String annotations = base + "/inner-state-annotations";
    cases.add(Arguments.of("annotation without row", annotations, "{\"wpId\":\"WP01\",\"context\":" + context + "}", null));
    cases.add(Arguments.of("annotation of another WP", annotations, "{\"wpId\":\"WP01\",\"row\":" + row.replace("WP01", "WP03") + ",\"context\":" + context + "}", null));
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedWrites")
  void aMalformedWriteIsRefusedBeforeTheGateAndEchoesNothing(String name, String path, String body, String instance) {
    List<String> before = server.tree();

    RawClient.Reply reply = post(path, body);

    assertThat(reply.status()).as(reply.text()).isEqualTo(400);
    assertThat(ContractSchemas.violations("WriteRefusal", reply.body())).isEmpty();
    JsonNode refusal = JSON.readTree(reply.body());
    assertThat(refusal.get("code").asString()).isEqualTo("REQUEST_MALFORMED");
    if (instance == null) {
      assertThat(refusal.has("instance")).isFalse();
    } else {
      assertThat(refusal.get("instance").asString()).isEqualTo(instance);
    }
    assertThat(server.tree()).isEqualTo(before);
    assertThat(reply.body()).doesNotContain("/home/me").doesNotContain("outside").doesNotContain("primary_checkout_append");
  }

  @Test
  void aRowWhoseMissionIdIsNullCountsAsNamingNoMission() {
    String context = "{\"actor\":\"claude\",\"checkout\":\".\",\"missionDirectory\":\"kitty-specs/m\",\"writeSurface\":\"checkout_append\",\"destinationRef\":null}";
    String unit =
        "{\"wpId\":\"WP01\",\"rows\":[{\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EY\",\"wp_id\":\"WP01\",\"mission_id\":null}],\"context\":" + context + "}";

    RawClient.Reply reply = post("/api/v1/missions/" + MISSION + "/work-package-transitions", unit);

    assertThat(reply.status()).as("well-formed, so it reaches the fail-closed gate").isEqualTo(500);
  }

  @Test
  void theRefusalCodesUsedAreContractCodesWithTheirStatus() {
    List<String> codes = ContractSchemas.constants("WriteRefusalCode");
    for (org.speckitty.commandcli.domain.service.RefusalCode code : org.speckitty.commandcli.domain.service.RefusalCode.values()) {
      assertThat(codes).contains(code.name());
      String body = "{\"code\":\"" + code.name() + "\",\"status\":" + code.status() + ",\"title\":\"" + code.title() + "\"}";
      assertThat(ContractSchemas.violations("WriteRefusal", body)).as(code.name()).isEmpty();
      String wrongStatus = "{\"code\":\"" + code.name() + "\",\"status\":" + (code.status() == 500 ? 501 : 500) + ",\"title\":\"x\"}";
      assertThat(ContractSchemas.violations("WriteRefusal", wrongStatus)).as(code.name() + " with another status").isNotEmpty();
    }
  }

  @Test
  void theContractExamplesAreWhereTheTestsReadThem() {
    assertThat(Files.isDirectory(ContractSchemas.root().resolve("examples"))).isTrue();
  }
}
